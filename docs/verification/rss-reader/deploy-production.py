#!/usr/bin/env python3
"""仅发布 RSS 和网关增量，保留旧服务并在验收失败时回退。"""
import argparse
import fcntl
import grp
import hashlib
import json
import os
from pathlib import Path
import pwd
import secrets
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path('/opt/yuyutian/mytools')
RELEASE = ROOT / 'releases/rss-reader-20261101-v1'
CONFIG = ROOT / 'config/rss'
GATEWAY = 'mytools-mytools-gateway.service'
RSS = 'mytools-rss-service.service'
UNIT = Path('/etc/systemd/system') / RSS
BASE = 'http://127.0.0.1:23200'
PREFIX = '/api/app/v1/rss'


def command(*args):
    """执行受控命令，不回显参数或环境。"""
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def sha(path):
    """校验发布产物。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def running():
    """只解析网关当前启动参数，禁止输出进程环境。"""
    pid = command('systemctl', 'show', GATEWAY, '-p', 'MainPID', '--value')
    assert pid != '0', 'gateway_not_running'
    args = [v.decode() for v in (Path('/proc') / pid / 'cmdline').read_bytes().split(b'\0') if v]
    return pid, args, Path(args[args.index('-jar') + 1])


def write_private(path, text):
    """原子写入仅 root 可读的专用配置。"""
    temporary = path.with_suffix(path.suffix + '.new')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(text)
    temporary.replace(path)


def api(base, method, path, body=None, token=None):
    """令牌只保留在内存，HTTP 错误只返回状态。"""
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + path, method=method, headers=headers,
                                    data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            data = json.loads(raw) if raw else {}
            return response.status, data.get('data', data) if isinstance(data, dict) else data
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            data = json.loads(raw)
        except ValueError:
            data = {}
        return error.code, {'code': data.get('code', '')}


def health(port):
    """限时等待健康，失败由调用者回退。"""
    for _ in range(60):
        try:
            status, data = api('http://127.0.0.1:' + str(port), 'GET', '/actuator/health')
            if status == 200 and data.get('status') == 'UP':
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('health_failed_' + str(port))


def db_admin():
    """复用主机已有管理连接，不读取或输出其凭据。"""
    import pymysql
    return pymysql.connect(read_default_file='/etc/mysql/debian.cnf', connect_timeout=5, autocommit=True)


def preflight(source):
    """发布前核对基线、文件清单和新增资源边界。"""
    _, _, original = running()
    manifest = json.loads((source / 'manifest.json').read_text())
    for name, digest in manifest.items():
        assert name in {'rss-service.jar', 'gateway-rss-delta.zip'}, 'unknown_artifact'
        assert sha(source / name) == digest, 'artifact_hash_mismatch'
    with zipfile.ZipFile(source / 'gateway-rss-delta.zip') as patch:
        delta = json.loads(patch.read('manifest.json'))
        assert sha(original) == delta['baseSha256'], 'gateway_baseline_changed'
        assert set(patch.namelist()) == set(delta['members']) | {'manifest.json'}, 'patch_manifest_mismatch'
        assert len(delta['members']) == 11, 'patch_size_mismatch'
        for name, digest in delta['members'].items():
            assert name.startswith('BOOT-INF/classes/com/yuyutian/mytools/gateway/'), 'unsafe_patch_path'
            assert Path(name).name.startswith(('Rss', 'GatewayRequestFilter', 'ErrorCode')), 'unrelated_patch'
            assert hashlib.sha256(patch.read(name)).hexdigest() == digest, 'patch_member_mismatch'
    assert not RELEASE.exists() and not CONFIG.exists() and not UNIT.exists(), 'release_already_present'
    with db_admin() as db, db.cursor() as cursor:
        cursor.execute('SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s', ('mytools_rss',))
        assert cursor.fetchone()[0] == 0, 'rss_schema_already_present'
        cursor.execute('SELECT COUNT(*) FROM mysql.user WHERE User=%s', ('mytools_rss',))
        assert cursor.fetchone()[0] == 0, 'rss_user_already_present'
    print(json.dumps({'preflightPassed': True, 'changedGatewayClasses': 11}), flush=True)
    return manifest, delta


def verify():
    """用两个专用账号验证生产网关的真实身份、采集和隔离，随后清理测试订阅。"""
    report = {'release': RELEASE.name, 'checks': [], 'passed': False}
    sessions = []
    subscriptions = []
    group = None
    starred = None
    def check(condition, name):
        assert condition, name
        report['checks'].append(name)
        print(json.dumps({'checkPassed': name}), flush=True)
    def call(method, path, body=None, index=0, prefix=PREFIX):
        status, data = api(BASE, method, prefix + path, body, sessions[index]['accessToken'])
        assert 200 <= status < 300, 'rss_api_' + str(status) + '_' + str(data.get('code', '') if isinstance(data, dict) else '')
        return data
    try:
        for i in (0, 1):
            account = json.loads((ROOT / 'config/pdf-tools' / ('acceptance-account-' + str(i) + '.json')).read_text())
            assert account['username'].startswith('pdf-agent-'), 'test_account_required'
            status, pair = api(BASE, 'POST', '/api/app/v1/identity/login', {
                'username': account['username'], 'password': account['password'], 'deviceId': 'rss-production-acceptance'})
            assert status == 200 and pair.get('accessToken'), 'identity_login_failed'
            sessions.append(pair)
        check(True, 'real_identity_two_accounts')
        for base, path in [(BASE, PREFIX + '/capabilities'), ('http://127.0.0.1:23360', '/internal/v1/rss/counts')]:
            status, _ = api(base, 'GET', path)
            check(status == 401, 'unauthenticated_rejected_' + ('gateway' if base == BASE else 'internal'))
        capabilities = call('GET', '/capabilities')
        check(capabilities['pollingEnabled'] and capabilities['opml'], 'polling_and_features_enabled')
        check(call('GET', '/capabilities', prefix='/api/v1/rss') == capabilities, 'both_gateway_prefixes')
        other_before = call('GET', '/counts', index=1)
        group = call('POST', '/groups', {'name': 'RSS deployment verification', 'sortOrder': 0})['id']
        totals = []
        for feed in ['https://www.ruanyifeng.com/blog/atom.xml', 'https://sspai.com/feed']:
            preview = call('POST', '/discover', {'url': feed})
            check(bool(preview.get('preview', {}).get('entries')), 'discover_' + ('atom' if 'atom.xml' in feed else 'rss'))
            sub = call('POST', '/subscriptions', {'url': feed, 'title': '', 'groupId': group, 'initialReadAll': False})
            subscriptions.append(sub['id'])
            deadline = time.monotonic() + 100
            while time.monotonic() < deadline:
                current = call('GET', '/subscriptions/' + sub['id'])
                if current['lastSuccessAt']:
                    break
                time.sleep(2)
            assert current['lastSuccessAt'], 'initial_poll_failed'
            page = call('GET', '/entries?subscriptionId=' + sub['id'] + '&limit=5')
            check(bool(page['items']), 'background_poll_' + ('atom' if 'atom.xml' in feed else 'rss'))
            totals.append(len(page['items']))
        entry = call('GET', '/entries?subscriptionId=' + subscriptions[0] + '&limit=1')['items'][0]
        detail = call('GET', '/entries/' + entry['id'])
        check(bool(detail['contentHtml'] or detail['summary']), 'article_body_available')
        changed = call('PUT', '/entries/' + entry['id'] + '/state', {'expectedRevision': detail['revision'], 'read': True, 'starred': True})
        starred = changed
        check(changed['read'] and changed['starred'], 'read_and_star_persisted')
        status, _ = api(BASE, 'GET', PREFIX + '/entries/' + entry['id'], token=sessions[1]['accessToken'])
        check(status == 404 and call('GET', '/counts', index=1) == other_before, 'owner_isolation')
        xml = call('GET', '/opml/export')['xml']
        preview = call('POST', '/opml/preview', {'xml': xml})
        check(len([v for v in preview if v['status'] == 'DUPLICATE']) >= 2, 'opml_export_and_duplicate_preview')
        check(command('systemctl', 'is-enabled', RSS) == 'enabled', 'service_enabled_on_boot')
        health(23200)
        health(23360)
        check(True, 'both_services_healthy')
        report['passed'] = True
    finally:
        # 只清理本次专用验收账号创建的状态，不触及真实用户数据。
        if sessions:
            if starred:
                call('PUT', '/entries/' + starred['id'] + '/state', {'expectedRevision': starred['revision'], 'read': True, 'starred': False})
            for subscription in subscriptions:
                call('DELETE', '/subscriptions/' + subscription)
            if group:
                call('DELETE', '/groups/' + group)
        for pair in sessions:
            status, _ = api(BASE, 'POST', '/api/app/v1/identity/logout', {}, pair['accessToken'])
            assert status in (200, 204), 'test_logout_failed'
        report['testSubscriptionsRemoved'] = len(subscriptions)
        report['testSessionsRevoked'] = len(sessions)
        (RELEASE / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def rollback():
    """停用 RSS 并恢复旧网关启动配置，保留数据库和版本证据。"""
    audit = json.loads((CONFIG / 'deployment.json').read_text())
    drop = Path(audit['gatewayDropin'])
    drop.unlink(missing_ok=True)
    subprocess.run(['systemctl', 'disable', '--now', RSS], check=False, capture_output=True)
    command('systemctl', 'daemon-reload')
    command('systemctl', 'restart', GATEWAY)
    health(23200)
    assert sha(running()[2]) == audit['previousGatewaySha256'], 'rollback_binary_mismatch'
    print(json.dumps({'rolledBack': True, 'rssDataRetained': True}), flush=True)


def deploy(source):
    """创建独立配置和服务，核对健康后切换网关并完成生产业务验收。"""
    manifest, delta = preflight(source)
    pid, argv, original = running()
    CONFIG.mkdir(mode=0o700)
    RELEASE.mkdir(mode=0o750)
    apps = RELEASE / 'apps'
    apps.mkdir(mode=0o750)
    shutil.copy2(source / 'rss-service.jar', apps / 'rss-service.jar')
    shutil.copy2(original, apps / 'previous-gateway.jar')
    gateway_jar = apps / 'mytools-gateway.jar'
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(source / 'gateway-rss-delta.zip') as patch, zipfile.ZipFile(gateway_jar, 'w') as target:
        updates = {n: patch.read(n) for n in delta['members']}
        for entry in before.infolist():
            target.writestr(entry, updates.get(entry.filename, before.read(entry.filename)))
        for name in sorted(set(updates) - set(before.namelist())):
            target.writestr(name, updates[name])
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(gateway_jar) as after:
        assert all(before.read(n) == after.read(n) for n in before.namelist() if n not in updates), 'unrelated_gateway_content_changed'
    # 使用专用额外配置，不替换线上 application.yml。
    settings = RELEASE / 'gateway-rss.properties'
    settings.write_text('gateway.rss.route-enabled=${GATEWAY_RSS_ROUTE_ENABLED:false}\ngateway.rss.url=http://127.0.0.1:23360\ngateway.rss.token=${RSS_INTERNAL_TOKEN}\n')
    token, password = secrets.token_urlsafe(48), secrets.token_urlsafe(36)
    write_private(CONFIG / 'service.env', '\n'.join(['RSS_DB_URL=jdbc:mysql://127.0.0.1:3306/mytools_rss?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC', 'RSS_DB_USERNAME=mytools_rss', 'RSS_DB_PASSWORD=' + password, 'RSS_INTERNAL_TOKEN=' + token, 'RSS_POLLING_ENABLED=true', 'RSS_BIND_ADDRESS=127.0.0.1', 'RSS_PORT=23360', '']) )
    write_private(CONFIG / 'gateway.env', 'RSS_INTERNAL_TOKEN=' + token + '\nGATEWAY_RSS_ROUTE_ENABLED=true\n')
    with db_admin() as db, db.cursor() as cursor:
        cursor.execute('CREATE DATABASE mytools_rss CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
        cursor.execute("CREATE USER 'mytools_rss'@'127.0.0.1' IDENTIFIED BY %s", (password,))
        cursor.execute("GRANT ALL PRIVILEGES ON mytools_rss.* TO 'mytools_rss'@'127.0.0.1'")
    user = pwd.getpwnam('mytools')
    group = grp.getgrnam('mytools').gr_gid
    for path in [RELEASE, *RELEASE.rglob('*')]:
        os.chown(path, 0, group)
        path.chmod(0o750 if path.is_dir() else 0o640)
    log = Path('/opt/yuyutian/logs/mytools/rss-service')
    log.mkdir(mode=0o750, exist_ok=True)
    os.chown(log, user.pw_uid, group)
    UNIT.write_text('[Unit]\nDescription=MyTools RSS Reader\nAfter=network-online.target mysql.service\nWants=network-online.target\n[Service]\nType=simple\nUser=mytools\nGroup=mytools\nWorkingDirectory=' + str(RELEASE) + '\nEnvironmentFile=' + str(CONFIG / 'service.env') + '\nExecStart=/usr/bin/java -Xms64m -Xmx384m -jar ' + str(apps / 'rss-service.jar') + '\nRestart=on-failure\nRestartSec=5s\nTimeoutStopSec=45s\nUMask=0027\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=full\nProtectHome=true\nStandardOutput=append:' + str(log / 'service.log') + '\nStandardError=inherit\n[Install]\nWantedBy=multi-user.target\n')
    UNIT.chmod(0o644)
    rotation = Path('/etc/logrotate.d/mytools-rss-service')
    assert not rotation.exists(), 'logrotate_exists'
    rotation.write_text(str(log / 'service.log') + ' {\n    su root root\n    daily\n    rotate 9\n    maxage 10\n    maxsize 10M\n    compress\n    delaycompress\n    missingok\n    notifempty\n    copytruncate\n}\n')
    rotation.chmod(0o644)
    folder = Path('/etc/systemd/system') / (GATEWAY + '.d')
    drop = folder / (max(p.name for p in folder.glob('*.conf')) + '.rss.conf')
    assert not drop.exists(), 'dropin_exists'
    audit = {'release': str(RELEASE), 'previousGatewaySha256': sha(original), 'gatewaySha256': sha(gateway_jar), 'rssSha256': manifest['rss-service.jar'], 'gatewayDropin': str(drop), 'gatewayChangedClasses': sorted(updates)}
    write_private(CONFIG / 'deployment.json', json.dumps(audit, indent=2) + '\n')
    try:
        command('systemctl', 'daemon-reload')
        command('systemctl', 'start', RSS)
        health(23360)
        print(json.dumps({'rssServiceHealthy': True, 'schemaMigrated': True}), flush=True)
        assert running()[0] == pid and sha(original) == delta['baseSha256'], 'concurrent_gateway_release'
        argv[argv.index('-jar') + 1] = str(gateway_jar)
        for i, value in enumerate(argv):
            if value.startswith('--spring.config.additional-location='):
                argv[i] += ',' + str(settings)
                break
        else:
            argv.append('--spring.config.additional-location=' + str(settings))
        def quote(value):
            return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%').replace('$', '$$') + '"'
        write_private(drop, '[Service]\nEnvironmentFile=' + str(CONFIG / 'gateway.env') + '\nExecStart=\nExecStart=' + ' '.join(map(quote, argv)) + '\n')
        command('systemctl', 'daemon-reload')
        command('systemctl', 'restart', GATEWAY)
        health(23200)
        assert running()[2] == gateway_jar, 'wrong_running_gateway'
        command('systemctl', 'enable', RSS)
        report = verify()
        audit['passed'] = report['passed']
        (RELEASE / 'deployment.json').write_text(json.dumps(audit, indent=2) + '\n')
        print(json.dumps({'deployed': True, 'release': RELEASE.name, 'checks': len(report['checks'])}), flush=True)
    except BaseException:
        rollback()
        raise


def main():
    """默认只读核对，显式 apply 才切换生产。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['preflight', 'apply', 'verify', 'rollback'])
    parser.add_argument('--source', type=Path, default=Path('/tmp/mytools-rss-production-20261101'))
    args = parser.parse_args()
    with (ROOT / 'config/.rss-deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == 'preflight':
            preflight(args.source)
        elif args.action == 'apply':
            deploy(args.source)
        elif args.action == 'verify':
            print(json.dumps(verify()))
        else:
            rollback()


if __name__ == '__main__':
    try:
        main()
    except BaseException as error:
        # 不输出可能包含认证材料、连接字符串或响应正文的异常文本。
        print(json.dumps({'error': type(error).__name__, 'stageFailure': str(error) if isinstance(error, (AssertionError, RuntimeError)) else 'redacted'}), flush=True)
        raise SystemExit(1) from None
