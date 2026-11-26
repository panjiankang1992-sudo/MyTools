from pathlib import Path
import json,hashlib,subprocess,os,pwd
rows=json.loads(Path('/tmp/missing-preview-sources.json').read_text());out=[]
for row in rows:
 m=row['media'];old=row['legacy'][0];source=Path(old['file_path']);assert source.is_file() and source.stat().st_size==m['size_bytes']
 digest=hashlib.sha256()
 with source.open('rb') as f:
  while block:=f.read(8*1024*1024):digest.update(block)
 assert digest.hexdigest()==m['content_sha256'],'source digest mismatch'
 target=Path('/opt/extend/resource/.thumbnails')/(str(old['id'])+'.jpg');assert not target.exists()
 temp=target.with_name(target.stem+'.history-preview.tmp.jpg');assert not temp.exists()
 try:
  subprocess.run(['ffmpeg','-nostdin','-v','error','-i',str(source),'-an','-sn','-dn','-frames:v','1','-vf','scale=640:-2:force_original_aspect_ratio=decrease:out_range=full,format=yuvj420p','-q:v','3',str(temp)],check=True,timeout=60,stderr=subprocess.PIPE)
  assert temp.read_bytes()[:3]==b'\xff\xd8\xff'
 except subprocess.CalledProcessError as error:
  temp.unlink(missing_ok=True)
  out.append({'mediaId':m['id'],'verifiedSourceDigest':True,'error':error.stderr.decode(errors='replace').splitlines()[0]});print(json.dumps(out[-1]),flush=True)
  Path('/tmp/legacy-preview-recovery.json').write_text(json.dumps(out))
  continue
 account=pwd.getpwnam('mytools');os.chown(temp,account.pw_uid,account.pw_gid);temp.chmod(0o644);temp.replace(target)
 out.append({'mediaId':m['id'],'legacyId':old['id'],'verifiedSourceDigest':True,'thumbnailBytes':target.stat().st_size});print(json.dumps(out[-1]),flush=True)
Path('/tmp/legacy-preview-recovery.json').write_text(json.dumps(out))
