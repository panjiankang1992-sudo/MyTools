#!/usr/bin/env python3
"""增量发布三十天会话修复，使用专用验收账户验证并在失败时回退。"""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import zipfile

UNIT = 'mytools-identity-service'
ROOT = Path('/opt/yuyutian/mytools')
SECONDS = 30 * 24 * 60 * 60
ENTRIES = {
    'BOOT-INF/classes/application.yml',
    'BOOT-INF/classes/com/yuyutian/mytools/identity/service/IdentityService.class',
    'BOOT-INF/classes/com/yuyutian/mytools/identity/repository/IdentityRepository.class',
}


def command(*args):
    """读取系统状态，不打印服务启动参数或环境。"""
    return subprocess.check_output(args, text=True).strip()


def api(base, path, body=None, token=None):
    """调用认证接口，所有凭据和响应令牌仅留在进程内。"""
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + path, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(request, timeout=20) as response:
        data = response.read()
        result = json.loads(data) if data else {}
        return result.get('data', result) if isinstance(result, dict) else result


def health():
    """等待身份服务恢复健康。"""
    for _ in range(45):
        try:
            if api('http://127.0.0.1:23290', '/actuator/health').get('status') == 'UP':
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('identity_health_failed')


def quote_argument(value):
    """按 systemd 规则引用原有启动参数并禁用变量与说明符展开。"""
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%').replace('$', '$$') + '"'


def deploy(args):
    """验证增量基线，执行最小发布并验证真实网关认证协议。"""
    if not re.fullmatch(r'[a-zA-Z0-9._-]+', args.release_id):
        raise ValueError('invalid_release_id')
    pid = command('systemctl', 'show', UNIT, '-p', 'MainPID', '--value')
    process = Path('/proc') / pid
    argv = [value.decode() for value in (process / 'cmdline').read_bytes().split(b'\0') if value]
    jar_index = argv.index('-jar') + 1
    original = Path(argv[jar_index])
    with zipfile.ZipFile(args.patch) as patch:
        manifest = json.loads(patch.read('manifest.json'))
        assert set(patch.namelist()) == ENTRIES | {'manifest.json'}, 'unexpected_patch_members'
        assert hashlib.sha256(original.read_bytes()).hexdigest() == manifest['baseSha256'], 'baseline_changed'
        updates = {name: patch.read(name) for name in ENTRIES}
        assert all(hashlib.sha256(data).hexdigest() == manifest['members'][name]
                   for name, data in updates.items()), 'patch_hash_mismatch'
    with zipfile.ZipFile(original) as source:
        assert ENTRIES <= set(source.namelist()), 'baseline_members_missing'
    release = ROOT / 'releases' / args.release_id
    override = Path('/etc/systemd/system') / (UNIT + '.service.d') / ('z' * 48 + '-session-lifetime.conf')
    assert not release.exists() and not override.exists(), 'release_already_exists'
    account = json.loads(Path(args.account_file).read_text())
    assert account['username'].startswith('pdf-agent-'), 'dedicated_acceptance_account_required'
    print(json.dumps({'preflightPassed': True, 'changedMembers': len(updates), 'apply': args.apply}), flush=True)
    if not args.apply:
        return

    environment = dict(value.decode().split('=', 1) for value in (process / 'environ').read_bytes().split(b'\0')
                       if b'=' in value)
    internal_token = environment['IDENTITY_INTERNAL_TOKEN']
    gateway = 'http://127.0.0.1:23200'
    prefix = '/api/app/v1/identity'
    sessions = set()
    switched = False
    report = {'release': str(release), 'checks': []}

    def login():
        pair = api(gateway, prefix + '/login', {'username': account['username'], 'password': account['password'],
                                              'deviceId': 'identity-session-lifetime-acceptance'})
        sessions.add(pair['sessionId'])
        return pair

    def check_pair(pair):
        assert pair['expiresIn'] == 900, 'access_token_lifetime_changed'
        assert SECONDS - 5 <= pair['refreshExpiresIn'] <= SECONDS, 'session_lifetime_incorrect'
        view = api(gateway, prefix + '/me', token=pair['accessToken'])
        assert str(view['userId']) == str(account['id']), 'principal_mismatch'

    def require_rejected(refresh_token):
        try:
            api(gateway, prefix + '/refresh', {'refreshToken': refresh_token})
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                return
            raise
        raise RuntimeError('invalid_refresh_was_accepted')

    try:
        # 发布前创建旧七天会话，验证升级不需要再次输入密码。
        legacy = login()
        assert 7 * 86400 - 5 <= legacy['refreshExpiresIn'] <= 7 * 86400, 'unexpected_original_lifetime'
        release.mkdir(mode=0o750)
        shutil.chown(release, group='mytools')
        jar = release / 'identity-service.jar'
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(jar, 'w') as target:
            for entry in source.infolist():
                target.writestr(entry, updates.get(entry.filename, source.read(entry.filename)))
        jar.chmod(0o644)
        # 逐成员核对新包，确保其余类、依赖及迁移与当前部署一致。
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(jar) as target:
            assert source.namelist() == target.namelist(), 'release_members_changed'
            for name in source.namelist():
                assert target.read(name) == updates.get(name, source.read(name)), 'release_content_mismatch'
        argv[jar_index] = str(jar)
        argv = [value for value in argv if not value.startswith('--identity.refresh-seconds=')]
        argv.append('--identity.refresh-seconds=' + str(SECONDS))
        override.parent.mkdir(exist_ok=True)
        override.write_text('[Service]\nExecStart=\nExecStart=' + ' '.join(map(quote_argument, argv)) + '\n')
        override.chmod(0o600)
        switched = True
        command('systemctl', 'daemon-reload')
        command('systemctl', 'restart', UNIT)
        health()
        new_pid = command('systemctl', 'show', UNIT, '-p', 'MainPID', '--value')
        assert str(jar).encode() in (Path('/proc') / new_pid / 'cmdline').read_bytes().split(b'\0'), 'wrong_running_jar'
        report['checks'].append('running_release_and_health')
        upgraded = api(gateway, prefix + '/refresh', {'refreshToken': legacy['refreshToken']})
        check_pair(upgraded)
        require_rejected(legacy['refreshToken'])
        report['checks'].append('existing_seven_day_session_renews_to_thirty_days')
        current = login()
        check_pair(current)
        report['checks'].append('new_login_thirty_days_access_fifteen_minutes')
        refreshed = api(gateway, prefix + '/refresh', {'refreshToken': current['refreshToken']})
        check_pair(refreshed)
        require_rejected(current['refreshToken'])
        report['checks'].append('refresh_rotation_and_stale_token_rejection')
        records = api('http://127.0.0.1:23290', '/internal/v1/identity/users/' + str(account['id']) + '/sessions',
                      token=internal_token)
        from datetime import datetime, timezone
        for pair in (upgraded, refreshed):
            row = next(value for value in records if value['id'] == pair['sessionId'])
            deadline = datetime.fromisoformat(row['refreshExpiresAt'].replace('Z', '+00:00'))
            assert (deadline - datetime.now(timezone.utc)).total_seconds() > SECONDS - 60, 'deadline_not_persisted'
        report['checks'].append('thirty_day_deadline_persisted_in_database')
        api(gateway, prefix + '/logout', {}, refreshed['accessToken'])
        require_rejected(refreshed['refreshToken'])
        report['checks'].append('logout_still_revokes_session')
        for session_id in list(sessions):
            api('http://127.0.0.1:23290', '/internal/v1/identity/sessions/' + session_id + '/revoke', {}, internal_token)
            sessions.remove(session_id)
        report['checks'].append('acceptance_sessions_revoked')
        report['passed'] = True
        report['jarSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        (release / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)
    except Exception:
        if switched:
            override.unlink()
            command('systemctl', 'daemon-reload')
            command('systemctl', 'restart', UNIT)
            health()
        print(json.dumps({'passed': False, 'rolledBack': switched, 'checksPassed': report['checks']}), flush=True)
        raise
    finally:
        for session_id in sessions:
            api('http://127.0.0.1:23290', '/internal/v1/identity/sessions/' + session_id + '/revoke', {}, internal_token)


def main():
    """默认仅执行预检查，显式启用后发布。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--patch', required=True)
    parser.add_argument('--release-id', required=True)
    parser.add_argument('--account-file', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    with (ROOT / 'config' / '.identity-session-lifetime.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        deploy(args)


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        # 异常正文可能携带服务配置或响应，只输出类型供定位阶段。
        print(json.dumps({'error': type(failure).__name__}), flush=True)
        raise SystemExit(1) from None
