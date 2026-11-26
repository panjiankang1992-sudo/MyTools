#!/usr/bin/env python3
"""通过现有 Scheduler 启动 Flyway 协议迁移，保留原有二进制与配置并支持回退。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import time
from urllib.request import urlopen
import zipfile

import pymysql

UNIT = 'mytools-task-scheduler-service'
ROOT = Path('/opt/yuyutian/mytools')
RELEASE = ROOT / 'releases/streamed-attachment-metadata-20261012-v1'
MIGRATION = 'V172__carry_streamed_attachment_metadata.sql'
OVERRIDE = Path('/etc/systemd/system') / (UNIT + '.service.d/' + 'z' * 32 + '-streamed-attachment-metadata.conf')


def run(*args):
    """执行固定运维命令。"""
    return subprocess.check_output(args, text=True).strip()


def ready():
    """等待调度器完成迁移并恢复健康。"""
    for _ in range(45):
        try:
            with urlopen('http://127.0.0.1:23410/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('scheduler_health_failed')


def verify_migration():
    """只读核验实际迁移记录和协议，避免把服务存活误判为发布成功。"""
    pid = run('systemctl', 'show', UNIT, '-p', 'MainPID', '--value')
    environment = dict(value.split('=', 1) for value in
                       Path('/proc/' + pid + '/environ').read_text().split('\0') if '=' in value)
    url = environment.get('TASK_DB_URL', 'jdbc:mysql://127.0.0.1:3306/mytools_task')
    match = re.match(r'jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)', url)
    connection = pymysql.connect(host=match[1], port=int(match[2] or 3306),
                                 user=environment['TASK_DB_USERNAME'],
                                 password=environment['TASK_DB_PASSWORD'], database=match[3])
    try:
        with connection.cursor() as cursor:
            cursor.execute('SELECT success FROM flyway_schema_history WHERE version = %s', ('172',))
            assert cursor.fetchone() == (1,), 'migration_not_applied'
            cursor.execute('SELECT parameter_schema FROM task_definition WHERE name = %s',
                           ('download_message_attachment',))
            schema = json.loads(cursor.fetchone()[0])
            assert schema['additionalProperties'] is False, 'strict_contract_lost'
            assert {'resourceUsername', 'assetMimeType', 'receivedAt'}.issubset(
                schema['properties']), 'metadata_contract_missing'
    finally:
        connection.close()


def main():
    """仅向当前生产 JAR 添加新迁移，失败时恢复启动命令。"""
    sql = Path('/tmp/' + MIGRATION).read_bytes()
    command = re.search(r'argv\[\]=(.*?) ; ignore_errors=',
                       run('systemctl', 'show', UNIT, '-p', 'ExecStart')).group(1)
    original = re.search(r'-jar\s+(\S+)', command).group(1)
    RELEASE.mkdir(exist_ok=False)
    jar = RELEASE / 'scheduler.jar'
    entry = 'BOOT-INF/classes/db/migration/' + MIGRATION
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(jar, 'w') as target:
        assert entry not in source.namelist(), 'migration_already_packaged'
        for item in source.infolist():
            target.writestr(item, source.read(item.filename))
        target.writestr(entry, sql)
    jar.chmod(0o644)
    OVERRIDE.parent.mkdir(exist_ok=True)
    assert not OVERRIDE.exists(), 'override_already_exists'
    OVERRIDE.write_text('[Service]\nExecStart=\nExecStart=' + command.replace(original, str(jar)) + '\n')
    try:
        run('systemctl', 'daemon-reload')
        effective = run('systemctl', 'show', UNIT, '-p', 'ExecStart')
        if str(jar) not in effective:
            raise RuntimeError('scheduler_override_not_effective')
        run('systemctl', 'restart', UNIT)
        ready()
        verify_migration()
    except Exception:
        OVERRIDE.unlink()
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', UNIT)
        raise
    print(json.dumps({'release': str(RELEASE), 'migrationSha256': hashlib.sha256(sql).hexdigest(),
                      'status': run('systemctl', 'is-active', UNIT)}))


if __name__ == '__main__':
    main()
