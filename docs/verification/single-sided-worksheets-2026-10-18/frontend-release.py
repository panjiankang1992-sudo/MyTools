"""前端独立发布与回退；不读取配置、不写数据库、不替换或重启后端。"""
from pathlib import Path
import ctypes
import fcntl
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = Path('/opt/yuyutian/toolbox')
mode, release_id, previous_index, previous_backend = sys.argv[1:]
assert mode in ('prepare', 'deploy', 'rollback')
assert release_id.startswith('single-sided-') and all(character.isalnum() or character == '-' for character in release_id)
release = ROOT / 'releases' / release_id
frontend = ROOT / 'frontend'
candidate = release / 'candidate-frontend'
backend = ROOT / 'backend/toolbox-service.jar'
lock = (ROOT / '.deployment.lock').open('a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exchange():
    library = ctypes.CDLL(None, use_errno=True)
    call = library.renameat2
    call.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    if call(-100, os.fsencode(frontend), -100, os.fsencode(candidate), 2):
        raise OSError(ctypes.get_errno(), 'Atomic frontend exchange failed')


def verify_live(expected):
    with urllib.request.urlopen('http://127.0.0.1:23113/index.html', timeout=5) as response:
        assert hashlib.sha256(response.read()).hexdigest() == expected, 'Served frontend mismatch'
    with urllib.request.urlopen('http://127.0.0.1:23112/public/v1/health', timeout=5) as response:
        assert json.load(response).get('status') == 'UP', 'Backend unhealthy'
    assert sha(backend) == previous_backend, 'Backend changed'
    subprocess.run(['systemctl', 'is-active', '--quiet', 'toolbox-service.service'], check=True)


assert sha(backend) == previous_backend, 'Backend baseline changed'
if mode == 'prepare':
    assert sha(frontend / 'index.html') == previous_index, 'Frontend baseline changed'
    assert shutil.disk_usage(ROOT).free > 512 * 1024 * 1024, 'Insufficient staging space'
    release.mkdir(mode=0o750, exist_ok=False)
    shutil.copytree(frontend, candidate, symlinks=True)
    record = {'releaseId': release_id, 'previousIndexSha256': previous_index,
              'previousBackendSha256': previous_backend, 'preparedAt': time.time()}
    (release / 'prepared.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record))
    sys.exit(0)

manifest = json.loads((release / 'release-manifest.json').read_text())
assert manifest['releaseId'] == release_id
assert manifest['previousIndexSha256'] == previous_index
assert manifest['previousBackendSha256'] == previous_backend
expected = next(item['sha256'] for item in manifest['files'] if item['path'] == 'index.html')
if mode == 'rollback':
    assert sha(frontend / 'index.html') == expected, 'Refusing to replace a later release'
    assert sha(candidate / 'index.html') == previous_index, 'Rollback artifact mismatch'
    exchange()
    verify_live(previous_index)
    print(json.dumps({'releaseId': release_id, 'status': 'rolled-back', 'indexSha256': previous_index}))
    sys.exit(0)

assert sha(frontend / 'index.html') == previous_index, 'Frontend baseline changed'
assert not any(path.is_symlink() for path in candidate.rglob('*')), 'Candidate contains symbolic links'
for item in manifest['files']:
    path = candidate / item['path']
    assert path.resolve().is_relative_to(candidate.resolve()), 'Invalid frontend path'
    assert path.stat().st_size == item['bytes'] and sha(path) == item['sha256'], 'Upload mismatch: ' + item['path']
subprocess.run(['chown', '-R', 'mytools:mytools', str(candidate)], check=True)
for directory, _, files in os.walk(candidate):
    os.chmod(directory, 0o755)
    for name in files:
        os.chmod(Path(directory) / name, 0o644)
assert sha(frontend / 'index.html') == previous_index and sha(backend) == previous_backend, 'Live baseline changed'
switched = False
try:
    exchange()
    switched = True
    verify_live(expected)
    result = {'releaseId': release_id, 'status': 'deployed', 'indexSha256': expected,
              'backendSha256': sha(backend), 'backendRestarted': False,
              'frontendFilesVerified': len(manifest['files']), 'frontendBackup': str(candidate),
              'contentModified': False, 'configurationModified': False, 'finishedAt': time.time()}
    (release / 'deployment.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
except BaseException:
    if switched:
        exchange()
        verify_live(previous_index)
    raise
