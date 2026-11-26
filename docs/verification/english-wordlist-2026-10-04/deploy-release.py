"""校验并原子替换词表与前端，服务重载失败时恢复两份旧目录。"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import time
from urllib.request import urlopen

stage = Path(sys.argv[1])
manifest = json.loads((stage / 'release-files.json').read_text())
content_manifest = json.loads((stage / 'content-files.json').read_text())
release_id = 'english-wordlist-20261004-140500'
assert manifest['releaseId'] == content_manifest['releaseId'] == release_id
root = Path('/opt/yuyutian/toolbox')
release = root / 'releases' / release_id
frontend, content = root / 'frontend', root / 'content'
old_frontend, old_content = release / 'previous-frontend', release / 'previous-content'
previous_index = '4fc28ccbca27fa98dd30b8cab174361b8e9ed1f2d7c619c6bff148ad3a9ed327'
previous_catalog = 'f2c8623d50d5ee7015d6ec81ba9431ea2daa842488788a2d11d8d7ec679215da'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exchange(left, right):
    library = ctypes.CDLL(None, use_errno=True)
    fn = library.renameat2
    fn.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    fn.restype = ctypes.c_int
    if fn(-100, os.fsencode(left), -100, os.fsencode(right), 2):
        raise OSError(ctypes.get_errno(), 'Atomic directory exchange failed')


def prepare(live, candidate, archive, files):
    assert live.is_dir() and not live.is_symlink()
    shutil.copytree(live, candidate)
    with tarfile.open(archive, 'r:gz') as package:
        package.extractall(candidate, filter='data')
    for item in files:
        path = candidate / item['path']
        assert path.resolve().is_relative_to(candidate.resolve())
        assert path.stat().st_size == item['bytes'] and digest(path) == item['sha256'], item['path']
    # 目录中均为公开静态资源与教材数据，服务以只读方式使用。
    for directory, _, names in os.walk(candidate):
        os.chmod(directory, 0o755)
        for name in names:
            os.chmod(Path(directory) / name, 0o644)


def restart():
    subprocess.run(['systemctl', 'restart', 'toolbox-service.service'], check=True, timeout=45)


def read_api(path):
    with urlopen('http://127.0.0.1:23113/public/v1' + path, timeout=5) as response:
        assert response.status == 200
        return json.load(response)['data']


def wait_catalog(version):
    error = None
    for _ in range(30):
        try:
            catalog = read_api('/textbooks')
            edition = next(item for item in catalog['editions'] if item['editionId'] == 'en-yl-3-1-yilin-2024')
            assert edition['contentVersion'] == version
            assert len(catalog['editions']) == 5
            return catalog
        except Exception as failure:
            error = failure
            time.sleep(1)
    raise RuntimeError('Catalog readiness check failed') from error


assert digest(frontend / 'index.html') == previous_index, 'Frontend baseline changed'
assert digest(content / 'catalog/published.json') == previous_catalog, 'Catalog baseline changed'
release.mkdir(parents=True, exist_ok=False)
prepare(frontend, old_frontend, stage / 'frontend.tar.gz', manifest['files'])
prepare(content, old_content, stage / 'content.tar.gz', content_manifest['files'])
published = json.loads((old_content / 'catalog/published.json').read_text())
assert len(published['editions']) == 5
for edition in published['editions']:
    path = old_content / 'textbooks' / edition['editionId'] / edition['contentVersion'] / 'book.json'
    assert digest(path) == edition['contentSha256'], 'Book hash mismatch'
new_index = digest(old_frontend / 'index.html')
new_catalog = digest(old_content / 'catalog/published.json')
result = {'releaseId': release_id, 'frontend': str(frontend), 'content': str(content),
          'backupPath': str(old_frontend), 'contentBackupPath': str(old_content),
          'previousIndexSha256': previous_index, 'indexSha256': new_index,
          'previousCatalogSha256': previous_catalog, 'catalogSha256': new_catalog,
          'verifiedFiles': len(manifest['files']), 'verifiedContentFiles': len(content_manifest['files']),
          'englishContentVersion': '1.0.1', 'backendChanged': False, 'contentChanged': True,
          'atomicDirectoryExchange': True}
for name in ['release-files.json', 'content-files.json']:
    shutil.copy2(stage / name, release / name)
# 切换之前保存恢复身份；即使后续写盘失败，仍有明确的新旧版本记录。
(release / 'prepared-deployment.json').write_text(json.dumps(result, indent=2) + '\n')
assert digest(frontend / 'index.html') == previous_index, 'Frontend changed during preparation'
assert digest(content / 'catalog/published.json') == previous_catalog, 'Catalog changed during preparation'
content_switched = frontend_switched = False
try:
    exchange(content, old_content)
    content_switched = True
    restart()
    catalog = wait_catalog('1.0.1')
    expected_editions = sorted(published['editions'], key=lambda item: item['editionId'])
    assert sorted(catalog['editions'], key=lambda item: item['editionId']) == expected_editions
    detail = read_api('/textbooks/en-yl-3-1-yilin-2024/versions/1.0.1')
    assert len(detail['units']) == 9 and len(detail['lessons']) == 0 and detail['entryCount'] == 140
    exchange(frontend, old_frontend)
    frontend_switched = True
    with urlopen('http://127.0.0.1:23113/index.html', timeout=10) as response:
        assert response.status == 200 and hashlib.sha256(response.read()).hexdigest() == new_index
    result.update({'originIndex': 'pass', 'catalogApi': 'pass', 'serviceRestarted': True})
    (release / 'deployment.json').write_text(json.dumps(result, indent=2) + '\n')
except BaseException as failure:
    recovery_errors = []
    if frontend_switched:
        try:
            exchange(frontend, old_frontend)
        except BaseException as error:
            recovery_errors.append('frontend: ' + str(error))
    if content_switched:
        try:
            exchange(content, old_content)
        except BaseException as error:
            recovery_errors.append('content: ' + str(error))
        try:
            restart()
            wait_catalog('1.0.0')
        except BaseException as error:
            recovery_errors.append('service: ' + str(error))
    if recovery_errors:
        raise RuntimeError('Rollback incomplete: ' + '; '.join(recovery_errors)) from failure
    raise
print(json.dumps(result))
