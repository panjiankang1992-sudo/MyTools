#!/usr/bin/env python3
"""通过正式配置发布独立 PDF 服务，保留其他业务的二进制及启动参数。"""
from pathlib import Path
import hashlib
import json
import os
import pwd
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

ROOT = Path('/opt/yuyutian/mytools')
SOURCE = Path('/tmp/pdf-tools-20260926-v1')
RELEASE = ROOT / 'releases/pdf-tools-20260926-v1'
RUNTIME = ROOT / 'runtime/pdf-tools'
CONFIG = ROOT / 'config/pdf-tools'
UNITS = ('task-scheduler-service', 'mytools-gateway')


def command(*args):
    """执行命令，不回显秘密参数或环境。"""
    return subprocess.check_output(args, text=True).strip()


def values(path):
    """读取正式环境配置，禁止进程环境探测。"""
    result = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.removeprefix('export ').split('=', 1)
            parts = shlex.split(value)
            result[key.strip()] = parts[0] if parts else ''
    return result


def private(path, body, owner=None):
    """将敏感配置限制为属主可读写。"""
    path.write_text(body)
    path.chmod(0o600)
    if owner:
        user = pwd.getpwnam(owner)
        os.chown(path, user.pw_uid, user.pw_gid)


def environment(name, entries):
    """生成专用服务环境，不复制无关凭据。"""
    private(CONFIG / (name + '.env'), ''.join(k + '=' + json.dumps(str(v)) + '\n' for k, v in entries.items()))


def request(path, body=None):
    """使用正式运维身份访问调度 API。"""
    config = values(ROOT / 'config/services.env')
    headers = {'X-Task-Service-Id': 'task-operator-service', 'X-Task-Internal-Token': config['TASK_OPERATOR_INTERNAL_TOKEN'], 'Content-Type': 'application/json'}
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:23410' + path, data=data, headers=headers), timeout=20) as response:
        return json.load(response)


def health(port):
    """限定等待时间检查应用健康状态。"""
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=3) as response:
                if json.load(response)['status'] == 'UP':
                    return
        except Exception:
            time.sleep(2)
    raise RuntimeError('health_failed_' + str(port))


def start_args(unit):
    """读取 systemd 的当前命令，精确替换 JAR。"""
    result = command('systemctl', 'show', 'mytools-' + unit, '-p', 'ExecStart')
    return re.search(r'argv\[\]=(.*?) ; ignore_errors=', result).group(1)


def stage():
    """创建不可变发布、最小权限账户和可审计配置。"""
    assert not RELEASE.exists(), 'release_exists'
    for relative, expected in json.loads((SOURCE / 'manifest.json').read_text()).items():
        assert hashlib.sha256((SOURCE / relative).read_bytes()).hexdigest() == expected, 'source_digest_mismatch'
    old = {unit: start_args(unit) for unit in UNITS}
    shutil.copytree(SOURCE, RELEASE)
    audit = {'previousCommands': old, 'jarChanges': {}, 'dropins': []}
    for unit in UNITS:
        old_jar = Path(re.search(r'-jar (\S+)', old[unit]).group(1))
        target = RELEASE / 'apps' / (unit + '.jar')
        with zipfile.ZipFile(old_jar) as source, zipfile.ZipFile(RELEASE / (unit + '-delta.zip')) as delta, zipfile.ZipFile(target, 'w') as output:
            names = set(delta.namelist())
            for entry in source.infolist():
                output.writestr(entry, delta.read(entry.filename) if entry.filename in names else source.read(entry.filename))
            for name in sorted(names - set(source.namelist())):
                output.writestr(delta.getinfo(name), delta.read(name))
        # 逐成员验证全部非 PDF 内容保持不变。
        with zipfile.ZipFile(old_jar) as before, zipfile.ZipFile(target) as after:
            assert all(before.read(n) == after.read(n) for n in before.namelist() if n not in names)
        audit['jarChanges'][unit] = sorted(names)
    executor_jar = Path(re.search(r'-jar (\S+)', start_args('task-executor-service')).group(1))
    shutil.copy2(executor_jar, RELEASE / 'apps/task-executor-service.jar')
    CONFIG.mkdir(mode=0o700, exist_ok=True)
    config = values(ROOT / 'config/services.env')
    tokens = {key: secrets.token_urlsafe(36) for key in ('PDF_DB_PASSWORD', 'PDF_INTERNAL_TOKEN', 'PDF_WORKER_TOKEN', 'TASK_BUSINESS_PDF_TOKEN')}
    import pymysql
    with pymysql.connect(read_default_file='/etc/mysql/debian.cnf', autocommit=True) as db, db.cursor() as cursor:
        cursor.execute("CREATE DATABASE IF NOT EXISTS mytools_pdf CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        cursor.execute("CREATE USER IF NOT EXISTS 'mytools_pdf'@'127.0.0.1' IDENTIFIED BY %s", (tokens['PDF_DB_PASSWORD'],))
        cursor.execute("ALTER USER 'mytools_pdf'@'127.0.0.1' IDENTIFIED BY %s", (tokens['PDF_DB_PASSWORD'],))
        cursor.execute("GRANT ALL PRIVILEGES ON mytools_pdf.* TO 'mytools_pdf'@'127.0.0.1'")
    user = pwd.getpwnam('mytools')
    for name in ('inputs', 'tasks', 'tasks-report-journal', 'mcp'):
        folder = RUNTIME / name
        folder.mkdir(mode=0o700, exist_ok=True)
        os.chown(folder, user.pw_uid, user.pw_gid)
    private(RUNTIME / 'mcp/accounts.json', '{}\n', 'mytools')
    environment('service', {**tokens, 'PDF_ENABLED': 'true', 'PDF_TEXT_ENABLED': 'true', 'PDF_ROOT': RUNTIME / 'inputs', 'STORAGE_INTERNAL_TOKEN': config['STORAGE_INTERNAL_TOKEN']})
    environment('gateway', {'PDF_INTERNAL_TOKEN': tokens['PDF_INTERNAL_TOKEN'], 'PDF_ROUTE_ENABLED': 'true', 'PDF_OWNER_ALLOWLIST': '9223372036854775806', 'PDF_MCP_ROUTE_ENABLED': 'true'})
    environment('scheduler', {'TASK_BUSINESS_PDF_TOKEN': tokens['TASK_BUSINESS_PDF_TOKEN']})
    executor_env = {'TASK_EXECUTOR_INTERNAL_TOKEN': config['TASK_EXECUTOR_INTERNAL_TOKEN'], 'PDF_WORKER_TOKEN': tokens['PDF_WORKER_TOKEN'], 'STORAGE_INTERNAL_TOKEN': config['STORAGE_INTERNAL_TOKEN'], 'ASSET_REGISTRY_INTERNAL_TOKEN': config['ASSET_REGISTRY_INTERNAL_TOKEN']}
    environment('executor', executor_env)
    environment('mcp', {'MYTOOLS_GATEWAY_URL': 'http://127.0.0.1:23200', 'PDF_MCP_CREDENTIALS_FILE': RUNTIME / 'mcp/accounts.json', 'PDF_MCP_PUBLIC_URL': 'https://mytools.yuyutian.top/api/app/v1/pdf-mcp', 'PDF_MCP_FILES_URL': 'https://mytools.yuyutian.top/api/app/v1/pdf-mcp/files'})
    clusters = request('/api/v1/execution-topology/clusters')
    cluster_names = sorted({c['name'] for c in clusters} | {'pdf-processing'})
    (RELEASE / 'scheduler.properties').write_text('task.security.business-clients.pdf-service=${TASK_BUSINESS_PDF_TOKEN}\ntask.node-registration.allowed-cluster-names=' + ','.join(cluster_names) + '\ntask.node-registration.trusted-labels[pdf.processing]=enabled\n')
    settings = {'server.address': '127.0.0.1', 'server.port': '23102', 'executor.node-name': 'pdf-executor-1', 'executor.scheduler-url': 'http://127.0.0.1:23410', 'executor.internal-token': '${TASK_EXECUTOR_INTERNAL_TOKEN}', 'executor.work-root': RUNTIME / 'tasks', 'executor.script-root': RELEASE / 'task-packages', 'executor.python-sdk-root': RELEASE / 'sdk', 'executor.python-executable': RUNTIME / 'venv/bin/python', 'executor.cluster-names': 'pdf-processing', 'executor.labels[pdf.processing]': 'enabled', 'executor.max-concurrent-tasks': '1', 'executor.require-package-index': 'true', 'executor.require-non-root': 'true', 'executor.resource-limits.maximum-virtual-memory-bytes': '0', 'executor.resource-limits.maximum-file-bytes': '681574400'}
    worker = {'PDF_SERVICE_URL': 'http://127.0.0.1:23342', 'PDF_WORKER_TOKEN': '${PDF_WORKER_TOKEN}', 'PDF_JAVA_BINARY': command('readlink', '-f', '/usr/bin/java'), 'PDF_JAVA_HOME': '/usr/lib/jvm', 'PDF_FONT_FILE': '/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf', 'PDF_STORAGE_ROOT': 'managed', 'PLAYWRIGHT_BROWSERS_PATH': RUNTIME / 'browsers', 'STORAGE_GATEWAY_URL': 'http://127.0.0.1:23240', 'STORAGE_INTERNAL_TOKEN': '${STORAGE_INTERNAL_TOKEN}', 'ASSET_REGISTRY_URL': 'http://127.0.0.1:23270', 'ASSET_REGISTRY_INTERNAL_TOKEN': '${ASSET_REGISTRY_INTERNAL_TOKEN}'}
    settings.update({'executor.script-environments.pdf_process.' + k: v for k, v in worker.items()})
    (RELEASE / 'executor.properties').write_text(''.join(k + '=' + str(v) + '\n' for k, v in settings.items()))
    for path in [RELEASE, *RELEASE.rglob('*')]:
        if not path.is_symlink():
            path.chmod(0o755 if path.is_dir() else 0o644)
    private(CONFIG / 'deployment.json', json.dumps(audit, indent=2))
    print(json.dumps({'staged': True, 'release': str(RELEASE), 'jarChanges': audit['jarChanges']}))


def activate():
    """无运行任务时激活，失败自动恢复原网关和调度器。"""
    audit = json.loads((CONFIG / 'deployment.json').read_text())
    assert not audit['dropins'], 'already_activated'
    nodes = request('/api/v1/execution-topology/nodes')
    assert all(n.get('runningTasks', 0) == 0 for n in nodes), 'running_tasks_present'
    try:
        for unit in UNITS:
            args = audit['previousCommands'][unit]
            assert start_args(unit) == args, 'concurrent_deployment_detected'
            old_jar = re.search(r'-jar (\S+)', args).group(1)
            args = args.replace('-jar ' + old_jar, '-jar ' + str(RELEASE / 'apps' / (unit + '.jar')), 1)
            kind = 'scheduler' if unit.startswith('task-') else 'gateway'
            if kind == 'scheduler':
                extra = re.search(r'--spring.config.additional-location=(\S+)', args)
                args = args.replace(extra.group(0), extra.group(0) + ',' + str(RELEASE / 'scheduler.properties'), 1) if extra else args + ' --spring.config.additional-location=' + str(RELEASE / 'scheduler.properties')
            folder = Path('/etc/systemd/system/mytools-' + unit + '.service.d')
            folder.mkdir(exist_ok=True)
            drop = folder / (max((p.name for p in folder.glob('*.conf')), default='zz') + '.pdf.conf')
            drop.write_text('[Service]\nEnvironmentFile=' + str(CONFIG / (kind + '.env')) + '\nExecStart=\nExecStart=' + args + '\n')
            audit['dropins'].append(str(drop))
            private(CONFIG / 'deployment.json', json.dumps(audit, indent=2))
        for unit, env, command_line, memory in (
            ('pdf-service', 'service', '/usr/bin/java -Xmx512m -jar ' + str(RELEASE / 'apps/pdf-service.jar'), '1G'),
            ('pdf-executor-service', 'executor', '/usr/bin/java -Xmx512m -jar ' + str(RELEASE / 'apps/task-executor-service.jar') + ' --spring.config.additional-location=' + str(RELEASE / 'executor.properties'), '3G'),
            ('pdf-mcp-service', 'mcp', str(RUNTIME / 'venv/bin/python') + ' ' + str(RELEASE / 'mcp/server.py') + ' --transport http', '512M')):
            path = Path('/etc/systemd/system/mytools-' + unit + '.service')
            path.write_text('[Unit]\nDescription=MyTools ' + unit + '\nAfter=network.target\n[Service]\nUser=mytools\nGroup=mytools\nWorkingDirectory=' + str(RELEASE) + '\nEnvironmentFile=' + str(CONFIG / (env + '.env')) + '\nExecStart=' + command_line + '\nRestart=on-failure\nRestartSec=5\nUMask=0077\nMemoryMax=' + memory + '\nTasksMax=512\n[Install]\nWantedBy=multi-user.target\n')
        command('systemctl', 'daemon-reload')
        command('systemctl', 'restart', 'mytools-task-scheduler-service')
        health(23410)
        command('systemctl', 'restart', 'mytools-mytools-gateway')
        health(23200)
        request('/internal/v1/pdf-deployment', {'enabled': True, 'auditId': 'pdf_tools_20260926_acceptance'})
        command('systemctl', 'enable', '--now', 'mytools-pdf-service', 'mytools-pdf-executor-service', 'mytools-pdf-mcp-service')
        health(23342)
        deadline = time.monotonic() + 100
        while True:
            nodes = request('/api/v1/execution-topology/nodes')
            if any(n['name'] == 'pdf-executor-1' and n['status'] == 'ONLINE' and 'pdf_process:1.0.0' in n.get('capabilities', {}).get('scriptReleases', {}) for n in nodes):
                break
            assert time.monotonic() < deadline, 'pdf_executor_not_registered'
            time.sleep(2)
        audit['activated'] = True
        private(CONFIG / 'deployment.json', json.dumps(audit, indent=2))
        print(json.dumps({'activated': True, 'appAccess': 'acceptance_allowlist_only'}))
    except Exception:
        rollback()
        raise


def rollback():
    """禁用 PDF 新服务，恢复旧启动项；保留兼容迁移及验收数据。"""
    audit = json.loads((CONFIG / 'deployment.json').read_text())
    try:
        request('/internal/v1/pdf-deployment', {'enabled': False, 'auditId': 'pdf_tools_20260926_rollback'})
    except Exception:
        pass
    subprocess.run(['systemctl', 'disable', '--now', 'mytools-pdf-service', 'mytools-pdf-executor-service', 'mytools-pdf-mcp-service'], capture_output=True)
    for path in audit['dropins']:
        Path(path).unlink(missing_ok=True)
    command('systemctl', 'daemon-reload')
    for unit in UNITS:
        command('systemctl', 'restart', 'mytools-' + unit)
    audit['dropins'] = []
    audit['activated'] = False
    private(CONFIG / 'deployment.json', json.dumps(audit, indent=2))
    print(json.dumps({'rolledBack': True}))


if __name__ == '__main__':
    {'stage': stage, 'activate': activate, 'rollback': rollback}[sys.argv[1]]()
