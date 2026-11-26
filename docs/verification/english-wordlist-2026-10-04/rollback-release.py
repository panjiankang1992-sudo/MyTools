"""检查当前版本身份后恢复本轮前端和教材备份，重新加载旧目录。"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.request import urlopen

root = Path('/opt/yuyutian/toolbox')
record = json.loads((root / 'releases/english-wordlist-20261004-140500/deployment.json').read_text())
frontend, content = root / 'frontend', root / 'content'
previous_frontend, previous_content = Path(record['backupPath']), Path(record['contentBackupPath'])


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exchange(left, right):
    library = ctypes.CDLL(None, use_errno=True)
    fn = library.renameat2
    fn.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    fn.restype = ctypes.c_int
    if fn(-100, os.fsencode(left), -100, os.fsencode(right), 2):
        raise OSError(ctypes.get_errno(), 'Atomic directory exchange failed')


assert digest(frontend / 'index.html') == record['indexSha256'], 'Frontend version changed'
assert digest(content / 'catalog/published.json') == record['catalogSha256'], 'Catalog version changed'
assert digest(previous_frontend / 'index.html') == record['previousIndexSha256']
assert digest(previous_content / 'catalog/published.json') == record['previousCatalogSha256']
exchange(content, previous_content)
try:
    exchange(frontend, previous_frontend)
except BaseException:
    exchange(content, previous_content)
    raise
subprocess.run(['systemctl', 'restart', 'toolbox-service.service'], check=True, timeout=45)
for attempt in range(30):
    try:
        with urlopen('http://127.0.0.1:23113/public/v1/textbooks?subject=english', timeout=5) as response:
            catalog = json.load(response)['data']
            assert catalog['editions'][0]['contentVersion'] == '1.0.0'
        with urlopen('http://127.0.0.1:23113/index.html', timeout=5) as response:
            assert hashlib.sha256(response.read()).hexdigest() == record['previousIndexSha256']
        break
    except Exception:
        if attempt == 29:
            raise
        time.sleep(1)
print(json.dumps({'rollback': 'pass', 'indexSha256': record['previousIndexSha256'],
                  'catalogSha256': record['previousCatalogSha256']}))
