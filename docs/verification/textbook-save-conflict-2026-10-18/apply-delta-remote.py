"""在隔离发布目录重建完整产物；所有文件必须符合完整发布清单。"""
from pathlib import Path
import base64
import fcntl
import gzip
import hashlib
import json
import os
import shutil

root = Path('/opt/yuyutian/toolbox')
release = root / 'releases/textbook-conflict-20261018-183955'
lock = (root / '.deployment.lock').open('a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
manifest = json.loads((release / 'release-manifest.json').read_text())


def sha(path):
    """校验内容，不依赖时间戳或文件名判断一致性。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


assert sha(root / 'backend/toolbox-service.jar') == manifest['previousBackendSha256']
assert sha(root / 'frontend/index.html') == manifest['previousIndexSha256']
patch = json.loads(gzip.decompress((release / 'backend-delta.json.gz').read_bytes()))
old = (release / 'previous-backend.jar').read_bytes()
assert hashlib.sha256(old).hexdigest() == patch['oldSha256'] == manifest['previousBackendSha256']
output = b''.join(base64.b64decode(op['data']) if 'data' in op else old[op['offset']:op['offset'] + op['length']] for op in patch['operations'])
assert hashlib.sha256(output).hexdigest() == patch['newSha256'] == manifest['backendSha256']
temporary = release / 'rebuilt-backend.jar'
temporary.write_bytes(output)
os.chmod(temporary, 0o600)
os.replace(temporary, release / 'next-backend.jar')
candidate = release / 'candidate-frontend'
assert candidate.parent == release and not candidate.is_symlink()
shutil.rmtree(candidate)
candidate.mkdir()
for item in manifest['files']:
    target = candidate / item['path']
    assert target.resolve().is_relative_to(candidate.resolve())
    changed = release / 'frontend-delta' / item['path']
    source = changed if changed.exists() else root / 'frontend' / item['path']
    assert source.is_file() and not source.is_symlink() and sha(source) == item['sha256']
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    assert target.stat().st_size == item['bytes'] and sha(target) == item['sha256']
print(json.dumps({'reconstructed': True, 'backendSha256': sha(release / 'next-backend.jar'), 'frontendFilesVerified': len(manifest['files']), 'liveModified': False}))
