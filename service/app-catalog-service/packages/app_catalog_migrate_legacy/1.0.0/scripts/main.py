#!/usr/bin/env python3
"""从 MyTools 旧库迁移不可再生的应用目录聚合。"""
from __future__ import annotations
import hashlib,json,os,re,tempfile
from datetime import datetime
from pathlib import Path
from urllib.request import Request,urlopen
from zoneinfo import ZoneInfo
import pymysql
from pymysql.cursors import DictCursor

KEY=re.compile(r"^[A-Za-z0-9._:-]{1,128}$")

def canonical(value:object)->bytes:
    """返回稳定 JSON。"""
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(",",":"),default=str).encode()

def timestamp(value:object)->str:
    """将旧库无时区时间按 MyTools 时区转换为带偏移 ISO 时间。"""
    if not isinstance(value,datetime):return str(value)
    current=value if value.tzinfo is not None else value.replace(tzinfo=ZoneInfo("Asia/Shanghai"))
    return current.isoformat()

class Client:
    """调用应用目录迁移接口。"""
    def __init__(self,url:str,token:str,opener=urlopen):
        if not token: raise ValueError("app catalog token is missing")
        self.url=url.rstrip("/");self.token=token;self.opener=opener
    def import_app(self,payload:dict)->dict:
        """导入一个应用聚合。"""
        request=Request(self.url+"/internal/v1/catalog/migrations/legacy-apps",data=canonical(payload),method="POST",headers={"Authorization":f"Bearer {self.token}","Content-Type":"application/json","Accept":"application/json"})
        with self.opener(request,timeout=30)as response: body=response.read()
        if len(body)>1024*1024: raise RuntimeError("catalog migration response is too large")
        value=json.loads(body.decode())
        if not isinstance(value,dict): raise RuntimeError("catalog migration response is invalid")
        return value

def aggregate(cursor,app:dict)->dict:
    """读取一个旧应用的完整版本和文件关系。"""
    cursor.execute("SELECT * FROM t_app_version WHERE app_id=%s ORDER BY id",(app["id"],));versions=list(cursor.fetchall())
    cursor.execute("SELECT * FROM t_app_file WHERE app_id=%s ORDER BY id",(app["id"],));files=list(cursor.fetchall())
    return {"app":{"legacyId":str(app["id"]),"ownerId":int(app["user_id"]),"name":app["name"],"appType":app["type"],"currentVersion":app["version"],"legacyThumbnailId":app.get("thumbnail_id"),"content":app.get("content"),"installCommand":app.get("install_cmd"),"downloadUrl":app.get("download_url"),"status":app["status"],"createdAt":timestamp(app["created_time"]),"updatedAt":timestamp(app["update_time"])},"versions":[{"legacyId":str(value["id"]),"version":value["version"],"content":value.get("content"),"legacyFileId":value.get("file_id"),"createdAt":timestamp(value["created_time"])}for value in versions],"files":[{"legacyId":str(value["id"]),"legacyVersionId":value.get("version_id"),"fileType":value["file_type"],"fileName":value["file_name"],"legacyStoragePath":value["file_path"],"fileSize":int(value["file_size"]),"createdAt":timestamp(value["created_time"])}for value in files]}

def execute(connection,client:Client,migration_key:str,dry_run:bool)->dict:
    """在一致性只读事务内迁移全部旧应用。"""
    if not KEY.fullmatch(migration_key)or not isinstance(dry_run,bool): raise ValueError("catalog migration parameters are invalid")
    with connection.cursor()as cursor:
        cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");cursor.execute("SELECT COALESCE(MAX(id),'') value FROM t_app_market");high_water=str(cursor.fetchone()["value"])
    after="";apps=versions=files=accepted=skipped=rejected=0;digest=hashlib.sha256()
    while True:
        with connection.cursor()as cursor:
            cursor.execute("SELECT * FROM t_app_market WHERE id>%s AND id<=%s ORDER BY id LIMIT 200",(after,high_water));rows=list(cursor.fetchall())
            payloads=[aggregate(cursor,row)for row in rows]
        if not rows: break
        for value in payloads:
            app_id=value["app"]["legacyId"];encoded=canonical(value);item_digest=hashlib.sha256(encoded).hexdigest()
            for part in(app_id,item_digest): data=part.encode();digest.update(len(data).to_bytes(4,"big"));digest.update(data)
            result=client.import_app({"migrationKey":migration_key,"dryRun":dry_run,**value})
            counts=[result.get(name)for name in("accepted","skipped","rejected")]
            if result.get("dryRun")is not dry_run or any(not isinstance(count,int)for count in counts)or sum(counts)!=1: raise RuntimeError("catalog import result is invalid")
            accepted+=counts[0];skipped+=counts[1];rejected+=counts[2];apps+=1;versions+=len(value["versions"]);files+=len(value["files"])
        after=str(rows[-1]["id"])
    connection.rollback()
    return {"migrationKey":migration_key,"dryRun":dry_run,"sourceHighWater":high_water,"apps":apps,"versions":versions,"files":files,"accepted":accepted,"skipped":skipped,"rejected":rejected,"digestSha256":digest.hexdigest()}

def main()->None:
    """运行应用目录迁移任务。"""
    context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text());parameters=context["parameters"]
    connection=pymysql.connect(host=os.getenv("MYTOOLS_LEGACY_DB_HOST","127.0.0.1"),port=int(os.getenv("MYTOOLS_LEGACY_DB_PORT","3306")),user=os.environ["MYTOOLS_LEGACY_DB_USER"],password=os.environ["MYTOOLS_LEGACY_DB_PASSWORD"],database=os.getenv("MYTOOLS_LEGACY_DB_NAME","my_tools"),charset="utf8mb4",cursorclass=DictCursor,autocommit=False)
    try: result=execute(connection,Client(os.getenv("APP_CATALOG_URL","http://127.0.0.1:23310"),os.getenv("APP_CATALOG_INTERNAL_TOKEN","")),str(parameters["migrationKey"]),parameters["dryRun"])
    finally: connection.close()
    target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False)as handle: json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
    temporary.replace(target)
if __name__=="__main__":main()
