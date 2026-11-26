#!/usr/bin/env python3
"""仅替换 DSH 连接相关类，核对线上基线并在健康检查失败时回退。"""
import fcntl
import grp
import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import time
import zipfile

ROOT = Path('/opt/yuyutian/mytools')
UNIT = 'mytools-mytools-gateway.service'
RELEASE = ROOT / 'releases/dsh-connection-20261102-v2'
PATCH = Path('/tmp/mytools-dsh-connection-20261102/gateway-dsh-delta.zip')
PREFIX = 'BOOT-INF/classes/com/yuyutian/mytools/gateway/service/'
ALLOWED = {PREFIX + 'DshWebSessionService.class', PREFIX + 'DshWebSessionService$Grant.class'}
ALLOWED.add('BOOT-INF/classes/com/yuyutian/mytools/gateway/controller/DshWebController.class')


def command(*args):
    """命令结果仅由调用者选择输出，不回显启动参数。"""
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def sha(path):
    """计算工件摘要。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def running():
    """只读取当前网关启动参数，不读取生产环境变量。"""
    pid = command('systemctl', 'show', UNIT, '-p', 'MainPID', '--value')
    assert pid != '0', 'gateway_not_running'
    argv = [v.decode() for v in (Path('/proc') / pid / 'cmdline').read_bytes().split(b'\0') if v]
    return pid, argv, Path(argv[argv.index('-jar') + 1])


def status(port, method, path):
    """仅返回状态码，响应体和凭据不进入日志。"""
    client = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
    try:
        client.request(method, path, headers={'Host': 'dsh-app.yuyutian.top'})
        response = client.getresponse()
        response.read()
        return response.status
    finally:
        client.close()


def healthy():
    """短时等待网关恢复健康。"""
    for _ in range(45):
        try:
            if status(23200, 'GET', '/actuator/health') == 200:
                return
        except OSError:
            pass
        time.sleep(1)
    raise RuntimeError('gateway_health_failed')


def main():
    """校验最小增量并保留原工件及启动配置作为回退基线。"""
    with (ROOT / 'config/.dsh-connection-deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        pid, argv, original = running()
        with zipfile.ZipFile(PATCH) as patch:
            manifest = json.loads(patch.read('manifest.json'))
            assert sha(original) == manifest['baseSha256'], 'baseline_changed'
            assert set(manifest['members']) == ALLOWED, 'unrelated_patch_member'
            assert set(patch.namelist()) == ALLOWED | {'manifest.json'}, 'unexpected_patch_entries'
            updates = {name: patch.read(name) for name in ALLOWED}
            assert all(hashlib.sha256(value).hexdigest() == manifest['members'][name]
                       for name, value in updates.items()), 'patch_digest_mismatch'
        assert not RELEASE.exists(), 'release_already_exists'
        RELEASE.mkdir(mode=0o750)
        gid = grp.getgrnam('mytools').gr_gid
        os.chown(RELEASE, 0, gid)
        target = RELEASE / 'mytools-gateway.jar'
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(target, 'w') as after:
            assert ALLOWED.issubset(before.namelist()), 'baseline_class_missing'
            for entry in before.infolist():
                after.writestr(entry, updates.get(entry.filename, before.read(entry.filename)))
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(target) as after:
            assert before.namelist() == after.namelist(), 'jar_member_set_changed'
            assert all(after.read(n) == updates.get(n, before.read(n)) for n in before.namelist()), 'jar_verification_failed'
        os.chown(target, 0, gid)
        target.chmod(0o640)
        folder = Path('/etc/systemd/system') / (UNIT + '.d')
        drop = folder / (max(p.name for p in folder.glob('*.conf')) + '.dsh-connection.conf')
        assert not drop.exists(), 'dropin_already_exists'
        audit = {'previousJar': str(original), 'previousSha256': sha(original),
                 'jar': str(target), 'sha256': sha(target), 'dropin': str(drop),
                 'patchedClasses': sorted(ALLOWED), 'unrelatedMembersUnchanged': True}
        try:
            assert running()[0] == pid and sha(original) == manifest['baseSha256'], 'concurrent_release'
            argv[argv.index('-jar') + 1] = str(target)
            def quote(value):
                return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%').replace('$', '$$') + '"'
            with os.fdopen(os.open(drop, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
                output.write('[Service]\nExecStart=\nExecStart=' + ' '.join(map(quote, argv)) + '\n')
            command('systemctl', 'daemon-reload')
            command('systemctl', 'restart', UNIT)
            healthy()
            assert running()[2] == target, 'wrong_running_jar'
            assert status(23200, 'POST', '/api/app/v1/dsh/web-ticket') == 401, 'anonymous_ticket_not_denied'
            assert status(23200, 'GET', '/internal/dsh-web/connect?ticket=invalid') == 401, 'invalid_ticket_not_denied'
            assert status(3081, 'GET', '/') == 401, 'anonymous_browser_not_denied'
            audit['healthy'] = True
            (RELEASE / 'deployment.json').write_text(json.dumps(audit, indent=2) + '\n')
            print(json.dumps(audit), flush=True)
        except BaseException:
            drop.unlink(missing_ok=True)
            command('systemctl', 'daemon-reload')
            command('systemctl', 'restart', UNIT)
            healthy()
            print(json.dumps({'rolledBack': True}), flush=True)
            raise


if __name__ == '__main__':
    try:
        main()
    except BaseException as error:
        print(json.dumps({'error': type(error).__name__,
                          'reason': str(error) if isinstance(error, (AssertionError, RuntimeError)) else 'redacted'}))
        raise SystemExit(1) from None
