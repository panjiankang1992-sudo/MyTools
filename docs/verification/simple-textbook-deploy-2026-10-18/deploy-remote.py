"""仅切换工具箱产物，健康检查失败自动回滚代码；不修改生产配置或教材数据。"""
from pathlib import Path
import fcntl, ctypes, hashlib, json, os, pwd, grp, shutil, subprocess, sys, time, urllib.request, zipfile
ROOT=Path('/opt/yuyutian/toolbox')
RELEASE=ROOT/'releases/simple-textbook-20261018-160439'
lock=(ROOT/'.deployment.lock').open('a')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
manifest=json.loads((RELEASE/'release-manifest.json').read_text())
frontend=ROOT/'frontend'
candidate=RELEASE/'candidate-frontend'
backend=ROOT/'backend/toolbox-service.jar'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def exchange(left,right):
    library=ctypes.CDLL(None,use_errno=True)
    call=library.renameat2
    call.argtypes=[ctypes.c_int,ctypes.c_char_p,ctypes.c_int,ctypes.c_char_p,ctypes.c_uint]
    if call(-100,os.fsencode(left),-100,os.fsencode(right),2):
        raise OSError(ctypes.get_errno(),'Atomic frontend exchange failed')
def local_json(path):
    with urllib.request.urlopen('http://127.0.0.1:23112'+path,timeout=3) as response:
        return json.load(response)
def wait_health():
    for _ in range(40):
        try:
            if local_json('/public/v1/health').get('status')=='UP': return
        except Exception: pass
        time.sleep(1)
    raise RuntimeError('Backend health timeout')
def install_jar(source):
    target=backend.with_suffix('.jar.deploying')
    shutil.copyfile(source,target)
    os.chmod(target,0o640)
    os.chown(target,pwd.getpwnam('mytools').pw_uid,grp.getgrnam('mytools').gr_gid)
    os.replace(target,backend)
assert sha(backend)==manifest['previousBackendSha256'],'Backend baseline changed'
assert sha(frontend/'index.html')==manifest['previousIndexSha256'],'Frontend baseline changed'
assert sha(RELEASE/'previous-backend.jar')==manifest['previousBackendSha256'],'Rollback backend hash mismatch'
assert not any(path.is_symlink() for path in candidate.rglob('*')),'Candidate contains symbolic links'
assert sha(RELEASE/'next-backend.jar')==manifest['backendSha256'],'Backend upload hash mismatch'
with zipfile.ZipFile(RELEASE/'next-backend.jar') as archive:
    migrations={n.rsplit('/',1)[-1]:hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist() if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql')}
assert migrations==manifest['migrationSha256'],'Migration mismatch'
for item in manifest['files']:
    path=candidate/item['path']
    assert path.resolve().is_relative_to(candidate.resolve()),'Invalid frontend path'
    assert path.stat().st_size==item['bytes'] and sha(path)==item['sha256'],'Frontend upload hash mismatch: '+item['path']
subprocess.run(['chown','-R','mytools:mytools',str(candidate)],check=True)
for directory,_,files in os.walk(candidate):
    os.chmod(directory,0o755)
    for name in files: os.chmod(Path(directory)/name,0o644)
# 紧邻切换再校验，防止覆盖另一次并发部署。
assert sha(backend)==manifest['previousBackendSha256'] and sha(frontend/'index.html')==manifest['previousIndexSha256'],'Live baseline changed during staging'
changed_backend=False
changed_frontend=False
started=time.time()
try:
    install_jar(RELEASE/'next-backend.jar')
    changed_backend=True
    subprocess.run(['systemctl','restart','toolbox-service.service'],check=True,timeout=25)
    wait_health()
    exchange(frontend,candidate)
    changed_frontend=True
    with urllib.request.urlopen('http://127.0.0.1:23113/index.html',timeout=5) as response:
        served=hashlib.sha256(response.read()).hexdigest()
    expected=next(item['sha256'] for item in manifest['files'] if item['path']=='index.html')
    assert served==expected,'Nginx served a different frontend'
    assert sha(backend)==manifest['backendSha256'],'Backend changed after restart'
    subprocess.run(['systemctl','is-active','--quiet','toolbox-service.service'],check=True)
    result={'releaseId':manifest['releaseId'],'status':'deployed','backendSha256':sha(backend),'indexSha256':served,'frontendFilesVerified':len(manifest['files']),'backupDirectory':str(RELEASE),'frontendBackup':str(candidate),'databaseBackup':'database-before.sql','contentModified':False,'configurationModified':False,'startedAt':started,'finishedAt':time.time()}
    (RELEASE/'deployment.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))
except BaseException:
    rollback_errors=[]
    if changed_frontend:
        try: exchange(frontend,candidate)
        except Exception: rollback_errors.append('frontend')
    if changed_backend:
        try:
            install_jar(RELEASE/'previous-backend.jar')
            subprocess.run(['systemctl','restart','toolbox-service.service'],check=True,timeout=25)
            wait_health()
        except Exception: rollback_errors.append('backend')
    print(json.dumps({'status':'rollback-failed' if rollback_errors else 'rolled-back','releaseId':manifest['releaseId'],'rollbackErrors':rollback_errors}))
    raise
