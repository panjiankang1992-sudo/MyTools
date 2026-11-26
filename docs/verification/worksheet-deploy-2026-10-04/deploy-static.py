"""仅发布指定静态产物，校验后原子切换目录并保留旧版本。"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile
import urllib.request

archive, manifest_file = map(Path, sys.argv[1:3])
manifest = json.loads(manifest_file.read_text())
release_id = manifest['releaseId']
assert release_id == 'worksheet-three-step-20261004-003105'
root = Path('/opt/yuyutian/toolbox')
live = root / 'frontend'
release = root / 'releases' / release_id
previous = release / 'previous-frontend'
expected_previous = '59868ce744de7cbdded44781b924a85fd61e53897574cb6f7ed5511ccae7d0b9'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def exchange():
    library = ctypes.CDLL(None, use_errno=True)
    fn = library.renameat2
    fn.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    fn.restype = ctypes.c_int
    if fn(-100, os.fsencode(live), -100, os.fsencode(previous), 2):
        raise OSError(ctypes.get_errno(), 'Atomic frontend exchange failed')

assert live.is_dir() and not live.is_symlink()
assert digest((live / 'index.html').read_bytes()) == expected_previous, 'Live release changed; stop instead of overwriting it'
release.mkdir(parents=True, exist_ok=False)
# 保留旧散列资源，使已经打开的页面仍能加载对应的异步模块。
shutil.copytree(live, previous)
with tarfile.open(archive, 'r:gz') as package:
    package.extractall(previous, filter='data')
for item in manifest['files']:
    target = previous / item['path']
    assert target.resolve().is_relative_to(previous.resolve())
    assert target.stat().st_size == item['bytes']
    assert digest(target.read_bytes()) == item['sha256'], item['path']
for directory, _, names in os.walk(previous):
    os.chmod(directory, 0o755)
    for name in names:
        os.chmod(Path(directory) / name, 0o644)
expected_new = next(item['sha256'] for item in manifest['files'] if item['path'] == 'index.html')
exchange()
try:
    with urllib.request.urlopen('http://127.0.0.1:23113/index.html', timeout=15) as response:
        assert response.status == 200
        assert digest(response.read()) == expected_new
    with urllib.request.urlopen('http://127.0.0.1:23113/public/v1/textbooks', timeout=15) as response:
        catalog = json.load(response)
        assert isinstance(catalog.get('data', {}).get('editions'), list)
except BaseException:
    exchange()
    raise
result = {'releaseId': release_id, 'frontend': str(live), 'backupPath': str(previous),
          'previousIndexSha256': expected_previous, 'indexSha256': expected_new,
          'verifiedFiles': len(manifest['files']), 'originIndex': 'pass', 'catalogApi': 'pass',
          'backendChanged': False, 'contentChanged': False, 'atomicDirectoryExchange': True}
(release / 'deployment.json').write_text(json.dumps(result, indent=2) + '\n')
(release / 'release-files.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(result))
