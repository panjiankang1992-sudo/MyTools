#!/usr/bin/env python3
"""在原 APP 完整发布包上叠加身份修复，保留原 Python 加载路径。"""
from pathlib import Path
import py_compile
import shlex
import shutil
import subprocess


def main():
    """验证完整路由后创建不可变修复包，并只重启下载接入服务。"""
    unit = 'mytools-download-ingestion-service'
    directory = Path('/etc/systemd/system/' + unit + '.service.d')
    original = None
    for config in sorted(directory.glob('*.conf')):
        if 'app-identity' in config.name or 'app-route' in config.name:
            continue
        for line in config.read_text().splitlines():
            if line.startswith('Environment='):
                for value in shlex.split(line.split('=', 1)[1]):
                    if value.startswith('PYTHONPATH='):
                        original = value.split('=', 1)[1]
    assert original, 'original_python_path_missing'
    source = next((Path(value) / 'mytools_download_ingestion' for value in original.split(':')
                   if (Path(value) / 'mytools_download_ingestion/app_tools.py').is_file()), None)
    assert source and 'app-download-tools' in (source / 'http_api.py').read_text()
    root = Path('/opt/yuyutian/mytools/releases/app-download-identity-20261104-v2')
    root.mkdir(exist_ok=False)
    package = root / 'mytools_download_ingestion'
    shutil.copytree(source, package, ignore=shutil.ignore_patterns('__pycache__'))
    target = package / 'service.py'
    content = target.read_text()
    needle = '        parameters = {**accepted.parameters, "ownerId": accepted.owner_id,\n                      "downloadRequestId": str(accepted.id)}\n'
    assert content.count(needle) == 1
    content = content.replace(needle, needle + '''        if accepted.source_type == "APP_TOOL" and command.request_kind in {"MESSAGE_URL_BATCH", "X_USER"}:
            # APP 批次不是消息身份，使用稳定命名空间避免误登记到机器人消息去重表。
            parameters["messageBatchId"] = f"app:{accepted.id}"
''')
    target.write_text(content)
    py_compile.compile(str(target), doraise=True)
    subprocess.run(['chown', '-R', '--reference=' + str(source), str(package)], check=True)
    override = directory / ('z' * 53 + '-app-route-identity.conf')
    assert not override.exists()
    override.write_text('[Service]\nEnvironment="PYTHONPATH=' + str(root) + ':' + original + '"\n')
    subprocess.run(['systemctl', 'daemon-reload'], check=True)
    subprocess.run(['systemctl', 'restart', unit], check=True)
    print('APP routes restored with identity fix; original Python path preserved')


if __name__ == '__main__':
    main()
