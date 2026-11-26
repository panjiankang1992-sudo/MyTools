#!/usr/bin/env python3
"""使用专用账号和合成视频验收下载聊天，不输出认证材料。"""
import json
from pathlib import Path
import time
from uuid import uuid4
from urllib.error import HTTPError
from accept_pdf_tools import api

PREFIX='/api/app/v1/download-tools'
ROOT=Path('/opt/yuyutian/mytools')

def session(index):
    """从私有验收凭据获取会话。"""
    a=json.loads((ROOT/f'config/pdf-tools/acceptance-account-{index}.json').read_text())
    return api('POST','/api/app/v1/identity/login',{'username':a['username'],'password':a['password'],'deviceId':'download-chat-acceptance'})['accessToken']

def run():
    """验证复用批次下载、上传直入归档、隔离与幂等。"""
    token=session(1)
    def call(method,path,body=None,**kwargs): return api(method,PREFIX+path,body,token=token,**kwargs)
    report={'checks':[],'jobs':[],'passed':False}
    try:
        api('GET',PREFIX+'/history')
        raise AssertionError('anonymous_allowed')
    except HTTPError as error: assert error.code==401
    report['checks'].append('anonymous_rejected')
    text={'text':'Chat acceptance message','idempotencyKey':str(uuid4())}
    row=call('POST','/messages',text)
    assert row['status']=='NO_MATCH' and call('POST','/messages',text)['id']==row['id']
    report['checks'].append('text_reply_and_idempotency')
    rows=[call('POST','/messages',{'text':'https://example.com/','idempotencyKey':str(uuid4())})]
    data=Path('/tmp/download-chat-fixture.mp4').read_bytes()
    upload=call('POST','/uploads',{'name':'download-chat-fixture.mp4','bytes':len(data),'idempotencyKey':str(uuid4())})
    call('PUT',f'/uploads/{upload["id"]}/content',data,offset=0)
    call('PUT',f'/uploads/{upload["id"]}/content',data,offset=0)
    rows.append(call('POST',f'/uploads/{upload["id"]}/complete',{}))
    assert call('POST',f'/uploads/{upload["id"]}/complete',{})['requestId']==rows[-1]['requestId']
    report['checks'].append('chunk_and_complete_idempotency')
    other=session(0)
    try:
        api('GET',PREFIX+'/messages/'+rows[-1]['id'],token=other)
        raise AssertionError('cross_owner_allowed')
    except HTTPError as error: assert error.code==404
    report['checks'].append('cross_owner_rejected')
    deadline=time.monotonic()+420
    while time.monotonic()<deadline:
        current=[call('GET','/messages/'+row['id']) for row in rows]
        if all(row['status'] in ('SUCCEEDED','FAILED','CANCELLED') for row in current):break
        time.sleep(5)
    for row in current:
        report['jobs'].append(row)
    history=call('GET','/history')
    report['imageFromSimulator']=[r for r in history if r['name']=='download-chat-fixture.png']
    report['passed']=all(row['status']=='SUCCEEDED' for row in current) and any(r['status']=='SUCCEEDED' for r in report['imageFromSimulator'])
    out=ROOT/'runtime/download-chat/acceptance.json'
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2));out.chmod(0o600)
    print(json.dumps({'passed':report['passed'],'checks':report['checks'],'jobs':[{'kind':r['kind'],'status':r['status'],'tags':[i['tagStatus'] for i in (r.get('result') or {}).get('items',[])]} for r in current]}),flush=True)

if __name__=='__main__':run()
