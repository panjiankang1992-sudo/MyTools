#!/usr/bin/env python3
"""仅发布图片风格服务及网关控制器，保留所有生产配置。"""
import fcntl
import hashlib
import importlib.util
import json
from pathlib import Path
import pwd
import os
import shutil
import sys
import zipfile

spec=importlib.util.spec_from_file_location('extension',Path(__file__).with_name('deploy_image_extension.py'))
e=importlib.util.module_from_spec(spec);spec.loader.exec_module(e)
NAME='image-styles-20260914-v1'
ROOT=e.ROOT
SOURCE=Path('/tmp')/NAME
RELEASE=ROOT/'releases'/NAME
STATE=ROOT/'runtime'/NAME
SERVICES=['image-generation-service','mytools-gateway']
DROPIN='zzzz-image-styles.conf'
CLASS='BOOT-INF/classes/com/yuyutian/mytools/gateway/controller/ImageGenerationGatewayController.class'


def stage():
    assert not RELEASE.exists(),'release_exists'
    STATE.mkdir(parents=True,exist_ok=True);STATE.chmod(0o700)
    for name,digest in json.loads((SOURCE/'manifest.json').read_text()).items():
        assert hashlib.sha256((SOURCE/name).read_bytes()).hexdigest()==digest,'source_digest_mismatch'
    previous={}
    for name in SERVICES:
        _,args=e.process(name)
        previous[name]={'args':args}
    e.d.write(STATE/'before.json',json.dumps(previous))
    (RELEASE/'apps').mkdir(parents=True)
    shutil.copy2(SOURCE/'image-generation-service.jar',RELEASE/'apps/image-generation-service.jar')
    args=previous['mytools-gateway']['args'];base=Path(args[args.index('-jar')+1])
    # 仅替换单个类，逐条保留线上其他 JAR 内容及压缩信息。
    with zipfile.ZipFile(base) as src,zipfile.ZipFile(RELEASE/'apps/mytools-gateway.jar','w') as dest:
        assert CLASS in src.namelist(),'gateway_class_missing'
        for entry in src.infolist():
            dest.writestr(entry,(SOURCE/'ImageGenerationGatewayController.class').read_bytes() if entry.filename==CLASS else src.read(entry.filename))
    with zipfile.ZipFile(base) as src,zipfile.ZipFile(RELEASE/'apps/mytools-gateway.jar') as dest:
        assert src.namelist()==dest.namelist()
        assert all(src.read(n)==dest.read(n) for n in src.namelist() if n!=CLASS),'unrelated_gateway_change'
    for path in [RELEASE,*RELEASE.rglob('*')]:
        os.chown(path,0,pwd.getpwnam('mytools').pw_gid);path.chmod(0o750 if path.is_dir() else 0o640)
    report={'release':NAME,'gatewayBaseSha256':hashlib.sha256(base.read_bytes()).hexdigest(),'gatewayModifiedEntries':[CLASS],
            'artifacts':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (RELEASE/'apps').iterdir()}}
    e.d.write(STATE/'release.json',json.dumps(report,indent=2));e.emit(report)


def activate():
    previous=json.loads((STATE/'before.json').read_text())
    for name in SERVICES:
        assert e.process(name)[1]==previous[name]['args'],'active_release_changed'
        assert not (e.UNITS/(e.unit(name)+'.d')/DROPIN).exists(),'dropin_exists'
    changed=[]
    try:
        for name,port in [('image-generation-service',23340),('mytools-gateway',23200)]:
            args=previous[name]['args'][:];args[args.index('-jar')+1]=str(RELEASE/'apps'/(name+'.jar'))
            assert all(not any(c.isspace() for c in a) for a in args),'unexpected_command_quoting'
            path=e.UNITS/(e.unit(name)+'.d')/DROPIN
            e.d.write(path,'[Service]\nExecStart=\nExecStart='+' '.join(args)+'\n',mode=0o644)
            changed.append(name)
            e.run(['systemctl','daemon-reload']);e.run(['systemctl','restart',e.unit(name)])
            e.d.health(port)
        assert len(e.image('/styles'))==24,'builtin_count_mismatch'
        e.d.write(STATE/'activated.json',json.dumps({'activated':True,'release':NAME}))
        e.emit({'activated':True,'release':NAME,'builtinStyles':24})
    except Exception:
        # 发布验收前未创建风格任务，可撤销本次入口并保留新增表。
        for name in reversed(changed):
            (e.UNITS/(e.unit(name)+'.d')/DROPIN).unlink(missing_ok=True)
            e.run(['systemctl','daemon-reload']);e.run(['systemctl','restart',e.unit(name)])
        raise


if __name__=='__main__':
    try:
        with (ROOT/'runtime/deployment.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            {'stage':stage,'activate':activate}[sys.argv[1]]()
    except Exception as error:
        e.emit({'failed':True,'type':type(error).__name__,'reason':str(error) if isinstance(error,(AssertionError,RuntimeError)) else 'private_diagnostic_suppressed'})
        raise SystemExit(1)
