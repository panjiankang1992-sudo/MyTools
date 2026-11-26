import json,os,subprocess,time,struct,zlib,hashlib,urllib.request,urllib.error
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
def png(i):
 w,h=320,240
 raw=b''.join(b'\0'+bytes(c for x in range(w) for c in ((40+i*30,140,220) if y<160 else (60,140+i*20,60))) for y in range(h))
 def chunk(k,v):return struct.pack('!I',len(v))+k+v+struct.pack('!I',zlib.crc32(k+v)&0xffffffff)
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
prefix='/api/app/v1/download-tools'
assert e.get('DOWNLOAD_ENRICHMENT_PAUSED')=='true'
token=login(1)
def call(m,p,b=None,**kw):return api(m,prefix+p,b,token,**kw)
evidence=ROOT/'secure-evidence/async-download-20261104-v2';evidence.mkdir(exist_ok=True,mode=0o700)
f=evidence/'acceptance.json'
if not f.exists():
 rows=[]
 for i in range(4):
  data=png(i);u=call('POST','/uploads',{'name':f'async-landscape-{i}.png','bytes':len(data),'idempotencyKey':f'async-download-20261104-v2:{i}'})
  call('PUT','/uploads/'+u['id']+'/content',data,offset=0)
  call('PUT','/uploads/'+u['id']+'/content',data,offset=0)
  row=call('POST','/uploads/'+u['id']+'/complete',{})
  assert call('POST','/uploads/'+u['id']+'/complete',{})['requestId']==row['requestId']
  rows.append({'id':row['id'],'requestId':row['requestId']})
 report={'rows':rows,'checks':['chunk_and_complete_idempotency']}
 f.write_text(json.dumps(report));f.chmod(0o600)
else:report=json.loads(f.read_text())
ids=[x['requestId'] for x in report['rows']];place=','.join(['%s']*len(ids))
for _ in range(100):
 core=q('SELECT id,status,TIMESTAMPDIFF(MICROSECOND,created_at,updated_at)/1000000 seconds FROM download_request WHERE id IN ('+place+')',ids)
 if len(core)==4 and all(x['status']=='SUCCEEDED' for x in core):break
 time.sleep(2)
report['core']=[{**r,'seconds':float(r['seconds'])} for r in core]
report['background']=q('SELECT kind,state,COUNT(*) count FROM download_background_job WHERE download_request_id IN ('+place+') GROUP BY kind,state',ids)
report['tags']=q('SELECT tag_status,COUNT(*) count FROM download_item WHERE download_request_id IN ('+place+') GROUP BY tag_status',ids)
assert all(x['status']=='SUCCEEDED' for x in core),report['core']
other=login(0)
try:api('GET',prefix+'/messages/'+report['rows'][0]['id'],token=other);raise AssertionError('owner_isolation_failed')
except urllib.error.HTTPError as ex:assert ex.code==404
assert any(x['kind']=='ENRICHMENT' and x['state']=='PENDING' and x['count']==4 for x in report['background'])
assert report['tags']==[{'tag_status':'PENDING','count':4}]
report['checks'].extend(['owner_isolation','core_success_while_consumer_paused'])
report['app']=[{'status':r['status'],'postProcessingStatus':r['result'].get('postProcessingStatus')} for r in [call('GET','/messages/'+x['id']) for x in report['rows']]]
f.write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
print(json.dumps({'unboundBackgroundRequests':q('SELECT COUNT(*) count FROM download_background_job b JOIN download_request r ON r.id=b.download_request_id WHERE r.task_instance_id IS NULL')[0]['count']}))
