#!/usr/bin/env python3
"""将灰度验收修复固定为独立的 1.0.1 任务包和 Flyway 迁移。"""
from pathlib import Path
import hashlib
import json
import shutil
import time
import zipfile
import deploy_pdf_tools as d


def main():
    """只切换 PDF 任务包和迁移，保留其他 JAR 成员及服务启动配置。"""
    nodes = d.request('/api/v1/execution-topology/nodes')
    assert all(n.get('runningTasks', 0) == 0 for n in nodes), 'running_tasks_present'
    root = d.ROOT / 'releases/pdf-tools-20260926-v3'
    root.mkdir(exist_ok=False)
    shutil.copytree(d.RELEASE / 'task-packages', root / 'task-packages')
    package = root / 'task-packages/pdf_process/1.0.1'
    shutil.copytree(d.ROOT / 'releases/pdf-tools-20260926-v2/task-packages/pdf_process/1.0.0', package)
    manifest = package / 'manifest.yaml'
    manifest.write_text(manifest.read_text().replace('"1.0.0"', '"1.0.1"'))
    index_path = root / 'task-packages/package-index.json'
    index = json.loads(index_path.read_text())
    entry = {'name': 'pdf_process', 'version': '1.0.1', 'entrypoint': 'scripts/main.py', 'files': []}
    for path in sorted(package.rglob('*')):
        if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
            entry['files'].append({'path': str(path.relative_to(package)), 'sizeBytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    index['packages'].append(entry)
    index['packageCount'] = len(index['packages'])
    index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    index_path.write_text(json.dumps(index, indent=2))
    (root / 'executor.properties').write_text('executor.script-root=' + str(root / 'task-packages') + '\n')
    old_args = d.start_args('task-scheduler-service')
    old_jar = d.RELEASE / 'apps/task-scheduler-service.jar'
    assert str(old_jar) in old_args
    jar = root / 'task-scheduler-service.jar'
    migration = 'db/migration/V167__upgrade_pdf_linux_runtime.sql'
    with zipfile.ZipFile(old_jar) as before, zipfile.ZipFile(jar, 'w') as after:
        for item in before.infolist():
            after.writestr(item, before.read(item.filename))
        after.writestr('BOOT-INF/classes/' + migration, Path('/tmp/V167__upgrade_pdf_linux_runtime.sql').read_bytes())
    with zipfile.ZipFile(old_jar) as before, zipfile.ZipFile(jar) as after:
        assert all(before.read(n) == after.read(n) for n in before.namelist())
    for path in [root, *root.rglob('*')]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    unit = Path('/etc/systemd/system/mytools-pdf-executor-service.service')
    unit.write_text(unit.read_text().replace(str(d.ROOT / 'releases/pdf-tools-20260926-v2/executor.properties'), str(root / 'executor.properties')))
    folder = Path('/etc/systemd/system/mytools-task-scheduler-service.service.d')
    drop = folder / (max(p.name for p in folder.glob('*.conf')) + '.worker101.conf')
    drop.write_text('[Service]\nExecStart=\nExecStart=' + old_args.replace(str(old_jar), str(jar)) + '\n')
    audit = json.loads((d.CONFIG / 'deployment.json').read_text())
    audit['dropins'].append(str(drop))
    audit['workerRelease'] = str(root)
    audit['workerVersion'] = '1.0.1'
    d.private(d.CONFIG / 'deployment.json', json.dumps(audit, indent=2))
    try:
        d.command('systemctl', 'daemon-reload')
        d.command('systemctl', 'restart', 'mytools-pdf-executor-service')
        d.command('systemctl', 'restart', 'mytools-task-scheduler-service')
        d.health(23410)
        deadline = time.monotonic() + 100
        while True:
            nodes = d.request('/api/v1/execution-topology/nodes')
            if any(n['name'] == 'pdf-executor-1' and n['status'] == 'ONLINE' and 'pdf_process:1.0.1' in n.get('capabilities', {}).get('scriptReleases', {}) for n in nodes):
                break
            assert time.monotonic() < deadline, 'new_worker_not_online'
            time.sleep(2)
    except Exception:
        d.rollback()
        raise
    print(json.dumps({'workerVersion': '1.0.1', 'release': str(root), 'otherJarMembersUnchanged': True}))


if __name__ == '__main__':
    main()
