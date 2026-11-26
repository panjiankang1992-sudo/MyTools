"""只回退本次工具箱前后端，不回退数据库，不覆盖后续其他发布。"""
from pathlib import Path
import fcntl,ctypes,hashlib,json,os,pwd,grp,shutil,subprocess,time,urllib.request
root=Path('/opt/yuyutian/toolbox')
release=root/'releases/local-textbook-20261018-172325'
lock=(root/'.deployment.lock').open('a')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
manifest=json.loads((release/'release-manifest.json').read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
index=next(item['sha256'] for item in manifest['files'] if item['path']=='index.html')
assert sha(root/'backend/toolbox-service.jar')==manifest['backendSha256'],'Another backend release is active; stop'
assert sha(root/'frontend/index.html')==index,'Another frontend release is active; stop'
assert sha(release/'candidate-frontend/index.html')==manifest['previousIndexSha256'],'Frontend backup mismatch'
assert sha(release/'previous-backend.jar')==manifest['previousBackendSha256'],'Backend backup mismatch'
lib=ctypes.CDLL(None,use_errno=True)
call=lib.renameat2
call.argtypes=[ctypes.c_int,ctypes.c_char_p,ctypes.c_int,ctypes.c_char_p,ctypes.c_uint]
if call(-100,os.fsencode(root/'frontend'),-100,os.fsencode(release/'candidate-frontend'),2):raise OSError(ctypes.get_errno(),'Frontend rollback failed')
target=root/'backend/toolbox-service.jar.rollback'
shutil.copyfile(release/'previous-backend.jar',target)
os.chmod(target,0o640)
os.chown(target,pwd.getpwnam('mytools').pw_uid,grp.getgrnam('mytools').gr_gid)
os.replace(target,root/'backend/toolbox-service.jar')
subprocess.run(['systemctl','restart','toolbox-service.service'],check=True,timeout=25)
for _ in range(40):
 try:
  with urllib.request.urlopen('http://127.0.0.1:23112/public/v1/health',timeout=3) as response:
   if json.load(response).get('status')=='UP':
    print(json.dumps({'status':'rolled-back','databaseModified':False}));break
 except Exception:pass
 time.sleep(1)
else:raise RuntimeError('Rollback health check failed')
