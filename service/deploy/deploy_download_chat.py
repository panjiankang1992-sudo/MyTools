#!/usr/bin/env python3
"""在已验证的 QQbot 下载服务上增量部署 APP 聊天入口。"""
from pathlib import Path
import hashlib
import json
import os
import pwd
import re
import shutil
import subprocess
import sys
import time
from urllib.request import urlopen
import zipfile
import shlex

ROOT=Path('/opt/yuyutian/mytools')
RELEASE=ROOT/'releases/download-chat-20260926-v1'
SOURCE=Path('/tmp/download-chat-release')
BASE=ROOT/'releases/qq-download-contract-20260921-v1/python/mytools_download_ingestion'
UNITS=('mytools-download-ingestion-service','mytools-mytools-gateway')
EXPECTED={'service.py':'fdc026acc2bd421cbb5eb89cc473928407793931b253263b67672f852525ef13',
          'http_api.py':'10dd6e4efd35d3145a55c26728186672f844887277bbe082a0b6d34c45ff4864',
          'main.py':'92c7644fd2650fa9732703a3f3b548b78e822ec626f695a62e5592c9400aa9f5'}

def run(*args): return subprocess.check_output(args,text=True).strip()
def config(path):
    result={}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key,value=line.removeprefix('export ').split('=',1);parts=shlex.split(value)
            result[key.strip()]=parts[0] if parts else ''
    return result

def health(url):
    end=time.monotonic()+90
    while time.monotonic()<end:
        try:
            with urlopen(url,timeout=3) as response:
                if json.load(response).get('status')=='UP': return
        except Exception: time.sleep(2)
    raise RuntimeError('service_health_failed')

def rollback():
    for unit in UNITS:
        (Path('/etc/systemd/system')/(unit+'.service.d')/('zzzzzz-download-chat.conf' if unit==UNITS[1] else 'zzzz-download-chat.conf')).unlink(missing_ok=True)
    run('systemctl','daemon-reload');run('systemctl','restart',*UNITS)
    health('http://127.0.0.1:23220/health');health('http://127.0.0.1:23200/actuator/health')

def deploy():
    for name,digest in EXPECTED.items():
        assert hashlib.sha256((BASE/name).read_bytes()).hexdigest()==digest,'live_source_changed'
    for name,digest in json.loads((SOURCE/'manifest.json').read_text()).items():
        assert hashlib.sha256((SOURCE/name).read_bytes()).hexdigest()==digest,'stage_digest_mismatch'
    RELEASE.mkdir(exist_ok=False)
    target=RELEASE/'python/mytools_download_ingestion'
    shutil.copytree(BASE,target,ignore=shutil.ignore_patterns('__pycache__'))
    for name in (*EXPECTED,'app_tools.py'):
        shutil.copyfile(SOURCE/'python/mytools_download_ingestion'/name,target/name)
    shutil.copytree(SOURCE/'python/mytools_task_sdk',RELEASE/'python/mytools_task_sdk',ignore=shutil.ignore_patterns('__pycache__'))
    args=run('systemctl','show',UNITS[1],'-p','ExecStart')
    command=re.search(r'argv\[\]=(.*?) ; ignore_errors=',args).group(1)
    old=re.search(r'-jar\s+(\S+)',command).group(1)
    jar=RELEASE/'mytools-gateway.jar'
    with zipfile.ZipFile(old) as original,zipfile.ZipFile(SOURCE/'gateway-delta.zip') as delta,zipfile.ZipFile(jar,'w') as output:
        replacements=set(delta.namelist())
        for item in original.infolist():
            output.writestr(item,delta.read(item.filename) if item.filename in replacements else original.read(item.filename))
        for name in replacements-set(original.namelist()): output.writestr(name,delta.read(name))
    runtime=ROOT/'runtime/download-chat';runtime.mkdir(exist_ok=True)
    user=pwd.getpwnam('mytools');os.chown(runtime,user.pw_uid,user.pw_gid);runtime.chmod(0o700)
    values=config(ROOT/'config/services.env')
    env=ROOT/'config/download-chat.env'
    entries={'DOWNLOAD_APP_TOOLS_ENABLED':'true','DOWNLOAD_APP_UPLOAD_DIR':str(runtime),
             'STORAGE_GATEWAY_URL':'http://127.0.0.1:23240','STORAGE_GATEWAY_TOKEN':values['STORAGE_INTERNAL_TOKEN']}
    env.write_text(''.join(k+'='+json.dumps(v)+'\n' for k,v in entries.items()));env.chmod(0o600)
    gateway_env=ROOT/'config/download-chat-gateway.env'
    gateway_env.write_text('DOWNLOAD_INGESTION_TOKEN='+json.dumps(values['DOWNLOAD_INTERNAL_TOKEN'])+'\n');gateway_env.chmod(0o600)
    for file in RELEASE.rglob('*'):
        file.chmod(0o755 if file.is_dir() else 0o644)
    for unit in UNITS:
        folder=Path('/etc/systemd/system')/(unit+'.service.d');folder.mkdir(exist_ok=True)
        content='[Service]\n'
        if unit==UNITS[0]: content+=f'Environment=PYTHONPATH={RELEASE}/python\nEnvironmentFile={env}\n'
        else: content+='ExecStart=\nExecStart='+command.replace(old,str(jar))+'\nEnvironmentFile='+str(gateway_env)+'\n'
        (folder/('zzzzzz-download-chat.conf' if unit==UNITS[1] else 'zzzz-download-chat.conf')).write_text(content)
    try:
        run('systemctl','daemon-reload');run('systemctl','restart',*UNITS)
        health('http://127.0.0.1:23220/health');health('http://127.0.0.1:23200/actuator/health')
    except Exception:
        rollback();raise
    print(json.dumps({'release':str(RELEASE),'services':{unit:run('systemctl','is-active',unit) for unit in UNITS},'existingQqCodeVerified':True}))

if __name__=='__main__':
    if '--rollback' in sys.argv: rollback();print('{"rolledBack":true}')
    elif '--deploy' in sys.argv: deploy()
    else: raise SystemExit('explicit_deploy_or_rollback_required')
