#!/usr/bin/env python3
"""旁路验证并启用周报 RSS 消费器，只重启新闻服务，保留旧发布用于回退。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import time

BASE = Path('/home/pankang/workspace/news-personal')
SOURCE = BASE / 'validation/weekly-rss-20261122'
RELEASE = BASE / 'weekly-releases/weekly-rss-1.0.0'
SYSTEM_RELEASE = Path('/opt/yuyutian/mytools/releases/weekly-rss-20261122-v1')
PRIVATE = Path('/opt/yuyutian/mytools/config/weekly-news')
NEWS = 'mytools-news-weekly-service'
RSS = 'mytools-news-weekly-rss'
PYTHON = BASE / 'venvs/content-1.2.0/bin/python'


def command(*args):
    """捕获管理命令输出，失败不把完整命令或环境写入日志。"""
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('COMMAND_FAILED')
    return result.stdout.strip()


def private(path, value, owner=False):
    """凭据与审计只保存在部署机，应用配置明确归属运行账号。"""
    path.write_text(value)
    path.chmod(0o600)
    if owner:
        user = pwd.getpwnam('pankang')
        os.chown(path, user.pw_uid, user.pw_gid)


def deployment():
    """复用既有只读健康和每日任务停用检查。"""
    spec = importlib.util.spec_from_file_location('weekly_deployment', BASE / 'validation/weekly-20261122/deploy_weekly_news.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def stage():
    """验证不可变包，创建隔离 RSS 站点配置，不切换生产进程。"""
    deployment().no_daily()
    manifest = json.loads((SOURCE / 'manifest.json').read_text())
    for name, digest in manifest['files'].items():
        path = SOURCE / name
        assert path.resolve().is_relative_to(SOURCE) and not path.is_symlink(), 'INVALID_PACKAGE_PATH'
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'PACKAGE_HASH_MISMATCH'
    assert not RELEASE.exists() and not SYSTEM_RELEASE.exists(), 'RELEASE_ALREADY_EXISTS'
    shutil.copytree(SOURCE / 'scripts', RELEASE / 'scripts')
    SYSTEM_RELEASE.mkdir(mode=0o755)
    shutil.copy2(SOURCE / 'news-digest-service.jar', SYSTEM_RELEASE / 'news-digest-service.jar')
    for root in (RELEASE, SYSTEM_RELEASE):
        for path in [root, *root.rglob('*')]:
            path.chmod(0o755 if path.is_dir() else 0o644)
    rss = json.loads((BASE / 'rss-publishing.json').read_text())
    owner = int(json.loads((BASE / 'personal.json').read_text())['ownerId'])
    config = {'apiBaseUrl': 'http://127.0.0.1:23350', 'ownerId': owner,
              'stateDirectory': str(BASE / 'weekly-rss-state'), 'rss': rss}
    private(BASE / 'weekly-rss.json', json.dumps(config), True)
    config['stateDirectory'] = str(SOURCE / 'staged-state')
    config['rss'] = dict(rss, directory=str(SOURCE / 'staged-public'))
    private(SOURCE / 'staged-config.json', json.dumps(config), True)
    env = dict(line.split('=', 1) for line in (PRIVATE / 'service.env').read_text().splitlines() if '=' in line)
    token = json.loads(env['NEWS_INTERNAL_TOKEN'])
    private(PRIVATE / 'rss.env', 'NEWS_INTERNAL_TOKEN=' + json.dumps(token) + '\n')
    result = subprocess.run(['runuser', '-u', 'pankang', '--preserve-environment', '--', str(PYTHON),
                             str(RELEASE / 'scripts/weekly_rss.py'), '--config', str(SOURCE / 'staged-config.json')],
                            env=dict(os.environ, NEWS_INTERNAL_TOKEN=token, PYTHONDONTWRITEBYTECODE='1'),
                            capture_output=True, text=True, timeout=240)
    assert result.returncode == 0, 'STAGING_PUBLICATION_FAILED'
    print(result.stdout.strip())


def activate():
    """核验旁路成功后启用消费；任何启动错误都恢复本次新闻服务切换。"""
    deploy = deployment()
    deploy.no_daily()
    assert (SOURCE / 'staged-state/state.json').is_file(), 'STAGING_REQUIRED'
    nodes = deploy.request('/api/v1/execution-topology/nodes')
    assert all(node.get('runningTasks', 0) == 0 for node in nodes if node['name'] == 'weekly-news-1'), 'NEWS_NODE_BUSY'
    previous = deploy.start_args(NEWS)
    updated = re.sub(r'-jar \S+', '-jar ' + str(SYSTEM_RELEASE / 'news-digest-service.jar'), previous, count=1)
    folder = Path('/etc/systemd/system') / (NEWS + '.service.d')
    folder.mkdir(exist_ok=True)
    drop = folder / 'zz-weekly-rss-20261122.conf'
    unit = Path('/etc/systemd/system') / (RSS + '.service')
    assert not drop.exists() and not unit.exists(), 'ALREADY_ACTIVATED'
    private(PRIVATE / 'weekly-rss-deployment.json', json.dumps({
        'previousCommand': previous, 'dropin': str(drop), 'publisherUnit': str(unit),
        'previousPublicSnapshot': str((Path('/var/lib/mytools-news-public') / 'current').resolve()),
        'manifestSha256': hashlib.sha256((SOURCE / 'manifest.json').read_bytes()).hexdigest()}))
    try:
        drop.write_text('[Service]\nExecStart=\nExecStart=' + updated + '\n')
        unit.write_text('[Unit]\nDescription=Publish accepted manual weekly news to RSS\nAfter=' + NEWS + '.service\n'
                       '[Service]\nUser=pankang\nGroup=pankang\nWorkingDirectory=' + str(RELEASE) + '\n'
                       'EnvironmentFile=' + str(PRIVATE / 'rss.env') + '\nEnvironment=PYTHONDONTWRITEBYTECODE=1\n'
                       'ExecStart=' + str(PYTHON) + ' ' + str(RELEASE / 'scripts/weekly_rss.py') + ' --config='
                       + str(BASE / 'weekly-rss.json') + ' --watch\nRestart=on-failure\nRestartSec=10\n'
                       'UMask=0077\nNoNewPrivileges=true\nMemoryMax=384M\n[Install]\nWantedBy=multi-user.target\n')
        command('systemctl', 'daemon-reload')
        command('systemctl', 'restart', NEWS)
        deploy.health(23350)
        command('systemctl', 'enable', '--now', RSS)
        deadline = time.monotonic() + 120
        while not (BASE / 'weekly-rss-state/state.json').is_file():
            assert time.monotonic() < deadline, 'PUBLICATION_NOT_CONFIRMED'
            time.sleep(2)
        deploy.no_daily()
        print(json.dumps({'activated': True, 'rssService': RSS, 'automaticCollection': False}))
    except Exception:
        command('systemctl', 'disable', '--now', RSS)
        unit.unlink(missing_ok=True)
        drop.unlink(missing_ok=True)
        command('systemctl', 'daemon-reload')
        command('systemctl', 'restart', NEWS)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('stage', 'activate'))
    args = parser.parse_args()
    try:
        stage() if args.action == 'stage' else activate()
    except Exception as error:
        print(json.dumps({'failed': True, 'type': type(error).__name__,
                          'code': str(error) if isinstance(error, AssertionError) else 'RSS_DEPLOYMENT_FAILED'}))
        raise SystemExit(1)
