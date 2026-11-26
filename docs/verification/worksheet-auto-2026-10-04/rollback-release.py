"""显式回退本次程序和前端；保留增量数据库表及迁移历史。"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from urllib.request import urlopen

root = Path('/opt/yuyutian/toolbox')
release = root / 'releases/worksheet-auto-20261004-144500'
manifest = json.loads((release / 'release-files.json').read_text())
frontend, backend = root / 'frontend', root / 'backend/toolbox-service.jar'
previous_frontend, previous_backend = release / 'previous-frontend', release / 'previous-backend.jar'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exchange(left, right):
    function = ctypes.CDLL(None, use_errno=True).renameat2
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    if function(-100, os.fsencode(left), -100, os.fsencode(right), 2):
        raise OSError(ctypes.get_errno(), 'Atomic exchange failed')


def ready():
    subprocess.run(['systemctl', 'restart', 'toolbox-service.service'], check=True, timeout=45)
    for _ in range(35):
        try:
            with urlopen('http://127.0.0.1:23113/public/v1/textbooks', timeout=3) as response:
                assert len(json.load(response)['data']['editions']) == 5
                return
        except Exception:
            time.sleep(1)
    raise RuntimeError('Service readiness failed')


current_index = next(item['sha256'] for item in manifest['files'] if item['path'] == 'index.html')
assert digest(frontend / 'index.html') == current_index
assert digest(backend) == manifest['backend']['sha256']
assert digest(previous_frontend / 'index.html') == manifest['previousIndexSha256']
assert digest(previous_backend) == manifest['previousBackendSha256']
shutil.copy2(backend, release / 'rolled-back-backend.jar')
shutil.copy2(previous_backend, release / 'restore-backend.jar')
current_stat, previous_stat = backend.stat(), previous_backend.stat()
os.chown(release / 'rolled-back-backend.jar', current_stat.st_uid, current_stat.st_gid)
os.chown(release / 'restore-backend.jar', previous_stat.st_uid, previous_stat.st_gid)
service_user = subprocess.check_output(['systemctl', 'show', 'toolbox-service.service', '-p', 'User', '--value'], text=True).strip()
subprocess.run(['sudo', '-n', '-u', service_user, 'test', '-r', str(release / 'restore-backend.jar')], check=True)
frontend_changed = False
try:
    os.replace(release / 'restore-backend.jar', backend)
    ready()
    exchange(frontend, previous_frontend)
    frontend_changed = True
    assert digest(frontend / 'index.html') == manifest['previousIndexSha256']
except BaseException:
    if frontend_changed:
        exchange(frontend, previous_frontend)
    shutil.copy2(release / 'rolled-back-backend.jar', release / 'restore-current-backend.jar')
    os.chown(release / 'restore-current-backend.jar', current_stat.st_uid, current_stat.st_gid)
    os.replace(release / 'restore-current-backend.jar', backend)
    ready()
    raise
print(json.dumps({'rolledBack': manifest['releaseId'], 'databasePreserved': True, 'catalogPreserved': True}))
