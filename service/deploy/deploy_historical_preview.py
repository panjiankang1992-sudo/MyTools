from pathlib import Path
import re,subprocess,zipfile,json
root=Path('/opt/yuyutian/mytools/releases/historical-preview-20260927-v2');root.mkdir(exist_ok=False)
with zipfile.ZipFile('/tmp/historical-preview-v2-stage/delta.zip') as z:z.extractall(root/'delta')
units={'scheduler':'mytools-task-scheduler-service','media':'mytools-media-library-service'}
for k,u in units.items():
 s=subprocess.check_output(['systemctl','show',u,'-p','ExecStart'],text=True);cmd=re.search(r'argv\[\]=(.*?) ; ignore_errors=',s).group(1);old=re.search(r'-jar\s+(\S+)',cmd).group(1);new=root/(k+'.jar')
 changes={str(p.relative_to(root/'delta'/k)):p for p in (root/'delta'/k).rglob('*') if p.is_file()}
 with zipfile.ZipFile(old) as src,zipfile.ZipFile(new,'w') as out:
  for info in src.infolist():out.writestr(info,changes.pop(info.filename).read_bytes() if info.filename in changes else src.read(info.filename))
  for n,p in changes.items():out.writestr(n,p.read_bytes())
 p=Path('/etc/systemd/system')/(u+'.service.d')/'zzzzzzzzzzzzzzz-history-preview.conf';assert not p.exists();p.write_text('[Service]\nExecStart=\nExecStart='+cmd.replace(old,str(new))+'\n')
subprocess.run(['systemctl','daemon-reload'],check=True)
for u in units.values():subprocess.run(['systemctl','restart',u],check=True)
print(json.dumps({'release':str(root)}))

# 后续连接池修复的独立部署脚本与验证结果见 docs/verification/2026-09-27-historical-preview。
