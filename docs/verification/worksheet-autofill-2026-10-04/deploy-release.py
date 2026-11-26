"""校验并原子切换前端目录，校验失败自动回退，不重启后端。"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
from urllib.request import urlopen

stage = Path(sys.argv[1])
manifest = json.loads((stage / 'release-files.json').read_text())
assert manifest['releaseId'] == 'autofill-20261004-180000'
root = Path('/opt/yuyutian/toolbox')
frontend = root / 'frontend'
backend = root / 'backend/toolbox-service.jar'
catalog = root / 'content/catalog/published.json'
release = root / 'releases' / manifest['releaseId']
candidate = release / 'previous-frontend'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exchange(left, right):
    library = ctypes.CDLL(None, use_errno=True)
    function = library.renameat2
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    if function(-100, os.fsencode(left), -100, os.fsencode(right), 2):
        raise OSError(ctypes.get_errno(), 'Atomic directory exchange failed')


assert digest(frontend / 'index.html') == manifest['previousIndexSha256'], 'Frontend baseline changed'
assert digest(backend) == manifest['backendSha256'], 'Backend baseline changed'
assert digest(catalog) == manifest['catalogSha256'], 'Catalog baseline changed'
release.mkdir(parents=True, exist_ok=False)
shutil.copytree(frontend, candidate)
with tarfile.open(stage / 'frontend.tar.gz', 'r:gz') as archive:
    archive.extractall(candidate, filter='data')
for item in manifest['files']:
    target = candidate / item['path']
    assert target.resolve().is_relative_to(candidate.resolve())
    assert target.stat().st_size == item['bytes'] and digest(target) == item['sha256'], item['path']
for directory, _, names in os.walk(candidate):
    os.chmod(directory, 0o755)
    for name in names:
        os.chmod(Path(directory) / name, 0o644)
shutil.copy2(stage / 'release-files.json', release / 'release-files.json')
result = {'releaseId': manifest['releaseId'], 'indexSha256': digest(candidate / 'index.html'),
          'verifiedFrontendFiles': len(manifest['files']), 'frontendBackup': str(candidate),
          'backendChanged': False, 'contentChanged': False, 'serviceRestarted': False}
assert digest(frontend / 'index.html') == manifest['previousIndexSha256'], 'Frontend changed during preparation'
changed = False
try:
    exchange(frontend, candidate)
    changed = True
    with urlopen('http://127.0.0.1:23113/index.html', timeout=10) as response:
        assert hashlib.sha256(response.read()).hexdigest() == result['indexSha256']
    assert digest(backend) == manifest['backendSha256']
    assert digest(catalog) == manifest['catalogSha256']
    subprocess.run(['systemctl', 'is-active', '--quiet', 'toolbox-service.service'], check=True)
    result['originIndex'] = 'pass'
    (release / 'deployment.json').write_text(json.dumps(result, indent=2) + '\n')
except BaseException:
    if changed:
        exchange(frontend, candidate)
        assert digest(frontend / 'index.html') == manifest['previousIndexSha256']
    raise
print(json.dumps(result))
