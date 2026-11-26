#!/usr/bin/env python3
"""受控延长 GPU 锁等待并执行真实视频、图片、标签任务；不冒充八分钟推理基准。"""
import fcntl
import json
import signal
from pathlib import Path
import time
import urllib.request
import uuid

import deploy_video_generation as d
from accept_ltx_tasks import scheduler, submit, wait, ROOT, TERMINAL

OUT=ROOT/'ltx-mixed-20260921'


def image_api(path, body=None):
    """只使用本机图片服务，测试图片归属验收账号。"""
    env=d.values(d.ROOT/'config/image-generation.env')
    req=urllib.request.Request('http://127.0.0.1:23340/internal/v1/images'+path,
        data=None if body is None else json.dumps(body).encode(),headers={
            'Authorization':'Bearer '+env['IMAGE_GENERATION_INTERNAL_TOKEN'],'X-Owner-Id':'1','Content-Type':'application/json'})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=30) as response:
        return json.load(response)


def tag_definition():
    """单独验收已有标签步骤，避免提交虚构资产到正式标签回写流水线。"""
    name='video_ltx_tag_acceptance'
    definitions=scheduler('/api/v1/task-definitions')
    existing=next((x for x in definitions if x['name']==name),None)
    if existing:
        return name
    media=next(x for x in scheduler('/api/v1/execution-topology/clusters') if x['name']=='media')
    definition=scheduler('/api/v1/task-definitions','POST',{'name':name,'description':'Isolated LTX GPU coordination acceptance',
        'taskType':'IMMEDIATE','timeoutSeconds':900,'clusterId':media['id'],'executionMode':'SINGLE_NODE','enabled':True,
        'maxConcurrency':1,'overlapPolicy':'QUEUE','misfirePolicy':'IGNORE','parameterSchema':{},'resultSchema':{}})
    scheduler('/api/v1/task-definitions/'+definition['id']+'/steps','POST',{'name':'generate_tags','description':'Real tag inference only',
        'stepKind':'NORMAL','scriptPackage':'media_generate_tags','scriptVersion':'1.4.1','entrypoint':'scripts/main.py',
        'argumentsTemplate':[],'enabled':True,'timeoutSeconds':180,'failurePolicy':'FAIL_TASK','sequenceNumber':10,'maxAttempts':1})
    return name


def main():
    """超过旧 120 秒等待边界后释放锁，检查三类真实任务均能完成。"""
    def terminate(*unused):
        raise SystemExit(143)
    signal.signal(signal.SIGTERM,terminate)
    OUT.mkdir(parents=True,exist_ok=True)
    assert not (OUT/'result.json').exists(),'already_verified'
    with d.task_connection() as db,db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_instance WHERE status IN ('RUNNING','QUEUED','CANCELLING')")
        assert cursor.fetchone()[0]==0,'production_tasks_active'
    sources=json.loads((ROOT/'ltx-accept-20260921-r2/inputs.json').read_text())
    case=sources[0];tag_name=tag_definition();tasks=[];image_id=None;history=[]
    try:
        with (ROOT.parent/'image-generation/gpu.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            job,video=submit(case,2026,'ltx-mixed-video-20260921');tasks.append(video)
            deadline=time.monotonic()+60
            while scheduler('/api/v1/task-instances/'+video)['status']!='RUNNING':
                assert time.monotonic()<deadline,'video_not_dispatched'
                time.sleep(1)
            image=image_api('/jobs',{'resourceId':'krea2-local','mode':'TEXT_TO_IMAGE',
                'prompt':'A small blue ceramic vase on a pale table, soft studio lighting.',
                'references':[],'size':'1024x1024','count':1,'seed':2026,'idempotencyKey':'ltx-mixed-image-20260921'})
            image_id=image['id']
            tag=scheduler('/api/v1/task-instances','POST',{'taskName':tag_name,'idempotencyKey':'ltx-mixed-tag-20260921',
                'businessType':'VIDEO_ACCEPTANCE','businessId':job,'priority':40,'parameters':{
                    'sourcePath':str(ROOT/'uploads'/case['uploadId']),'filename':'acceptance-product.png',
                    'mimeType':'image/png','contentSha256':case['sha256']}})['id'];tasks.append(tag)
            held=time.monotonic()
            while time.monotonic()-held<130:
                video_state=scheduler('/api/v1/task-instances/'+video)
                tag_state=scheduler('/api/v1/task-instances/'+tag)
                image_state=image_api('/jobs/'+image_id)
                row={'elapsedSeconds':round(time.monotonic()-held,2),'video':video_state['status'],
                     'tag':tag_state['status'],'image':image_state['status']}
                history.append(row)
                (OUT/'queue.json').write_text(json.dumps(history,indent=2)+'\n')
                assert row['video']=='RUNNING' and row['tag']=='QUEUED', 'gpu_admission_failed'
                assert row['image'] not in ('RUNNING','FAILED','SUCCEEDED'), 'image_started_too_early'
                time.sleep(5)
        video_result=wait(video);tag_result=wait(tag)
        deadline=time.monotonic()+900
        while True:
            image_result=image_api('/jobs/'+image_id)
            if image_result['status'] in TERMINAL:break
            assert time.monotonic()<deadline,'image_timeout'
            time.sleep(3)
        result={'controlledLockHoldSeconds':130,'realEightMinuteVideo':False,'videoTaskId':video,'tagTaskId':tag,
                'imageJobId':image_id,'video':video_result['status'],'tag':tag_result['status'],'image':image_result['status'],
                'queueSamples':len(history),'maxObservedQueuedSeconds':history[-1]['elapsedSeconds']}
        (OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
        assert all(result[k]=='SUCCEEDED' for k in ('video','tag','image')),'mixed_task_failed'
    finally:
        for task in tasks:
            if scheduler('/api/v1/task-instances/'+task)['status'] not in TERMINAL:
                scheduler('/api/v1/task-instances/'+task+'/cancel','POST',{})
        if image_id and image_api('/jobs/'+image_id)['status'] not in TERMINAL:
            image_api('/jobs/'+image_id+'/cancel',{})


if __name__=='__main__':main()
