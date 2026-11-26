#!/usr/bin/env bash
set -euo pipefail
# 临时替换模拟器入口，退出时始终恢复生产入口和页面清单。
root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d /private/tmp/weekly-news-acceptance.XXXXXX)"
cp "$root/entry/src/main/ets/ability/EntryAbility.ets" "$test_dir/ability"
cp "$root/entry/src/main/resources/base/profile/main_pages.json" "$test_dir/pages"
cleanup() {
  cp "$test_dir/ability" "$root/entry/src/main/ets/ability/EntryAbility.ets"
  cp "$test_dir/pages" "$root/entry/src/main/resources/base/profile/main_pages.json"
  rm -f "$root/entry/src/main/ets/pages/WeeklyNewsAcceptance.ets"
  rm -rf "$test_dir"
}
trap cleanup EXIT
cp "$root/tests/device/WeeklyNewsAcceptance.ets" "$root/entry/src/main/ets/pages/WeeklyNewsAcceptance.ets"
"${PYTHON:-python3}" - "$root" <<'PY'
from pathlib import Path
import json,sys,os
root=Path(sys.argv[1])
p=root/'entry/src/main/ets/pages/WeeklyNewsAcceptance.ets'
if os.environ.get('WEEKLY_NEWS_DARK_FIXTURE') == '1':
    p.write_text(p.read_text().replace('  private api: WeeklyAcceptanceApi',
        "  aboutToAppear(): void { AppStorage.setOrCreate('systemColorMode', 0); }\n  private api: WeeklyAcceptanceApi"))
p=root/'entry/src/main/ets/ability/EntryAbility.ets'
ability=p.read_text().replace("loadContent('pages/Index'", "loadContent('pages/WeeklyNewsAcceptance'")
font_scale=os.environ.get('WEEKLY_NEWS_FONT_SCALE', '1')
if font_scale not in ('1', '1.3', '2'):
    raise ValueError('WEEKLY_FIXTURE_FONT_INVALID')
if font_scale != '1':
    # 仅修改临时应用入口，不改变模拟器系统设置；退出后恢复生产文件。
    ability=ability.replace('    windowStage.loadContent(',
        f'    this.context.getApplicationContext().setFontSizeScale({font_scale});\n    windowStage.loadContent(')
p.write_text(ability)
p=root/'entry/src/main/resources/base/profile/main_pages.json'
value=json.loads(p.read_text());value['src'].append('pages/WeeklyNewsAcceptance');p.write_text(json.dumps(value))
PY
cd "$root"
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw --mode module -p module=entry@default -p product=default assembleHap --no-daemon
cp "$root/entry/build/default/outputs/default/entry-default-signed.hap" /private/tmp/weekly-news-acceptance.hap
