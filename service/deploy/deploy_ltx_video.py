#!/usr/bin/env python3
"""部署 LTX 增量：保留当前启动参数及任务包，只追加模型资源与新包。"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import time
import zipfile

import deploy_video_generation as d

ROOT = Path('/opt/yuyutian/mytools')
RELEASE = ROOT/'releases/video-ltx-20260921-v1'
SOURCE = Path('/tmp/video-ltx-release')


def command(*args):
    """执行本机运维命令，不回显环境或凭据。"""
    return subprocess.check_output(args, text=True).strip()


def start_args(unit):
    """读取完整生效命令，后续只替换目标 JAR 或追加配置位置。"""
    value = command('systemctl','show','mytools-'+unit,'-p','ExecStart')
    return re.search(r'argv\[\]=(.*?) ; ignore_errors=',value).group(1)


def members(path):
    """逐成员核对业务类与资源，防止覆盖其他任务的修复。"""
    with zipfile.ZipFile(path) as archive:
        return {n:hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist()
                if n.startswith('BOOT-INF/classes/') and not n.endswith('/')}


def main():
    """无在途任务时激活；服务健康失败则恢复启动项，保留兼容的数据库迁移。"""
    assert not (RELEASE/'deployment.json').exists(), 'release_already_deployed'
    # 检查真实执行器解释器，避免只验证运维 Python 而遗漏图像依赖。
    pid=command('systemctl','show','mytools-task-executor-service','-p','MainPID','--value')
    process_env=dict(item.split('=',1) for item in Path('/proc/'+pid+'/environ').read_text().split('\0') if '=' in item)
    interpreter=process_env.get('TASK_EXECUTOR_PYTHON_EXECUTABLE','/usr/bin/python3')
    command(interpreter,'-c','import PIL,numpy')
    for relative, expected in json.loads((SOURCE/'manifest.json').read_text()).items():
        assert hashlib.sha256((SOURCE/relative).read_bytes()).hexdigest()==expected
    units=['task-scheduler-service','video-generation-service','task-executor-service']
    old={unit:start_args(unit) for unit in units}
    audit={}
    for unit, allowed in [('task-scheduler-service',['db/migration/V163__add_ltx_video_resource.sql']),
                         ('video-generation-service',['application.yml','com/yuyutian/mytools/video/service/VideoCapability.class',
                                                      'com/yuyutian/mytools/video/service/VideoService.class'])]:
        jar=re.search(r'-jar (\S+)',old[unit]).group(1)
        before=members(jar);after=members(SOURCE/'apps'/(unit+'.jar'))
        changed=[n for n in set(before)|set(after) if before.get(n)!=after.get(n)]
        assert all(n.removeprefix('BOOT-INF/classes/') in allowed for n in changed), 'unexpected_jar_changes_'+unit
        audit[unit]={'previousJar':jar,'changed':sorted(changed)}
    with d.task_connection() as db,db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_instance WHERE status IN ('RUNNING','CANCELLING','QUEUED')")
        assert cursor.fetchone()[0]==0,'active_tasks_present'
    RELEASE.mkdir(parents=True,exist_ok=True)
    if not (RELEASE/'task-packages').exists():
        shutil.copytree(d.current_script_root(), RELEASE/'task-packages')
    target=RELEASE/'task-packages/video_generate/1.1.0'
    if target.exists():
        assert d.package_files(target)==d.package_files(SOURCE/'packages/video_generate/1.1.0')
    else:
        shutil.copytree(SOURCE/'packages/video_generate/1.1.0',target)
    index_path=RELEASE/'task-packages/package-index.json'
    index=json.loads(index_path.read_text())
    if not any(x['name']=='video_generate' and x['version']=='1.1.0' for x in index['packages']):
        index['packages'].append({'name':'video_generate','version':'1.1.0','entrypoint':'scripts/main.py',
                                  'files':d.package_files(target)})
    index['packages'].sort(key=lambda x:(x['name'],x['version']))
    index['packageCount']=len(index['packages'])
    index['contentSha256']=hashlib.sha256(json.dumps(index['packages'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
    index_path.write_text(json.dumps(index,indent=2)+'\n')
    available={(x['name'],x['version']) for x in index['packages']}
    assert all(pair in available for pair in d.pinned_packages()),'pinned_package_missing'
    shutil.copytree(SOURCE/'apps',RELEASE/'apps',dirs_exist_ok=True)
    properties=RELEASE/'ltx.properties'
    properties.write_text('executor.script-root='+str(RELEASE/'task-packages')+'\n'
                         'executor.script-environments.video_generate.VIDEO_LTX_ENABLED=true\n')
    (RELEASE/'ltx.env').write_text('TASK_EXECUTOR_SCRIPT_ROOT='+str(RELEASE/'task-packages')+'\n'
                                  'VIDEO_LTX_FIRST_FRAME_VALIDATED=false\n')
    gid=pwd.getpwnam('mytools').pw_gid
    for path in [RELEASE,*RELEASE.rglob('*')]:
        if path.is_symlink():
            continue
        os.chown(path,0,gid);path.chmod(0o750 if path.is_dir() else 0o640)
    created=[]
    try:
        for unit in units:
            args=old[unit]
            if unit!='task-executor-service':
                jar=re.search(r'-jar (\S+)',args).group(1)
                args=args.replace('-jar '+jar,'-jar '+str(RELEASE/'apps'/(unit+'.jar')),1)
            else:
                assert '--executor.script-root=' not in args,'explicit_script_root_conflict'
                match=re.search(r'--spring.config.additional-location=(\S+)',args)
                if match:
                    args=args.replace(match.group(0),match.group(0)+','+str(properties),1)
                else:
                    args+=' --spring.config.additional-location='+str(properties)
            folder=Path('/etc/systemd/system/mytools-'+unit+'.service.d')
            folder.mkdir(exist_ok=True)
            # 在当前全部片段之后追加，不能猜测其他发布使用了多少个 z。
            last=max((p.name for p in folder.glob('*.conf')), default='zz')
            drop=folder/(last+'.ltx.conf')
            drop.write_text('[Service]\nEnvironmentFile='+str(RELEASE/'ltx.env')+'\nExecStart=\nExecStart='+args+'\n')
            created.append(drop)
        command('systemctl','daemon-reload')
        for unit in ('task-scheduler-service','video-generation-service'):
            assert str(RELEASE/'apps'/(unit+'.jar')) in start_args(unit),'effective_jar_mismatch'
        command('systemctl','restart','mytools-task-scheduler-service');d.health(23410)
        command('systemctl','restart','mytools-video-generation-service','mytools-task-executor-service');d.health(23341)
        deadline=time.monotonic()+120
        while True:
            code,nodes,_=d.request(23410,'/api/v1/execution-topology/nodes')
            online=[n for n in nodes if n['name']=='executor-remote-1' and n['status']=='ONLINE'] if code==200 else []
            if online and any(key == 'video_generate:1.1.0'
                              for key in online[0].get('capabilities',{}).get('scriptReleases',{})):
                break
            assert time.monotonic()<deadline,'executor_package_not_online'
            time.sleep(3)
        with d.task_connection() as db,db.cursor() as cursor:
            cursor.execute("SELECT script_version FROM task_step_definition WHERE script_package=%s AND task_definition_id=(SELECT id FROM task_definition WHERE name=%s)",('video_generate','video_generate'))
            assert cursor.fetchone()[0]=='1.1.0','migration_not_applied'
        result={'release':RELEASE.name,'deployed':True,'ltxProductEnabled':False,'jarAudit':audit,
                'workerVersion':'1.1.0','executorOnline':True}
        (RELEASE/'deployment.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result))
    except Exception:
        # 迁移向后兼容旧资源；执行器保留新包配置，以免已升级定义找不到包。
        for drop in created:
            if 'task-executor-service' not in str(drop):
                drop.unlink(missing_ok=True)
        command('systemctl','daemon-reload')
        command('systemctl','restart',*[('mytools-'+u) for u in units])
        raise


if __name__ == '__main__':
    main()
