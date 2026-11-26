#!/usr/bin/env python3
"""补充下载聊天边界验收，仅使用生成文件与公开演示种子。"""
import hashlib
import io
import json
from pathlib import Path
import time
from uuid import uuid4
import zipfile
from urllib.error import HTTPError
from accept_download_chat import session, PREFIX, ROOT
from accept_pdf_tools import api

def run():
    token=session(1)
    def call(method,path,body=None,**kwargs):return api(method,PREFIX+path,body,token=token,**kwargs)
    report={'checks':[],'jobs':[],'finished':False}
    target=ROOT/'runtime/download-chat/extended-acceptance.json'
    def save():
        target.write_text(json.dumps(report,ensure_ascii=False,indent=2));target.chmod(0o600)
    def rejected(path,body,expected):
        try:call('POST',path,body)
        except HTTPError as e:
            assert e.code==expected;return
        raise AssertionError('request_not_rejected')
    rejected('/uploads',{'name':'over-limit.bin','bytes':536870913,'idempotencyKey':str(uuid4())},400)
    report['checks'].append('512MiB_plus_one_rejected')
    rejected('/messages',{'text':'magnet:?xt=urn:btih:invalid','idempotencyKey':str(uuid4())},400)
    report['checks'].append('invalid_magnet_rejected')
    pending=[]
    def upload(name,chunks,total):
        row=call('POST','/uploads',{'name':name,'bytes':total,'idempotencyKey':str(uuid4())})
        digest=hashlib.sha256();offset=0
        for chunk in chunks:
            call('PUT',f'/uploads/{row["id"]}/content',chunk,offset=offset)
            digest.update(chunk);offset+=len(chunk)
            if offset%(64*1048576)==0:print(json.dumps({'uploadMiB':offset//1048576}),flush=True)
        done=call('POST',f'/uploads/{row["id"]}/complete',{})
        pending.append({'name':name,'id':done['id'],'sha256':digest.hexdigest(),'bytes':total})
        report['submitted']=pending;save()
    # 逐块生成恰好 512 MiB 的合成文件，不在内存中分配整个文件。
    seed=b'MyTools synthetic download acceptance.\n'
    block=(seed*((1048576//len(seed))+1))[:1048576]
    upload('acceptance-512MiB.bin',(block for _ in range(512)),536870912)
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('acceptance-note.txt','Synthetic archive acceptance.\n')
    data=buffer.getvalue();upload('acceptance-small.zip',[data],len(data))
    # WebTorrent 官方文档使用的 Sintel 演示种子。
    magnet='magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel'
    try:
        row=call('POST','/messages',{'text':magnet,'idempotencyKey':str(uuid4())})
        pending.append({'name':'Sintel official demo magnet','id':row['id']})
    except HTTPError as e:report['magnetHttpStatus']=e.code
    report['submitted']=pending;save()
    deadline=time.monotonic()+600
    while time.monotonic()<deadline:
        report['jobs']=[call('GET','/messages/'+r['id']) for r in pending];save()
        if all(r['status'] in ('SUCCEEDED','FAILED','CANCELLED') for r in report['jobs']):break
        time.sleep(5)
    report['finished']=True;save()
    print(json.dumps({'finished':True,'checks':report['checks'],'jobs':[{'kind':r['kind'],'name':r['name'],'status':r['status'],'items':(r.get('result') or {}).get('itemCount')} for r in report['jobs']]}),flush=True)

if __name__=='__main__':run()
