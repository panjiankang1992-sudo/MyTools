from pathlib import Path
import subprocess,re,zipfile,json,time,urllib.request
unit='mytools-task-executor-service'
def run(*a):return subprocess.check_output(a,text=True)
pid=run('systemctl','show',unit,'-p','MainPID','--value').strip()
assert not Path('/proc/'+pid+'/task/'+pid+'/children').read_text().strip(),'executor_busy'
s=run('systemctl','show',unit,'-p','ExecStart');cmd=re.search(r'argv\[\]=(.*?) ; ignore_errors=',s).group(1);old=re.search(r'-jar\s+(\S+)',cmd).group(1)
root=Path('/opt/yuyutian/mytools/releases/download-tag-env-20261003-v1');root.mkdir(exist_ok=False);new=root/'task-executor-service.jar'
key='BOOT-INF/classes/application.yml'
with zipfile.ZipFile(old) as src:
 text=src.read(key).decode();m=re.search(r'(?m)^    download_record_tags:\n((?:      .*\n)*)',text);assert m
 assert 'MEDIA_LIBRARY_INTERNAL_TOKEN:' not in m.group(0)
 text=text[:m.end()]+'''      MEDIA_LIBRARY_URL: ${MEDIA_LIBRARY_URL:http://127.0.0.1:23300}
      MEDIA_LIBRARY_INTERNAL_TOKEN: ${MEDIA_LIBRARY_INTERNAL_TOKEN:}
'''+text[m.end():]
 with zipfile.ZipFile(new,'w') as out:
  for info in src.infolist():out.writestr(info,text.encode() if info.filename==key else src.read(info.filename))
override=Path('/etc/systemd/system')/(unit+'.service.d')/'zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-download-tag-env.conf'
assert not override.exists();override.write_text('[Service]\nExecStart=\nExecStart='+cmd.replace(old,str(new))+'\n')
try:
 run('systemctl','daemon-reload');run('systemctl','restart',unit)
 for i in range(40):
  try:
   with urllib.request.urlopen('http://127.0.0.1:23101/actuator/health',timeout=2) as f:
    if json.load(f).get('status')=='UP':break
  except Exception:pass
  time.sleep(1)
 else:raise RuntimeError('health_failed')
 assert str(new) in run('systemctl','show',unit,'-p','ExecStart')
 print('DEPLOYED health=UP')
except Exception:
 override.unlink();run('systemctl','daemon-reload');run('systemctl','restart',unit);raise
