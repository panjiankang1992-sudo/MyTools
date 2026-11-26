from pathlib import Path
import subprocess,zipfile,sys,os,re
sys.path.insert(0,'/tmp')
from deploy_download_chat import config
c=config(Path('/opt/yuyutian/mytools/config/services.env'))
import pymysql
db=pymysql.connect(host='127.0.0.1',user=c['TASK_DB_USERNAME'],password=c['TASK_DB_PASSWORD'],database='mytools_task')
with db.cursor() as q:
 q.execute('select version from flyway_schema_history where success=0');assert q.fetchall()==(('170',),)
 q.execute('select count(*) from task_definition where name=%s',('media_generate_video_preview',));assert q.fetchone()[0]==0
root=Path('/opt/yuyutian/mytools/releases/historical-preview-20260927-v4');root.mkdir(exist_ok=False)
old=Path('/opt/yuyutian/mytools/releases/historical-preview-20260927-v2/scheduler.jar');new=root/'scheduler.jar'
name='BOOT-INF/classes/db/migration/V170__seed_video_preview_task.sql'
with zipfile.ZipFile(old) as src,zipfile.ZipFile(new,'w') as out:
 for info in src.infolist():out.writestr(info,Path('/tmp/V170__seed_video_preview_task.sql').read_bytes() if info.filename==name else src.read(info.filename))
stage=Path('/tmp/preview-flyway');stage.mkdir(exist_ok=True);(stage/'libs').mkdir(exist_ok=True);(stage/'migrations').mkdir(exist_ok=True)
with zipfile.ZipFile(new) as z:
 for n in z.namelist():
  if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'):(stage/'libs'/Path(n).name).write_bytes(z.read(n))
  if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql'):(stage/'migrations'/Path(n).name).write_bytes(z.read(n))
subprocess.run(['systemctl','stop','mytools-task-scheduler-service'],check=True)
subprocess.run(['java','-cp',str(stage/'libs/*'),'/tmp/RepairPreview.java'],env={**os.environ,**c},check=True,stdout=subprocess.DEVNULL)
u='mytools-task-scheduler-service';s=subprocess.check_output(['systemctl','show',u,'-p','ExecStart'],text=True);cmd=re.search(r'argv\[\]=(.*?) ; ignore_errors=',s).group(1)
p=Path('/etc/systemd/system')/(u+'.service.d')/'zzzzzzzzzzzzzzzz-history-preview-repair.conf';p.write_text('[Service]\nExecStart=\nExecStart='+cmd.replace(str(old),str(new))+'\n')
subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','restart',u],check=True)
print('Flyway repaired failed V170 and applied corrected migration; scheduler restarted')
