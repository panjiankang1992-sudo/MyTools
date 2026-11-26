"""生成图片识别增强版本的前端差量包与校验清单。"""
from pathlib import Path
import hashlib
import json
import tarfile

root = Path(__file__).resolve().parent
release_id = 'once-print-bg-20261005-125500'
stage = Path('/private/tmp') / release_id
dist = stage / 'dist'
previous = json.loads((root.parent / 'release-files.json').read_text())
previous_files = {item['path']: item for item in previous['files']}
files = []
for path in sorted(dist.rglob('*')):
    if path.is_file():
        content = path.read_bytes()
        files.append({'path': str(path.relative_to(dist)), 'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
assert files and any(item['path'] == 'index.html' for item in files)
changed = [item for item in files if previous_files.get(item['path'], {}).get('sha256') != item['sha256']]
manifest = {
    'releaseId': release_id, 'files': files,
    'previousIndexSha256': '0547a17728864014490891e1e402ba9559c722b1626ee882a05160f3b15033ed',
    'backendSha256': '64ae3cb1549840b4669995e48c89d10a73553e34b363032da1353936b7aa992a',
    'catalogSha256': '107460ab893b7cbee890c3667807d534e5deaa7cdd96485a190c1b60b571f3cf'
}
for name, value in [('release-files.json', manifest), ('changed-files.json', {'releaseId': release_id, 'files': changed})]:
    content = json.dumps(value, indent=2) + '\n'
    (stage / name).write_text(content)
    (root / name).write_text(content)
with tarfile.open(stage / 'frontend.tar.gz', 'w:gz') as archive:
    for item in changed:
        archive.add(dist / item['path'], arcname=item['path'])
print(json.dumps({'releaseId': release_id, 'frontendFiles': len(files), 'changedFiles': len(changed), 'archiveBytes': (stage / 'frontend.tar.gz').stat().st_size}))
