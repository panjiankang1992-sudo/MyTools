#!/usr/bin/env python3
"""从已验证的不可变 Maven 依赖重建逐字节一致的发布包，减少 SSH 传输。"""
import base64
import concurrent.futures
import gzip
import hashlib
import json
from pathlib import Path
import re
import urllib.request

ROOT = Path('/tmp/mytools-rss-production-20261101')
recipe = json.loads(gzip.decompress((ROOT / 'rss-artifact-recipe.json.gz').read_bytes()))
assert 0 < recipe['size'] < 64 * 1024 * 1024
assert len(recipe['operations']) < 300
cache = ROOT / 'dependency-cache'
cache.mkdir(mode=0o700, exist_ok=True)


def fetch(operation):
    """仅从 Maven Central 下载并按本地构建摘要逐一验证。"""
    relative = operation['artifact']
    assert re.fullmatch(r'[A-Za-z0-9_./-]+\.jar', relative) and '..' not in relative
    target = cache / operation['sha256']
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == operation['sha256']:
        return
    assert 0 < operation['size'] < 16 * 1024 * 1024
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/' + relative, timeout=30) as response:
        data = response.read(operation['size'] + 1)
    assert len(data) == operation['size']
    assert hashlib.sha256(data).hexdigest() == operation['sha256'], 'dependency_digest_mismatch'
    target.write_bytes(data)


with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    list(executor.map(fetch, [operation for operation in recipe['operations'] if 'artifact' in operation]))
target = ROOT / 'rss-service.jar.verified'
with target.open('wb') as output:
    for operation in recipe['operations']:
        if 'artifact' in operation:
            output.write((cache / operation['sha256']).read_bytes())
        else:
            output.write(base64.b64decode(operation['literal'], validate=True))
assert target.stat().st_size == recipe['size']
assert hashlib.sha256(target.read_bytes()).hexdigest() == recipe['sha256'], 'final_jar_digest_mismatch'
target.replace(ROOT / 'rss-service.jar')
print(json.dumps({'byteExactArtifactVerified': True, 'sha256': recipe['sha256']}), flush=True)
