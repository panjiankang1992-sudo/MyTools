#!/usr/bin/env python3
"""空闲时切换新闻分析运行包，不重启网关、调度器或改写已发布工作包。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

BASE = Path('/home/pankang/workspace/news-personal')
UNIT = 'mytools-news-weekly-executor'
CONFIG = Path('/opt/yuyutian/mytools/config/weekly-news')


def activate(version):
    """验证不可变清单与节点空闲，切换失败自动删除本次配置恢复旧进程。"""
    assert re.fullmatch(r'[1-9][0-9]*\.[0-9]+\.[0-9]+', version), 'RUNTIME_VERSION_INVALID'
    source = BASE/f'validation/weekly-20261122/runtime-{version}'
    target = BASE/f'weekly-releases/news-runtime-{version}'
    spec = importlib.util.spec_from_file_location('deployment', BASE/'validation/weekly-20261122/deploy_weekly_news.py')
    deployment = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(deployment)
    deployment.no_daily()
    nodes = deployment.request('/api/v1/execution-topology/nodes')
    news_nodes = [n for n in nodes if n['name'] == 'weekly-news-1']
    assert news_nodes and all(n.get('runningTasks', 0) == 0 for n in news_nodes), 'NEWS_NODE_BUSY'
    manifest = json.loads((source/'runtime-manifest.json').read_text())
    assert manifest['version'] == 'news-personal-'+version, 'RUNTIME_VERSION_MISMATCH'
    for name, digest in manifest['files'].items():
        path = source/name
        assert not path.is_symlink() and path.resolve().is_relative_to(source.resolve()) and hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'RUNTIME_HASH_MISMATCH'
    assert not target.exists(), 'RUNTIME_ALREADY_PUBLISHED'
    shutil.copytree(source, target)
    for path in [target, *target.rglob('*')]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    settings = target.parent/f'news-runtime-{version}.properties'
    settings.write_text('executor.script-environments.news_generate_weekly.NEWS_WEEKLY_RUNTIME='+str(target)+'\n')
    settings.chmod(0o644)
    old = deployment.start_args(UNIT)
    match = re.search(r'--spring.config.additional-location=(\S+)', old)
    assert match, 'EXECUTOR_CONFIG_NOT_FOUND'
    updated = old.replace(match.group(0), match.group(0)+','+str(settings), 1)
    folder = Path('/etc/systemd/system')/(UNIT+'.service.d')
    folder.mkdir(exist_ok=True)
    # systemd 按文件名排序；使用部署时刻，避免 1.10 被旧的 1.9 配置覆盖。
    drop = folder/f'zz-news-runtime-{time.strftime("%Y%m%d%H%M%S", time.gmtime())}-{version}.conf'
    assert not drop.exists(), 'DROPIN_EXISTS'
    deployment.private(CONFIG/f'runtime-{version}.json', json.dumps({'previousCommand': old, 'dropin': str(drop), 'runtime': str(target), 'manifestSha256': hashlib.sha256((source/'runtime-manifest.json').read_bytes()).hexdigest()}))
    try:
        drop.write_text('[Service]\nExecStart=\nExecStart='+updated+'\n')
        deployment.command('systemctl', 'daemon-reload')
        deployment.command('systemctl', 'restart', UNIT)
        deployment.health(23106)
        end = time.monotonic()+60
        while True:
            nodes = deployment.request('/api/v1/execution-topology/nodes')
            if any(n['name'] == 'weekly-news-1' and n['status'] == 'ONLINE' for n in nodes):
                break
            assert time.monotonic() < end, 'NEWS_NODE_OFFLINE'
            time.sleep(2)
        print(json.dumps({'activatedRuntime': version, 'jobNotAutomaticallyCreated': True}))
    except Exception:
        drop.unlink(missing_ok=True)
        deployment.command('systemctl', 'daemon-reload')
        deployment.command('systemctl', 'restart', UNIT)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('version', nargs='?', default='1.6.1')
    args = parser.parse_args()
    try:
        activate(args.version)
    except Exception as error:
        print(json.dumps({'failed': True, 'type': type(error).__name__, 'code': str(error) if isinstance(error, AssertionError) else 'RUNTIME_SWITCH_FAILED'}))
        raise SystemExit(1)
