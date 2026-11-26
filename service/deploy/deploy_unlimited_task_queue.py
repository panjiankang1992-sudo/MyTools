#!/usr/bin/env python3
"""增量发布无排队期限调度策略，保留现行配置和数据库记录，启动失败自动回退。"""
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
RELEASE = Path('/opt/yuyutian/mytools/releases/unlimited-task-queue-20261021-v1')
OVERRIDE = Path('/etc/systemd/system') / (UNIT + '.service.d') / ('z' * 48 + '-unlimited-queue.conf')


def run(*args):
    """运行固定运维命令。"""
    return subprocess.check_output(args, text=True).strip()


def current_executions():
    """只读记录运行中执行数，发布期间由既有租约与执行器回报日志保障恢复。"""
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
            cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status = 'RUNNING'")
            return cursor.fetchone()[0]
    finally:
        connection.close()


def healthy():
    """确认新 Scheduler 健康。"""
    for _ in range(45):
        try:
            with urlopen('http://127.0.0.1:23410/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('scheduler_health_failed')


def main():
    """保留生产 JAR 其余条目，只替换已校验的五个调度类。"""
    active = current_executions()
    with zipfile.ZipFile('/tmp/unlimited-task-queue-20261021.zip') as archive:
        manifest = json.loads(archive.read('manifest.json'))
        allowed = {'BOOT-INF/classes/com/yuyutian/mytools/task/scheduler/' + name + '.class'
                   for name in ('repository/TaskInstanceRepository', 'repository/GpuTaskRepository',
                                'service/TaskDeadlineService', 'service/TaskDispatchService',
                                'config/DispatchDeadlineProperties')}
        assert set(manifest) == allowed, 'unexpected_delta_entries'
        files = {entry: archive.read(entry) for entry in manifest}
        assert all(hashlib.sha256(data).hexdigest() == manifest[entry]
                   for entry, data in files.items()), 'delta_hash_mismatch'
    command = re.search(r'argv\[\]=(.*?) ; ignore_errors=',
                        run('systemctl', 'show', UNIT, '-p', 'ExecStart')).group(1)
    original = re.search(r'-jar\s+(\S+)', command).group(1)
    RELEASE.mkdir(exist_ok=False)
    jar = RELEASE / 'scheduler.jar'
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(jar, 'w') as target:
        assert set(files).issubset(source.namelist()), 'production_class_missing'
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
        healthy()
    except Exception:
        OVERRIDE.unlink()
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', UNIT)
        raise
    print(json.dumps({'release': str(RELEASE), 'status': run('systemctl', 'is-active', UNIT),
                      'queueExpiryEnabled': False, 'replacedClasses': len(files), 'existingExecutions': active}))


if __name__ == '__main__':
    main()
