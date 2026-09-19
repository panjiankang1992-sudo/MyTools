#!/usr/bin/env python3
"""Transfer byte-exact JAR deltas using existing compressed ZIP entries as a basis."""
import base64
import gzip
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def segments(path, raw):
    with zipfile.ZipFile(path) as archive:
        for entry in sorted(archive.infolist(), key=lambda item: item.header_offset):
            header = entry.header_offset
            assert raw[header:header + 4] == b'PK\x03\x04'
            name, extra = struct.unpack('<HH', raw[header + 26:header + 30])
            start = header + 30 + name + extra
            yield start, entry.compress_size


def inventory(path):
    raw = path.read_bytes()
    return {'basisSha256': sha(raw), 'segments': {sha(raw[start:start + size]): [start, size]
        for start, size in segments(path, raw) if size > 0}}


def build(target, basis, output):
    raw = target.read_bytes()
    assert len(raw) < 134217728
    operations = []
    cursor = 0
    for start, size in segments(target, raw):
        assert start >= cursor
        if start > cursor:
            operations.append({'literal': base64.b64encode(raw[cursor:start]).decode()})
        data = raw[start:start + size]
        match = basis['segments'].get(sha(data))
        if match and match[1] == size:
            operations.append({'copy': match})
        elif size:
            operations.append({'literal': base64.b64encode(data).decode()})
        cursor = start + size
    if cursor < len(raw):
        operations.append({'literal': base64.b64encode(raw[cursor:]).decode()})
    result = {'schema': 1, 'basisSha256': basis['basisSha256'], 'targetSha256': sha(raw), 'targetBytes': len(raw), 'operations': operations}
    output.write_bytes(gzip.compress(json.dumps(result, separators=(',', ':')).encode()))
    print(json.dumps({'targetSha256': result['targetSha256'], 'targetBytes': len(raw), 'transferBytes': output.stat().st_size}))


def apply(basis, patch, destination):
    assert destination.parent == Path('/tmp/mytools-adaptation-production-20260911')
    assert destination.name in ('reader-service.jar', 'task-scheduler-service.jar')
    assert patch.stat().st_size < 8388608
    data = json.loads(gzip.decompress(patch.read_bytes()))
    raw = basis.read_bytes()
    assert data['schema'] == 1 and sha(raw) == data['basisSha256'] and 0 < data['targetBytes'] < 134217728
    assert len(data['operations']) < 30000
    result = bytearray()
    for operation in data['operations']:
        if set(operation) == {'copy'}:
            start, size = operation['copy']
            assert isinstance(start, int) and isinstance(size, int) and 0 <= start <= len(raw) and 0 <= size <= len(raw) - start
            result.extend(raw[start:start + size])
        else:
            assert set(operation) == {'literal'}
            result.extend(base64.b64decode(operation['literal'], validate=True))
        assert len(result) <= data['targetBytes']
    assert len(result) == data['targetBytes'] and sha(result) == data['targetSha256']
    temporary = destination.with_suffix('.verified-new')
    with temporary.open('xb') as stream:
        stream.write(result)
    temporary.chmod(0o600)
    temporary.replace(destination)
    print(json.dumps({'verifiedSha256': sha(result), 'bytes': len(result)}))


if __name__ == '__main__':
    if sys.argv[1] == 'inventory':
        print(json.dumps(inventory(Path(sys.argv[2]))))
    elif sys.argv[1] == 'build':
        build(Path(sys.argv[2]), json.loads(Path(sys.argv[3]).read_text()), Path(sys.argv[4]))
    elif sys.argv[1] == 'apply':
        apply(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
    else:
        raise SystemExit(2)
