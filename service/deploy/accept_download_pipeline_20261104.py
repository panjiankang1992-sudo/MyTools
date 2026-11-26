#!/usr/bin/env python3
"""通过专用账号和公开测试文件，验证批次并行、幂等与所有者隔离。"""
import json,subprocess,time,urllib.request,urllib.error
from pathlib import Path
import pymysql
ROOT=Path('/opt/yuyutian/mytools')
pid=subprocess.check_output(['systemctl','show','mytools-download-ingestion-service','-p','MainPID','--value'],text=True).strip()
e=dict(x.split('=',1) for x in Path('/proc/'+pid+'/environ').read_text().split('\0') if '=' in x)
db=pymysql.connect(host='127.0.0.1',user=e['DOWNLOAD_DB_USER'],password=e['DOWNLOAD_DB_PASSWORD'],database=e.get('DOWNLOAD_DB_NAME','mytools_download'),autocommit=True,cursorclass=pymysql.cursors.DictCursor)
def q(sql,args=()):
 with db.cursor() as c:c.execute(sql,args);return c.fetchall()
def api(method,path,body=None,token=None,offset=None):
 h={'Content-Type':'application/octet-stream' if isinstance(body,bytes) else 'application/json'}
 if token:h['Authorization']='Bearer '+token
 if offset is not None:h['X-Upload-Offset']=str(offset)
 data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
 with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:23200'+path,data=data,headers=h,method=method),timeout=60) as r:
  v=json.load(r);return v.get('data',v)
def login(i):
 a=json.loads((ROOT/f'config/pdf-tools/acceptance-account-{i}.json').read_text())
 return api('POST','/api/app/v1/identity/login',{'username':a['username'],'password':a['password'],'deviceId':'async-download-acceptance'})['accessToken']
from concurrent.futures import ThreadPoolExecutor
prefix='/api/app/v1/download-tools'
token=login(1)
root=ROOT/'secure-evidence/download-pipeline-20261104-v3'
root.mkdir(mode=0o700,exist_ok=True)
path=root/'acceptance.json'
requests=[('slow','https://httpbingo.org/delay/8'),
          ('fast','https://www.w3.org/Icons/w3c_home.png')]
def submit(value):
 kind,url=value
 body={'text':url,'idempotencyKey':'download-pipeline-20261104-v3:'+kind}
 row=api('POST',prefix+'/messages',body,token)
 again=api('POST',prefix+'/messages',body,token)
 assert row['requestId']==again['requestId']
 return {'kind':kind,'id':row['id'],'requestId':row['requestId']}
if path.exists():
 report=json.loads(path.read_text())
else:
 with ThreadPoolExecutor(max_workers=2) as pool:rows=list(pool.map(submit,requests))
 report={'rows':rows,'checks':['idempotent_app_submission']}
 path.write_text(json.dumps(report));path.chmod(0o600)
ids=[x['requestId'] for x in report['rows']]
for _ in range(120):
 core=q('SELECT id,status,task_instance_id FROM download_request WHERE id IN (%s,%s)',ids)
 if len(core)==2 and all(x['status'] in ('SUCCEEDED','FAILED','CANCELLED') for x in core):break
 time.sleep(1)
assert len(core)==2 and all(x['status']=='SUCCEEDED' for x in core),core
other=login(0)
try:api('GET',prefix+'/messages/'+report['rows'][0]['id'],token=other);raise AssertionError('owner_isolation')
except urllib.error.HTTPError as error:assert error.code==404
report['checks'].append('owner_isolation')
tdb=pymysql.connect(host='127.0.0.1',user=e['TASK_DB_USERNAME'],password=e['TASK_DB_PASSWORD'],database='mytools_task',cursorclass=pymysql.cursors.DictCursor,autocommit=True)
with tdb.cursor() as c:
 c.execute("SELECT task_instance_id,started_at,finished_at FROM task_execution WHERE task_instance_id IN (%s,%s) AND status='SUCCEEDED'",tuple(x['task_instance_id'] for x in core));executions=c.fetchall()
 c.execute("SELECT version,success FROM flyway_schema_history WHERE version IN ('181','182')");report['migration']=c.fetchall()
 c.execute("SELECT script_package,script_version FROM task_step_definition WHERE script_package IN ('download_message_url_batch','download_resolve_x_user')");report['versions']=c.fetchall()
assert len(executions)==2
start=max(x['started_at'] for x in executions);end=min(x['finished_at'] for x in executions)
report['overlapSeconds']=(end-start).total_seconds()
report['executions']=[{**x,'durationSeconds':(x['finished_at']-x['started_at']).total_seconds()} for x in executions]
report['core']=core
assert report['overlapSeconds']>0,report['executions']
report['checks'].append('two_batch_parents_overlap_without_blocking')
for row in report['rows']:
 detail=api('GET',prefix+'/messages/'+row['id'],token=token)
 assert detail['status']=='SUCCEEDED'
 assert detail['result']['itemCount']==1
report['checks'].append('real_files_visible_in_app')
path.write_text(json.dumps(report,indent=2,default=str));print(json.dumps(report,default=str),flush=True)
