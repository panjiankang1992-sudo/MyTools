#!/usr/bin/env python3
"""发布手动七天新闻；仅替换增量类，保留旧日报停用状态和其他业务产物。"""
import hashlib
import json
import os
from pathlib import Path
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
HOME_DIR = Path('/home/pankang')
SOURCE = HOME_DIR / 'workspace/news-personal/validation/weekly-20261122'
RELEASE = ROOT / 'releases/weekly-news-20261122-v2'
CONFIG = ROOT / 'config/weekly-news'
RUNTIME = ROOT / 'runtime/weekly-news'
UNITS = {'scheduler': 'mytools-task-scheduler-service', 'gateway': 'mytools-mytools-gateway'}
NEW_UNITS = ('mytools-news-weekly-service', 'mytools-news-weekly-executor')


def command(*args):
    """捕获命令输出，禁止回显环境或凭据。"""
    return subprocess.check_output(args, text=True, stderr=subprocess.DEVNULL).strip()


def private(path, value):
    """秘密配置与部署前状态仅允许 root 访问。"""
    path.write_text(value)
    path.chmod(0o600)


def values(path):
    """从正式环境文件读取必要键，不探测进程环境。"""
    result = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.removeprefix('export ').split('=', 1)
            parts = shlex.split(value)
            result[key.strip()] = parts[0] if parts else ''
    return result


def environment(name, entries):
    """写入独立的最小服务环境。"""
    private(CONFIG / (name + '.env'), ''.join(k+'='+json.dumps(str(v))+'\n' for k, v in entries.items()))


def request(path, body=None):
    """通过已有运维身份调用 Scheduler，不直接修改调度数据库。"""
    token = values(ROOT / 'config/services.env')['TASK_OPERATOR_INTERNAL_TOKEN']
    headers = {'X-Task-Service-Id': 'task-operator-service', 'X-Task-Internal-Token': token, 'Content-Type': 'application/json'}
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:23410'+path, data=data, headers=headers), timeout=20) as response:
        return json.load(response)


def health(port):
    """等待有界健康结果，失败由激活流程回退。"""
    end = time.monotonic()+100
    while time.monotonic() < end:
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=3) as response:
                if json.load(response)['status'] == 'UP':
                    return
        except Exception:
            time.sleep(2)
    raise RuntimeError('HEALTH_FAILED_'+str(port))


def start_args(unit):
    """保留当前服务参数，精确定位 JAR。"""
    return re.search(r'argv\[\]=(.*?) ; ignore_errors=', command('systemctl', 'show', unit, '-p', 'ExecStart')).group(1)


def properties(path, entries):
    """发布文件只使用占位符引用秘密。"""
    path.write_text(''.join(k+'='+str(v)+'\n' for k, v in entries.items()))


def no_daily():
    """检查原用户定时器仍关闭，不以本次发布重启日报。"""
    user = pwd.getpwnam('pankang')
    for field, expected in [('ActiveState', 'inactive'), ('UnitFileState', 'disabled')]:
        value = command('sudo', '-u', 'pankang', 'env', 'XDG_RUNTIME_DIR=/run/user/'+str(user.pw_uid),
                        'systemctl', '--user', 'show', 'mytools-personal-news.timer', '-p', field, '--value')
        assert value == expected, 'DAILY_TIMER_NOT_DISABLED'


def stage():
    """创建新发布和独立新闻账号，逐文件核验运行包与未修改的线上类。"""
    no_daily()
    assert not RELEASE.exists() and not CONFIG.exists(), 'RELEASE_ALREADY_EXISTS'
    runtime = SOURCE / 'runtime-1.6.0-rc3'
    for name, digest in json.loads((runtime/'runtime-manifest.json').read_text())['files'].items():
        assert hashlib.sha256((runtime/name).read_bytes()).hexdigest() == digest, 'RUNTIME_HASH_MISMATCH'
    for name, digest in json.loads((SOURCE/'delta-manifest-final.json').read_text()).items():
        assert hashlib.sha256((SOURCE/name).read_bytes()).hexdigest() == digest, 'DELTA_HASH_MISMATCH'
    index = json.loads((SOURCE/'packages-1.0.0-final/package-index.json').read_text())
    for package in index['packages']:
        for item in package['files']:
            path = SOURCE/'packages-1.0.0-final'/package['name']/package['version']/item['path']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256'], 'PACKAGE_HASH_MISMATCH'
    old = {kind: start_args(unit) for kind, unit in UNITS.items()}
    RELEASE.mkdir()
    CONFIG.mkdir(mode=0o700)
    shutil.copytree(runtime, RELEASE/'runtime')
    shutil.copytree(SOURCE/'packages-1.0.0-final', RELEASE/'task-packages')
    shutil.copytree(SOURCE/'task-executor-sdk', RELEASE/'sdk')
    audit = {'previousCommands': old, 'dropins': [], 'deltaEntries': {}, 'activated': False}
    for kind in UNITS:
        base = Path(re.search(r'-jar (\S+)', old[kind]).group(1))
        delta = SOURCE/('scheduler-delta-complete.zip' if kind == 'scheduler' else 'gateway-delta-complete.zip')
        target = RELEASE/(kind+'.jar')
        with zipfile.ZipFile(base) as original, zipfile.ZipFile(delta) as changes, zipfile.ZipFile(target, 'w') as output:
            if kind == 'scheduler':
                expected = json.loads((SOURCE/'baseline-class-hashes.json').read_text())['schedulerTaskInstance']
                assert hashlib.sha256(original.read('BOOT-INF/classes/com/yuyutian/mytools/task/scheduler/service/TaskInstanceService.class')).hexdigest() == expected, 'SCHEDULER_BASELINE_CHANGED'
            names = set(changes.namelist())
            for entry in original.infolist():
                output.writestr(entry, changes.read(entry.filename) if entry.filename in names else original.read(entry.filename))
            for name in sorted(names-set(original.namelist())):
                output.writestr(changes.getinfo(name), changes.read(name))
        with zipfile.ZipFile(base) as before, zipfile.ZipFile(target) as after:
            assert all(before.read(n) == after.read(n) for n in before.namelist() if n not in names), 'UNRELATED_CLASS_CHANGED'
            if kind == 'gateway':
                assert 'BOOT-INF/classes/com/yuyutian/mytools/gateway/config/NewsGatewayProperties.class' in after.namelist(), 'NEWS_GATEWAY_CONFIG_MISSING'
        audit['deltaEntries'][kind] = sorted(names)
    executor = Path(re.search(r'-jar (\S+)', start_args('mytools-task-executor-service')).group(1))
    shutil.copy2(executor, RELEASE/'executor.jar')
    tokens = {key: secrets.token_urlsafe(36) for key in ('NEWS_DB_PASSWORD', 'NEWS_INTERNAL_TOKEN', 'NEWS_ADMIN_TOKEN', 'NEWS_JOB_TOKEN', 'TASK_BUSINESS_NEWS_TOKEN')}
    import pymysql
    with pymysql.connect(read_default_file='/etc/mysql/debian.cnf', autocommit=True) as db, db.cursor() as cursor:
        cursor.execute('CREATE DATABASE IF NOT EXISTS mytools_news CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
        cursor.execute("CREATE USER 'mytools_news_weekly'@'127.0.0.1' IDENTIFIED BY %s", (tokens['NEWS_DB_PASSWORD'],))
        cursor.execute("GRANT ALL PRIVILEGES ON mytools_news.* TO 'mytools_news_weekly'@'127.0.0.1'")
    for name, owner in [('assets', 'mytools'), ('tasks', 'pankang'), ('work', 'pankang')]:
        path = RUNTIME/name
        path.mkdir(mode=0o700, parents=True)
        user = pwd.getpwnam(owner)
        os.chown(path, user.pw_uid, user.pw_gid)
    RUNTIME.chmod(0o755)
    environment('service', {**tokens, 'NEWS_DB_USER': 'mytools_news_weekly', 'NEWS_WEEKLY_ENABLED': 'true',
                'NEWS_WEEKLY_ASSET_DIRECTORY': RUNTIME/'assets', 'NEWS_COLLECTION_ENABLED': 'false', 'NEWS_PIPELINE_ENABLED': 'false',
                'NEWS_SCHEDULING_ENABLED': 'false', 'NEWS_DELIVERY_ENABLED': 'false', 'NEWS_REGION_NODE_ID': 'weekly-news-1'})
    environment('gateway', {'NEWS_INTERNAL_TOKEN': tokens['NEWS_INTERNAL_TOKEN']})
    environment('scheduler', {'TASK_BUSINESS_NEWS_TOKEN': tokens['TASK_BUSINESS_NEWS_TOKEN']})
    shared = values(ROOT/'config/services.env')
    environment('executor', {'TASK_EXECUTOR_INTERNAL_TOKEN': shared['TASK_EXECUTOR_INTERNAL_TOKEN'], 'NEWS_JOB_TOKEN': tokens['NEWS_JOB_TOKEN']})
    clusters = request('/api/v1/execution-topology/clusters')
    properties(RELEASE/'scheduler.properties', {
        'task.security.business-clients.news-digest-service': '${TASK_BUSINESS_NEWS_TOKEN}',
        'task.node-registration.allowed-cluster-names': ','.join(sorted({c['name'] for c in clusters}|{'news'})),
        'task.node-registration.trusted-labels[news.node]': 'weekly-news-1'})
    properties(RELEASE/'gateway.properties', {'gateway.news.route-enabled': 'true', 'gateway.news.url': 'http://127.0.0.1:23350', 'gateway.news.token': '${NEWS_INTERNAL_TOKEN}'})
    settings = {'server.address': '127.0.0.1', 'server.port': '23106', 'executor.node-name': 'weekly-news-1',
        'executor.scheduler-url': 'http://127.0.0.1:23410', 'executor.internal-token': '${TASK_EXECUTOR_INTERNAL_TOKEN}',
        'executor.work-root': RUNTIME/'tasks', 'executor.script-root': RELEASE/'task-packages',
        'executor.python-sdk-root': RELEASE/'sdk',
        'executor.python-executable': HOME_DIR/'workspace/news-personal/venvs/content-1.2.0/bin/python',
        'executor.cluster-names': 'news', 'executor.labels[news.node]': 'weekly-news-1', 'executor.max-concurrent-tasks': 1,
        'executor.require-package-index': 'true', 'executor.require-non-root': 'true',
        'executor.resource-limits.maximum-virtual-memory-bytes': 0, 'executor.resource-limits.maximum-file-bytes': 1073741824}
    worker = {'NEWS_SERVICE_URL': 'http://127.0.0.1:23350', 'NEWS_JOB_TOKEN': '${NEWS_JOB_TOKEN}',
        'NEWS_WEEKLY_RUNTIME': RELEASE/'runtime', 'NEWS_WEEKLY_CONFIG': HOME_DIR/'workspace/news-personal/personal.json',
        'NEWS_WEEKLY_WORK_ROOT': RUNTIME/'work', 'HOME': HOME_DIR}
    settings.update({'executor.script-environments.news_generate_weekly.'+key: value for key, value in worker.items()})
    properties(RELEASE/'executor.properties', settings)
    for path in [RELEASE, *RELEASE.rglob('*')]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    private(CONFIG/'deployment.json', json.dumps(audit, indent=2))
    print(json.dumps({'staged': True, 'release': str(RELEASE), 'deltaCounts': {k:len(v) for k,v in audit['deltaEntries'].items()}}))


def activate():
    """全部准备完成才切换服务，失败时自动回退启动项。"""
    no_daily()
    audit = json.loads((CONFIG/'deployment.json').read_text())
    assert not audit['dropins'], 'ALREADY_ACTIVATED'
    nodes = request('/api/v1/execution-topology/nodes')
    assert all(node.get('runningTasks', 0) == 0 for node in nodes), 'RUNNING_TASKS_PRESENT'
    # DSH 所属账号不穿越其他服务的私有目录，复制相同不可变包到其专属目录。
    executor_release = HOME_DIR/'workspace/news-personal/weekly-releases'/RELEASE.name
    executor_work = HOME_DIR/'workspace/news-personal/weekly-work'
    if not executor_release.exists():
        executor_release.mkdir(parents=True, mode=0o755)
        shutil.copy2(RELEASE/'executor.jar', executor_release/'executor.jar')
        for name in ('runtime', 'task-packages', 'sdk'):
            shutil.copytree(RELEASE/name, executor_release/name)
        settings = (RELEASE/'executor.properties').read_text().replace(str(RELEASE), str(executor_release))
        settings = settings.replace(str(RUNTIME/'tasks'), str(executor_work/'tasks')).replace(str(RUNTIME/'work'), str(executor_work/'work'))
        (executor_release/'executor.properties').write_text(settings)
        for path in [executor_release, *executor_release.rglob('*')]:
            path.chmod(0o755 if path.is_dir() else 0o644)
    user = pwd.getpwnam('pankang')
    for path in (executor_work, executor_work/'tasks', executor_work/'work'):
        path.mkdir(exist_ok=True, mode=0o700)
        os.chown(path, user.pw_uid, user.pw_gid)
    try:
        for kind, unit in UNITS.items():
            args = audit['previousCommands'][kind]
            assert start_args(unit) == args, 'CONCURRENT_DEPLOYMENT'
            old_jar = re.search(r'-jar (\S+)', args).group(1)
            args = args.replace('-jar '+old_jar, '-jar '+str(RELEASE/(kind+'.jar')), 1)
            extra = re.search(r'--spring.config.additional-location=(\S+)', args)
            args = args.replace(extra.group(0), extra.group(0)+','+str(RELEASE/(kind+'.properties')), 1) if extra else args+' --spring.config.additional-location='+str(RELEASE/(kind+'.properties'))
            folder = Path('/etc/systemd/system')/(unit+'.service.d')
            folder.mkdir(exist_ok=True)
            drop = folder/(max((p.name for p in folder.glob('*.conf')), default='zz')+'.weekly-news.conf')
            drop.write_text('[Service]\nEnvironmentFile='+str(CONFIG/(kind+'.env'))+'\nExecStart=\nExecStart='+args+'\n')
            audit['dropins'].append(str(drop))
            private(CONFIG/'deployment.json', json.dumps(audit, indent=2))
        for unit, owner, env, jar, extra, memory in [
                (NEW_UNITS[0], 'mytools', 'service', RELEASE/'runtime/news-digest-service.jar', '', '1G'),
                (NEW_UNITS[1], 'pankang', 'executor', executor_release/'executor.jar', ' --spring.config.additional-location='+str(executor_release/'executor.properties'), '4G')]:
            path = Path('/etc/systemd/system')/(unit+'.service')
            assert not path.exists() or any('/releases/'+name+'/' in path.read_text() for name in ('weekly-news-20261122-v1', RELEASE.name)), 'UNIT_ALREADY_EXISTS'
            path.write_text('[Unit]\nDescription=MyTools manual weekly news\nAfter=network.target\n[Service]\nUser='+owner+'\nGroup='+owner+
                '\nWorkingDirectory='+str(executor_release if owner == 'pankang' else RELEASE)+'\nEnvironmentFile='+str(CONFIG/(env+'.env'))+'\nExecStart=/usr/bin/java -Xmx512m -jar '+str(jar)+extra+
                '\nRestart=on-failure\nRestartSec=5\nUMask=0077\nMemoryMax='+memory+'\nTasksMax=512\n[Install]\nWantedBy=multi-user.target\n')
        command('systemctl', 'daemon-reload')
        command('systemctl', 'restart', UNITS['scheduler'])
        health(23410)
        command('systemctl', 'enable', '--now', NEW_UNITS[0])
        health(23350)
        request('/internal/v1/news-weekly-deployment', {'enabled': True, 'auditId': 'weekly_news_activate_'+secrets.token_hex(8)})
        command('systemctl', 'enable', '--now', NEW_UNITS[1])
        end = time.monotonic()+100
        while True:
            nodes = request('/api/v1/execution-topology/nodes')
            if any(n['name'] == 'weekly-news-1' and n['status'] == 'ONLINE' and 'news_generate_weekly:1.0.0' in n.get('capabilities', {}).get('scriptReleases', {}) for n in nodes):
                break
            assert time.monotonic() < end, 'EXECUTOR_NOT_REGISTERED'
            time.sleep(2)
        command('systemctl', 'restart', UNITS['gateway'])
        health(23200)
        no_daily()
        audit['activated'] = True
        private(CONFIG/'deployment.json', json.dumps(audit, indent=2))
        print(json.dumps({'activated': True, 'dailyTimer': 'disabled', 'executor': 'weekly-news-1'}))
    except Exception:
        rollback()
        raise


def revise():
    """保留首轮候选，生成修正的不可变发布；只修复本次失败迁移元数据。"""
    no_daily()
    audit = json.loads((CONFIG/'deployment.json').read_text())
    assert not audit['dropins'] and not audit['activated'] and not RELEASE.exists(), 'REVISION_NOT_ALLOWED'
    for kind, unit in UNITS.items():
        assert start_args(unit) == audit['previousCommands'][kind], 'CONCURRENT_DEPLOYMENT'
    old = ROOT/'releases/weekly-news-20261122-v1'
    shutil.copytree(old, RELEASE)
    delta = SOURCE/'scheduler-delta-v2.zip'
    expected = json.loads((SOURCE/'scheduler-delta-v2.sha256.json').read_text())['sha256']
    assert hashlib.sha256(delta.read_bytes()).hexdigest() == expected, 'REVISION_HASH_MISMATCH'
    base = Path(re.search(r'-jar (\S+)', audit['previousCommands']['scheduler']).group(1))
    with zipfile.ZipFile(base) as original, zipfile.ZipFile(delta) as changes, zipfile.ZipFile(RELEASE/'scheduler.jar', 'w') as output:
        names = set(changes.namelist())
        for entry in original.infolist():
            output.writestr(entry, changes.read(entry.filename) if entry.filename in names else original.read(entry.filename))
        for name in sorted(names-set(original.namelist())):
            output.writestr(changes.getinfo(name), changes.read(name))
    for name in ('scheduler.properties', 'gateway.properties', 'executor.properties'):
        p = RELEASE/name
        p.write_text(p.read_text().replace(str(old), str(RELEASE)))
    # 恢复工具使用原来已成功运行的完整迁移集，禁止重写其他迁移的校验和。
    repair = CONFIG/'repair'
    repair.mkdir(mode=0o700)
    with zipfile.ZipFile(base) as jar:
        for name in jar.namelist():
            if name.startswith(('BOOT-INF/lib/', 'BOOT-INF/classes/db/migration/')) and not name.endswith('/'):
                target = repair/name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(jar.read(name))
    shutil.copy2(SOURCE/'RepairWeeklyNewsMigration.class', repair/'RepairWeeklyNewsMigration.class')
    import pymysql
    with pymysql.connect(read_default_file='/etc/mysql/debian.cnf', database='mytools_task') as db, db.cursor() as cursor:
        cursor.execute('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')
        before = cursor.fetchall()
        assert [(v,s) for v,_,s in before if not s] == [('185', 0)], 'UNEXPECTED_FAILED_MIGRATIONS'
        cursor.execute('SELECT COUNT(*) FROM news_weekly_deployment_audit')
        assert cursor.fetchone()[0] == 0, 'AUDIT_ALREADY_USED'
        cursor.execute('SELECT COUNT(*) FROM task_definition WHERE name=%s', ('news_generate_weekly',))
        assert cursor.fetchone()[0] == 0, 'TASK_ALREADY_CREATED'
    env = values(ROOT/'config/services.env')
    # 部署身份只传递三个数据库变量；工具输出保存在私有审计目录。
    keys = {key:env[key] for key in ('TASK_DB_USERNAME','TASK_DB_PASSWORD')}
    keys['TASK_DB_URL'] = env.get('TASK_DB_URL', 'jdbc:mysql://127.0.0.1:3306/mytools_task?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai')
    result = subprocess.run(['/usr/bin/java', '-cp', str(repair)+':'+str(repair/'BOOT-INF/classes')+':'+str(repair/'BOOT-INF/lib/*'), 'RepairWeeklyNewsMigration'], env=keys, text=True, capture_output=True)
    private(repair/'repair.log', result.stdout+result.stderr)
    assert result.returncode == 0 and 'NEWS_WEEKLY_MIGRATION_REPAIRED' in result.stdout, 'FLYWAY_REPAIR_FAILED'
    with pymysql.connect(read_default_file='/etc/mysql/debian.cnf', database='mytools_task') as db, db.cursor() as cursor:
        cursor.execute('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')
        assert cursor.fetchall() == tuple(row for row in before if row[2]), 'UNRELATED_MIGRATION_CHANGED'
    audit['revisedRelease'] = str(RELEASE)
    private(CONFIG/'deployment.json', json.dumps(audit, indent=2))
    print(json.dumps({'revised': True, 'failedMigrationRepaired': '185', 'otherMigrationsUnchanged': True}))


def rollback():
    """关闭手动能力并还原原服务；不删除报告、不启用每日任务。"""
    audit = json.loads((CONFIG/'deployment.json').read_text())
    try:
        request('/internal/v1/news-weekly-deployment', {'enabled': False, 'auditId': 'weekly_news_rollback_'+secrets.token_hex(8)})
    except Exception:
        pass
    subprocess.run(['systemctl', 'disable', '--now', *NEW_UNITS], capture_output=True)
    for path in audit['dropins']:
        Path(path).unlink(missing_ok=True)
    command('systemctl', 'daemon-reload')
    for unit in UNITS.values():
        command('systemctl', 'restart', unit)
    audit['dropins'] = []
    audit['activated'] = False
    private(CONFIG/'deployment.json', json.dumps(audit, indent=2))
    no_daily()
    print(json.dumps({'rolledBack': True, 'dailyTimer': 'disabled'}))


if __name__ == '__main__':
    try:
        {'stage': stage, 'activate': activate, 'rollback': rollback, 'revise': revise}[sys.argv[1]]()
    except Exception as error:
        # 不输出 HTTP 请求、数据库连接串或服务启动参数。
        print(json.dumps({'failed': True, 'type': type(error).__name__, 'code': str(error) if isinstance(error, AssertionError) else 'DEPLOYMENT_FAILED'}))
        sys.exit(1)
