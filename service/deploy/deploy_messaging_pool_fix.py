#!/usr/bin/env python3
"""增量发布 Messaging 入站查询连接池修复，保留原 JAR 并在健康失败时回退。"""
from pathlib import Path
import subprocess,re,zipfile,json,time,hashlib
from urllib.request import urlopen
unit='mytools-messaging-service'
root=Path('/opt/yuyutian/mytools/releases/messaging-pool-fix-20261018-v1')
command=subprocess.check_output(['systemctl','show',unit,'-p','ExecStart'],text=True)
command=re.search(r'argv\[\]=(.*?) ; ignore_errors=',command).group(1)
old=re.search(r'-jar\s+(\S+)',command).group(1)
root.mkdir(exist_ok=False)
jar=root/'messaging-service.jar'
entry='BOOT-INF/classes/com/yuyutian/mytools/messaging/repository/MessagingRepository.class'
data=Path('/tmp/MessagingRepository-20261018.class').read_bytes()
with zipfile.ZipFile(old) as src,zipfile.ZipFile(jar,'w') as dest:
 assert entry in src.namelist()
 for item in src.infolist():dest.writestr(item,data if item.filename==entry else src.read(item.filename))
jar.chmod(0o644)
override=Path('/etc/systemd/system/'+unit+'.service.d/'+'z'*32+'-pool-fix.conf')
override.parent.mkdir(exist_ok=True)
assert not override.exists()
override.write_text('[Service]\nExecStart=\nExecStart='+command.replace(old,str(jar))+'\n')
try:
 subprocess.run(['systemctl','daemon-reload'],check=True)
 assert str(jar) in subprocess.check_output(['systemctl','show',unit,'-p','ExecStart'],text=True)
 subprocess.run(['systemctl','restart',unit],check=True)
 for _ in range(45):
  try:
   with urlopen('http://127.0.0.1:23250/actuator/health',timeout=2) as r:
    if json.load(r).get('status')=='UP':break
  except Exception:pass
  time.sleep(1)
 else:raise RuntimeError('health_failed')
except Exception:
 override.unlink();subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','restart',unit],check=True);raise
print(json.dumps({'release':str(root),'repositorySha256':hashlib.sha256(data).hexdigest(),'healthy':True}))
