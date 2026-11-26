#!/usr/bin/env python3
"""为已审计的重跑结果提供独立回执身份，普通通知保留原协议，失败时回退原 JAR。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import urlopen
import zipfile

UNIT = 'mytools-message-automation-service'
RELEASE = Path('/opt/yuyutian/mytools/releases/recovery-completion-identity-20261021-v1')
OVERRIDE = Path('/etc/systemd/system') / (UNIT + '.service.d') / ('z' * 48 + '-recovery-completion.conf')


def run(*args):
    """执行固定运维命令。"""
    return subprocess.check_output(args, text=True).strip()


def main():
    """保留当前生产二进制和配置，仅替换完成中继及配套仓储两个类。"""
    with zipfile.ZipFile('/tmp/recovery-completion-identity.zip') as archive:
        manifest = json.loads(archive.read('manifest.json'))
        assert set(manifest) == {'BOOT-INF/classes/com/yuyutian/mytools/automation/' + suffix
                                 for suffix in ('repository/AutomationRepository.class',
                                                'service/CompletionOutboxRelay.class')}
        files = {entry: archive.read(entry) for entry in manifest}
        assert all(hashlib.sha256(data).hexdigest() == manifest[entry] for entry, data in files.items())
    command = re.search(r'argv\[\]=(.*?) ; ignore_errors=',
                        run('systemctl', 'show', UNIT, '-p', 'ExecStart')).group(1)
    original = re.search(r'-jar\s+(\S+)', command).group(1)
    RELEASE.mkdir(exist_ok=False)
    jar = RELEASE / 'message-automation-service.jar'
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(jar, 'w') as target:
        assert set(files).issubset(source.namelist())
        for item in source.infolist():
            target.writestr(item, files.get(item.filename, source.read(item.filename)))
    jar.chmod(0o644)
    (RELEASE / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    OVERRIDE.parent.mkdir(exist_ok=True)
    assert not OVERRIDE.exists()
    OVERRIDE.write_text('[Service]\nExecStart=\nExecStart=' + command.replace(original, str(jar)) + '\n')
    try:
        run('systemctl', 'daemon-reload')
        assert str(jar) in run('systemctl', 'show', UNIT, '-p', 'ExecStart')
        run('systemctl', 'restart', UNIT)
        for _ in range(45):
            try:
                with urlopen('http://127.0.0.1:23260/actuator/health', timeout=2) as response:
                    assert json.load(response).get('status') == 'UP'
                    break
            except HTTPError as error:
                # 已存在的历史死信保留 DOWN 告警，不把它伪装成无死信的 UP。
                if error.code == 503 and json.load(error).get('status') == 'DOWN':
                    break
            except (OSError, ValueError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('automation_start_failed')
    except Exception:
        OVERRIDE.unlink()
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', UNIT)
        raise
    print(json.dumps({'release': str(RELEASE), 'status': run('systemctl', 'is-active', UNIT)}))


if __name__ == '__main__':
    main()
