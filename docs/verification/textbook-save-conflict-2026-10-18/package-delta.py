"""按 ZIP 数据段生成精确二进制增量；本地重建校验后才生成上传包。"""
from pathlib import Path
import base64
import gzip
import hashlib
import io
import json
import struct
import tarfile
import tempfile
import zipfile

folder = Path(__file__).resolve().parent
root = folder.parents[2]
manifest = json.loads((folder / 'release-manifest.json').read_text())
old = Path((folder / 'previous-jar-path.txt').read_text().strip()).read_bytes()
new = (root / 'service/toolbox-service/target/toolbox-service-0.1.0-SNAPSHOT.jar').read_bytes()


def offset(raw, entry):
    """跳过 ZIP 局部文件头、文件名和扩展字段，定位压缩数据。"""
    name_size, extra_size = struct.unpack_from('<HH', raw, entry.header_offset + 26)
    return entry.header_offset + 30 + name_size + extra_size


ops = []
with zipfile.ZipFile(io.BytesIO(old)) as before, zipfile.ZipFile(io.BytesIO(new)) as after:
    old_entries = {entry.filename: entry for entry in before.infolist()}
    cursor = 0
    for entry in sorted(after.infolist(), key=lambda value: value.header_offset):
        start = offset(new, entry)
        if start > cursor:
            ops.append({'data': base64.b64encode(new[cursor:start]).decode()})
        end = start + entry.compress_size
        prior = old_entries.get(entry.filename)
        previous_start = offset(old, prior) if prior else 0
        if prior and prior.compress_size == entry.compress_size and old[previous_start:previous_start + prior.compress_size] == new[start:end]:
            ops.append({'offset': previous_start, 'length': entry.compress_size})
        else:
            ops.append({'data': base64.b64encode(new[start:end]).decode()})
        cursor = end
    if cursor < len(new):
        ops.append({'data': base64.b64encode(new[cursor:]).decode()})

rebuilt = b''.join(base64.b64decode(op['data']) if 'data' in op else old[op['offset']:op['offset'] + op['length']] for op in ops)
assert rebuilt == new, 'Local delta reconstruction differs'
patch = {'oldSha256': hashlib.sha256(old).hexdigest(), 'newSha256': hashlib.sha256(new).hexdigest(), 'operations': ops}
payload = gzip.compress(json.dumps(patch, separators=(',', ':')).encode())
server = json.loads((folder / 'frontend-server-files.json').read_text())
changed = [item for item in manifest['files'] if server.get(item['path']) != item['sha256']]
archive = Path(tempfile.gettempdir()) / (manifest['releaseId'] + '-delta.tar.gz')
with tarfile.open(archive, 'w:gz') as bundle:
    info = tarfile.TarInfo('backend-delta.json.gz')
    info.size = len(payload)
    info.mode = 0o600
    bundle.addfile(info, io.BytesIO(payload))
    for item in changed:
        bundle.add(root / 'toolbox-web/dist' / item['path'], arcname='frontend-delta/' + item['path'])
    bundle.add(folder / 'release-manifest.json', arcname='release-manifest.json')
(folder / 'delta-archive-path.txt').write_text(str(archive) + '\n')
summary = {'jarDeltaBytes': len(payload), 'changedFrontendFiles': len(changed), 'reusedFrontendFiles': len(manifest['files']) - len(changed), 'archiveBytes': archive.stat().st_size, 'exactLocalJarReconstruction': True}
(folder / 'delta-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary))
