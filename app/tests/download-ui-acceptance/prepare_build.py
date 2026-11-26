"""隔离构建下载回复验收入口，正式源码和登录会话不变。"""
from pathlib import Path
import hashlib, json, shutil, sys
source = Path(__file__).resolve().parents[2]
target = Path(sys.argv[1]).resolve()
assert str(target).startswith('/private/tmp/') and not target.exists()
shutil.copytree(source, target, ignore=shutil.ignore_patterns('build', 'oh_modules', '.hvigor', '.git'))
(target / 'oh_modules').symlink_to(source / 'oh_modules', target_is_directory=True)
page = target / 'entry/src/main/ets/pages/DownloadUiAcceptance.ets'
page.write_text((source / 'tests/download-ui-acceptance/DownloadUiAcceptance.ets').read_text().replace("'../../features/", "'../features/"))
ability = target / 'entry/src/main/ets/ability/EntryAbility.ets'
ability.write_text(ability.read_text().replace("'pages/Index'", "'pages/DownloadUiAcceptance'"))
profile = target / 'entry/src/main/resources/base/profile/main_pages.json'
profile.write_text(json.dumps({'src': ['pages/DownloadUiAcceptance']}))
for name in ['DownloadReplyCard.ets', 'DownloadPresentationPolicy.ets']:
    rel = Path('entry/src/main/ets/features/tools/download') / name
    assert (source / rel).read_bytes() == (target / rel).read_bytes()
    print(name, hashlib.sha256((source / rel).read_bytes()).hexdigest())
