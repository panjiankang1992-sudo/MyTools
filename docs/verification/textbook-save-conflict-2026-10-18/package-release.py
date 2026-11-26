"""校验数据库迁移未变化，并打包可核验、可回退的工具箱产物。"""
from pathlib import Path
import hashlib, json, tarfile, tempfile, zipfile
root=Path(__file__).resolve().parents[3]
folder=Path(__file__).resolve().parent
release=(folder/'release-id.txt').read_text().strip()
previous=Path((folder/'previous-jar-path.txt').read_text().strip())
backend=root/'service/toolbox-service/target/toolbox-service-0.1.0-SNAPSHOT.jar'
frontend=root/'toolbox-web/dist'
def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()
def zip_hashes(path):
    with zipfile.ZipFile(path) as archive:
        return {name:hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist() if not name.endswith('/')}
old=zip_hashes(previous)
new=zip_hashes(backend)
migrations=lambda items:{name.rsplit('/',1)[-1]:digest for name,digest in items.items() if name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql')}
assert migrations(old)==migrations(new),'Unexpected database migration change'
changes=[name for name in sorted(old.keys()|new.keys()) if old.get(name)!=new.get(name)]
assert not any(name.startswith('BOOT-INF/lib/') for name in changes),'Unexpected dependency change'
files=[{'path':str(path.relative_to(frontend)),'bytes':path.stat().st_size,'sha256':sha(path)} for path in sorted(frontend.rglob('*')) if path.is_file()]
manifest={'releaseId':release,'previousBackendSha256':sha(previous),'previousIndexSha256':'bca0dbfa89c040c509147b29a501b528bb7082af10f4e1f57fe3e72a92e48859','backendSha256':sha(backend),'migrationSha256':migrations(new),'files':files}
(folder/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(folder/'backend-artifact-diff.json').write_text(json.dumps({'changedFiles':changes,'migrationUnchanged':True,'dependenciesUnchanged':True},indent=2)+'\n')
archive=Path(tempfile.gettempdir())/(release+'.tar.gz')
with tarfile.open(archive,'w:gz') as bundle:
    bundle.add(backend,arcname='next-backend.jar')
    bundle.add(frontend,arcname='candidate-frontend')
    bundle.add(folder/'release-manifest.json',arcname='release-manifest.json')
(folder/'archive-path.txt').write_text(str(archive)+'\n')
print(json.dumps({'releaseId':release,'backendSha256':manifest['backendSha256'],'indexSha256':next(item['sha256'] for item in files if item['path']=='index.html'),'files':len(files),'changedBackendFiles':changes,'archiveBytes':archive.stat().st_size}))
