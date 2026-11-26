"""仅回滚本轮前端；检查两端版本身份后原子交换，不删除任一版本。"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import urllib.request

root = Path('/opt/yuyutian/toolbox')
release = root / 'releases/worksheet-three-step-20261004-003105'
record = json.loads((release / 'deployment.json').read_text())
live = root / 'frontend'
previous = Path(record['backupPath'])

def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()

assert digest(live / 'index.html') == record['indexSha256'], 'Current release differs; do not roll back another deployment'
assert digest(previous / 'index.html') == record['previousIndexSha256']
library = ctypes.CDLL(None, use_errno=True)
exchange = library.renameat2
exchange.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
exchange.restype = ctypes.c_int
if exchange(-100, os.fsencode(live), -100, os.fsencode(previous), 2):
    raise OSError(ctypes.get_errno(), 'Atomic rollback failed')
with urllib.request.urlopen('http://127.0.0.1:23113/index.html', timeout=15) as response:
    assert hashlib.sha256(response.read()).hexdigest() == record['previousIndexSha256']
print(json.dumps({'rollback': 'pass', 'indexSha256': record['previousIndexSha256']}))
