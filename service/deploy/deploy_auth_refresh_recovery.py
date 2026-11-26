#!/usr/bin/env python3
"""按当前运行包安装续期恢复增量，验证失败时回退两个服务。"""
import argparse
import concurrent.futures
import fcntl
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path('/opt/yuyutian/mytools')
PORTS = {'identity-service': 23290, 'mytools-gateway': 23200}


def command(*args):
    """捕获系统输出，避免打印可能含私密配置的启动参数。"""
    return subprocess.check_output(args, text=True).strip()


def running(module):
    """读取当前进程的启动参数，仅供内存内重建命令。"""
    pid = command('systemctl', 'show', 'mytools-' + module, '-p', 'MainPID', '--value')
    return [v for v in (Path('/proc') / pid / 'cmdline').read_text().split('\0') if v]


def api(port, path, body=None, token=None):
    """请求令牌仅保留在内存内，不打印响应正文。"""
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('http://127.0.0.1:' + str(port) + path, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(request, timeout=15) as response:
        raw = response.read()
        result = json.loads(raw) if raw else {}
        return result.get('data', result) if isinstance(result, dict) else result


def health(module):
    """等待服务恢复健康。"""
    for _ in range(40):
        try:
            if api(PORTS[module], '/actuator/health').get('status') == 'UP':
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('health_failed')


def quote(value):
    """引用 systemd 参数并禁用变量和说明符展开。"""
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%').replace('$', '$$') + '"'


def verify(account_file):
    """用独立验收账户验证丢失响应、重启、并发和撤销，不接触用户会话。"""
    account = json.loads(account_file.read_text())
    assert account['username'].startswith('pdf-agent-'), 'dedicated_account_required'
    prefix = '/api/app/v1/identity'
    pair = api(23200, prefix + '/login', {'username': account['username'], 'password': account['password'],
                                        'deviceId': 'refresh-recovery-acceptance'})
    token = pair['accessToken']
    checks = []

    def reject(body):
        try:
            api(23200, prefix + '/refresh', body)
        except urllib.error.HTTPError as error:
            assert error.code == 401, 'unexpected_rejection_status'
            return
        raise AssertionError('invalid_refresh_accepted')

    try:
        assert pair['expiresIn'] == 900 and 2591990 <= pair['refreshExpiresIn'] <= 2592000
        checks.append('thirty_day_session_fifteen_minute_access')
        intent = {'refreshToken': pair['refreshToken'], 'rotationId': str(uuid.uuid4())}
        first = api(23200, prefix + '/refresh', intent)
        # 模拟客户端没有收到第一次响应，原操作必须在服务重启后仍然可恢复。
        command('systemctl', 'restart', 'mytools-identity-service')
        health('identity-service')
        recovered = api(23200, prefix + '/refresh', intent)
        assert recovered['refreshToken'] == first['refreshToken']
        assert recovered['sessionId'] == pair['sessionId']
        token = recovered['accessToken']
        checks.append('lost_response_recovered_after_service_restart')
        reject({'refreshToken': intent['refreshToken']})
        reject({'refreshToken': intent['refreshToken'], 'rotationId': str(uuid.uuid4())})
        checks.append('old_token_without_original_operation_rejected')
        next_intent = {'refreshToken': recovered['refreshToken'], 'rotationId': str(uuid.uuid4())}
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda _: api(23200, prefix + '/refresh', next_intent), range(6)))
        assert len({v['refreshToken'] for v in results}) == 1
        token = results[0]['accessToken']
        reject(intent)
        checks.append('concurrent_retries_converge_and_previous_receipt_expires')
        assert str(api(23200, prefix + '/me', token=token)['userId']) == str(account['id'])
        api(23200, prefix + '/logout', {}, token)
        reject(next_intent)
        checks.append('logout_blocks_recovery')
        return checks
    finally:
        # 清理专用测试会话；重复注销是无害操作。
        try:
            api(23200, prefix + '/logout', {}, token)
        except urllib.error.HTTPError as error:
            if error.code != 401:
                raise


def deploy(args):
    """校验基线后增量发布，保留无关类、资源与既有启动参数。"""
    assert re.fullmatch(r'[a-zA-Z0-9._-]+', args.release_id)
    release = ROOT / 'releases' / args.release_id
    assert not release.exists(), 'immutable_release_exists'
    states = {}
    with zipfile.ZipFile(args.patch) as patch:
        manifest = json.loads(patch.read('manifest.json'))
        assert set(manifest) == set(PORTS), 'unexpected_modules'
        for module, specification in manifest.items():
            argv = running(module)
            index = argv.index('-jar') + 1
            original = Path(argv[index])
            assert hashlib.sha256(original.read_bytes()).hexdigest() == specification['baseSha256'], 'baseline_changed'
            members = {name: patch.read(module + '/' + name) for name in specification['members']}
            for name, data in members.items():
                assert name.startswith('BOOT-INF/classes/') and '..' not in name.split('/'), 'invalid_member'
                assert hashlib.sha256(data).hexdigest() == specification['members'][name], 'member_hash_mismatch'
            suffix = getattr(args, 'dropin_suffix', 'refresh-recovery')
            rank = getattr(args, 'dropin_rank', 64)
            assert re.fullmatch(r'[a-zA-Z0-9-]+', suffix) and 1 <= rank <= 128
            dropin = Path('/etc/systemd/system') / ('mytools-' + module + '.service.d') / ('z' * rank + '-' + suffix + '.conf')
            assert not dropin.exists(), 'dropin_exists'
            states[module] = (argv, index, original, members, dropin)
    print(json.dumps({'preflightPassed': True, 'apply': args.apply}), flush=True)
    if not args.apply:
        return
    release.mkdir(mode=0o755)
    changed = []
    report = {'release': str(release), 'artifacts': {}}
    try:
        for module, (argv, index, original, members, dropin) in states.items():
            target = release / (module + '.jar')
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(target, 'w') as output:
                existing = set(source.namelist())
                for entry in source.infolist():
                    output.writestr(entry, members.get(entry.filename, source.read(entry.filename)))
                for name in members.keys() - existing:
                    output.writestr(name, members[name])
            target.chmod(0o644)
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(target) as output:
                assert set(output.namelist()) == set(source.namelist()) | set(members)
                for name in source.namelist():
                    assert output.read(name) == members.get(name, source.read(name)), 'unrelated_member_changed'
            assert running(module) == argv, 'concurrent_deployment_detected'
            argv[index] = str(target)
            dropin.parent.mkdir(exist_ok=True)
            with dropin.open('x') as stream:
                stream.write('[Service]\nExecStart=\nExecStart=' + ' '.join(map(quote, argv)) + '\n')
            dropin.chmod(0o600)
            changed.append(module)
            command('systemctl', 'daemon-reload')
            command('systemctl', 'restart', 'mytools-' + module)
            health(module)
            assert running(module)[index] == str(target), 'release_overridden'
            report['artifacts'][module] = {'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                                           'changedMembers': len(members), 'dropIn': str(dropin)}
        report['checks'] = verify(args.account_file)
        report['passed'] = True
        (release / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)
    except Exception:
        for module in reversed(changed):
            states[module][4].unlink()
            command('systemctl', 'daemon-reload')
            command('systemctl', 'restart', 'mytools-' + module)
            health(module)
        print(json.dumps({'passed': False, 'rolledBack': changed}), flush=True)
        raise


def main():
    """持有共享发布锁后执行默认只读检查或显式发布。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--patch', type=Path, required=True)
    parser.add_argument('--release-id', required=True)
    parser.add_argument('--account-file', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    with (ROOT / 'runtime/deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        deploy(args)


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(json.dumps({'errorType': type(failure).__name__}), flush=True)
        raise SystemExit(1) from None
