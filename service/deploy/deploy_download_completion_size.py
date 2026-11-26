from pathlib import Path
import re,subprocess,zipfile,json,time,urllib.request
u='mytools-message-automation-service'
s=subprocess.check_output(['systemctl','show',u,'-p','ExecStart'],text=True);cmd=re.search(r'argv\[\]=(.*?) ; ignore_errors=',s).group(1);old=re.search(r'-jar\s+(\S+)',cmd).group(1)
root=Path('/opt/yuyutian/mytools/releases/download-size-20260927-v1');root.mkdir(exist_ok=False);new=root/'message-automation-service.jar'
with zipfile.ZipFile('/tmp/download-size-stage/classes.zip') as z:changes={n:z.read(n) for n in z.namelist()}
with zipfile.ZipFile(old) as src,zipfile.ZipFile(new,'w') as out:
 for info in src.infolist():out.writestr(info,changes.pop(info.filename) if info.filename in changes else src.read(info.filename))
 for n,data in changes.items():out.writestr(n,data)
p=Path('/etc/systemd/system')/(u+'.service.d')/'zzzzzzzzzzzzzzzzzzzzzzzz-download-size.conf';assert not p.exists();p.write_text('[Service]\nExecStart=\nExecStart='+cmd.replace(old,str(new))+'\n')
subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','restart',u],check=True)
for i in range(35):
 try:
  with urllib.request.urlopen('http://127.0.0.1:23260/actuator/health',timeout=2) as r:
   if json.load(r).get('status')=='UP':break
 except Exception:time.sleep(1)
else:raise RuntimeError('health_check_failed')
pid=subprocess.check_output(['systemctl','show',u,'-p','MainPID','--value'],text=True).strip();assert str(new).encode() in Path('/proc/'+pid+'/cmdline').read_bytes()
print(json.dumps({'release':str(root),'health':'UP'}))
