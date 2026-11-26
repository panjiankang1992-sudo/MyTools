#!/usr/bin/env python3
"""验证失败迁移无部分业务变更后，通过 Flyway 正常修复再启动服务。"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import time
from urllib.request import urlopen
import zipfile


def run(*args, **kwargs):
    """不把环境凭据拼接到命令参数中。"""
    return subprocess.check_output(args, text=True, **kwargs).strip()


def main():
    """新建修复发布目录，原脚本版本和失败包保持不变。"""
    root = Path('/opt/yuyutian/mytools/releases/download-async-schema-repair-20261104-v1')
    root.mkdir(exist_ok=False)
    stage = root / 'stage'
    with zipfile.ZipFile('/tmp/download-async-schema-repair.zip') as archive:
        archive.extractall(stage)
    units = ('mytools-task-scheduler-service', 'mytools-task-executor-service')
    commands = {unit: re.search(r'argv\[\]=(.*?) ; ignore_errors=',
        run('systemctl', 'show', unit, '-p', 'ExecStart')).group(1) for unit in units}
    old_root = re.search(r'--executor\.script-root=(\S+)', commands[units[1]]).group(1)
    packages = root / 'task-packages'
    run('cp', '-al', old_root, str(packages))
    package = packages / 'message_submit_attachment_download/1.2.1'
    shutil.copytree(stage / 'message_submit_attachment_download/1.2.1', package)
    run('chown', '-R', '--reference=' + old_root, str(package))
    index_path = packages / 'package-index.json'
    index = json.loads(index_path.read_text())
    files = [{'path': str(p.relative_to(package)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
              'sizeBytes': p.stat().st_size} for p in sorted(package.rglob('*')) if p.is_file()]
    index['packages'].append({'name': 'message_submit_attachment_download', 'version': '1.2.1',
                              'entrypoint': 'scripts/main.py', 'files': files})
    index['packages'].sort(key=lambda item: (item['name'], item['version']))
    index['packageCount'] = len(index['packages'])
    index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    index_path.unlink();index_path.write_text(json.dumps(index, indent=2))
    old_jar = re.search(r'-jar\s+(\S+)', commands[units[0]]).group(1)
    jar = root / 'scheduler.jar'
    entry = 'BOOT-INF/classes/db/migration/V180__detach_download_enrichment.sql'
    libraries, migrations = root / 'libraries', root / 'migrations'
    libraries.mkdir();migrations.mkdir()
    with zipfile.ZipFile(old_jar) as source, zipfile.ZipFile(jar, 'w') as target:
        for item in source.infolist():
            data = (stage / 'V180__detach_download_enrichment.sql').read_bytes() if item.filename == entry else source.read(item.filename)
            target.writestr(item, data)
            if item.filename.startswith('BOOT-INF/lib/') and item.filename.endswith('.jar'):
                (libraries / Path(item.filename).name).write_bytes(data)
            if item.filename.startswith('BOOT-INF/classes/db/migration/') and item.filename.endswith('.sql'):
                (migrations / Path(item.filename).name).write_bytes(data)
    jar.chmod(0o644)
    pid = run('systemctl', 'show', 'mytools-download-ingestion-service', '-p', 'MainPID', '--value')
    environment = dict(value.split('=', 1) for value in Path('/proc/' + pid + '/environ').read_text().split('\0') if '=' in value)
    run('javac', '-encoding', 'UTF-8', '-Xlint:all', '-cp', str(libraries / '*'), '-d', str(root), str(stage / 'RepairFailedDownloadMigration.java'))
    # 修复日志只留在受保护发布目录，官方工具只处理已确认的失败元数据。
    log = root / 'flyway-repair.log'
    log.touch(mode=0o600)
    with log.open('w') as output:
        subprocess.run(['java', '-cp', str(root) + ':' + str(libraries / '*'),
                        'RepairFailedDownloadMigration', str(migrations)], env=environment,
                       stdout=output, stderr=subprocess.STDOUT, check=True)
    for unit in units:
        updated = commands[unit].replace(old_jar, str(jar)) if unit == units[0] else commands[unit].replace(
            '--executor.script-root=' + old_root, '--executor.script-root=' + str(packages))
        override = Path('/etc/systemd/system') / (unit + '.service.d') / ('z' * 65 + '-async-schema-repair.conf')
        override.write_text('[Service]\nExecStart=\nExecStart=' + updated + '\n')
    run('systemctl', 'daemon-reload')
    for unit, port in ((units[0], 23410), (units[1], 23101)):
        run('systemctl', 'start', unit)
        for _ in range(120):
            try:
                with urlopen('http://127.0.0.1:' + str(port) + '/actuator/health', timeout=2) as response:
                    if json.load(response).get('status') == 'UP':break
            except (OSError, ValueError):pass
            time.sleep(1)
        else:raise RuntimeError('repaired_service_health_failed')
    print(json.dumps({'migration': 180, 'scheduler': 'UP', 'executor': 'UP', 'enrichmentStillPaused': True}))


if __name__ == '__main__':
    main()
