#!/usr/bin/env python3
"""校验当前运行包，增量安装媒体预览并用私有样本验收，失败自动回退。"""
import argparse
import fcntl
import io
import json
import os
from pathlib import Path
import pwd
import time
import urllib.error
import urllib.parse
import urllib.request
from PIL import Image
import deploy_auth_refresh_recovery as deployment


def environment(module):
    """服务令牌只在进程内读取，不写入日志或验收文件。"""
    pid = deployment.command('systemctl', 'show', 'mytools-' + module, '-p', 'MainPID', '--value')
    return dict(item.split('=', 1) for item in (Path('/proc') / pid / 'environ').read_text().split('\0') if '=' in item)


def request(path, token):
    """请求内部预览并返回状态及图片内容。"""
    req = urllib.request.Request('http://127.0.0.1:23300' + path, headers={'Authorization': 'Bearer ' + token})
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, b''


def verify(sample_file):
    """只记录尺寸、字节数和耗时，不记录用户标识、图片路径或内容。"""
    sample = json.loads(sample_file.read_text())
    token = environment('media-library-service')['MEDIA_LIBRARY_INTERNAL_TOKEN']
    records = []
    for item in sample['items']:
        for edge in (512, 2048):
            path = '/internal/v1/media/items/' + str(item) + '/preview?' + urllib.parse.urlencode({'ownerId': sample['owner'], 'edge': edge})
            result = {'sample': len(records) // 2 + 1, 'edge': edge}
            for phase in ('cold', 'warm'):
                started = time.monotonic()
                status, body = request(path, token)
                result[phase + 'Ms'] = round((time.monotonic() - started) * 1000)
                assert status == 200, 'preview_status_failed'
                with Image.open(io.BytesIO(body)) as image:
                    assert image.format == 'JPEG' and max(image.size) <= edge, 'preview_dimensions_failed'
                    result['width'], result['height'] = image.size
                assert len(body) <= (192 * 1024 if edge == 512 else 1024 * 1024), 'preview_bytes_failed'
                result['bytes'] = len(body)
            records.append(result)
    path = '/internal/v1/media/items/' + str(sample['items'][0]) + '/preview?ownerId=' + str(sample['owner'])
    assert request(path, 'invalid-preview-acceptance-token')[0] == 401
    assert request(path + '&edge=123', token)[0] == 400
    wrong = path.replace('ownerId=' + str(sample['owner']), 'ownerId=-1')
    assert request(wrong, token)[0] == 400
    return {'samples': records, 'unauthorizedRejected': True, 'wrongOwnerRejected': True, 'invalidEdgeRejected': True}


def main():
    """在共享发布锁内执行当前版本校验、运行依赖检查和可回退发布。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--patch', type=Path, required=True)
    parser.add_argument('--release-id', required=True)
    parser.add_argument('--sample-file', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    args.account_file = args.sample_file
    args.dropin_suffix = 'media-preview'
    args.dropin_rank = 72
    deployment.PORTS = {'media-library-service': 23300, 'mytools-gateway': 23200}
    deployment.verify = verify
    deployment.command('runuser', '-u', 'mytools', '--', '/usr/bin/python3', '-c', 'from PIL import Image,ImageOps')
    with (deployment.ROOT / 'runtime/deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.apply:
            directory = deployment.ROOT / 'runtime/media-previews-v1'
            directory.mkdir(mode=0o700, exist_ok=True)
            account = pwd.getpwnam('mytools')
            os.chown(directory, account.pw_uid, account.pw_gid)
            directory.chmod(0o700)
        deployment.deploy(args)


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(json.dumps({'errorType': type(failure).__name__}), flush=True)
        raise SystemExit(1) from None
