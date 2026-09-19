#!/usr/bin/env python3
"""Install the source-format Reader hotfix without restarting other services."""
import importlib.util
import json
import os
from pathlib import Path
import pwd
import shutil
import sys

BASE = Path('/opt/yuyutian/mytools/runtime/adaptation-production-20260911')
OLD = Path('/opt/yuyutian/mytools/releases/chapter-adaptation-production-20260911-v3')
NEW = OLD.with_name('chapter-adaptation-production-20260911-v4')
UPLOAD = Path('/tmp/mytools-adaptation-production-20260911/reader-service.jar')
UNIT = Path('/etc/systemd/system/mytools-reader-service.service.d/adaptation-production.conf')


def main(expected):
    specification = importlib.util.spec_from_file_location('deployment', BASE / 'operator/deploy.py')
    deployment = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(deployment)
    deployment.checked_current(OLD)
    assert len(expected) == 64 and deployment.digest(UPLOAD) == expected
    assert not NEW.exists() and not (BASE / 'operator/reader-source-format-hotfix.json').exists()
    with deployment.connection('READER') as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM novel_chapter_adaptation WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')")
        assert cursor.fetchone()[0] == 0, 'active_adaptations'
    previous_unit = UNIT.read_bytes()
    original_jar = str(OLD / 'apps/reader-service.jar').encode()
    assert previous_unit.count(original_jar) == 1
    deployment.write(BASE / 'operator/reader-before-source-format.conf', previous_unit)
    shutil.copytree(OLD, NEW, symlinks=True)
    shutil.copy2(UPLOAD, NEW / 'apps/reader-service.jar')
    (NEW / 'apps/reader-service.jar').chmod(0o640)
    group = pwd.getpwnam('mytools').pw_gid
    for path in (NEW, *NEW.rglob('*')):
        os.chown(path, 0, group, follow_symlinks=False)
    unchanged = 0
    for before in OLD.rglob('*'):
        relative = before.relative_to(OLD)
        after = NEW / relative
        if before.is_symlink():
            assert after.is_symlink() and before.readlink() == after.readlink()
        elif before.is_file() and relative.as_posix() != 'apps/reader-service.jar':
            assert deployment.digest(before) == deployment.digest(after)
            unchanged += 1
    current = OLD.parent / 'current'
    temporary = OLD.parent / '.source-format-current'
    assert not temporary.exists()
    try:
        deployment.write(UNIT, previous_unit.replace(original_jar, str(NEW / 'apps/reader-service.jar').encode()), mode=0o644)
        temporary.symlink_to(NEW.name)
        os.replace(temporary, current)
        deployment.run(['systemctl', 'daemon-reload'])
        deployment.run(['systemctl', 'restart', 'mytools-reader-service'])
        deployment.wait_health(23230)
        deployment.wait_health(24231, True)
    except Exception:
        deployment.write(UNIT, previous_unit, mode=0o644)
        temporary.symlink_to(OLD.name)
        os.replace(temporary, current)
        deployment.run(['systemctl', 'daemon-reload'])
        deployment.run(['systemctl', 'restart', 'mytools-reader-service'])
        raise
    deployment.audit('reader-source-format-hotfix', {'release': NEW.name, 'previous': OLD.name,
        'readerSha256': expected, 'unchangedReleaseFiles': unchanged,
        'restartedServices': ['reader-service'], 'databaseRowsModifiedByDeployment': 0})


def repair_runtime_environment():
    """Remove only legacy runtime overrides; keep authentication enabled and keys private."""
    specification = importlib.util.spec_from_file_location('deployment', BASE / 'operator/deploy.py')
    deployment = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(deployment)
    deployment.checked_current(NEW)
    marker = BASE / 'operator/runtime-environment-fixed.json'
    assert not marker.exists()
    previous = UNIT.read_bytes()
    assert str(NEW / 'apps/reader-service.jar').encode() in previous
    line = b'UnsetEnvironment=READER_RUNTIME_BASE_URL READER_RUNTIME_SECURE_KEY\n'
    assert line not in previous and previous.count(b'[Service]\n') == 1
    deployment.write(BASE / 'operator/reader-before-runtime-environment.conf', previous)
    deployment.write(UNIT, previous.replace(b'[Service]\n', b'[Service]\n' + line), mode=0o644)
    deployment.run(['systemctl', 'daemon-reload'])
    deployment.run(['systemctl', 'restart', 'mytools-reader-service'])
    deployment.wait_health(23230)
    deployment.wait_health(24231, True)
    pid = deployment.run(['systemctl', 'show', 'mytools-reader-service', '--property=MainPID', '--value']).decode().strip()
    keys = {item.split(b'=', 1)[0] for item in Path('/proc/' + pid + '/environ').read_bytes().split(b'\0')}
    assert b'READER_RUNTIME_BASE_URL' not in keys and b'READER_RUNTIME_SECURE_KEY' not in keys
    deployment.audit('runtime-environment-fixed', {'legacyRuntimeOverridesRemoved': True, 'authenticationDisabled': False,
        'restartedServices': ['reader-service'], 'keysPrinted': False})


if __name__ == '__main__':
    os.umask(0o077)
    try:
        assert os.geteuid() == 0 and len(sys.argv) == 2
        if sys.argv[1] == 'runtime-environment':
            repair_runtime_environment()
        else:
            main(sys.argv[1])
    except Exception as error:
        print(json.dumps({'errorType': type(error).__name__, 'reason': str(error)
            if isinstance(error, (AssertionError, RuntimeError)) else 'private_diagnostic_suppressed'}))
        raise SystemExit(1)
