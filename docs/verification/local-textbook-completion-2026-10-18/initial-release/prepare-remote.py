"""核对发布基线，备份当前产物与数据库，准备隔离的候选目录。"""
from pathlib import Path
import fcntl, hashlib, json, os, re, shlex, shutil, subprocess, tempfile, time
ROOT=Path('/opt/yuyutian/toolbox')
lock=(ROOT/'.deployment.lock').open('a')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
RELEASE=ROOT/'releases/local-textbook-20261018-172325'
OLD_JAR='a98599b1dd1514f51f082db95f45a93f25c03bab121ef400dde5d32eb975c627'
OLD_INDEX='118068330477c342b85568e06c5ae25530b4661b36683033c3e9703e8173a3fa'
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(ROOT/'backend/toolbox-service.jar')==OLD_JAR, 'Backend baseline changed'
assert sha(ROOT/'frontend/index.html')==OLD_INDEX, 'Frontend baseline changed'
assert shutil.disk_usage(ROOT).free>512*1024*1024, 'Insufficient backup space'
RELEASE.mkdir(mode=0o750,exist_ok=False)
shutil.copy2(ROOT/'backend/toolbox-service.jar', RELEASE/'previous-backend.jar')
shutil.copy2(ROOT/'backend/toolbox-service.jar', RELEASE/'next-backend.jar')
shutil.copytree(ROOT/'frontend', RELEASE/'candidate-frontend', symlinks=True)
# 数据库凭据仅在服务器本机使用，既不回传也不写入发布报告。
config={}
for raw in (ROOT/'config/toolbox.env').read_text().splitlines():
    line=raw.strip()
    if not line or line.startswith('#') or '=' not in line: continue
    key,value=line.split('=',1)
    if key.startswith('TOOLBOX_DB_'):
        parts=shlex.split(value,comments=False)
        config[key]=' '.join(parts)
def option(value):
    return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n')+'"'
database=config.get('TOOLBOX_DB_NAME','mytools_toolbox')
assert re.fullmatch(r'[A-Za-z0-9_]+',database), 'Invalid database name'
fd,credential=tempfile.mkstemp(prefix='toolbox-backup-',dir='/run')
try:
    os.fchmod(fd,0o600)
    with os.fdopen(fd,'w') as stream:
        stream.write('[client]\n')
        for key,value in [('host',config.get('TOOLBOX_DB_HOST','127.0.0.1')),('port',config.get('TOOLBOX_DB_PORT','3306')),('user',config.get('TOOLBOX_DB_USER','mytools_toolbox')),('password',config.get('TOOLBOX_DB_PASSWORD',''))]:
            stream.write(key+'='+option(value)+'\n')
    backup=RELEASE/'database-before.sql'
    with backup.open('wb') as stream:
        os.chmod(backup,0o600)
        result=subprocess.run(['mysqldump','--defaults-extra-file='+credential,'--single-transaction','--quick','--skip-lock-tables','--no-tablespaces',database],stdout=stream,stderr=subprocess.PIPE,timeout=90)
    assert result.returncode==0, 'Database backup failed; credentials withheld'
    assert backup.stat().st_size>100, 'Database backup empty'
finally:
    os.unlink(credential)
record={'release':str(RELEASE),'previousBackendSha256':OLD_JAR,'previousIndexSha256':OLD_INDEX,'databaseBackupBytes':backup.stat().st_size,'databaseBackupSha256':sha(backup),'preparedAt':time.time()}
(RELEASE/'prepared.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record))
