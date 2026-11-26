"""校验差异并原子发布工具箱修复，健康检查失败时恢复前后端。"""
from pathlib import Path
import ctypes, fcntl, hashlib, json, os, pwd, grp, shutil, subprocess, tarfile, time, urllib.request, zipfile
root = Path('/opt/yuyutian/toolbox')
release = root / 'releases/textbook-loading-20261101-1655'
lock = (root / '.deployment.lock').open('a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
release.mkdir(exist_ok=False)
with tarfile.open('/tmp/mytools-textbook-delta-20261101.tgz') as archive:
    archive.extractall(release, filter='data')
manifest = json.loads((release / 'release-manifest.json').read_text())
backend = root / 'backend/toolbox-service.jar'
frontend = root / 'frontend'
candidate = release / 'candidate-frontend'
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(backend) == manifest['backendSha256'], 'Backend baseline changed'
assert sha(frontend / 'index.html') == manifest['indexSha256'], 'Frontend baseline changed'
# 只传输已核对的类文件，保留线上 JAR 的全部依赖与配置。
for name, expected in manifest['patches'].items():
    assert sha(release / 'backend-patch' / name) == expected, 'Backend patch upload mismatch'
with zipfile.ZipFile(backend) as source, zipfile.ZipFile(release / 'next-backend.jar', 'w') as target:
    for info in source.infolist():
        patch = release / 'backend-patch' / info.filename
        target.writestr(info, patch.read_bytes() if info.filename in manifest['patches'] else source.read(info.filename))
with zipfile.ZipFile(release / 'next-backend.jar') as archive:
    current = {n: hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist() if n.startswith('BOOT-INF/classes/')}
allowed = {'BOOT-INF/classes/com/yuyutian/mytools/toolbox/' + path for path in [
    'controller/curriculum/CurriculumController.class', 'service/curriculum/CurriculumCatalog.class',
    'service/curriculum/CurriculumCatalog$StoredVersion.class']}
changed = {n for n in current.keys() | manifest['entries'].keys() if current.get(n) != manifest['entries'].get(n)}
assert changed <= allowed, 'Unexpected backend changes'
for item in manifest['deltaFiles']:
    assert sha(release / 'dist' / item['path']) == item['sha256'], 'Frontend upload mismatch'
shutil.copy2(backend, release / 'previous-backend.jar')
# 保留旧哈希资源，已经打开的页面仍可加载懒加载模块。
shutil.copytree(frontend, candidate)
shutil.copytree(release / 'dist', candidate, dirs_exist_ok=True)
for item in manifest['files']:
    assert sha(candidate / item['path']) == item['sha256'], 'Candidate frontend mismatch'
subprocess.run(['chown', '-R', 'mytools:mytools', str(candidate)], check=True)
for directory, _, files in os.walk(candidate):
    os.chmod(directory, 0o755)
    for name in files:
        os.chmod(Path(directory) / name, 0o644)
def exchange():
    library = ctypes.CDLL(None, use_errno=True)
    call = library.renameat2
    call.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    if call(-100, os.fsencode(frontend), -100, os.fsencode(candidate), 2):
        raise OSError(ctypes.get_errno(), 'Frontend exchange failed')
def install(source):
    staged = backend.with_suffix('.jar.deploying')
    shutil.copy2(source, staged)
    os.chmod(staged, 0o640)
    os.chown(staged, pwd.getpwnam('mytools').pw_uid, grp.getgrnam('mytools').gr_gid)
    os.replace(staged, backend)
def healthy():
    for _ in range(45):
        try:
            with urllib.request.urlopen('http://127.0.0.1:23112/public/v1/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('Backend health timeout')
def restart():
    subprocess.run(['systemctl', 'restart', 'toolbox-service.service'], check=True, timeout=25)
    healthy()
assert sha(backend) == manifest['backendSha256'] and sha(frontend / 'index.html') == manifest['indexSha256'], 'Live baseline changed'
backend_changed = frontend_changed = False
try:
    install(release / 'next-backend.jar')
    backend_changed = True
    restart()
    # 历史目录 ETag 必须返回新正文，不能继续错误命中 304。
    request = urllib.request.Request('http://127.0.0.1:23112/public/v1/textbooks?subject=english', headers={'If-None-Match': '"catalog-6"'})
    with urllib.request.urlopen(request, timeout=5) as response:
        assert response.status == 200
        assert len(response.headers['ETag']) == 66
        catalog = json.load(response)['data']
    edition = next(e for e in catalog['editions'] if e['grade'] == 4 and e['semester'] == 'first')
    with urllib.request.urlopen('http://127.0.0.1:23112/public/v1/textbooks/' + edition['editionId'] + '/versions/' + edition['contentVersion'] + '/entries?listTypes=vocabulary&limit=500', timeout=5) as response:
        entries = json.load(response)['data']
        assert entries['total'] == 157 and len(entries['entries']) == 157
    exchange()
    frontend_changed = True
    with urllib.request.urlopen('http://127.0.0.1:23113/index.html', timeout=5) as response:
        assert hashlib.sha256(response.read()).hexdigest() == sha(release / 'dist/index.html')
    result = {'releaseId': manifest['releaseId'], 'status': 'deployed', 'backendSha256': sha(backend), 'contentVersion': edition['contentVersion'], 'entryCount': entries['total'], 'changedBackendClasses': sorted(changed), 'databaseModified': False, 'configurationModified': False, 'frontendBackup': str(candidate), 'backendBackup': str(release / 'previous-backend.jar')}
    (release / 'deployment.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
except BaseException:
    if frontend_changed:
        exchange()
    if backend_changed:
        install(release / 'previous-backend.jar')
        restart()
    raise
