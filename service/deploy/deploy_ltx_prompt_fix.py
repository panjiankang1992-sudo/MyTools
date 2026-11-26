#!/usr/bin/env python3
"""发布不可变 1.1.1 与 V164；逐成员验证重组 JAR 和本地构建完全一致。"""
from pathlib import Path
import hashlib
import json
import os
import pwd
import re
import shutil
import subprocess
import time
import zipfile
import deploy_video_generation as d
from deploy_ltx_video import command, start_args

ROOT=Path('/opt/yuyutian/mytools')
OLD=ROOT/'releases/video-ltx-20260921-v1'
NEW=ROOT/'releases/video-ltx-20260921-v2'
SOURCE=Path('/tmp/ltx-111')


def main():
    """在无在途任务时升级，并确认任务定义、节点能力及健康状态。"""
    assert not NEW.exists(),'release_exists'
    with d.task_connection() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM task_instance WHERE status IN ('RUNNING','QUEUED','CANCELLING')")
        assert cursor.fetchone()[0]==0,'active_tasks_present'
    shutil.copytree(OLD,NEW)
    (NEW/'deployment.json').unlink()
    jar=NEW/'apps/task-scheduler-service.jar'
    expected=json.loads((SOURCE/'jar-members.json').read_text())
    with zipfile.ZipFile(OLD/'apps/task-scheduler-service.jar') as before, zipfile.ZipFile(SOURCE/'delta.zip') as delta, zipfile.ZipFile(jar,'w') as target:
        for name,digest in expected.items():
            source=delta if name in delta.namelist() else before
            content=source.read(name)
            assert hashlib.sha256(content).hexdigest()==digest,'unexpected_member_change'
            target.writestr(source.getinfo(name),content)
    with zipfile.ZipFile(jar) as archive:
        assert {n:hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist()}==expected
    package=NEW/'task-packages/video_generate/1.1.1'
    shutil.copytree(SOURCE/'1.1.1',package)
    index_path=NEW/'task-packages/package-index.json';index=json.loads(index_path.read_text())
    index['packages'].append({'name':'video_generate','version':'1.1.1','entrypoint':'scripts/main.py','files':d.package_files(package)})
    index['packages'].sort(key=lambda x:(x['name'],x['version']))
    index['packageCount']=len(index['packages'])
    index['contentSha256']=hashlib.sha256(json.dumps(index['packages'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
    index_path.write_text(json.dumps(index,indent=2)+'\n')
    for file in ('ltx.properties','ltx.env'):
        path=NEW/file;path.write_text(path.read_text().replace(str(OLD),str(NEW)))
    gid=pwd.getpwnam('mytools').pw_gid
    for path in [NEW,*NEW.rglob('*')]:
        if not path.is_symlink():
            os.chown(path,0,gid);path.chmod(0o750 if path.is_dir() else 0o640)
    for unit in ('task-scheduler-service','task-executor-service','video-generation-service'):
        args=start_args(unit);assert str(OLD) in args
        folder=Path('/etc/systemd/system/mytools-'+unit+'.service.d')
        drop=folder/(max(p.name for p in folder.glob('*.conf'))+'.v2.conf')
        drop.write_text('[Service]\nEnvironmentFile='+str(NEW/'ltx.env')+'\nExecStart=\nExecStart='+args.replace(str(OLD),str(NEW))+'\n')
    command('systemctl','daemon-reload')
    command('systemctl','restart','mytools-task-scheduler-service');d.health(23410)
    command('systemctl','restart','mytools-task-executor-service','mytools-video-generation-service');d.health(23341)
    deadline=time.monotonic()+120
    while True:
        code,nodes,_=d.request(23410,'/api/v1/execution-topology/nodes')
        if code==200 and any(n['name']=='executor-remote-1' and n['status']=='ONLINE' and
            'video_generate:1.1.1' in n.get('capabilities',{}).get('scriptReleases',{}) for n in nodes):break
        assert time.monotonic()<deadline,'executor_package_not_online'
        time.sleep(3)
    with d.task_connection() as db,db.cursor() as cursor:
        cursor.execute("SELECT script_version FROM task_step_definition WHERE script_package='video_generate' AND task_definition_id=(SELECT id FROM task_definition WHERE name='video_generate')")
        assert cursor.fetchone()[0]=='1.1.1'
    result={'release':NEW.name,'workerVersion':'1.1.1','allJarMembersMatchLocalBuild':True,'ltxProductEnabled':False,'executorOnline':True}
    (NEW/'deployment.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))


if __name__=='__main__':main()
