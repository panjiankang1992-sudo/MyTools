from pathlib import Path
import zipfile,re,subprocess
u='mytools-media-library-service';s=subprocess.check_output(['systemctl','show',u,'-p','ExecStart'],text=True);cmd=re.search(r'argv\[\]=(.*?) ; ignore_errors=',s).group(1);old=re.search(r'-jar\s+(\S+)',cmd).group(1)
root=Path('/opt/yuyutian/mytools/releases/media-tags-20260927-v2');root.mkdir(exist_ok=False);target=root/'media.jar'
with zipfile.ZipFile('/tmp/media-tags-final/classes.zip') as delta:changes={n:delta.read(n) for n in delta.namelist()}
with zipfile.ZipFile(old) as src,zipfile.ZipFile(target,'w') as out:
 for info in src.infolist():out.writestr(info,changes.pop(info.filename) if info.filename in changes else src.read(info.filename))
 for name,data in changes.items():out.writestr(name,data)
p=Path('/etc/systemd/system')/(u+'.service.d')/'zzzzzzzzzzzzzzzzzzzzzzzz-media-tags.conf';assert not p.exists();p.write_text('[Service]\nExecStart=\nExecStart='+cmd.replace(old,str(target))+'\n')
subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','restart',u],check=True);print('media error codes deployed')
