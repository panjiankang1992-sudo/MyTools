"""Build an isolated native UI probe without changing production entry or credentials."""
from __future__ import annotations
import hashlib
import json
import shutil
import sys
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2]
OWNED = Path(__file__).resolve().parent
PRODUCTION_FILES = (
    'entry/src/main/ets/ability/EntryAbility.ets',
    'entry/src/main/ets/pages/Index.ets',
    'entry/src/main/ets/components/AppControls.ets',
    'entry/src/main/ets/features/tools/download/DownloadToolsPage.ets',
    'entry/src/main/ets/features/rss/RssHomePage.ets',
    'entry/src/main/ets/features/tools/pdf/PdfToolsPage.ets',
    'entry/src/main/resources/base/profile/main_pages.json',
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(target: Path) -> dict:
    if not str(target).startswith('/private/tmp/mytools-ui-subpage-probe-') or target.exists():
        raise ValueError('Probe requires a fresh dedicated private temporary directory')
    before = {name: digest(SOURCE / name) for name in PRODUCTION_FILES}
    shutil.copytree(SOURCE, target, ignore=shutil.ignore_patterns('build', 'oh_modules', '.hvigor', '.git', '__pycache__'))
    target.chmod(0o700)
    (target / 'oh_modules').symlink_to(SOURCE / 'oh_modules', target_is_directory=True)
    page = target / 'entry/src/main/ets/pages/SubpageUiAcceptance.ets'
    page.write_bytes((OWNED / 'SubpageUiAcceptance.ets').read_bytes())
    ability = target / 'entry/src/main/ets/ability/EntryAbility.ets'
    original = ability.read_text()
    if original.count("loadContent('pages/Index'") != 1:
        raise ValueError('Unexpected entry structure')
    ability.write_text(original.replace("loadContent('pages/Index'", "loadContent('pages/SubpageUiAcceptance'"))
    profile = target / 'entry/src/main/resources/base/profile/main_pages.json'
    profile.write_text(json.dumps({'src': ['pages/SubpageUiAcceptance']}))
    strings = target / 'entry/src/main/resources/base/element/string.json'
    resource = json.loads(strings.read_text())
    resource['string'].append({'name': 'ui_probe_long_action', 'value': '新建文件夹换行校验'})
    strings.write_text(json.dumps(resource, ensure_ascii=False, indent=2))
    unchanged = {name: digest(SOURCE / name) for name in PRODUCTION_FILES}
    if unchanged != before:
        raise ValueError('Production source changed during preparation')
    copied = {name: digest(target / name) for name in PRODUCTION_FILES if name not in (
        'entry/src/main/ets/ability/EntryAbility.ets',
        'entry/src/main/resources/base/profile/main_pages.json')}
    if any(copied[name] != before[name] for name in copied):
        raise ValueError('Probe modified a production component')
    report = {'isolated': True, 'source': str(SOURCE), 'temporaryProject': str(target),
              'entrySha256': digest(page), 'productionSourceHashes': before,
              'copiedComponentHashes': copied, 'productionEntryUnchanged': True,
              'fixtureOrigin': 'http://127.0.0.1:23460'}
    (target / 'probe-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    print(json.dumps(prepare(Path(sys.argv[1]).resolve()), indent=2))
