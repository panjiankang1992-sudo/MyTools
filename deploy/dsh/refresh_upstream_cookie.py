#!/usr/bin/env python3
"""从本机 DSH 启动日志换取上游 Cookie，仅写入受限运行文件，不打印凭据。"""
import grp
import http.client
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    # 日志仅在内存中解析；仅使用本次进程启动之后的记录。
    invocation = subprocess.check_output(['systemctl', 'show', 'dsh', '-p', 'InvocationID', '--value'], text=True, timeout=10).strip()
    # 服务尚未启动时拒绝读取日志，避免空时间戳及旧进程凭据被误用。
    if not re.fullmatch(r'[0-9a-f]{32}', invocation):
        raise RuntimeError('DSH is not running')
    journal = subprocess.check_output(['journalctl', '_SYSTEMD_INVOCATION_ID=' + invocation, '--grep', 'token=', '-n', '10', '--no-pager', '-o', 'cat'], text=True, timeout=10)
    tokens = re.findall(r'[?&]token=([A-Za-z0-9_-]{43})', journal)
    if not tokens:
        raise RuntimeError('DSH bootstrap credential is unavailable')
    connection = http.client.HTTPConnection('127.0.0.1', 3080, timeout=5)
    connection.request('GET', '/?token=' + tokens[-1], headers={'Host': 'dsh-app.yuyutian.top'})
    response = connection.getresponse()
    cookie = (response.getheader('Set-Cookie') or '').split(';', 1)[0]
    if response.status != 303 or not re.fullmatch(r'dsh-auth-[A-Za-z0-9_-]{43}=v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}', cookie):
        raise RuntimeError('DSH session exchange failed')
    target = Path('/opt/yuyutian/mytools/config/dsh-web/upstream-cookie')
    target.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
    gid = grp.getgrnam('mytools').gr_gid
    os.chown(target.parent, 0, gid)
    fd, temporary = tempfile.mkstemp(dir=target.parent)
    try:
        os.fchmod(fd, 0o640)
        os.fchown(fd, 0, gid)
        with os.fdopen(fd, 'w') as output:
            output.write(cookie)
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    connection.close()


if __name__ == '__main__':
    try:
        main()
        print('DSH upstream session refreshed')
    except Exception:
        # 不记录 HTTP 对象或异常上下文，防止启动口令进入日志。
        print('DSH upstream session refresh failed')
        raise SystemExit(1)
