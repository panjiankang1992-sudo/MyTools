#!/usr/bin/env python3
"""以不可变脚本包和前向 Flyway 迁移发布下载流水线，不改写调度业务表。"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import time
from urllib.request import urlopen
import zipfile

import pymysql

ROOT = Path('/opt/yuyutian/mytools/releases/download-pipeline-capacity-20261104-v2')
EXECUTOR = 'mytools-task-executor-service'
SCHEDULER = 'mytools-task-scheduler-service'
MIGRATION = 'V182__align_batch_execution_capacity.sql'
PACKAGES = []


def run(*args):
    """只执行固定命令，不回显进程环境。"""
    return subprocess.check_output(args, text=True).strip()


def health(port):
    """允许 Java 冷启动完成后再判定服务就绪。"""
    for _ in range(120):
        try:
            with urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=3) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('pipeline_service_health_failed')


def main():
    """仅空闲执行器允许重启，已有配置、包和历史迁移保持不变。"""
    pid = run('systemctl', 'show', SCHEDULER, '-p', 'MainPID', '--value')
    environment = dict(value.split('=', 1) for value in
                       Path('/proc/' + pid + '/environ').read_text().split('\0') if '=' in value)
    database = pymysql.connect(host='127.0.0.1', user=environment['TASK_DB_USERNAME'],
                               password=environment['TASK_DB_PASSWORD'], database='mytools_task',
                               autocommit=True)
    with database.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status='RUNNING'")
        assert cursor.fetchone()[0] == 0, 'active_tasks_prevent_restart'
        cursor.execute("SELECT success FROM flyway_schema_history WHERE version='180'")
        assert cursor.fetchone() == (1,), 'previous_migration_required'
        cursor.execute("SELECT COUNT(*) FROM cluster_node cn JOIN task_definition d ON d.cluster_id=cn.cluster_id "
                       "WHERE d.name='download_message_url_batch' AND cn.enabled=TRUE")
        assert cursor.fetchone()[0] > 0, 'batch_node_missing'
    commands = {unit: re.search(r'argv\[\]=(.*?) ; ignore_errors=',
                run('systemctl', 'show', unit, '-p', 'ExecStart')).group(1)
                for unit in (EXECUTOR, SCHEDULER)}
    ROOT.mkdir(exist_ok=False)
    stage = ROOT / 'stage'
    with zipfile.ZipFile('/tmp/download-pipeline-capacity-20261104.zip') as archive:
        archive.extractall(stage)
    old_root = re.search(r'--executor\.script-root=(\S+)', commands[EXECUTOR]).group(1)
    packages = ROOT / 'task-packages'
    run('cp', '-al', old_root, str(packages))
    index_path = packages / 'package-index.json'
    index = json.loads(index_path.read_text())
    for name, version in PACKAGES:
        package = packages / name / version
        assert not package.exists(), 'immutable_package_already_exists'
        shutil.copytree(stage / name / version, package)
        run('chown', '-R', '--reference=' + old_root, str(package))
        files = [{'path': str(p.relative_to(package)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
                  'sizeBytes': p.stat().st_size} for p in sorted(package.rglob('*')) if p.is_file()]
        index['packages'].append({'name': name, 'version': version,
                                  'entrypoint': 'scripts/main.py', 'files': files})
    index['packages'].sort(key=lambda item: (item['name'], item['version']))
    index['packageCount'] = len(index['packages'])
    index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True,
                                                       separators=(',', ':')).encode()).hexdigest()
    # 先解除硬链接再更新索引，原发布目录不可变。
    index_path.unlink()
    index_path.write_text(json.dumps(index, indent=2))
    old_jar = re.search(r'-jar\s+(\S+)', commands[SCHEDULER]).group(1)
    jar = ROOT / 'scheduler.jar'
    entry = 'BOOT-INF/classes/db/migration/' + MIGRATION
    with zipfile.ZipFile(old_jar) as source, zipfile.ZipFile(jar, 'w') as target:
        assert entry not in source.namelist(), 'migration_already_packaged'
        for item in source.infolist():
            target.writestr(item, source.read(item.filename))
        target.writestr(entry, (stage / MIGRATION).read_bytes())
    jar.chmod(0o644)
    (ROOT / 'previous-paths.json').write_text(json.dumps({'schedulerJar': old_jar, 'scriptRoot': old_root}))
    run('systemctl', 'stop', EXECUTOR)
    try:
        with database.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status='RUNNING'")
            assert cursor.fetchone()[0] == 0, 'task_claim_raced_with_release'
        for unit in (EXECUTOR, SCHEDULER):
            updated = commands[unit].replace(old_jar, str(jar)) if unit == SCHEDULER else commands[unit].replace(
                '--executor.script-root=' + old_root, '--executor.script-root=' + str(packages))
            override = Path('/etc/systemd/system') / (unit + '.service.d') / ('z' * 68 + '-download-pipeline-capacity.conf')
            assert not override.exists()
            override.write_text('[Service]\nExecStart=\nExecStart=' + updated + '\n')
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', SCHEDULER)
        health(23410)
    finally:
        # 无论是否通过就绪检查，恢复执行器服务，避免因发布脚本退出而遗留停机。
        run('systemctl', 'start', EXECUTOR)
        database.close()
    health(23101)
    print(json.dumps({'scheduler': 'UP', 'executor': 'UP', 'migration': 182, 'packages': PACKAGES}), flush=True)


if __name__ == '__main__':
    main()
