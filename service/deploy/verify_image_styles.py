#!/usr/bin/env python3
"""使用独立测试账户验收风格 CRUD、版本快照与真实 GPU 生成。"""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import time
import uuid

spec=importlib.util.spec_from_file_location('styles_deploy',Path(__file__).with_name('deploy_image_styles.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
e=s.e
OTHER=e.OWNER+1


def wait(job):
    deadline=time.monotonic()+1200
    while time.monotonic()<deadline:
        job=e.image('/jobs/'+job['id'])
        if job['status']=='SUCCEEDED':return job
        assert job['status'] not in ['FAILED','CANCELLED','PARTIAL_SUCCESS','UNCONFIRMED'],'job_failed'
        time.sleep(3)
    raise RuntimeError('image_style_timeout')


def main():
    output=s.STATE/'evidence';output.mkdir(exist_ok=True)
    styles=e.image('/styles');assert len(styles)==24
    assert all(x['origin']=='BUILTIN' for x in styles)
    style_id=str(uuid.uuid4())
    body={'name':'Acceptance Ink','promptTemplate':'Render {prompt} as monochrome ink wash on textured rice paper, with flowing brush strokes, soft gray washes and generous negative space.'}
    first=e.image('/styles/'+style_id,'POST',body)
    assert e.image('/styles/'+style_id,'POST',body)==first
    e.image('/styles/'+style_id,'POST',dict(body,name='Different'),expected=409)
    e.image('/styles/'+style_id,'PUT',dict(body,expectedVersion=1),owner=OTHER,expected=404)
    e.image('/styles/'+style_id,'DELETE',owner=OTHER,expected=404)
    assert not any(x['id']==style_id for x in e.image('/styles',owner=OTHER))
    e.image('/styles/'+str(uuid.uuid4()),'POST',dict(body,promptTemplate='{prompt} {other}'),expected=400)
    common={'resourceId':'krea2-local','mode':'TEXT_TO_IMAGE','prompt':'A small wooden cabin beside a tranquil mountain lake at sunrise, framed by pine trees.','references':[],'size':'1024x1024','count':1,'seed':932}
    requests=[('baseline',dict(common,idempotencyKey=s.NAME+'-baseline')),
              ('watercolor',dict(common,styleId='watercolor',styleVersion=1,idempotencyKey=s.NAME+'-watercolor')),
              ('pixel',dict(common,styleId='pixel',styleVersion=1,idempotencyKey=s.NAME+'-pixel'))]
    raw=(e.EVAL/'source.png').read_bytes()
    ref=e.image('/uploads','POST',{'base64':base64.b64encode(raw).decode()})['id']
    custom=dict(common,mode='IMAGE_TO_IMAGE',references=[ref],prompt='Keep the alpine lake and mountain composition.',styleId=style_id,styleVersion=1,idempotencyKey=s.NAME+'-ink')
    e.image('/jobs','POST',custom,owner=OTHER,expected=404)
    created=e.image('/jobs','POST',custom)
    assert created['request']['effectivePrompt']==body['promptTemplate'].replace('{prompt}',custom['prompt'])
    edit=dict(body,name='Acceptance Blue',promptTemplate='{prompt}. Render as a blue watercolor.',expectedVersion=1)
    second=e.image('/styles/'+style_id,'PUT',edit)
    assert second['version']==2 and e.image('/styles/'+style_id,'PUT',edit)==second
    e.image('/styles/'+style_id,'PUT',dict(edit,name='Conflict'),expected=409)
    e.image('/styles/'+style_id,'DELETE');assert not any(x['id']==style_id for x in e.image('/styles'))
    assert e.image('/jobs','POST',custom)['id']==created['id']
    e.image('/jobs','POST',dict(custom,styleVersion=2),expected=409)
    e.image('/jobs','POST',dict(custom,idempotencyKey=s.NAME+'-deleted'),expected=404)
    historical=dict(custom,styleSourceJobId=created['id'],idempotencyKey=s.NAME+'-history')
    replay=e.image('/jobs','POST',historical)
    assert replay['request']['effectivePrompt']==created['request']['effectivePrompt']
    e.image('/jobs/'+replay['id']+'/cancel','POST',{})
    e.image('/jobs','POST',dict(historical,idempotencyKey=s.NAME+'-other-history'),owner=OTHER,expected=404)
    jobs=[]
    for name,request in [('ink',custom),*requests]:
        job=wait(e.image('/jobs','POST',request))
        value=e.image('/jobs/'+job['id']+'/images/0')
        assert value.startswith(b'\x89PNG\r\n\x1a\n') and len(value)>10000
        (output/(name+'.png')).write_bytes(value)
        jobs.append({'name':name,'id':job['id'],'elapsedMillis':job['elapsedMillis'],'request':job['request'],'sha256':hashlib.sha256(value).hexdigest()})
        e.emit({'completed':name,'elapsedMillis':job['elapsedMillis']})
        e.d.write(output/'jobs.json',json.dumps(jobs,indent=2))
    assert len({j['sha256'] for j in jobs})==4
    e.run(['systemctl','restart',e.unit('image-generation-service')]);e.d.health(23340)
    for job in jobs:
        saved=e.image('/jobs/'+job['id'])
        assert saved['request']==job['request']
        assert hashlib.sha256(e.image('/jobs/'+job['id']+'/images/0')).hexdigest()==job['sha256']
    assert len(e.image('/styles'))==24
    report={'release':s.NAME,'builtinCount':24,'ownerIsolation':True,'createIdempotency':True,'updateIdempotency':True,'conflictsRejected':True,'templateValidation':True,'deletedStyleReplay':True,'ownedHistoricalReuse':True,'restartPersistence':True,'jobs':jobs}
    e.d.write(output/'acceptance.json',json.dumps(report,indent=2));e.emit({'accepted':True,'jobs':len(jobs)})


if __name__=='__main__':
    try:main()
    except Exception as error:
        e.emit({'failed':True,'type':type(error).__name__,'reason':str(error) if isinstance(error,(AssertionError,RuntimeError)) else 'private_diagnostic_suppressed'})
        raise SystemExit(1)
