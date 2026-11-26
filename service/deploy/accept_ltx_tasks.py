#!/usr/bin/env python3
"""通过真实 Scheduler 和执行器验证 LTX，产品模式保持关闭，不暂停生产任务定义。"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import time
import urllib.request
import uuid

import deploy_video_generation as d

ROOT=Path('/opt/yuyutian/mytools/runtime/video-generation')
TERMINAL={'SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','PARTIAL_SUCCESS','SKIPPED'}
CASES=[('product','镜头缓慢向前推进。茶壶、草莓和盘子保持静止，保持红色与原有光照。不要新增人物或物品。'),
       ('portrait','人物自然眨眼并轻轻微笑，头部保持稳定，镜头固定，保持原来的面貌、服装和背景。'),
       ('landscape','镜头固定，湖水泛起细微波纹，保留山峰、雪和日落颜色，不要改变场景。')]


def scheduler(path, method='GET', body=None):
    """使用现有受管身份调用调度器；错误只输出状态码。"""
    code,value,_=d.request(23410,path,method,body,identity='video' if path.startswith('/api/v1/task-instances') else 'operator')
    if code not in (200,201,202,204):
        raise RuntimeError('scheduler_http_'+str(code))
    return value


def upload(path):
    """上传测试图片，令牌只在远端进程内读取。"""
    token=d.environment()['VIDEO_GENERATION_INTERNAL_TOKEN']
    req=urllib.request.Request('http://127.0.0.1:23341/internal/v1/videos/uploads/image',data=path.read_bytes(),
        headers={'Authorization':'Bearer '+token,'X-Owner-Id':'1','Content-Type':'image/png'},method='POST')
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=120) as response:
        return json.load(response)['id']


def prepare_sources(folder):
    """读取旧稳定性组的人像原图；它未参与 LTX 开发样例调参。"""
    import pymysql
    env=d.environment()
    with pymysql.connect(host=env.get('VIDEO_GENERATION_DB_HOST','127.0.0.1'),
        user=env.get('VIDEO_GENERATION_DB_USER','mytools_video_generation'),
        password=env['VIDEO_GENERATION_DB_PASSWORD'],database=env.get('VIDEO_GENERATION_DB_NAME','mytools_video_generation')) as db:
        with db.cursor() as cursor:
            cursor.execute('SELECT upload_id FROM video_input WHERE job_id=%s ORDER BY ordinal',
                           ('5158b3a1-26f5-440d-bea8-d6250c642d65',))
            uid=cursor.fetchone()[0]
    shutil.copyfile(ROOT/'uploads'/uid,folder/'portrait.png')
    result=[]
    for name,prompt in CASES:
        path=folder/(name+'.png');uid=upload(path)
        result.append({'name':name,'prompt':prompt,'uploadId':uid,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    return result


def submit(case, seed, key):
    """只提交固定资源参数，任务包与工作流由服务器固定。"""
    job=str(uuid.uuid4())
    parameters={'jobId':job,'resourceId':'ltx-video-2b-local','mode':'FIRST_FRAME','prompt':case['prompt'],
        'seed':seed,'width':640,'height':640,'workflowRevision':'video-ltx-2b-i2v-v1',
        'modelRevision':'ltxv-2b-0.9.8-distilled-8984fa25','audioPolicy':'SILENT',
        'output':{'size':'640x640','frames':49,'fps':16},
        'resolvedInputs':[{'inputId':str(uuid.uuid4()),'uploadId':case['uploadId'],'role':'FIRST_FRAME','sha256':case['sha256']}]}
    value=scheduler('/api/v1/task-instances','POST',{'taskName':'video_generate','idempotencyKey':key,
        'businessType':'VIDEO_ACCEPTANCE','businessId':job,'priority':40,'parameters':parameters})
    return job,value['id']


def wait(task, timeout=900):
    """有界等待真实执行结果，不把已入队误记为成功。"""
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        value=scheduler('/api/v1/task-instances/'+task)
        if value['status'] in TERMINAL:
            return value
        time.sleep(3)
    raise TimeoutError('acceptance_task_timeout')


def main():
    """独立六样例并补足连续十任务，输出原片与实际英文提示词。"""
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--recover',action='store_true')
    parser.add_argument('--count',type=int,default=10,choices=range(1,11));args=parser.parse_args()
    if args.recover:
        if (args.output/'active.json').exists():
            active=json.loads((args.output/'active.json').read_text())['taskId']
            scheduler('/api/v1/task-instances/'+active+'/cancel','POST',{})
            wait(active,180)
        return
    def terminate(*unused):
        raise SystemExit(143)
    signal.signal(signal.SIGTERM,terminate)
    args.output.mkdir(parents=True,exist_ok=True)
    assert not (args.output/'samples.json').exists(),'output_already_used'
    sources=prepare_sources(Path('/tmp/ltx-fixtures'))
    (args.output/'inputs.json').write_text(json.dumps(sources,ensure_ascii=False,indent=2)+'\n')
    for case in sources:shutil.copyfile('/tmp/ltx-fixtures/'+case['name']+'.png',args.output/(case['name']+'-source.png'))
    samples=[];active=None
    try:
        for index in range(args.count):
            case=sources[(index//2)%3];seed=(42,932)[index%2]
            name=f'{index:02d}-'+case['name']+'-seed'+str(seed);started=time.monotonic()
            job,active=submit(case,seed,args.output.name+'-'+str(index))
            (args.output/'active.json').write_text(json.dumps({'jobId':job,'taskId':active}))
            value=wait(active)
            sample={'name':name,'jobId':job,'taskId':active,'status':value['status'],'elapsedSeconds':round(time.monotonic()-started,2)}
            if value['status']=='SUCCEEDED':
                result=json.loads((ROOT/'outputs'/job/'result.json').read_text())
                sample['result']=result
                shutil.copyfile(ROOT/'outputs'/job/'video.mp4',args.output/(name+'.mp4'))
            samples.append(sample)
            (args.output/'samples.json').write_text(json.dumps(samples,ensure_ascii=False,indent=2)+'\n')
            active=None;(args.output/'active.json').unlink()
            print(json.dumps({k:v for k,v in sample.items() if k!='result'}),flush=True)
            assert value['status']=='SUCCEEDED','acceptance_failed'
    finally:
        if active:
            scheduler('/api/v1/task-instances/'+active+'/cancel','POST',{})
            wait(active,180)


if __name__=='__main__':main()
