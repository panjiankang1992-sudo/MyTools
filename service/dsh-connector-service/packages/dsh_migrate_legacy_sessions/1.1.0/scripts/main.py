#!/usr/bin/env python3
"""迁移 MyTools 的 DSH 用户会话绑定。"""
from __future__ import annotations
import hashlib,json,os,re,tempfile
from datetime import datetime,timezone
from pathlib import Path
from urllib.request import Request,urlopen
from zoneinfo import ZoneInfo
import pymysql
from pymysql.cursors import DictCursor
KEY=re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
def canonical(value:object)->bytes:
 """生成稳定 JSON。"""
 return json.dumps(value,sort_keys=True,separators=(",",":"),default=str).encode()
def payload_digest(value:dict)->str:
 """按 Java 记录字段顺序计算绑定摘要。"""
 return hashlib.sha256(json.dumps(value,separators=(",",":")).encode()).hexdigest()
def timestamp(value:object)->str:
 """将旧库时间转换为 Java Instant 的 UTC 格式。"""
 if not isinstance(value,datetime):return str(value)
 current=(value if value.tzinfo else value.replace(tzinfo=ZoneInfo("Asia/Shanghai"))).astimezone(timezone.utc)
 if current.microsecond==0:return current.strftime("%Y-%m-%dT%H:%M:%SZ")
 fraction=f"{current.microsecond:06d}";fraction=fraction[:3]if current.microsecond%1000==0 else fraction
 return current.strftime("%Y-%m-%dT%H:%M:%S")+f".{fraction}Z"
def normalize(row:dict)->dict:
 """完整映射旧会话绑定。"""
 return {"legacyId":int(row["id"]),"ownerId":int(row["user_id"]),"dshSessionId":row["dsh_session_id"],"workspaceKey":row["workspace_key"],"status":row["status"],"lastSequence":int(row["last_seq"]),"createdAt":timestamp(row["created_at"]),"updatedAt":timestamp(row["updated_at"])}
class Client:
 """DSH Connector 迁移客户端。"""
 def __init__(self,url:str,token:str,opener=urlopen):
  if not token:raise ValueError("dsh connector token is missing")
  self.url=url.rstrip("/");self.token=token;self.opener=opener
 def migrate(self,key:str,dry:bool,items:list[dict])->dict:
  """提交一个迁移批次。"""
  request=Request(self.url+"/internal/v1/dsh/sessions/migrations/legacy",data=canonical({"migrationKey":key,"dryRun":dry,"items":items}),method="POST",headers={"Authorization":f"Bearer {self.token}","Content-Type":"application/json","Accept":"application/json"})
  with self.opener(request,timeout=30)as response:body=response.read()
  if len(body)>1024*1024:raise RuntimeError("dsh migration response is too large")
  value=json.loads(body.decode())
  if not isinstance(value,dict):raise RuntimeError("dsh migration response is invalid")
  return value
 def reconcile(self,key:str)->dict:
  """读取目标迁移证据。"""
  request=Request(self.url+f"/internal/v1/dsh/sessions/migrations/legacy/reconciliation?migrationKey={key}",headers={"Authorization":f"Bearer {self.token}","Accept":"application/json"})
  with self.opener(request,timeout=30)as response:body=response.read()
  if len(body)>1024*1024:raise RuntimeError("dsh reconciliation response is too large")
  value=json.loads(body.decode())
  if not isinstance(value,dict):raise RuntimeError("dsh reconciliation response is invalid")
  return value
def execute(connection,client:Client,key:str,dry:bool)->dict:
 """在一致性视图内迁移全部会话。"""
 if not KEY.fullmatch(key)or not isinstance(dry,bool):raise ValueError("dsh migration parameters are invalid")
 with connection.cursor()as cursor:cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");cursor.execute("SELECT COALESCE(MAX(id),0) value FROM t_dsh_session_binding");high=int(cursor.fetchone()["value"])
 after=exported=accepted=skipped=rejected=0;digest=hashlib.sha256()
 while after<high:
  with connection.cursor()as cursor:cursor.execute("SELECT * FROM t_dsh_session_binding WHERE id>%s AND id<=%s ORDER BY id LIMIT 200",(after,high));rows=list(cursor.fetchall())
  if not rows:break
  values=[normalize(row)for row in rows]
  for value in values:
   value_digest=payload_digest(value)
   for part in(str(value["legacyId"]),value_digest):data=part.encode();digest.update(len(data).to_bytes(4,"big"));digest.update(data)
  result=client.migrate(key,dry,values);counts=[result.get(name)for name in("accepted","skipped","rejected")]
  if result.get("dryRun")is not dry or any(not isinstance(value,int)for value in counts)or sum(counts)!=len(values):raise RuntimeError("dsh import result does not close")
  exported+=len(values);accepted+=counts[0];skipped+=counts[1];rejected+=counts[2];after=int(rows[-1]["id"])
 connection.rollback();source_digest=digest.hexdigest();target_verified=False
 if not dry:
  evidence=client.reconcile(key);target_verified=evidence.get("migrationKey")==key and evidence.get("itemCount")==exported and evidence.get("collectionSha256")==source_digest
  if not target_verified:raise RuntimeError("dsh target evidence does not match source")
 return {"migrationKey":key,"dryRun":dry,"sourceHighWater":high,"exported":exported,"accepted":accepted,"skipped":skipped,"rejected":rejected,"digestSha256":source_digest,"targetVerified":target_verified}
def main()->None:
 """运行会话迁移任务。"""
 context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text());parameters=context["parameters"];connection=pymysql.connect(host=os.getenv("MYTOOLS_LEGACY_DB_HOST","127.0.0.1"),port=int(os.getenv("MYTOOLS_LEGACY_DB_PORT","3306")),user=os.environ["MYTOOLS_LEGACY_DB_USER"],password=os.environ["MYTOOLS_LEGACY_DB_PASSWORD"],database=os.getenv("MYTOOLS_LEGACY_DB_NAME","my_tools"),charset="utf8mb4",cursorclass=DictCursor,autocommit=False)
 try:result=execute(connection,Client(os.getenv("DSH_CONNECTOR_URL","http://127.0.0.1:23320"),os.getenv("DSH_CONNECTOR_INTERNAL_TOKEN","")),str(parameters["migrationKey"]),parameters["dryRun"])
 finally:connection.close()
 target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False)as handle:json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
 temporary.replace(target)
if __name__=="__main__":main()
