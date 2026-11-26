#!/usr/bin/env python3
"""增量修复自动化连接饥饿与附件入口队列期限，保留当前生产 JAR 和全部配置。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import time
from urllib.request import urlopen
from urllib.error import HTTPError
import zipfile

ROOT = Path('/opt/yuyutian/mytools/releases/download-reliability-20261018-v1')
PATCHES = (
    ('mytools-task-scheduler-service', 'task/scheduler', 'GpuTaskRepository', 23410),
    ('mytools-message-automation-service', 'automation', 'AutomationRepository', 23260),
)


def run(*args):
    """运行固定运维命令。"""
    return subprocess.check_output(args, text=True).strip()


def deploy(unit, module, name, port):
    """仅替换已回归验证的仓储类，启动失败时恢复旧命令。"""
    command = re.search(r'argv\[\]=(.*?) ; ignore_errors=',
                        run('systemctl', 'show', unit, '-p', 'ExecStart')).group(1)
    old = re.search(r'-jar\s+(\S+)', command).group(1)
    data = Path('/tmp/' + name + '-20261018.class').read_bytes()
    entry = 'BOOT-INF/classes/com/yuyutian/mytools/' + module + '/repository/' + name + '.class'
    jar = ROOT / (unit + '.jar')
    with zipfile.ZipFile(old) as source, zipfile.ZipFile(jar, 'w') as target:
        assert entry in source.namelist()
        for item in source.infolist():
            target.writestr(item, data if item.filename == entry else source.read(item.filename))
    jar.chmod(0o644)
    override = Path('/etc/systemd/system') / (unit + '.service.d') / ('z' * 40 + '-download-reliability.conf')
    override.parent.mkdir(exist_ok=True)
    assert not override.exists()
    override.write_text('[Service]\nExecStart=\nExecStart=' + command.replace(old, str(jar)) + '\n')
    try:
        run('systemctl', 'daemon-reload')
        assert str(jar) in run('systemctl', 'show', unit, '-p', 'ExecStart')
        run('systemctl', 'restart', unit)
        for _ in range(45):
            try:
                with urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=2) as response:
                    json.load(response)
                    break
            except HTTPError as error:
                # 自动化保留历史死信的 DOWN 告警，不据此误判进程启动失败。
                if unit == 'mytools-message-automation-service' and error.code == 503:
                    body = json.load(error)
                    if body.get('status') == 'DOWN':
                        break
            except (OSError, ValueError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('service_not_ready')
    except Exception:
        override.unlink()
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', unit)
        raise
    return {'service': unit, 'status': run('systemctl', 'is-active', unit),
            'classSha256': hashlib.sha256(data).hexdigest()}


def main():
    """使用不可变发布目录顺序部署两项独立修复。"""
    ROOT.mkdir(exist_ok=False)
    for patch in PATCHES:
        print(json.dumps(deploy(*patch)), flush=True)


if __name__ == '__main__':
    main()
