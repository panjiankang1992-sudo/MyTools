#!/usr/bin/env python3
"""部署 DSH 登录桥接配置；从已上传的工件目录安装，不输出凭据。"""
from pathlib import Path
import grp
import os
import secrets
import shutil
import subprocess

root = Path('/opt/yuyutian/mytools')
staging = Path('/tmp/mytools-dsh-sso-20261012')
runtime = root / 'runtime/dsh-web'
config = root / 'config/dsh-web'
runtime.mkdir(parents=True, exist_ok=True)
config.mkdir(mode=0o750, parents=True, exist_ok=True)
gid = grp.getgrnam('mytools').gr_gid
os.chown(config, 0, gid)
shutil.copy2(staging / 'refresh_upstream_cookie.py', runtime / 'refresh_upstream_cookie.py')
subprocess.run(['python3', str(runtime / 'refresh_upstream_cookie.py')], check=True)
# 密钥只保存在服务器受限配置文件中，不回传本机或写入仓库。
secret = secrets.token_urlsafe(32)
properties = config / 'application.properties'
properties.write_text('gateway.dsh-web.enabled=true\ngateway.dsh-web.allowed-usernames=yuyutian\n'
                      'gateway.dsh-web.cookie-file=' + str(config / 'upstream-cookie') + '\n'
                      'gateway.dsh-web.proxy-secret=' + secret + '\n')
os.chmod(properties, 0o640); os.chown(properties, 0, gid)
snippet = Path('/etc/nginx/snippets/mytools-dsh-auth-secret.conf')
snippet.write_text('proxy_set_header X-Dsh-Proxy-Key "' + secret + '";\n')
os.chmod(snippet, 0o600)
nginx = Path('/etc/nginx/sites-available/dsh-lan')
backup = runtime / 'dsh-lan.before-sso.conf'
if not backup.exists(): shutil.copy2(nginx, backup)
shutil.copy2(staging / 'dsh-lan.conf', nginx)
try:
    subprocess.run(['nginx', '-t'], check=True)
except subprocess.CalledProcessError:
    shutil.copy2(backup, nginx)
    raise
shutil.copy2(staging / 'mytools-gateway.jar', runtime / 'mytools-gateway.jar')
dropin = Path('/etc/systemd/system/mytools-mytools-gateway.service.d/zzzzzzz-dsh-web.conf')
dropin.write_text('[Service]\nExecStart=\nExecStart=/usr/bin/java -jar ' + str(runtime / 'mytools-gateway.jar')
    + ' --spring.config.additional-location=/opt/yuyutian/mytools/runtime/adaptation-production-20260911/gateway/application.properties,'
    + str(properties) + '\n')
for name in ['mytools-dsh-cookie.service', 'mytools-dsh-cookie.timer']:
    shutil.copy2(staging / name, Path('/etc/systemd/system') / name)
subprocess.run(['systemctl', 'daemon-reload'], check=True)
subprocess.run(['systemctl', 'restart', 'mytools-mytools-gateway'], check=True)
subprocess.run(['systemctl', 'enable', '--now', 'mytools-dsh-cookie.timer'], check=True)
print('DSH SSO installed; validate gateway health before reloading nginx')
