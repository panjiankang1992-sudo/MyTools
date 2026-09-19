#!/usr/bin/env python3
"""对效果不明确的风格追加同题材无风格对照，不覆盖首轮证据。"""
import importlib.util
import json
from pathlib import Path
import time
import hashlib

spec=importlib.util.spec_from_file_location('matrix',Path(__file__).with_name('verify_all_image_styles.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
e=m.e


def main():
    cases=json.loads(Path('/tmp/image-style-supplements.json').read_text())
    output=m.OUTPUT/'supplements';output.mkdir(exist_ok=True)
    rows=json.loads((output/'report.json').read_text()) if (output/'report.json').exists() else []
    for case in cases:
        for enabled in [False,True]:
            name=case['id']+('-styled' if enabled else '-baseline')
            if any(x['name']==name for x in rows):continue
            body={'resourceId':'krea2-local','prompt':case['prompt'],'mode':'TEXT_TO_IMAGE','references':[],'size':'1024x1024','count':1,'seed':933,'idempotencyKey':m.NAME+'-extra-'+name}
            if enabled:body.update(styleId=case['styleId'],styleVersion=1)
            job=e.image('/jobs','POST',body);e.emit({'supplementStarted':name})
            deadline=time.monotonic()+1200
            while job['status'] not in m.TERMINAL and time.monotonic()<deadline:
                time.sleep(3);job=e.image('/jobs/'+job['id'])
            assert job['status'] in m.TERMINAL,'supplement_timeout'
            row={'name':name,'styleId':case['styleId'],'status':job['status'],'jobId':job['id'],'request':job['request'],'elapsedMillis':job['elapsedMillis']}
            if job['status']=='SUCCEEDED':
                raw=e.image('/jobs/'+job['id']+'/images/0');e.d.write(output/(name+'.png'),raw);row['sha256']=hashlib.sha256(raw).hexdigest()
            rows.append(row);e.d.write(output/'report.tmp',json.dumps(rows,indent=2));(output/'report.tmp').replace(output/'report.json')
            e.emit({'supplementFinished':name,'status':job['status']})


if __name__=='__main__':
    try:main()
    except Exception as error:
        e.emit({'failed':True,'type':type(error).__name__});raise SystemExit(1)
