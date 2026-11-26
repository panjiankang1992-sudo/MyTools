"""恢复权限后确认旧服务健康，并保留第一次失败发布的完整证据目录。"""
import hashlib
import json
from pathlib import Path
import subprocess
from urllib.request import urlopen

root = Path('/opt/yuyutian/toolbox')
release = root / 'releases/worksheet-auto-20261004-144500'
failed = release.with_name(release.name + '.failed-1')
assert hashlib.sha256((root / 'backend/toolbox-service.jar').read_bytes()).hexdigest() == '7806d4d56207c3a6c9bc12ebc46330086c0e09c4b08a06be06f3b6a19cc12a2f'
assert hashlib.sha256((root / 'frontend/index.html').read_bytes()).hexdigest() == '801771b0ac4ab04074fc1d36e6f0c3ed8ed760e13cdab09d1ec94dab05d10e02'
subprocess.run(['sudo', '-n', '-u', 'mytools', 'test', '-r', str(root / 'backend/toolbox-service.jar')], check=True)
assert subprocess.check_output(['systemctl', 'is-active', 'toolbox-service.service'], text=True).strip() == 'active'
with urlopen('http://127.0.0.1:23113/public/v1/health', timeout=10) as response:
    assert json.load(response)['status'] == 'UP'
assert not failed.exists()
release.rename(failed)
result = {'attempt': 1, 'failure': 'Replacement and rollback JAR ownership was not preserved; mode 0640 denied service access.',
          'recovery': 'Restored root:mytools ownership on old JAR and backup; old backend health UP; original frontend retained.',
          'preservedFailedRelease': str(failed), 'resolved': True}
(failed / 'failure-and-recovery.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
