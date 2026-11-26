#!/usr/bin/env python3
"""只发布 DSH 手机网页资源，保留现行认证配置并支持自动回退。"""
import datetime
import fcntl
import gzip
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

BASE = Path('/opt/yuyutian/mytools/config/dsh-web')
STATIC = Path('/var/www/mytools-dsh-mobile')
CONFIG = Path('/etc/nginx/sites-available/dsh-lan')
BASELINE = 'd6a483adcb28de874fc40ce8896bc1fded91ae66e5db76f99c6a8701a74bc6bb'
VERSION = '20261103a'
EXPECTED = {
    'mobile.css': '31625e0e389000f33fc7d290d8ef20734ac1f0eda0697433edeb943ffd56997d',
    'mobile.js': 'e7bbf15492c1300f6b67675e0af7f6bef059e13fa91a534f167515168f7367fe',
    'dsh-lan.conf': '6e549ab061483cf30d58e53bae6446bf557effaef9b3c6b80534cb4ac2b11861',
}


def digest(data):
    """仅输出公开工件的摘要。"""
    return hashlib.sha256(data).hexdigest()


def command(*args):
    """捕获输出，避免异常时打印生产配置。"""
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def normalize(text):
    """除静态资源位置和 HTML 注入外，所有有效配置必须与基线相同。"""
    text = re.sub(r'    location = /_mytools/mobile\.(?:css|js) \{[^}]+\}\n', '', text)
    lines = [line.strip() for line in text.splitlines()
             if line.strip() and not line.lstrip().startswith('#')]
    return '\n'.join('sub_filter MOBILE;' if line.startswith("sub_filter '</head>'") else line
                     for line in lines)


def request(host, port, path, extra=None):
    """只保留状态和非敏感响应元信息。"""
    connection = http.client.HTTPConnection(host, port, timeout=15)
    try:
        connection.request('GET', path, headers={'Host': 'dsh-app.yuyutian.top', **(extra or {})})
        response = connection.getresponse()
        body = response.read()
        encoding = response.getheader('Content-Encoding')
        decoded = gzip.decompress(body) if encoding == 'gzip' else body
        return {'status': response.status, 'bytes': len(body), 'sha256': digest(decoded),
                'contentType': response.getheader('Content-Type'), 'encoding': encoding,
                'cacheControl': response.getheader('Cache-Control')}
    finally:
        connection.close()


def write_atomic(path, data, mode=0o644):
    """同目录原子替换，避免 Nginx 读取半写入资源。"""
    temporary = path.with_name(path.name + '.mobile-next')
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode), 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def main():
    """锁定基线、保存备份，再发布并验证。"""
    stage = Path(sys.argv[1]).resolve()
    assert stage.parent == Path('/tmp') and stage.name.startswith('mytools-dsh-mobile-release-'), 'invalid_stage'
    with (BASE / '.mobile-deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        candidate = {name: (stage / name).read_bytes() for name in EXPECTED}
        assert all(digest(data) == EXPECTED[name] for name, data in candidate.items()), 'artifact_changed'
        original = CONFIG.read_bytes()
        assert digest(original) == BASELINE, 'production_baseline_changed'
        assert normalize(original.decode()) == normalize(candidate['dsh-lan.conf'].decode()), 'unrelated_config_change'
        assert Path('/etc/nginx/sites-enabled/dsh-lan').resolve() == CONFIG, 'site_not_enabled'
        assert not STATIC.exists(), 'static_directory_already_exists'
        targets = {'mobile.css': STATIC / 'mobile.css', 'mobile.js': STATIC / 'mobile.js', 'dsh-lan.conf': CONFIG}
        assert not any(path.is_symlink() for path in targets.values()), 'unexpected_symlink'
        before = {name: path.read_bytes() if path.exists() else None for name, path in targets.items()}
        before_modes = {name: path.stat().st_mode & 0o777 for name, path in targets.items() if path.exists()}
        units = ['nginx', 'dsh', 'mytools-mytools-gateway']
        pids = {unit: command('systemctl', 'show', unit, '-p', 'MainPID', '--value') for unit in units}
        assert all(value != '0' for value in pids.values()), 'service_not_running'
        command('nginx', '-t')
        timestamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        backup = BASE / ('mobile-backup-' + timestamp)
        backup.mkdir(mode=0o700)
        for name, path in targets.items():
            if path.exists():
                shutil.copy2(path, backup / name)
        audit = {'resourceVersion': VERSION, 'timestamp': timestamp, 'backup': str(backup),
                 'previousFiles': {name: digest(data) if data is not None else None for name, data in before.items()},
                 'sourceHashes': EXPECTED, 'checks': {}, 'deviceInstallation': False}
        try:
            # 公开资源单独存放，不扩大生产配置目录的读取权限。
            STATIC.mkdir(mode=0o755)
            # 先提供资源，再切换引用，已有连接不需重启服务。
            for name, path in targets.items():
                write_atomic(path, candidate[name])
            command('nginx', '-t')
            command('systemctl', 'reload', 'nginx')
            for name in ['mobile.css', 'mobile.js']:
                for compressed in [False, True]:
                    key = name + ('Gzip' if compressed else '')
                    # 重载成功时旧工作进程仍可能短暂处理请求，等待新配置开始服务。
                    for attempt in range(20):
                        result = request('127.0.0.1', 3081, '/_mytools/' + name + '?v=' + VERSION,
                                         {'Accept-Encoding': 'gzip'} if compressed else {})
                        if result['status'] == 200 and result['sha256'] == EXPECTED[name]:
                            break
                        time.sleep(0.25)
                    audit['checks'][key] = result
                    assert result['status'] == 200 and result['sha256'] == EXPECTED[name], 'asset_check_failed'
                    assert result['cacheControl'] == 'no-cache', 'asset_cache_policy_changed'
                    if compressed:
                        assert result['encoding'] == 'gzip', 'asset_compression_missing'
            probes = [('/', {}, 401), ('/?token=invalid', {}, 403),
                      ('/', {'Origin': 'https://invalid.example'}, 403),
                      ('/', {'Sec-Fetch-Site': 'cross-site'}, 403),
                      ('/_mytools/connect?ticket=invalid', {}, 401)]
            for index, (path, headers, expected) in enumerate(probes):
                result = request('127.0.0.1', 3081, path, headers)
                assert result['status'] == expected, 'auth_boundary_changed'
                audit['checks']['authProbe' + str(index)] = {'status': result['status']}
            assert request('127.0.0.1', 23200, '/actuator/health')['status'] == 200, 'gateway_unhealthy'
            for unit in units:
                assert command('systemctl', 'is-active', unit) == 'active', 'service_unhealthy'
                assert command('systemctl', 'show', unit, '-p', 'MainPID', '--value') == pids[unit], 'unexpected_restart'
            audit.update({'productionDeployment': True, 'nginxConfigTest': True,
                          'authConfigurationUnchanged': True, 'servicesNotRestarted': True, 'rollbackUsed': False})
        except BaseException as error:
            audit.update({'productionDeployment': False, 'rollbackUsed': True,
                          'failureType': type(error).__name__,
                          'failureReason': str(error) if isinstance(error, AssertionError) else 'redacted'})
            # 还原三项工件，确认旧配置有效后再次平滑重载。
            for name, path in targets.items():
                if before[name] is None:
                    path.unlink(missing_ok=True)
                else:
                    write_atomic(path, before[name], before_modes[name])
            STATIC.rmdir()
            command('nginx', '-t')
            command('systemctl', 'reload', 'nginx')
            (backup / 'deployment.json').write_text(json.dumps(audit, indent=2) + '\n')
            print(json.dumps(audit, indent=2))
            raise RuntimeError('mobile_release_rolled_back') from None
        (backup / 'deployment.json').write_text(json.dumps(audit, indent=2) + '\n')
        print(json.dumps(audit, indent=2))


if __name__ == '__main__':
    try:
        main()
    except BaseException as error:
        print(json.dumps({'error': type(error).__name__,
                          'reason': str(error) if isinstance(error, (AssertionError, RuntimeError)) else 'redacted'}))
        raise SystemExit(1) from None
