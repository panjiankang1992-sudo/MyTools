#!/usr/bin/env python3
"""部署图片服务的固定版本；私密配置只在 Ubuntu 本机读取和写入。"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import secrets
import shlex
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.error

ROOT = Path('/opt/yuyutian/mytools')
NAME = 'image-production-20260913-v1'
RELEASE = ROOT / 'releases' / NAME
STATE = ROOT / 'runtime' / NAME
SOURCE = Path('/tmp') / NAME
COMFY = ROOT / 'runtime/krea2-evaluation-20260913'
ENV = ROOT / 'config/image-generation.env'
UNITS = Path('/etc/systemd/system')
SERVICES = ['task-scheduler-service', 'task-executor-service', 'mytools-gateway']


def emit(value):
    print(json.dumps(value), flush=True)


def run(args, timeout=120):
    result = subprocess.run([str(x) for x in args], capture_output=True, timeout=timeout)
    if result.returncode:
        if STATE.exists():
            write(STATE / 'last-command.log', result.stderr)
        raise RuntimeError('command_failed_' + Path(str(args[0])).name)
    return result.stdout


def write(path, content, user='root', mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content if isinstance(content, bytes) else content.encode())
    path.chmod(mode)
    os.chown(path, pwd.getpwnam(user).pw_uid, pwd.getpwnam(user).pw_gid)


def values(path):
    data = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator and not key.startswith('#'):
            data[key] = shlex.split(value)[0] if value else ''
    return data


def environment():
    data = values(ROOT / 'config/services.env')
    if ENV.exists():
        data.update(values(ENV))
    return data


def request(port, path, method='GET', body=None, owner=None, identity='operator'):
    data = environment()
    headers = {'Content-Type': 'application/json'}
    if port == 23410:
        if identity == 'image':
            headers.update({'X-Task-Service-Id': 'image-generation-service', 'X-Task-Business-Token': data['TASK_BUSINESS_IMAGE_GENERATION_TOKEN']})
        else:
            headers.update({'X-Task-Service-Id': 'task-operator-service', 'X-Task-Internal-Token': data['TASK_OPERATOR_INTERNAL_TOKEN']})
    if port == 23340:
        headers['Authorization'] = 'Bearer ' + data['IMAGE_GENERATION_INTERNAL_TOKEN']
        headers['X-Owner-Id'] = str(owner or 1)
    req = urllib.request.Request('http://127.0.0.1:' + str(port) + path, method=method, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read(8000000)
        if response.headers.get('Content-Type', '').startswith('image/'):
            return response.status, raw
        return response.status, json.loads(raw) if raw else None


def health(port):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            code, result = request(port, '/actuator/health')
            if code == 200 and result.get('status') == 'UP':
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('health_failed_' + str(port))


def task_connection():
    import pymysql
    data = environment()
    return pymysql.connect(host=data.get('TASK_DB_HOST', '127.0.0.1'), port=int(data.get('TASK_DB_PORT', '3306')),
        user=data.get('TASK_DB_USER') or data['TASK_DB_USERNAME'], password=data['TASK_DB_PASSWORD'], database='mytools_task')


def stage():
    assert not RELEASE.exists() and not ENV.exists(), 'stage_already_exists'
    manifest = json.loads((SOURCE / 'manifest.json').read_text())
    for name, digest in manifest.items():
        assert hashlib.sha256((SOURCE / name).read_bytes()).hexdigest() == digest, 'artifact_digest_mismatch'
    STATE.mkdir(parents=True, exist_ok=True)
    STATE.chmod(0o700)
    RELEASE.mkdir()
    shutil.copytree(SOURCE / 'apps', RELEASE / 'apps')
    # 延续普通节点的完整历史脚本目录，只增加两个新版本。
    old = values(ROOT / 'config/media-character-tags.env')['TASK_EXECUTOR_SCRIPT_ROOT']
    shutil.copytree(old, RELEASE / 'task-packages')
    for package in ['image_generate/1.0.0', 'media_generate_tags/1.4.0']:
        shutil.copytree(SOURCE / 'packages' / package, RELEASE / 'task-packages' / package)
    # 新文件必须同时进入索引，节点才会声明对应脚本发布能力。
    index_path = RELEASE / 'task-packages/package-index.json'
    index = json.loads(index_path.read_text())
    for name, version in [('image_generate', '1.0.0'), ('media_generate_tags', '1.4.0')]:
        package_root = RELEASE / 'task-packages' / name / version
        files = []
        for file in sorted(package_root.rglob('*')):
            if file.is_file() and '__pycache__' not in file.parts and 'tests' not in file.relative_to(package_root).parts:
                files.append({'path': str(file.relative_to(package_root)), 'sizeBytes': file.stat().st_size,
                              'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
        index['packages'].append({'name': name, 'version': version, 'entrypoint': 'scripts/main.py', 'files': files})
    index['packageCount'] = len(index['packages'])
    index['contentSha256'] = hashlib.sha256(json.dumps(index['packages'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    index_path.write_text(json.dumps(index, indent=2, sort_keys=True) + '\n')
    shutil.copy2(SOURCE / 'workflow.json', RELEASE / 'workflow.json')
    for path in [RELEASE, *RELEASE.rglob('*')]:
        os.chown(path, 0, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o750 if path.is_dir() else 0o640)
    data = {'IMAGE_GENERATION_INTERNAL_TOKEN': secrets.token_urlsafe(32),
        'TASK_BUSINESS_IMAGE_GENERATION_TOKEN': secrets.token_urlsafe(32),
        'IMAGE_GENERATION_DB_PASSWORD': secrets.token_urlsafe(32),
        'IMAGE_GENERATION_DB_USER': 'mytools_image_generation',
        'IMAGE_GENERATION_ROOT': str(ROOT / 'runtime/image-generation'),
        'IMAGE_GENERATION_ROUTE_ENABLED': 'false', 'IMAGE_GENERATION_LOCAL_VALIDATED': 'true',
        'IMAGE_GPU_COORDINATION_VALIDATED': 'true', 'IMAGE_GENERATION_STYLE_VALIDATED': 'false',
        'IMAGE_REMOTE_VALIDATED': 'false', 'IMAGE_GPU_LOCK_FILE': str(ROOT / 'runtime/image-generation/gpu.lock'),
        'IMAGE_COMFY_URL': 'http://127.0.0.1:8189', 'IMAGE_WORKFLOW_FILE': str(RELEASE / 'workflow.json'),
        'IMAGE_WORKFLOW_SHA256': hashlib.sha256((RELEASE / 'workflow.json').read_bytes()).hexdigest(),
        'TASK_EXECUTOR_SCRIPT_ROOT': str(RELEASE / 'task-packages')}
    write(ENV, ''.join(k + '=' + v + '\n' for k, v in data.items()))
    import pymysql
    with pymysql.connect(read_default_file='/etc/mysql/debian.cnf', autocommit=True) as db, db.cursor() as cursor:
        cursor.execute('CREATE DATABASE IF NOT EXISTS mytools_image_generation CHARACTER SET utf8mb4')
        cursor.execute('CREATE USER IF NOT EXISTS %s@%s IDENTIFIED BY %s', ('mytools_image_generation', 'localhost', data['IMAGE_GENERATION_DB_PASSWORD']))
        cursor.execute('GRANT ALL PRIVILEGES ON mytools_image_generation.* TO %s@%s', ('mytools_image_generation', 'localhost'))
    for path in [ROOT / 'runtime/image-generation', ROOT / 'runtime/image-generation/inputs', ROOT / 'runtime/image-generation/outputs', Path('/opt/yuyutian/logs/mytools/image-generation-service')]:
        path.mkdir(parents=True, exist_ok=True)
        os.chown(path, pwd.getpwnam('mytools').pw_uid, pwd.getpwnam('mytools').pw_gid)
        path.chmod(0o750)
    clusters = 'media,reader,reader-probe-orchestration,download,download-orchestration,messaging,asset,drive,identity,storage,image-generation'
    write(RELEASE / 'executor.properties', 'executor.cluster-names=' + clusters + '\nexecutor.labels[image.generation]=enabled\n', mode=0o640)
    os.chown(RELEASE / 'executor.properties', 0, pwd.getpwnam('mytools').pw_gid)
    original = values(ROOT / 'runtime/adaptation-production-20260911/scheduler/application.properties')
    allowed = original.get('task.node-registration.allowed-cluster-names', clusters)
    if 'image-generation' not in allowed.split(','):
        allowed += ',image-generation'
    write(RELEASE / 'scheduler.properties', 'task.node-registration.allowed-cluster-names=' + allowed + '\ntask.node-registration.trusted-labels[image.generation]=enabled\n', mode=0o640)
    os.chown(RELEASE / 'scheduler.properties', 0, pwd.getpwnam('mytools').pw_gid)
    for service in SERVICES:
        write(STATE / (service + '.before.txt'), run(['systemctl', 'cat', 'mytools-' + service]))
    emit({'staged': True, 'release': NAME, 'currentSymlinkChanged': False})


def install_units():
    for service in SERVICES:
        extras = ''
        if service == 'task-scheduler-service':
            extras = ' --spring.config.additional-location=/opt/yuyutian/mytools/runtime/adaptation-production-20260911/scheduler/application.properties,' + str(RELEASE / 'scheduler.properties')
        elif service == 'task-executor-service':
            extras = ' --spring.config.additional-location=' + str(RELEASE / 'executor.properties')
        else:
            extras = ' --spring.config.additional-location=/opt/yuyutian/mytools/runtime/adaptation-production-20260911/gateway/application.properties'
        write(UNITS / ('mytools-' + service + '.service.d/zz-image-generation.conf'),
            '[Service]\nEnvironmentFile=' + str(ENV) + '\nExecStart=\nExecStart=/usr/bin/java -jar ' + str(RELEASE / 'apps' / (service + '.jar')) + extras + '\n', mode=0o644)
    write(UNITS / 'mytools-image-generation-service.service', '[Unit]\nDescription=MyTools image generation\nAfter=network-online.target mytools-task-scheduler-service.service\n[Service]\nUser=mytools\nGroup=mytools\nEnvironmentFile=' + str(ROOT / 'config/services.env') + '\nEnvironmentFile=' + str(ENV) + '\nWorkingDirectory=' + str(ROOT / 'runtime/image-generation') + '\nExecStart=/usr/bin/java -Xmx256m -jar ' + str(RELEASE / 'apps/image-generation-service.jar') + '\nRestart=on-failure\nUMask=0027\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=full\nProtectHome=true\nStandardOutput=append:/opt/yuyutian/logs/mytools/image-generation-service/service.log\nStandardError=inherit\n[Install]\nWantedBy=mytools-services.target\n', mode=0o644)
    write(UNITS / 'mytools-image-comfy.service', '[Unit]\nDescription=MyTools Krea 2 local inference\nAfter=network-online.target\n[Service]\nUser=mytools\nGroup=mytools\nWorkingDirectory=' + str(COMFY / 'ComfyUI') + '\nExecStart=' + str(COMFY / 'venv/bin/python') + ' main.py --listen 127.0.0.1 --port 8189 --lowvram --cpu-vae --reserve-vram 8 --disable-pinned-memory --disable-all-custom-nodes --disable-api-nodes --preview-method none\nMemoryMax=18G\nMemorySwapMax=512M\nCPUQuota=600%\nNoNewPrivileges=true\nPrivateTmp=true\nRestart=on-failure\nRestartSec=10\n[Install]\nWantedBy=mytools-services.target\n', mode=0o644)
    run(['systemctl', 'daemon-reload'])


def activate():
    assert RELEASE.exists() and ENV.exists()
    with task_connection() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
        assert cursor.fetchone()[0] == 0, 'wait_for_active_executions'
    code, nodes = request(23410, '/api/v1/execution-topology/nodes')
    assert code == 200
    node = next(n for n in nodes if n['name'] == 'executor-remote-1')
    code, _ = request(23410, '/api/v1/execution-topology/nodes/' + node['id'] + '/status', 'PATCH',
        {'status': 'DRAINING', 'reason': NAME, 'expectedInstanceId': node['instanceId'], 'expectedRunningTasks': 0})
    assert code == 200, 'drain_failed'
    write(STATE / 'node.json', json.dumps({'id': node['id']}))
    install_units()
    run(['systemctl', 'stop', 'mytools-task-executor-service'])
    run(['systemctl', 'restart', 'mytools-task-scheduler-service'])
    run(['systemctl', 'start', 'mytools-task-executor-service', 'mytools-image-comfy', 'mytools-image-generation-service'])
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            code, nodes = request(23410, '/api/v1/execution-topology/nodes')
            if code == 200:
                break
        except Exception:
            pass
        time.sleep(2)
    else:
        raise RuntimeError('scheduler_start_failed')
    node = next(n for n in nodes if n['name'] == 'executor-remote-1')
    code, _ = request(23410, '/api/v1/execution-topology/nodes/' + node['id'] + '/status', 'PATCH',
        {'status': 'ONLINE', 'reason': NAME, 'expectedInstanceId': node['instanceId']})
    assert code == 200
    health(23410)
    health(23340)
    code, _ = request(23410, '/internal/v1/image-generation-deployment', 'POST', {'enabled': True, 'auditId': NAME})
    assert code == 200, 'image_enable_failed'
    code, _ = request(23410, '/api/v1/execution-topology/clusters/a13bb181-55e4-41d5-ab17-68ed4626123f/nodes', 'POST',
        {'nodeId': node['id'], 'weight': 1, 'priority': 0, 'enabled': True})
    assert code == 204, 'cluster_assignment_failed'
    emit({'activated': True, 'gatewayExposed': False})


def enable():
    data = values(ENV)
    data['IMAGE_GENERATION_ROUTE_ENABLED'] = 'true'
    write(ENV, ''.join(k + '=' + v + '\n' for k, v in data.items()))
    run(['systemctl', 'restart', 'mytools-mytools-gateway'])
    health(23200)
    run(['systemctl', 'enable', 'mytools-image-comfy', 'mytools-image-generation-service'])
    emit({'enabled': True, 'release': NAME})


def rollback():
    # 保留数据库和已产出图片；先停止新任务分流，再回退受影响服务。
    code, _ = request(23410, '/internal/v1/image-generation-deployment', 'POST', {'enabled': False, 'auditId': NAME + '-rollback'})
    assert code == 200, 'disable_before_rollback_failed'
    with task_connection() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_execution WHERE status IN ('PENDING','RUNNING')")
        assert cursor.fetchone()[0] == 0, 'rollback_wait_for_tasks'
    data = values(ENV)
    data.update(IMAGE_GENERATION_ROUTE_ENABLED='false', IMAGE_GENERATION_LOCAL_VALIDATED='false', IMAGE_GPU_COORDINATION_VALIDATED='false')
    write(ENV, ''.join(k + '=' + v + '\n' for k, v in data.items()))
    # 新标签定义仍引用 1.4.0，因此保留包含历史版本的新包目录。
    for service in ['task-scheduler-service', 'mytools-gateway']:
        path = UNITS / ('mytools-' + service + '.service.d/zz-image-generation.conf')
        if path.exists():
            path.unlink()
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'restart', 'mytools-task-scheduler-service', 'mytools-task-executor-service', 'mytools-mytools-gateway'])
    run(['systemctl', 'stop', 'mytools-image-comfy'])
    health(23410)
    health(23200)
    emit({'rolledBack': True, 'imagesRetained': True, 'tagPackageRetained': True})


if __name__ == '__main__':
    os.umask(0o077)
    try:
        assert os.geteuid() == 0
        {'stage': stage, 'activate': activate, 'enable': enable, 'rollback': rollback}[sys.argv[1]]()
    except Exception as error:
        import traceback
        emit({'failed': True, 'type': type(error).__name__, 'line': traceback.extract_tb(error.__traceback__)[-1].lineno,
              'reason': str(error) if isinstance(error, (AssertionError, RuntimeError)) else 'private_diagnostic_suppressed'})
        sys.exit(1)
