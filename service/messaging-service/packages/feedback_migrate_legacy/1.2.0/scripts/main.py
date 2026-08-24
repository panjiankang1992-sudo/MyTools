#!/usr/bin/env python3
"""从 MyTools 旧库迁移问题反馈。"""
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
 return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(",",":"),default=str).encode()
def payload_digest(value:dict)->str:
 """按 Java 记录字段顺序计算反馈摘要。"""
 return hashlib.sha256(json.dumps(value,ensure_ascii=False,separators=(",",":")).encode()).hexdigest()
def timestamp(value:object)->str:
 """将旧库时间转换为 Java Instant 的 UTC 格式。"""
 if not isinstance(value,datetime):return str(value)
 current=(value if value.tzinfo else value.replace(tzinfo=ZoneInfo("Asia/Shanghai"))).astimezone(timezone.utc)
 if current.microsecond==0:return current.strftime("%Y-%m-%dT%H:%M:%SZ")
 fraction=f"{current.microsecond:06d}";fraction=fraction[:3]if current.microsecond%1000==0 else fraction
 return current.strftime("%Y-%m-%dT%H:%M:%S")+f".{fraction}Z"
def item(row:dict)->dict:
 """映射一条完整旧反馈。"""
 return {"legacyId":int(row["id"]),"username":row["username"],"email":row["email"],"phone":row.get("phone"),"category":row["category"],"title":row["title"],"content":row["content"],"status":row["status"],"createdAt":timestamp(row["created_time"]),"updatedAt":timestamp(row["update_time"])}
class Client:
 """消息服务迁移客户端。"""
 def __init__(self,url:str,token:str,opener=urlopen):
  if not token:raise ValueError("messaging token is missing")
  self.url=url.rstrip("/");self.token=token;self.opener=opener
 def migrate(self,key:str,dry:bool,items:list[dict])->dict:
  """提交一个反馈批次。"""
  request=Request(self.url+"/internal/v1/support-feedback/migrations/legacy",data=canonical({"migrationKey":key,"dryRun":dry,"items":items}),method="POST",headers={"Authorization":f"Bearer {self.token}","Content-Type":"application/json","Accept":"application/json"})
  with self.opener(request,timeout=30)as response:body=response.read()
  if len(body)>1024*1024:raise RuntimeError("feedback response is too large")
  value=json.loads(body.decode())
  if not isinstance(value,dict):raise RuntimeError("feedback response is invalid")
  return value
 def reconcile(self,key:str)->dict:
  """读取目标迁移证据。"""
  request=Request(self.url+f"/internal/v1/support-feedback/migrations/legacy/reconciliation?migrationKey={key}",headers={"Authorization":f"Bearer {self.token}","Accept":"application/json"})
  with self.opener(request,timeout=30)as response:body=response.read()
  if len(body)>1024*1024:raise RuntimeError("feedback reconciliation response is too large")
  value=json.loads(body.decode())
  if not isinstance(value,dict):raise RuntimeError("feedback reconciliation response is invalid")
  return value
def execute(connection,client:Client,key:str,dry:bool)->dict:
 """在一致性只读快照内迁移全部反馈。"""
 if not KEY.fullmatch(key)or not isinstance(dry,bool):raise ValueError("feedback parameters are invalid")
 with connection.cursor()as cursor:
  cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
  cursor.execute("SELECT COUNT(*) value FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='t_feedback'")
  table_exists=int(cursor.fetchone()["value"])==1
  if table_exists:cursor.execute("SELECT COALESCE(MAX(id),0) value FROM t_feedback");high=int(cursor.fetchone()["value"])
  else:high=0
 after=exported=accepted=skipped=rejected=0;digest=hashlib.sha256()
 while after<high:
  with connection.cursor()as cursor:cursor.execute("SELECT * FROM t_feedback WHERE id>%s AND id<=%s ORDER BY id LIMIT 200",(after,high));rows=list(cursor.fetchall())
  if not rows:break
  values=[item(row)for row in rows]
  for value in values:
   value_digest=payload_digest(value)
   for part in(str(value["legacyId"]),value_digest):data=part.encode();digest.update(len(data).to_bytes(4,"big"));digest.update(data)
  result=client.migrate(key,dry,values);counts=[result.get(name)for name in("accepted","skipped","rejected")]
  if result.get("dryRun")is not dry or any(not isinstance(value,int)for value in counts)or sum(counts)!=len(values):raise RuntimeError("feedback import result does not close")
  exported+=len(values);accepted+=counts[0];skipped+=counts[1];rejected+=counts[2];after=int(rows[-1]["id"])
 connection.rollback();source_digest=digest.hexdigest();target_verified=False
 if not dry:
  evidence=client.reconcile(key);target_verified=evidence.get("migrationKey")==key and evidence.get("itemCount")==exported and evidence.get("collectionSha256")==source_digest
  if not target_verified:raise RuntimeError("feedback target evidence does not match source")
 return {"migrationKey":key,"dryRun":dry,"sourceHighWater":high,"exported":exported,"accepted":accepted,"skipped":skipped,"rejected":rejected,"digestSha256":source_digest,"targetVerified":target_verified}
def main()->None:
 """执行反馈迁移任务。"""
 context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text());parameters=context["parameters"];connection=pymysql.connect(host=os.getenv("MYTOOLS_LEGACY_DB_HOST","127.0.0.1"),port=int(os.getenv("MYTOOLS_LEGACY_DB_PORT","3306")),user=os.environ["MYTOOLS_LEGACY_DB_USER"],password=os.environ["MYTOOLS_LEGACY_DB_PASSWORD"],database=os.getenv("MYTOOLS_LEGACY_DB_NAME","my_tools"),charset="utf8mb4",cursorclass=DictCursor,autocommit=False)
 try:result=execute(connection,Client(os.getenv("MESSAGING_URL","http://127.0.0.1:23250"),os.getenv("MESSAGING_INTERNAL_TOKEN","")),str(parameters["migrationKey"]),parameters["dryRun"])
 finally:connection.close()
 target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False)as handle:json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
 temporary.replace(target)
if __name__=="__main__":main()
