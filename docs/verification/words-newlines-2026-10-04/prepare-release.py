"""生成英语按行输入版本的前端差量包与校验清单。"""
from pathlib import Path
import hashlib
import json
import tarfile

root = Path(__file__).resolve().parent
release_id = 'words-newlines-20261004-172000'
stage = Path('/private/tmp') / release_id
dist = stage / 'dist'
previous = json.loads((root.parent / 'worksheet-auto-2026-10-04/release-files.json').read_text())
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
    'previousIndexSha256': 'bb06ec172715c91a141a6ed6bd18b27ea6901d4c0d290ef384da1c5c3aede90b',
    'backendSha256': '90f0f39631b47f0058cc845ed3c5c7dedd58cc3c3b9f8399be49e2fec5269441',
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
