import sys,json,pymysql,urllib.request,hashlib
from pathlib import Path
sys.path.insert(0,'/tmp');from deploy_download_chat import config
c=config(Path('/opt/yuyutian/mytools/config/services.env'));owner=2054944045960138752
def db(prefix,name):return pymysql.connect(host=c.get(prefix+'_DB_HOST','127.0.0.1'),user=c[prefix+'_DB_USER'],password=c[prefix+'_DB_PASSWORD'],database=c.get(prefix+'_DB_NAME',name),cursorclass=pymysql.cursors.DictCursor)
m=db('MEDIA_LIBRARY','mytools_media');d=db('DOWNLOAD','mytools_download')
with m.cursor() as q:
 q.execute("select distinct i.id,i.asset_id,i.content_sha256,i.size_bytes,(select count(*) from media_tag_relation r where r.media_item_id=i.id) tag_count from media_item i join media_item_source s on s.media_item_id=i.id where i.owner_id=%s and i.status<>'MISSING' and s.source_type='DOWNLOAD'",(owner,));media={r['asset_id']:r for r in q.fetchall()}
with d.cursor() as q:
 q.execute("select i.id,i.asset_id,i.content_sha256,i.size_bytes,i.tag_status,i.tags_json from download_item i join download_request r on r.id=i.download_request_id where r.owner_id=%s and i.status='COMPLETED' order by i.id",(owner,));rows=q.fetchall()
headers={'Authorization':'Bearer '+c['MEDIA_LIBRARY_INTERNAL_TOKEN'],'Content-Type':'application/json'}
def request(path,payload=None):
 r=urllib.request.Request('http://127.0.0.1:23300'+path,headers=headers,data=None if payload is None else json.dumps(payload,separators=(',',':')).encode(),method='GET' if payload is None else 'POST')
 with urllib.request.urlopen(r,timeout=30) as f:return json.load(f)
matched={};events=[];failed=[]
for row in rows:
 item=media.get(row['asset_id'])
 if item is None:continue
 assert item['content_sha256']==row['content_sha256'] and item['size_bytes']==row['size_bytes']
 tags=row['tags_json'];tags=json.loads(tags) if isinstance(tags,str) else tags
 if row['tag_status']!='TAGGED' or not tags:continue
 tags=[{'name':x['name'],'confidence':x.get('confidence')} for x in tags]
 payload={'eventId':'download-tags-backfill:'+row['id'],'ownerId':owner,'assetId':row['asset_id'],'tags':tags}
 try:
  result=request('/internal/v1/media/downloaded-tags',payload)
  expected={x['name'].strip().lower() for x in tags};assert expected.issubset(set(result['tags']))
  matched.setdefault(item['id'],set()).update(expected);events.append({'mediaId':item['id'],'eventId':payload['eventId'],'tags':len(expected)})
 except Exception as error:failed.append({'mediaId':item['id'],'error':str(error)})
 if len(events)%100==0:print(json.dumps({'syncedEvents':len(events),'failed':len(failed)}),flush=True)
checks=[]
for mid,tags in matched.items():
 result=request(f'/internal/v1/media/items/{mid}?ownerId={owner}');checks.append({'mediaId':mid,'expectedTags':len(tags),'returnedTags':len(result['tags']),'passed':tags.issubset(set(result['tags']))})
page=request(f'/internal/v1/media/catalog?ownerId={owner}&mimePrefix=image%2F&pageSize=10')
out={'downloadMedia':len(media),'syncedMedia':len(matched),'newlyTaggedMedia':sum(x['tag_count']==0 and x['id'] in matched for x in media.values()),'syncedEvents':len(events),'failed':failed,'verificationPassed':sum(x['passed'] for x in checks),'stillWithoutGeneratedTags':sum(x['tag_count']==0 and x['id'] not in matched for x in media.values()),'catalogFirstPage':[{'mediaId':x['id'],'tagCount':len(x['tags'])} for x in page['items']],'checks':checks}
Path('/tmp/download-tags-backfill-result.json').write_text(json.dumps(out));print(json.dumps({k:v for k,v in out.items() if k!='checks'}))
