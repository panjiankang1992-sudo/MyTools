#!/usr/bin/env python3
"""发布有界文件名脚本包及 Flyway 迁移，保留原脚本根目录和运行配置。"""
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

ROOT = Path('/opt/yuyutian/mytools/releases/download-publish-name-fix-20261018-v1')
EXECUTOR = 'mytools-task-executor-service'
SCHEDULER = 'mytools-task-scheduler-service'
MIGRATION = 'V175__bound_published_download_file_names.sql'


def run(*args):
    """执行固定命令。"""
    return subprocess.check_output(args, text=True).strip()


def command(unit):
    """读取现行启动命令，不读取或输出生产环境文件。"""
    return re.search(r'argv\[\]=(.*?) ; ignore_errors=',
                     run('systemctl', 'show', unit, '-p', 'ExecStart')).group(1)


def main():
    """先使不可变新脚本包可用，再由 Scheduler 执行协议迁移。"""
    pid = run('systemctl', 'show', SCHEDULER, '-p', 'MainPID', '--value')
    environment = dict(value.split('=', 1) for value in
                       Path('/proc/' + pid + '/environ').read_text().split('\0') if '=' in value)
    database = pymysql.connect(host='127.0.0.1', user=environment['TASK_DB_USERNAME'],
                               password=environment['TASK_DB_PASSWORD'], database='mytools_task')
    try:
        with database.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status = 'RUNNING'")
            assert cursor.fetchone()[0] == 0, 'active_tasks_prevent_restart'
    finally:
        database.close()
    ROOT.mkdir(exist_ok=False)
    stage = ROOT / 'stage'
    with zipfile.ZipFile('/tmp/download-publish-1.2.1.zip') as archive:
        archive.extractall(stage)
    commands = {unit: command(unit) for unit in (EXECUTOR, SCHEDULER)}
    old_root = re.search(r'--executor\.script-root=(\S+)', commands[EXECUTOR]).group(1)
    packages = ROOT / 'task-packages'
    run('cp', '-al', old_root, str(packages))
    package = packages / 'download_publish_file/1.2.1'
    assert not package.exists()
    shutil.copytree(stage / 'download_publish_file/1.2.1', package)
    index_path = packages / 'package-index.json'
    index = json.loads(index_path.read_text())
    files = [{'path': str(path.relative_to(package)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
              'sizeBytes': path.stat().st_size} for path in sorted(package.rglob('*')) if path.is_file()]
    index['packages'].append({'name': 'download_publish_file', 'version': '1.2.1',
                              'entrypoint': 'scripts/main.py', 'files': files})
    index['packages'].sort(key=lambda item: (item['name'], item['version']))
    index['packageCount'] = len(index['packages'])
    index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True,
                                                       separators=(',', ':')).encode()).hexdigest()
    # 先解除新索引的硬链接，确保旧发布目录保持不可变。
    index_path.unlink()
    index_path.write_text(json.dumps(index, indent=2))
    commands[EXECUTOR] = commands[EXECUTOR].replace('--executor.script-root=' + old_root,
                                                   '--executor.script-root=' + str(packages))
    old_jar = re.search(r'-jar\s+(\S+)', commands[SCHEDULER]).group(1)
    jar = ROOT / 'scheduler.jar'
    entry = 'BOOT-INF/classes/db/migration/' + MIGRATION
    with zipfile.ZipFile(old_jar) as source, zipfile.ZipFile(jar, 'w') as target:
        assert entry not in source.namelist()
        for item in source.infolist():
            target.writestr(item, source.read(item.filename))
        target.writestr(entry, (stage / MIGRATION).read_bytes())
    jar.chmod(0o644)
    commands[SCHEDULER] = commands[SCHEDULER].replace(old_jar, str(jar))
    overrides = []
    try:
        for unit in (EXECUTOR, SCHEDULER):
            path = Path('/etc/systemd/system') / (unit + '.service.d') / ('z' * 44 + '-publish-name.conf')
            path.parent.mkdir(exist_ok=True)
            assert not path.exists()
            path.write_text('[Service]\nExecStart=\nExecStart=' + commands[unit] + '\n')
            overrides.append(path)
        run('systemctl', 'daemon-reload')
        for unit in (EXECUTOR, SCHEDULER):
            assert str(ROOT) in run('systemctl', 'show', unit, '-p', 'ExecStart')
            run('systemctl', 'restart', unit)
        for _ in range(45):
            try:
                with urlopen('http://127.0.0.1:23410/actuator/health', timeout=2) as response:
                    if json.load(response).get('status') == 'UP':
                        break
            except (OSError, ValueError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('scheduler_health_failed')
    except Exception:
        for path in overrides:
            path.unlink()
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', EXECUTOR, SCHEDULER)
        raise
    print(json.dumps({'release': str(ROOT), 'packageVersion': '1.2.1',
                      'services': {unit: run('systemctl', 'is-active', unit) for unit in commands}}))


if __name__ == '__main__':
    main()
