"""仅监听回环的 App 界面验收夹具；不连接生产、不执行模型推理。"""
import argparse,hashlib,json,threading,time,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit,parse_qs

ROOT=Path(__file__).resolve().parents[3]
PFX='/api/app/v1/video-generation'
LOCK=threading.RLock()
STATE={'jobs':{},'uploads':{},'tickets':{},'offline':False,'loseAck':False,'events':[]}
IMAGE_IDS={'acceptance-a':'10000000-0000-4000-8000-000000000001','acceptance-b':'20000000-0000-4000-8000-000000000002'}
ARGS=None


def models():
    """所有开放标志只属于夹具目录，不能作为模型质量验收证据。"""
    specs=[('TEXT_TO_VIDEO',0,0,[]),('FIRST_FRAME',1,1,[('FIRST_FRAME','IMAGE')]),
        ('SUBJECT_REFERENCES',2,2,[('SUBJECT','IMAGE')]),('FIRST_LAST_FRAMES',2,2,[('FIRST_FRAME','IMAGE'),('LAST_FRAME','IMAGE')]),
        ('STRUCTURE_RESTYLE',1,1,[('SOURCE_VIDEO','VIDEO')]),('MASKED_EDIT',1,2,[('SOURCE_VIDEO','VIDEO'),('MASK_VIDEO','VIDEO')])]
    modes=[{'mode':m,'minInputs':lo,'maxInputs':hi,'roles':[{'name':r,'kind':k} for r,k in roles],
        'requiresPrompt':True,'frames':[49],'validated':True,'reasonCode':''} for m,lo,hi,roles in specs]
    return [{'id':'wan-vace-1.3b-local','name':'VACE (fixture)','location':'LOCAL','status':'READY','sizes':['832x480'],
        'frames':[49],'fps':16,'modes':modes,'reasonCode':''},
        {'id':'ltx-video-2b-local','name':'LTX (fixture)','location':'LOCAL','status':'READY','sizes':['640x640'],
        'frames':[49],'fps':16,'modes':[modes[1]],'reasonCode':''}]


class Handler(BaseHTTPRequestHandler):
    """真实HTTP契约与可控状态；事件不保存认证头或生产资料。"""
    def log_message(self,*args):pass
    def respond(self,code=200,data=None,error=None):
        raw=json.dumps({'code':'0000' if error is None else error,'message':error or '', 'data':data}).encode()
        self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    def media(self,path,mime):
        raw=path.read_bytes();start=0;end=len(raw)-1;status=200
        if self.headers.get('Range'):
            import re
            match=re.fullmatch(r'bytes=(\d+)-(\d*)',self.headers['Range'])
            if not match or int(match[1])>=len(raw):
                self.send_response(416);self.send_header('Content-Range','bytes */'+str(len(raw)));self.end_headers();return
            start=int(match[1]);end=min(int(match[2]) if match[2] else end,end);status=206
        self.send_response(status);self.send_header('Content-Type',mime);self.send_header('Accept-Ranges','bytes')
        if status==206:self.send_header('Content-Range',f'bytes {start}-{end}/{len(raw)}')
        self.send_header('Content-Length',str(end-start+1));self.end_headers();self.wfile.write(raw[start:end+1])
    def do_GET(self):self.dispatch()
    def do_POST(self):self.dispatch()
    def dispatch(self):
        with LOCK:
            self.process()
    def process(self):
        path=urlsplit(self.path).path;body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        if path=='/_control':
            if self.command=='GET':self.respond(data=STATE);return
            control=json.loads(body)
            for key in ('offline','loseAck'):
                if key in control:STATE[key]=control[key]
            if 'status' in control:
                job=STATE['jobs'][control['jobId']];job['status']=control['status'];job['elapsedMillis']=int((time.time()-job['_start'])*1000)
                if job['status']=='SUCCEEDED':job['result']={'width':640,'height':640,'frames':49,'fps':16,'durationMs':3063}
                if job['status']=='FAILED':job['errorCode']=control.get('errorCode','VIDEO_004')
            self.respond(data={'fixture':True});return
        owner=self.headers.get('Authorization','').removeprefix('Bearer ')
        if '/tickets/' in path:
            ticket=STATE['tickets'].get(path.rsplit('/',1)[1])
            if ticket:self.media(ARGS.video,'video/mp4')
            else:self.respond(404,error='VIDEO_009')
            return
        if owner not in IMAGE_IDS:self.respond(401,error='VIDEO_007');return
        event={'at':time.time(),'owner':owner,'method':self.command,'path':path,'bytes':len(body)}
        STATE['events'].append(event)
        with ARGS.events.open('a') as log:log.write(json.dumps(event)+'\n')
        if STATE['offline']:self.respond(503,error='VIDEO_010');return
        if path.endswith('/models'):self.respond(data=models());return
        if path=='/api/app/v1/image-generation/works':
            self.respond(data=[{'id':IMAGE_IDS[owner],'status':'SUCCEEDED','createdAt':'2026-09-21T00:00:00Z','errorCode':'',
                'request':{'prompt':owner+' picture','resourceId':'krea2-local','mode':'TEXT_TO_IMAGE','size':'1024x1024'},
                'result':{'images':[{'index':0,'assetId':IMAGE_IDS[owner],'mimeType':'image/png','sizeBytes':ARGS.image.stat().st_size}]}}]);return
        if path.startswith('/api/app/v1/image-generation/jobs/'):
            if IMAGE_IDS[owner] not in path:self.respond(404,error='VIDEO_009');return
            self.media(ARGS.image,'image/png');return
        if path in (PFX+'/uploads/image',PFX+'/uploads/video'):
            if not body:self.respond(400,error='VIDEO_001');return
            uid=str(uuid.uuid4());STATE['uploads'][uid]={'owner':owner,'size':len(body),'sha256':hashlib.sha256(body).hexdigest()}
            self.respond(data={'id':uid,'kind':'IMAGE' if path.endswith('image') else 'VIDEO','sizeBytes':len(body)});return
        if path==PFX+'/works':
            jobs=[{k:v for k,v in job.items() if not k.startswith('_')} for job in STATE['jobs'].values() if job['_owner']==owner]
            self.respond(data=list(reversed(jobs)));return
        if path==PFX+'/jobs' and self.command=='POST':
            request=json.loads(body)
            old=next((j for j in STATE['jobs'].values() if j['_owner']==owner and j['request']['idempotencyKey']==request['idempotencyKey']),None)
            if old:
                if old['request']!=request:self.respond(409,error='VIDEO_008');return
                self.respond(data={k:v for k,v in old.items() if not k.startswith('_')});return
            for item in request['inputs']:
                if STATE['uploads'].get(item['uploadId'],{}).get('owner')!=owner:self.respond(404,error='VIDEO_009');return
            uid=str(uuid.uuid4());job={'id':uid,'status':'QUEUED','createdAt':'2026-09-21T00:00:00Z','elapsedMillis':0,'errorCode':'',
                'request':request,'result':{},'_owner':owner,'_start':time.time()};STATE['jobs'][uid]=job
            if STATE['loseAck']:STATE['loseAck']=False;self.respond(502,error='VIDEO_010');return
            self.respond(202,data={k:v for k,v in job.items() if not k.startswith('_')});return
        if path.endswith('/ticket'):
            uid=path.split('/')[-2];item=STATE['jobs'].get(uid) or STATE['uploads'].get(uid)
            if not item or (item.get('_owner') or item.get('owner'))!=owner:self.respond(404,error='VIDEO_009');return
            token=uuid.uuid4().hex;STATE['tickets'][token]=uid
            self.respond(data={'token':token,'url':PFX+'/tickets/'+token,'kind':'VIDEO','expiresAt':'2026-09-22T00:00:00Z'});return
        if path.startswith(PFX+'/jobs/'):
            uid=path.split('/')[6];job=STATE['jobs'].get(uid)
            if not job or job['_owner']!=owner:self.respond(404,error='VIDEO_009');return
            if path.endswith('/cancel'):job['status']='CANCEL_REQUESTED' if job['status']=='RUNNING' else 'CANCELLED'
            elif path.endswith('/cover'):self.media(ARGS.image,'image/png');return
            elif path.endswith('/video'):self.media(ARGS.video,'video/mp4');return
            self.respond(data={k:v for k,v in job.items() if not k.startswith('_')});return
        self.respond(404,error='VIDEO_009')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--image',type=Path,required=True);parser.add_argument('--video',type=Path,required=True)
    parser.add_argument('--events',type=Path,required=True);ARGS=parser.parse_args();ARGS.events.parent.mkdir(parents=True,exist_ok=True)
    ThreadingHTTPServer(('127.0.0.1',23110),Handler).serve_forever()
