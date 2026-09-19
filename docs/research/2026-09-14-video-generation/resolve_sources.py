"""读取官方公开仓库版本元数据，为方案提供可固定的源码与权重版本。"""
import concurrent.futures
import datetime
import json
from pathlib import Path
import urllib.request

repos=['Wan-Video/Wan2.1','Wan-Video/Wan2.2','ali-vilab/VACE','Lightricks/LTX-Video','Lightricks/LTX-2','Lightricks/LTX-Desktop','Tencent-Hunyuan/HunyuanVideo-1.5','ModelTC/LightX2V','MiniMax-AI/MiniMax-H3','showlab/Kiwi-Edit','WeChatCV/NovaEdit','PKU-YuanGroup/Helios']
models=['Wan-AI/Wan2.1-VACE-1.3B','Wan-AI/Wan2.1-T2V-1.3B','Lightricks/LTX-Video','lightx2v/LightWan2.2-A14B','Lightricks/LTX-2.5']

def fetch(url):
    req=urllib.request.Request(url,headers={'User-Agent':'MyTools-video-research','Accept':'application/json'})
    with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)

def github(repo):
    meta=fetch('https://api.github.com/repos/'+repo)
    commit=fetch('https://api.github.com/repos/'+repo+'/commits/'+meta['default_branch'])
    return {'repository':repo,'url':meta['html_url'],'branch':meta['default_branch'],'commit':commit['sha'],'commitDate':commit['commit']['committer']['date'],'license':(meta.get('license') or {}).get('spdx_id')}

def model(name):
    meta=fetch('https://huggingface.co/api/models/'+name+'?blobs=true')
    files=[{'path':x['rfilename'],'bytes':x.get('size')} for x in meta.get('siblings',[]) if x['rfilename'].endswith(('.safetensors','.pth','.pt','.bin','.json'))]
    return {'model':name,'url':'https://huggingface.co/'+name,'revision':meta.get('sha'),'lastModified':meta.get('lastModified'),'gated':meta.get('gated'),'files':files}

result={'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'github':[],'models':[],'errors':[]}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    jobs={pool.submit(fn,name):(kind,name) for kind,fn,names in [('github',github,repos),('models',model,models)] for name in names}
    for future in concurrent.futures.as_completed(jobs):
        kind,name=jobs[future]
        try:result[kind].append(future.result())
        except Exception as error:result['errors'].append({'name':name,'error':type(error).__name__})
Path(__file__).with_name('sources-lock.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'github':result['github'],'models':[{'model':x['model'],'revision':x['revision'],'gated':x['gated'],'totalListedBytes':sum(f['bytes'] or 0 for f in x['files'])} for x in result['models']],'errors':result['errors']},indent=2))
