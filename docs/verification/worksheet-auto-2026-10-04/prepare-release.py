"""生成前端差量清单和后端校验清单，不读取任何生产配置。"""
from pathlib import Path
import hashlib
import json
import shutil
import tarfile

RELEASE_ID = 'worksheet-auto-20261004-144500'
root = Path(__file__).resolve().parent
repo = root.parents[2]
stage = Path('/private/tmp') / RELEASE_ID
dist = stage / 'dist'
previous = json.loads((root.parent / 'english-wordlist-2026-10-04/release-files.json').read_text())
previous_files = {item['path']: item for item in previous['files']}


def describe(path, base):
    content = path.read_bytes()
    return {'path': str(path.relative_to(base)), 'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()}


files = [describe(path, dist) for path in sorted(dist.rglob('*')) if path.is_file()]
assert files and any(item['path'] == 'index.html' for item in files)
changed = [item for item in files if previous_files.get(item['path'], {}).get('sha256') != item['sha256']]
jar = repo / 'service/toolbox-service/target/toolbox-service-0.1.0-SNAPSHOT.jar'
shutil.copy2(jar, stage / 'toolbox-service.jar')
manifest = {
    'releaseId': RELEASE_ID, 'files': files, 'backend': describe(stage / 'toolbox-service.jar', stage),
    'previousIndexSha256': '801771b0ac4ab04074fc1d36e6f0c3ed8ed760e13cdab09d1ec94dab05d10e02',
    'previousBackendSha256': '7806d4d56207c3a6c9bc12ebc46330086c0e09c4b08a06be06f3b6a19cc12a2f',
    'catalogSha256': '107460ab893b7cbee890c3667807d534e5deaa7cdd96485a190c1b60b571f3cf'
}
for name, value in [('release-files.json', manifest), ('changed-files.json', {'releaseId': RELEASE_ID, 'files': changed})]:
    content = json.dumps(value, indent=2) + '\n'
    (stage / name).write_text(content)
    (root / name).write_text(content)
with tarfile.open(stage / 'frontend.tar.gz', 'w:gz') as archive:
    for item in changed:
        archive.add(dist / item['path'], arcname=item['path'])
print(json.dumps({'releaseId': RELEASE_ID, 'frontendFiles': len(files), 'changedFiles': len(changed), 'backendBytes': manifest['backend']['bytes']}))
