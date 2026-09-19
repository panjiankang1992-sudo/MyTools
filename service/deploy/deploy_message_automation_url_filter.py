#!/usr/bin/env python3
"""仅发布 message-automation-service 的附件文件名过滤修复，保留所有生产配置。

本次改动只影响 MessageAutomationService 的 URL 提取，不触碰数据库、任务包、
环境文件或其他服务，因此不动 releases/current，而是按图片/视频服务的做法
新建不可变版本目录，并用 systemd drop-in 只重定向本服务的 ExecStart。

注意：drop-in 会把本服务固定在本次版本目录上；后续整体切换 releases/current
时必须同步删除或重新指向该 drop-in，否则新版本对本服务不生效。
"""
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pwd
import shutil
import sys
import zipfile

spec = importlib.util.spec_from_file_location('base_deploy', Path(__file__).with_name('deploy_image_generation.py'))
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

NAME = 'message-automation-url-filter-20260919-v1'
ROOT = d.ROOT
SOURCE = Path('/tmp') / NAME
RELEASE = ROOT / 'releases' / NAME
STATE = ROOT / 'runtime' / NAME
UNITS = Path('/etc/systemd/system')
SERVICE = 'message-automation-service'
ARTIFACT = SERVICE + '.jar'
DROPIN = 'zzz-message-automation-url-filter.conf'
PORT = 23260
CLASS = 'BOOT-INF/classes/com/yuyutian/mytools/automation/service/MessageAutomationService.class'


def unit(name):
    return 'mytools-' + name + '.service'


def process(name):
    """读取服务当前进程的启动参数，作为切换入口的唯一依据。"""
    pid = d.run(['systemctl', 'show', unit(name), '-p', 'MainPID', '--value']).decode().strip()
    assert pid.isdigit() and int(pid) > 0, 'service_process_missing'
    return [x.decode() for x in Path('/proc/' + pid + '/cmdline').read_bytes().split(b'\0') if x]


def stage():
    assert not RELEASE.exists(), 'release_exists'
    for name, digest in json.loads((SOURCE / 'manifest.json').read_text()).items():
        assert hashlib.sha256((SOURCE / name).read_bytes()).hexdigest() == digest, 'source_digest_mismatch'
    STATE.mkdir(parents=True, exist_ok=True)
    STATE.chmod(0o700)
    args = process(SERVICE)
    base = Path(args[args.index('-jar') + 1])
    assert base.is_file(), 'base_artifact_missing'
    (RELEASE / 'apps').mkdir(parents=True)
    shutil.copy2(SOURCE / ARTIFACT, RELEASE / 'apps' / ARTIFACT)
    for path in [RELEASE, *RELEASE.rglob('*')]:
        os.chown(path, 0, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o750 if path.is_dir() else 0o640)
    artifact = RELEASE / 'apps' / ARTIFACT
    with zipfile.ZipFile(artifact) as archive:
        # 入口类必须存在且确实带有本次修复的方法，避免发布未包含修复的旧产物。
        assert CLASS in archive.namelist(), 'fix_class_missing'
        assert b'looksLikeFileName' in archive.read(CLASS), 'fix_marker_missing'
    report = {'release': NAME, 'service': SERVICE, 'base': str(base),
              'baseSha256': hashlib.sha256(base.read_bytes()).hexdigest(),
              'artifactSha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
              'artifactBytes': artifact.stat().st_size}
    assert report['baseSha256'] != report['artifactSha256'], 'artifact_unchanged'
    d.write(STATE / 'before.json', json.dumps({'args': args}))
    d.write(STATE / 'release.json', json.dumps(report, indent=2))
    d.emit({'staged': True, 'release': NAME, 'artifactBytes': report['artifactBytes']})


def activate():
    previous = json.loads((STATE / 'before.json').read_text())
    assert process(SERVICE) == previous['args'], 'active_release_changed'
    dropin = UNITS / (unit(SERVICE) + '.d') / DROPIN
    assert not dropin.exists(), 'dropin_exists'
    args = previous['args'][:]
    args[args.index('-jar') + 1] = str(RELEASE / 'apps' / ARTIFACT)
    assert all(not any(c.isspace() for c in value) for value in args), 'unexpected_command_quoting'
    d.write(dropin, '[Service]\nExecStart=\nExecStart=' + ' '.join(args) + '\n', mode=0o644)
    try:
        d.run(['systemctl', 'daemon-reload'])
        d.run(['systemctl', 'restart', unit(SERVICE)])
        d.health(PORT)
        assert str(RELEASE / 'apps' / ARTIFACT) in process(SERVICE), 'active_artifact_mismatch'
        d.write(STATE / 'activated.json', json.dumps({'activated': True, 'release': NAME}))
        d.emit({'activated': True, 'release': NAME, 'artifact': str(RELEASE / 'apps' / ARTIFACT)})
    except Exception:
        # 入口切换失败必须回到旧启动参数，新增版本目录保留以便排查。
        rollback()
        raise


def rollback():
    dropin = UNITS / (unit(SERVICE) + '.d') / DROPIN
    dropin.unlink(missing_ok=True)
    d.run(['systemctl', 'daemon-reload'])
    d.run(['systemctl', 'restart', unit(SERVICE)])
    d.health(PORT)
    d.emit({'rolledBack': True, 'release': NAME})


if __name__ == '__main__':
    os.umask(0o077)
    try:
        assert os.geteuid() == 0, 'root_required'
        with (ROOT / 'runtime/deployment.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            {'stage': stage, 'activate': activate, 'rollback': rollback}[sys.argv[1]]()
    except Exception as error:
        d.emit({'failed': True, 'type': type(error).__name__,
                'reason': str(error) if isinstance(error, (RuntimeError, AssertionError))
                else 'private_diagnostic_suppressed'})
        sys.exit(1)
