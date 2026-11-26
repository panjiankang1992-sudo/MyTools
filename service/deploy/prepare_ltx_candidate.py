#!/usr/bin/env python3
"""下载固定版本候选权重并校验摘要，不加载 GPU。"""
import json
import subprocess
import hashlib
from pathlib import Path
PROXY = 'http://127.0.0.1:17890'

def fetch(url, path):
 """通过已有本机代理续传公开文件。"""
 subprocess.run(['curl','-fsSL','--proxy',PROXY,'--retry','3','--connect-timeout','20','--max-time','7200','--continue-at','-','-o',str(path),url],check=True)

def main():
 """固定公开来源、版本和目标路径，保存完整下载证据。"""
 root=Path('/opt/yuyutian/mytools/runtime/video-generation/ltx-candidate-20260921')
 root.mkdir(parents=True,exist_ok=True)
 models=Path('/opt/yuyutian/mytools/runtime/video-generation/runtime-v1/ComfyUI/models')
 proxy='http://127.0.0.1:17890'
 entries=[]
 for repo,revision,files in [
  ('Lightricks/LTX-Video','8984fa25007f376c1a299016d0957a37a2f797bb', [('ltxv-2b-0.9.8-distilled.safetensors','checkpoints')]),
  ('comfyanonymous/flux_text_encoders','6af2a98e3f615bdfa612fbd85da93d1ed5f69ef5',[('t5xxl_fp8_e4m3fn_scaled.safetensors','text_encoders')])]:
  raw=subprocess.check_output(['curl','-fsSL','--proxy',proxy,'--max-time','60','https://huggingface.co/api/models/'+repo+'/revision/'+revision+'?blobs=true'])
  data=json.loads(raw)
  for name,folder in files:
   item=next(x for x in data['siblings'] if x['rfilename']==name)
   entries.append({'repository':repo,'revision':revision,'path':name,'target':str(models/folder/name),'bytes':item['size'],'sha256':item['lfs']['sha256']})
 (root/'model-lock.json').write_text(json.dumps(entries,indent=2)+'\n')
 fetch('https://huggingface.co/Lightricks/LTX-Video/resolve/8984fa25007f376c1a299016d0957a37a2f797bb/LTX-Video-Open-Weights-License-0.X.txt',root/'LICENSE.txt')
 for item in entries:
  target=Path(item['target']);partial=target.with_suffix('.partial')
  if not target.exists():
   print(json.dumps({'downloading':target.name,'bytes':item['bytes']}),flush=True)
   fetch('https://huggingface.co/'+item['repository']+'/resolve/'+item['revision']+'/'+item['path'],partial)
   assert partial.stat().st_size==item['bytes']
   with partial.open('rb') as f: assert hashlib.file_digest(f,'sha256').hexdigest()==item['sha256']
   partial.rename(target);target.chmod(0o644)
  else:
   with target.open('rb') as f: assert hashlib.file_digest(f,'sha256').hexdigest()==item['sha256']
  print(json.dumps({'verified':target.name}),flush=True)
 (root/'ready.json').write_text(json.dumps({'ready':True,'files':entries},indent=2)+'\n')


if __name__ == "__main__":
 main()
