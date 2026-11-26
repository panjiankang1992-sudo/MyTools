from pathlib import Path
import subprocess,re,zipfile,json
root=Path('/opt/yuyutian/mytools/releases/historical-preview-20260927-v3');root.mkdir(exist_ok=False)
u='mytools-media-library-service'
s=subprocess.check_output(['systemctl','show',u,'-p','ExecStart'],text=True)
command=re.search(r'argv\[\]=(.*?) ; ignore_errors=',s).group(1);old=re.search(r'-jar\s+(\S+)',command).group(1)
new=root/'media-library-service.jar';name='BOOT-INF/classes/com/yuyutian/mytools/media/library/repository/MediaRepository.class'
with zipfile.ZipFile(old) as src,zipfile.ZipFile(new,'w') as out:
 for info in src.infolist():out.writestr(info,Path('/tmp/historical-preview-v3-stage/MediaRepository.class').read_bytes() if info.filename==name else src.read(info.filename))
p=Path('/etc/systemd/system')/(u+'.service.d')/'zzzzzzzzzzzzzzzz-history-pool.conf';assert not p.exists()
p.write_text('[Service]\nExecStart=\nExecStart='+command.replace(old,str(new))+'\n')
subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','restart',u],check=True)
print(json.dumps({'release':str(root),'previousJar':old}))
