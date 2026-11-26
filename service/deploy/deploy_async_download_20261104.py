#!/usr/bin/env python3
"""发布持久化下载后处理和无槽位对账，完整保留现有 APP 包及运行配置。"""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import time
from urllib.request import urlopen
import zipfile

import pymysql

ROOT = Path('/opt/yuyutian/mytools/releases/download-async-20261104-v1')
EXECUTOR = 'mytools-task-executor-service'
SCHEDULER = 'mytools-task-scheduler-service'
DOWNLOAD = 'mytools-download-ingestion-service'
PACKAGES = [('download_record_result', '1.1.0'), ('download_record_tags', '1.2.0'),
            ('message_submit_attachment_download', '1.2.1'), ('media_generate_tags', '1.4.2')]
MODULES = ('service.py', 'mysql_repository.py', 'scheduler_client.py', 'main.py', 'app_tools.py', 'background.py')


def run(*args):
    """执行固定命令，不输出生产环境。"""
    return subprocess.check_output(args, text=True).strip()


def environment(unit):
    """只在进程内读取服务自身凭据和配置。"""
    pid = run('systemctl', 'show', unit, '-p', 'MainPID', '--value')
    return dict(value.split('=', 1) for value in Path('/proc/' + pid + '/environ').read_text().split('\0') if '=' in value)


def command(unit):
    """保留实际启动参数，只替换目标发布路径。"""
    return re.search(r'argv\[\]=(.*?) ; ignore_errors=', run('systemctl', 'show', unit, '-p', 'ExecStart')).group(1)


def health(url):
    """验证真实服务就绪，不能用进程 active 代替健康状态。"""
    for _ in range(120):
        try:
            with urlopen(url, timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('service_health_failed')


def main():
    """先冻结领取并迁移 Download，再切换新步骤，防止异步结果在旧服务上丢失。"""
    task_env, download_env, executor_env = environment(SCHEDULER), environment(DOWNLOAD), environment(EXECUTOR)
    old_python = download_env.get('PYTHONPATH', '')
    source = next((Path(path) / 'mytools_download_ingestion' for path in old_python.split(':')
                   if (Path(path) / 'mytools_download_ingestion/app_tools.py').is_file()), None)
    assert source and 'app-download-tools' in (source / 'http_api.py').read_text(), 'complete_app_package_required'
    assert executor_env.get('IMAGE_GPU_COORDINATION_VALIDATED') == 'true', 'gpu_coordination_required'
    database = pymysql.connect(host='127.0.0.1', user=task_env['TASK_DB_USERNAME'],
        password=task_env['TASK_DB_PASSWORD'], database='mytools_task')
    with database.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status='RUNNING'")
        assert cursor.fetchone()[0] == 0, 'active_tasks_prevent_restart'
    commands = {unit: command(unit) for unit in (EXECUTOR, SCHEDULER)}
    ROOT.mkdir(exist_ok=False)
    stage = ROOT / 'stage'
    with zipfile.ZipFile('/tmp/download-async-20261104-v1.zip') as archive:
        for name in archive.namelist():
            assert not Path(name).is_absolute() and '..' not in Path(name).parts
        archive.extractall(stage)
    manifest = json.loads((stage / 'manifest.json').read_text())
    for name, digest in manifest.items():
        assert hashlib.sha256((stage / name).read_bytes()).hexdigest() == digest, 'staged_digest_mismatch'
    python_root = ROOT / 'python'
    target = python_root / 'mytools_download_ingestion'
    shutil.copytree(source, target, ignore=shutil.ignore_patterns('__pycache__'))
    for name in MODULES:
        shutil.copyfile(stage / 'python' / name, target / name)
    run('chown', '-R', '--reference=' + str(source), str(target))
    assert (target / 'app_tools.py').exists() and 'app-download-tools' in (target / 'http_api.py').read_text()
    old_root = re.search(r'--executor\.script-root=(\S+)', commands[EXECUTOR]).group(1)
    packages = ROOT / 'task-packages'
    run('cp', '-al', old_root, str(packages))
    index_path = packages / 'package-index.json'
    index = json.loads(index_path.read_text())
    for name, version in PACKAGES:
        package = packages / name / version
        assert not package.exists(), 'immutable_package_already_published'
        shutil.copytree(stage / 'packages' / name / version, package)
        run('chown', '-R', '--reference=' + old_root, str(package))
        files = [{'path': str(path.relative_to(package)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                  'sizeBytes': path.stat().st_size} for path in sorted(package.rglob('*')) if path.is_file()]
        index['packages'].append({'name': name, 'version': version, 'entrypoint': 'scripts/main.py', 'files': files})
    index['packages'].sort(key=lambda item: (item['name'], item['version']))
    index['packageCount'] = len(index['packages'])
    index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True,
                                                       separators=(',', ':')).encode()).hexdigest()
    index_path.unlink()
    index_path.write_text(json.dumps(index, indent=2))
    assert (packages / 'media_materialize_input/1.0.0/scripts/main.py').is_file()
    old_jar = re.search(r'-jar\s+(\S+)', commands[SCHEDULER]).group(1)
    jar = ROOT / 'scheduler.jar'
    migration = 'V180__detach_download_enrichment.sql'
    entry = 'BOOT-INF/classes/db/migration/' + migration
    with zipfile.ZipFile(old_jar) as original, zipfile.ZipFile(jar, 'w') as output:
        assert entry not in original.namelist()
        for item in original.infolist():
            output.writestr(item, original.read(item.filename))
        output.writestr(entry, (stage / migration).read_bytes())
    jar.chmod(0o644)
    # 回退元数据不包含任何环境凭据，只保留原路径；已生产的异步工作必须先排空再停消费者。
    (ROOT / 'previous-paths.json').write_text(json.dumps({'pythonPath': old_python,
                                                       'scriptRoot': old_root, 'schedulerJar': old_jar}))
    run('systemctl', 'stop', EXECUTOR)
    database.rollback()
    with database.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status='RUNNING'")
        assert cursor.fetchone()[0] == 0, 'task_claim_raced_with_deployment'
    database.close()
    run('systemctl', 'stop', DOWNLOAD)
    # Python Schema 仅使用 Download 自身账号，通过既有带校验和的迁移器提交。
    spec = importlib.util.spec_from_file_location('async_download_migrations', stage / 'apply_python_migrations.py')
    migrations = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = migrations
    spec.loader.exec_module(migrations)
    path = stage / 'V7__durable_download_background_jobs.sql'
    change = migrations.Migration(7, 'durable_download_background_jobs', path,
                                  hashlib.sha256(path.read_bytes()).hexdigest(), path.read_text())
    migrations.apply_service({'name': 'download-ingestion-service', 'dbPrefix': 'DOWNLOAD',
                              'schema': 'mytools_download'}, [change], task_env)
    override_name = 'z' * 64 + '-async-download.conf'
    for unit in (DOWNLOAD, EXECUTOR, SCHEDULER):
        folder = Path('/etc/systemd/system') / (unit + '.service.d')
        folder.mkdir(exist_ok=True)
        override = folder / override_name
        assert not override.exists()
        if unit == DOWNLOAD:
            content = ('[Service]\nEnvironment="PYTHONPATH=' + str(python_root) + ':' + old_python + '"\n'
                       'Environment=DOWNLOAD_BACKGROUND_ENABLED=true\n'
                       'Environment=DOWNLOAD_ENRICHMENT_PAUSED=true\n')
        else:
            updated = commands[unit].replace('--executor.script-root=' + old_root,
                                             '--executor.script-root=' + str(packages)) if unit == EXECUTOR else commands[unit].replace(old_jar, str(jar))
            content = '[Service]\nExecStart=\nExecStart=' + updated + '\n'
        override.write_text(content)
    run('systemctl', 'daemon-reload')
    run('systemctl', 'start', DOWNLOAD)
    health('http://127.0.0.1:23220/health')
    run('systemctl', 'restart', SCHEDULER)
    health('http://127.0.0.1:23410/actuator/health')
    run('systemctl', 'start', EXECUTOR)
    health('http://127.0.0.1:23101/actuator/health')
    print(json.dumps({'release': str(ROOT), 'backgroundReconciliation': True,
                      'enrichmentPausedForAcceptance': True,
                      'services': {unit: run('systemctl', 'is-active', unit) for unit in (DOWNLOAD, SCHEDULER, EXECUTOR)}}))


if __name__ == '__main__':
    main()
