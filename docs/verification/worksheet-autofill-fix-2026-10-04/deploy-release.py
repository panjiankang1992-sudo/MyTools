"""校验、备份并切换字帖自动补全版本，失败时恢复程序与前端。"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import time
from urllib.request import Request, urlopen

stage = Path(sys.argv[1])
manifest = json.loads((stage / 'release-files.json').read_text())
assert manifest['releaseId'] == 'autofill-fix-20261004-175000'
root = Path('/opt/yuyutian/toolbox')
frontend = root / 'frontend'
backend = root / 'backend/toolbox-service.jar'
release = root / 'releases' / manifest['releaseId']
backup_frontend = release / 'previous-frontend'
backup_backend = release / 'previous-backend.jar'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exchange(left, right):
    library = ctypes.CDLL(None, use_errno=True)
    function = library.renameat2
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    if function(-100, os.fsencode(left), -100, os.fsencode(right), 2):
        raise OSError(ctypes.get_errno(), 'Atomic directory exchange failed')


def restart():
    subprocess.run(['systemctl', 'restart', 'toolbox-service.service'], check=True, timeout=45)


def wait_ready():
    for _ in range(35):
        try:
            with urlopen('http://127.0.0.1:23113/public/v1/textbooks', timeout=3) as response:
                data = json.load(response)['data']
                assert len(data['editions']) == 5
                assert next(item for item in data['editions'] if item['subject'] == 'english')['contentVersion'] == '1.0.1'
                return
        except Exception:
            time.sleep(1)
    raise RuntimeError('Backend readiness failed')


def translation_check():
    origin = 'https://toolbox.yuyutian.top'
    headers = {'Origin': origin, 'Content-Type': 'application/json'}
    with urlopen(Request('http://127.0.0.1:23113/public/v1/session', data=b'{}', headers=headers), timeout=10) as response:
        cookie = response.headers['Set-Cookie'].split(';', 1)[0]
        csrf = json.load(response)['data']['csrfToken']
    headers.update({'Cookie': cookie, 'X-Toolbox-Csrf': csrf})
    items = [{'id': 'weekday-1', 'text': '\u661f\u671f\u4e00'}, {'id': 'weekday-2', 'text': 'Monday'}]
    with urlopen(Request('http://127.0.0.1:23113/public/v1/translations', data=json.dumps({'items': items}).encode(), headers=headers), timeout=30) as response:
        values = json.load(response)['data']['items']
        assert len(values) == 2 and all(item['status'] == 'translated' for item in values)
        assert all(item['englishText'] == 'Monday' and item['meaning'] == '\u661f\u671f\u4e00' for item in values)


assert digest(frontend / 'index.html') == manifest['previousIndexSha256'], 'Frontend baseline changed'
assert digest(backend) == manifest['previousBackendSha256'], 'Backend baseline changed'
assert digest(root / 'content/catalog/published.json') == manifest['catalogSha256'], 'Catalog baseline changed'
assert digest(stage / 'toolbox-service.jar') == manifest['backend']['sha256'], 'Backend package mismatch'
backend_stat = backend.stat()
service_user = subprocess.check_output(['systemctl', 'show', 'toolbox-service.service', '-p', 'User', '--value'], text=True).strip()
assert service_user
release.mkdir(parents=True, exist_ok=False)
shutil.copy2(backend, backup_backend)
os.chown(backup_backend, backend_stat.st_uid, backend_stat.st_gid)
shutil.copytree(frontend, backup_frontend)
with tarfile.open(stage / 'frontend.tar.gz', 'r:gz') as archive:
    archive.extractall(backup_frontend, filter='data')
for item in manifest['files']:
    target = backup_frontend / item['path']
    assert target.resolve().is_relative_to(backup_frontend.resolve())
    assert target.stat().st_size == item['bytes'] and digest(target) == item['sha256'], item['path']
for directory, _, names in os.walk(backup_frontend):
    os.chmod(directory, 0o755)
    for name in names:
        os.chmod(Path(directory) / name, 0o644)
shutil.copy2(stage / 'toolbox-service.jar', release / 'next-backend.jar')
os.chmod(release / 'next-backend.jar', backend_stat.st_mode & 0o777)
os.chown(release / 'next-backend.jar', backend_stat.st_uid, backend_stat.st_gid)
# 发布与回退候选都必须实际允许服务账号读取，不能只核对内容哈希。
for candidate in [backend, backup_backend, release / 'next-backend.jar']:
    subprocess.run(['sudo', '-n', '-u', service_user, 'test', '-r', str(candidate)], check=True)
shutil.copy2(stage / 'release-files.json', release / 'release-files.json')
shutil.copy2(stage / 'rollback-release.py', release / 'rollback-release.py')
result = {'releaseId': manifest['releaseId'], 'backendSha256': manifest['backend']['sha256'],
          'indexSha256': digest(backup_frontend / 'index.html'), 'verifiedFrontendFiles': len(manifest['files']),
          'backendBackup': str(backup_backend), 'frontendBackup': str(backup_frontend),
          'databaseMigration': 'none; existing schema and Flyway history preserved',
          'contentChanged': False}
result.update({'backendUid': backend_stat.st_uid, 'backendGid': backend_stat.st_gid, 'serviceReadabilityVerified': True})
(release / 'prepared-deployment.json').write_text(json.dumps(result, indent=2) + '\n')
assert digest(frontend / 'index.html') == manifest['previousIndexSha256'], 'Frontend changed during preparation'
assert digest(backend) == manifest['previousBackendSha256'], 'Backend changed during preparation'
backend_changed = frontend_changed = False
try:
    os.replace(release / 'next-backend.jar', backend)
    backend_changed = True
    restart()
    wait_ready()
    translation_check()
    exchange(frontend, backup_frontend)
    frontend_changed = True
    with urlopen('http://127.0.0.1:23113/index.html', timeout=10) as response:
        assert hashlib.sha256(response.read()).hexdigest() == result['indexSha256']
    result.update({'originIndex': 'pass', 'originTranslation': 'pass', 'catalog': 'pass', 'serviceRestarted': True})
    (release / 'deployment.json').write_text(json.dumps(result, indent=2) + '\n')
except BaseException as failure:
    failures = []
    if frontend_changed:
        try:
            exchange(frontend, backup_frontend)
        except Exception as error:
            failures.append('frontend: ' + str(error))
    if backend_changed:
        try:
            shutil.copy2(backup_backend, release / 'restore-backend.jar')
            os.chown(release / 'restore-backend.jar', backend_stat.st_uid, backend_stat.st_gid)
            os.replace(release / 'restore-backend.jar', backend)
            restart()
            wait_ready()
        except Exception as error:
            failures.append('backend: ' + str(error))
    if failures:
        raise RuntimeError('Rollback incomplete: ' + '; '.join(failures)) from failure
    raise
print(json.dumps(result))
