from pathlib import Path
import subprocess,json,zipfile,re,hashlib,shutil,time,urllib.request
ROOT=Path('/opt/yuyutian/mytools');OUT=ROOT/'releases/media-threshold-20260926-v1';STAGE=Path('/tmp/media-100m-stage')
def run(*a):return subprocess.check_output(a,text=True).strip()
assert not OUT.exists();OUT.mkdir()
with zipfile.ZipFile(STAGE/'delta.zip') as z:z.extractall(OUT/'delta')
units={'media':'mytools-media-library-service','scheduler':'mytools-task-scheduler-service','executor':'mytools-task-executor-service'}
commands={}
for k,u in units.items():
 s=run('systemctl','show',u,'-p','ExecStart');commands[k]=re.search(r'argv\[\]=(.*?) ; ignore_errors=',s).group(1)
(OUT/'previous-commands.json').write_text(json.dumps(commands))
for k in ['media','scheduler']:
 old=Path(re.search(r'-jar\s+(\S+)',commands[k]).group(1));target=OUT/(k+'.jar')
 files={str(f.relative_to(OUT/'delta'/k)):f for f in (OUT/'delta'/k).rglob('*') if f.is_file()}
 with zipfile.ZipFile(old) as src,zipfile.ZipFile(target,'w') as dest:
  for item in src.infolist():dest.writestr(item,files.pop(item.filename).read_bytes() if item.filename in files else src.read(item.filename))
  for n,p in files.items():dest.writestr(n,p.read_bytes())
 commands[k]=commands[k].replace(str(old),str(target))
oldroot=ROOT/'releases/video-ltx-20260921-v2/task-packages';pkg=OUT/'task-packages'
run('cp','-al',str(oldroot),str(pkg))
index=json.loads((pkg/'package-index.json').read_text())
for name in (OUT/'delta/packages').iterdir():
 for version in name.iterdir():
  target=pkg/name.name/version.name;assert not target.exists();shutil.copytree(version,target)
  files=[{'path':str(f.relative_to(target)),'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'sizeBytes':f.stat().st_size} for f in sorted(target.rglob('*')) if f.is_file()]
  index['packages'].append({'name':name.name,'version':version.name,'entrypoint':'scripts/main.py','files':files})
index['packages'].sort(key=lambda x:(x['name'],x['version']));index['packageCount']=len(index['packages']);index['contentSha256']=hashlib.sha256(json.dumps(index['packages'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
(pkg/'package-index.json').unlink();(pkg/'package-index.json').write_text(json.dumps(index,indent=2))
for k,u in units.items():
 p=Path('/etc/systemd/system')/(u+'.service.d')/'zzzzzzzzzz-media-threshold.conf';p.parent.mkdir(exist_ok=True)
 assert not p.exists()
 text='[Service]\n'
 if k=='executor':text+='ExecStart=\nExecStart='+commands[k]+' --executor.script-root='+str(pkg)+'\n'
 else:text+='ExecStart=\nExecStart='+commands[k]+'\n'
 p.write_text(text)
run('systemctl','daemon-reload')
# 先使新脚本根目录生效，再由 Flyway 迁移切换步骤版本。
run('systemctl','restart',units['executor']);run('systemctl','restart',units['scheduler']);run('systemctl','restart',units['media'])
for port in [23410,23300]:
 for i in range(40):
  try:
   with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health',timeout=2) as r:
    if json.load(r).get('status')=='UP':break
  except Exception:time.sleep(1)
 else:raise RuntimeError('health_failed_'+str(port))
print(json.dumps({'release':str(OUT),'units':{k:run('systemctl','is-active',u) for k,u in units.items()},'thresholdBytes':104857600}))
