"""复制 App 到临时目录注入独立入口，生产源码/认证/能力开关不变。"""
from pathlib import Path
import hashlib,json,shutil,sys
source=Path(__file__).resolve().parents[2]
target=Path(sys.argv[1]).resolve()
assert str(target).startswith('/private/tmp/') or str(target).startswith('/tmp/')
assert not target.exists(), 'target_already_exists'
shutil.copytree(source,target,ignore=shutil.ignore_patterns('build','oh_modules','.hvigor','.git'))
(target/'oh_modules').symlink_to(source/'oh_modules',target_is_directory=True)
page=target/'entry/src/main/ets/pages/VideoAcceptancePage.ets'
page.write_text((source/'tests/video-acceptance/VideoAcceptancePage.ets').read_text().replace("'../../features/","'../features/"))
ability=target/'entry/src/main/ets/ability/EntryAbility.ets'
ability.write_text(ability.read_text().replace("'pages/Index'","'pages/VideoAcceptancePage'"))
profile=target/'entry/src/main/resources/base/profile/main_pages.json'
profile.write_text(json.dumps({'src':['pages/VideoAcceptancePage']}))
production=source/'entry/src/main/ets/features/tools/VideoGenerationPage.ets'
assert (target/'entry/src/main/ets/features/tools/VideoGenerationPage.ets').read_bytes()==production.read_bytes()
fixture=source.parent/'docs/verification/2026-09-14-video-generation/ltx-accept-20260921'
raw=target/'entry/src/main/resources/rawfile';raw.mkdir(exist_ok=True)
shutil.copyfile(fixture/'product-source.png',raw/'acceptance.png')
shutil.copyfile(fixture/'02-portrait-seed42.mp4',raw/'acceptance.mp4')
print(json.dumps({'testBuild':str(target),'productionPageSha256':hashlib.sha256(production.read_bytes()).hexdigest()}))
