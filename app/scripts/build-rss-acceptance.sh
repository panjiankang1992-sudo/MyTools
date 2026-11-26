#!/usr/bin/env bash
set -euo pipefail
# 临时替换入口后构建验收包，任何退出均恢复原文件。
root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d /private/tmp/rss-acceptance-build.XXXXXX)"
cp "$root/entry/src/main/ets/ability/EntryAbility.ets" "$test_dir/ability"
cp "$root/entry/src/main/resources/base/profile/main_pages.json" "$test_dir/pages"
cleanup() {
  cp "$test_dir/ability" "$root/entry/src/main/ets/ability/EntryAbility.ets"
  cp "$test_dir/pages" "$root/entry/src/main/resources/base/profile/main_pages.json"
  rm -f "$root/entry/src/main/ets/pages/RssAcceptance.ets"
  rm -rf "$test_dir"
}
trap cleanup EXIT
cp "$root/tests/device/RssAcceptance.ets" "$root/entry/src/main/ets/pages/RssAcceptance.ets"
python3 - "$root" <<'PY'
from pathlib import Path
import json,sys
root=Path(sys.argv[1])
p=root/'entry/src/main/ets/ability/EntryAbility.ets'
p.write_text(p.read_text().replace("loadContent('pages/Index'", "loadContent('pages/RssAcceptance'"))
p=root/'entry/src/main/resources/base/profile/main_pages.json'
value=json.loads(p.read_text());value['src'].append('pages/RssAcceptance');p.write_text(json.dumps(value))
PY
cd "$root"
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw --mode module -p module=entry@default -p product=default assembleHap --no-daemon
cp "$root/entry/build/default/outputs/default/entry-default-signed.hap" /private/tmp/rss-acceptance.hap
