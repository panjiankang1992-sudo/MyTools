#!/usr/bin/env python3
"""对固定风格目录逐项生成相同主体对照，并持久化可恢复的验收记录。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import time

spec=importlib.util.spec_from_file_location('styles_deploy',Path(__file__).with_name('deploy_image_styles.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
e=s.e
NAME='image-styles-matrix-20260914-v1'
OUTPUT=s.ROOT/'runtime'/NAME
PROMPT='A red teapot and a plate of strawberries on a wooden table beside a window, with a small green potted plant and soft morning light.'
TERMINAL={'SUCCEEDED','FAILED','CANCELLED','PARTIAL_SUCCESS','UNCONFIRMED','TIMED_OUT'}


def save(report):
    e.d.write(OUTPUT/'report.tmp',json.dumps(report,indent=2))
    (OUTPUT/'report.tmp').replace(OUTPUT/'report.json')


def main():
    OUTPUT.mkdir(exist_ok=True);OUTPUT.chmod(0o700)
    styles=[x for x in e.image('/styles') if x['origin']=='BUILTIN'];assert len(styles)==24
    if (OUTPUT/'catalog.json').exists():
        assert json.loads((OUTPUT/'catalog.json').read_text())==styles,'catalog_changed'
    else:e.d.write(OUTPUT/'catalog.json',json.dumps(styles,indent=2))
    report=json.loads((OUTPUT/'report.json').read_text()) if (OUTPUT/'report.json').exists() else {'run':NAME,'prompt':PROMPT,'seed':932,'size':'1024x1024','results':[]}
    for style in [None,*styles]:
        name=style['id'] if style else 'baseline'
        if any(x['style']==name for x in report['results']):continue
        body={'resourceId':'krea2-local','prompt':PROMPT,'mode':'TEXT_TO_IMAGE','references':[],'size':'1024x1024','count':1,'seed':932,'idempotencyKey':NAME+'-'+name}
        if style:body.update(styleId=name,styleVersion=style['version'])
        job=e.image('/jobs','POST',body)
        e.emit({'started':name,'jobId':job['id'],'completed':len(report['results'])})
        deadline=time.monotonic()+1200
        while job['status'] not in TERMINAL and time.monotonic()<deadline:
            time.sleep(3);job=e.image('/jobs/'+job['id'])
        assert job['status'] in TERMINAL,'task_wait_timeout'
        row={'style':name,'jobId':job['id'],'status':job['status'],'elapsedMillis':job.get('elapsedMillis'),'errorCode':job.get('errorCode'),'request':job['request']}
        if job['status']=='SUCCEEDED':
            raw=e.image('/jobs/'+job['id']+'/images/0')
            assert raw[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',raw[16:24])==(1024,1024),'invalid_output'
            assert job['request']['prompt']==PROMPT,'subject_changed'
            if style:assert job['request']['effectivePrompt']==style['promptTemplate'].replace('{prompt}',PROMPT),'style_not_composed'
            e.d.write(OUTPUT/(name+'.png'),raw)
            row.update(sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw))
        report['results'].append(row);save(report)
        e.emit({'finished':name,'status':job['status'],'elapsedMillis':row['elapsedMillis'],'completed':len(report['results']),'total':25})
    e.emit({'matrixCompleted':True,'successes':sum(r['status']=='SUCCEEDED' for r in report['results']),'total':25})


if __name__=='__main__':
    try:main()
    except Exception as error:
        e.emit({'failed':True,'type':type(error).__name__,'reason':str(error) if isinstance(error,(AssertionError,RuntimeError)) else 'private_diagnostic_suppressed'})
        raise SystemExit(1)
