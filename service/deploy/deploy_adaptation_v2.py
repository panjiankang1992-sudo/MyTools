#!/usr/bin/env python3
"""Upgrade only adaptation Reader, Gateway and dedicated Executor; keep private backups."""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

ROOT = Path('/opt/yuyutian/mytools')
BASE = ROOT / 'runtime/adaptation-production-20260911'
OLD = ROOT / 'releases/chapter-adaptation-production-20260911-v4'
NEW = ROOT / 'releases/chapter-adaptation-production-20260913-v5'
UPLOAD = Path('/tmp/mytools-adaptation-v2-20260913')
BACKUP = BASE / 'operator/v2-upgrade-20260913'
spec = importlib.util.spec_from_file_location('deployment', BASE / 'operator/deploy.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
UNITS = {
    'reader-service': Path('/etc/systemd/system/mytools-reader-service.service.d/adaptation-production.conf'),
    'mytools-gateway': Path('/etc/systemd/system/mytools-mytools-gateway.service.d/adaptation-production.conf'),
    'adaptation-executor': Path('/etc/systemd/system/mytools-adaptation-executor.service'),
}


def active():
    with d.connection('READER') as db, db.cursor() as c:
        c.execute("SELECT COUNT(*) FROM novel_chapter_adaptation WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED')")
        return c.fetchone()[0]


def creation(role, enabled):
    path = BASE / role / 'application.properties'
    data = d.values(path)
    key = 'reader.adaptation.create-enabled' if role == 'reader' else 'gateway.chapter-adaptation.create-enabled'
    data[key] = str(enabled).lower()
    d.props(path, data, d.identity(role) if role == 'reader' else 'mytools')


def stage():
    d.checked_current(OLD)
    assert not NEW.exists() and not BACKUP.exists(), 'stage_exists'
    expected = json.loads((UPLOAD / 'artifacts.json').read_text())
    assert set(expected) == {'reader-service', 'mytools-gateway', 'adaptation-executor-v2'}
    for name, sha in expected.items():
        assert d.digest(UPLOAD / (name + '.jar')) == sha, 'artifact_mismatch'
    assert active() == 0, 'active_adaptations'
    BACKUP.mkdir(mode=0o700)
    for name, path in UNITS.items():
        d.write(BACKUP / (name + '.unit'), path.read_bytes())
    for role in ('reader', 'gateway'):
        d.write(BACKUP / (role + '.properties'), (BASE / role / 'application.properties').read_bytes())
    # 新 Executor 使用独立文件名，普通 Executor 的现有二进制和服务均不变。
    shutil.copytree(OLD, NEW, symlinks=True)
    for source in (OLD, *OLD.rglob('*')):
        metadata = source.lstat()
        os.chown(NEW / source.relative_to(OLD), metadata.st_uid, metadata.st_gid, follow_symlinks=False)
    changed = {'apps/' + name + '.jar' for name in expected}
    for name in expected:
        d.write(NEW / 'apps' / (name + '.jar'), (UPLOAD / (name + '.jar')).read_bytes(), mode=0o640)
        shutil.chown(NEW / 'apps' / (name + '.jar'), group='mytools')
    unchanged = 0
    for path in OLD.rglob('*'):
        relative = path.relative_to(OLD)
        if path.is_file() and not path.is_symlink() and str(relative) not in changed:
            assert d.digest(path) == d.digest(NEW / relative), 'unrelated_artifact_changed'
            unchanged += 1
    d.write(BACKUP / 'staged.json', json.dumps({'artifacts': expected, 'unchangedFiles': unchanged}))
    print(json.dumps({'staged': NEW.name, 'unchangedFiles': unchanged}), flush=True)


def activate():
    d.checked_current(OLD)
    assert (BACKUP / 'staged.json').exists() and not (BACKUP / 'activated.json').exists()
    # 先关闭 APP 创建入口；无在途任务时停止 Reader，消除迁移期间的写入竞态。
    creation('gateway', False)
    d.run(['systemctl', 'restart', 'mytools-mytools-gateway'])
    d.wait_health(23200)
    assert active() == 0, 'active_adaptations_after_gate'
    d.run(['systemctl', 'stop', 'mytools-reader-service'])
    assert active() == 0, 'active_adaptations_after_stop'
    d.run(['systemctl', 'stop', 'mytools-adaptation-executor'])
    # 密码仅通过子进程环境传递，备份文件保留在服务器私有审计目录。
    env = d.environment()
    dump_env = os.environ.copy()
    dump_env['MYSQL_PWD'] = env['READER_DB_PASSWORD']
    dump_path = BACKUP / 'reader-before-v39-v40.sql'
    with dump_path.open('xb') as stream:
        dump_path.chmod(0o600)
        result = subprocess.run(['mysqldump', '--single-transaction', '--no-tablespaces',
            '--host=' + env.get('READER_DB_HOST', '127.0.0.1'), '--port=' + env.get('READER_DB_PORT', '3306'),
            '--user=' + (env.get('READER_DB_USER') or env['READER_DB_USERNAME']), 'mytools_reader'],
            env=dump_env, stdout=stream, stderr=subprocess.PIPE, timeout=120)
    assert result.returncode == 0 and dump_path.stat().st_size > 0, 'private_backup_failed'
    creation('reader', False)
    token = BASE / 'reader/style-admin.key'
    assert not token.exists(), 'style_key_already_exists'
    d.write(token, secrets.token_urlsafe(48), d.identity('reader'))
    props = d.values(BASE / 'reader/application.properties')
    props['reader.adaptation-style-admin-token-file'] = str(token)
    d.props(BASE / 'reader/application.properties', props, d.identity('reader'))
    for name, path in UNITS.items():
        text = path.read_text()
        old_version = OLD if name == 'reader-service' else ROOT / 'releases/chapter-adaptation-production-20260911-v3'
        old_jar = old_version / 'apps' / (('task-executor-service' if name == 'adaptation-executor' else name) + '.jar')
        new_jar = NEW / 'apps' / (('adaptation-executor-v2' if name == 'adaptation-executor' else name) + '.jar')
        assert text.count(str(old_jar)) == 1, 'unexpected_unit_path'
        d.write(path, text.replace(str(old_jar), str(new_jar)), mode=0o644)
    d.checked_current(OLD)
    temporary = ROOT / 'releases/.adaptation-v2-current'
    assert not temporary.exists()
    temporary.symlink_to(NEW.name)
    os.replace(temporary, ROOT / 'releases/current')
    d.run(['systemctl', 'daemon-reload'])
    finish_activation()


def finish_activation():
    d.checked_current(NEW)
    assert (BACKUP / 'reader-before-v39-v40.sql').stat().st_size > 0
    d.run(['systemctl', 'start', 'mytools-reader-service'])
    d.wait_health(23230)
    d.wait_health(24231, True)
    d.run(['systemctl', 'start', 'mytools-adaptation-executor'])
    d.wait_health(24102)
    d.run(['systemctl', 'restart', 'mytools-mytools-gateway'])
    d.wait_health(23200)
    with d.connection('READER') as db, db.cursor() as c:
        c.execute('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1')
        assert c.fetchone() == ('40', 1), 'migration_not_v40'
        c.execute('SELECT COUNT(*) FROM adaptation_style_template')
        assert c.fetchone()[0] == 6, 'default_templates_missing'
    d.write(BACKUP / 'activated.json', json.dumps({'release': NEW.name, 'migration': 40, 'createEnabled': False}))
    print(json.dumps({'activated': NEW.name, 'migration': 40, 'createEnabled': False}), flush=True)


def enable():
    d.checked_current(NEW)
    assert (BACKUP / 'activated.json').exists()
    d.wait_health(24102)
    creation('reader', True)
    d.run(['systemctl', 'restart', 'mytools-reader-service'])
    d.wait_health(23230)
    d.wait_health(24231, True)
    creation('gateway', True)
    d.run(['systemctl', 'restart', 'mytools-mytools-gateway'])
    d.wait_health(23200)
    d.write(BACKUP / 'enabled.json', json.dumps({'release': NEW.name, 'createEnabled': True}))
    print(json.dumps({'enabled': NEW.name}), flush=True)


if __name__ == '__main__':
    try:
        {'stage': stage, 'activate': activate, 'finish-activation': finish_activation, 'enable': enable}[sys.argv[1]]()
    except Exception as error:
        # 不输出异常正文或环境内容；保留关闭入口，供检查后恢复。
        print(json.dumps({'failed': type(error).__name__, 'reason': str(error) if isinstance(error, AssertionError) else 'inspect_private_diagnostics'}), flush=True)
        sys.exit(1)
