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

f=ROOT/'secure-evidence/async-download-20261104-v2/acceptance.json'
report=json.loads(f.read_text());ids=[r['requestId'] for r in report['rows']];place=','.join(['%s']*4)
for _ in range(100):
 jobs=q("SELECT state,COUNT(*) count FROM download_background_job WHERE kind='ENRICHMENT' AND download_request_id IN ("+place+") GROUP BY state",ids)
 if jobs==[{'state':'SUCCEEDED','count':4}]:break
 time.sleep(2)
report['afterRestart']={'jobs':jobs,'tags':q('SELECT tag_status,COUNT(*) count FROM download_item WHERE download_request_id IN ('+place+') GROUP BY tag_status',ids),'core':q('SELECT status,COUNT(*) count FROM download_request WHERE id IN ('+place+') GROUP BY status',ids)}
assert report['afterRestart']['jobs']==[{'state':'SUCCEEDED','count':4}],report['afterRestart']
assert report['afterRestart']['tags']==[{'tag_status':'TAGGED','count':4}]
assert report['afterRestart']['core']==[{'status':'SUCCEEDED','count':4}]
report['checks'].append('durable_jobs_survive_restart_and_complete');f.write_text(json.dumps(report,indent=2));print(json.dumps(report['afterRestart']))
