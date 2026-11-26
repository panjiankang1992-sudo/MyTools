from pathlib import Path
import json,shlex,pymysql,urllib.request,sys
c={}
for line in Path('/opt/yuyutian/mytools/config/services.env').read_text().splitlines():
 if line.strip() and not line.lstrip().startswith('#') and '=' in line:
  k,v=line.removeprefix('export ').split('=',1);parts=shlex.split(v);c[k.strip()]=parts[0] if parts else ''
def db(prefix,name):
 return pymysql.connect(host=c.get(prefix+'_DB_HOST','127.0.0.1'),port=int(c.get(prefix+'_DB_PORT','3306')),user=c[prefix+'_DB_USER'],password=c[prefix+'_DB_PASSWORD'],database=c.get(prefix+'_DB_NAME',name),cursorclass=pymysql.cursors.DictCursor)
d=db('DOWNLOAD','mytools_download');m=db('MEDIA_LIBRARY','mytools_media')
with d.cursor() as q:
 q.execute("SELECT i.id,i.asset_id,i.content_sha256,i.size_bytes,i.tags_json,r.owner_id FROM download_item i JOIN download_request r ON r.id=i.download_request_id WHERE i.status='COMPLETED' AND i.tag_status='TAGGED' ORDER BY i.id")
 rows=q.fetchall()
headers={'Authorization':'Bearer '+c['MEDIA_LIBRARY_INTERNAL_TOKEN'],'Content-Type':'application/json'}
def request(path,payload=None):
 req=urllib.request.Request('http://127.0.0.1:23300'+path,headers=headers,data=None if payload is None else json.dumps(payload,separators=(',',':')).encode(),method='GET' if payload is None else 'POST')
 with urllib.request.urlopen(req,timeout=30) as f:return json.load(f)
candidates=[];mismatch=0;missing=0;already=0
for r in rows:
 with m.cursor() as q:
  q.execute("SELECT DISTINCT i.id,i.content_sha256,i.size_bytes FROM media_item i JOIN media_item_source s ON s.media_item_id=i.id WHERE i.owner_id=%s AND i.asset_id=%s AND i.status<>'MISSING' AND s.source_type='DOWNLOAD'",(r['owner_id'],r['asset_id']));matches=q.fetchall()
  if not matches:missing+=1;continue
  if len(matches)!=1 or matches[0]['content_sha256']!=r['content_sha256'] or matches[0]['size_bytes']!=r['size_bytes']:mismatch+=1;continue
  media=matches[0];q.execute('SELECT t.name FROM media_tag_relation r JOIN media_tag t ON t.id=r.tag_id WHERE r.media_item_id=%s',(media['id'],));existing={x['name'].strip().lower() for x in q.fetchall()}
 tags=r['tags_json'];tags=json.loads(tags) if isinstance(tags,str) else tags
 tags=[{'name':x['name'],'confidence':x.get('confidence')} for x in tags]
 expected={x['name'].strip().lower() for x in tags}
 if not expected or expected.issubset(existing):already+=1;continue
 candidates.append((r,media,tags,expected,existing))
report={'scanned':len(rows),'candidates':len(candidates),'alreadyComplete':already,'mediaMissing':missing,'identityMismatch':mismatch,'applied':0,'verified':0,'failed':[]}
print(json.dumps(report),flush=True)
if '--apply' in sys.argv:
 for r,media,tags,expected,existing in candidates:
  try:
   payload={'eventId':'download-tags-repair-20261003:'+r['id'],'ownerId':r['owner_id'],'assetId':r['asset_id'],'tags':tags}
   result=request('/internal/v1/media/downloaded-tags',payload);report['applied']+=1
   result=request('/internal/v1/media/items/'+media['id']+'?ownerId='+str(r['owner_id']))
   actual={v.strip().lower() for v in result['tags']};assert (expected|existing).issubset(actual),'tag_verification_failed'
   report['verified']+=1
  except Exception as ex:report['failed'].append({'itemId':r['id'],'errorType':type(ex).__name__})
  if report['applied']%50==0:print('verified',report['verified'],flush=True)
 Path('/tmp/download-tag-repair-20261003-result.json').write_text(json.dumps(report))
 print(json.dumps(report),flush=True)
d.close();m.close()
