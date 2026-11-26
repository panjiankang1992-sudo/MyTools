#!/usr/bin/env bash
set -euo pipefail
# 只构建临时验收入口，退出时恢复生产入口。
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp_dir="$(mktemp -d /private/tmp/pdf-acceptance-build.XXXXXX)"
cp "$root/entry/src/main/ets/ability/EntryAbility.ets" "$tmp_dir/ability"
cp "$root/entry/src/main/resources/base/profile/main_pages.json" "$tmp_dir/pages"
cleanup() {
  cp "$tmp_dir/ability" "$root/entry/src/main/ets/ability/EntryAbility.ets"
  cp "$tmp_dir/pages" "$root/entry/src/main/resources/base/profile/main_pages.json"
  rm -f "$root/entry/src/main/ets/pages/PdfLocalAcceptance.ets"
  rm -f "$root/entry/src/main/resources/rawfile/"pdf-acceptance-*.pdf
}
trap cleanup EXIT
cp "$root/tests/device/PdfLocalAcceptance.ets" "$root/entry/src/main/ets/pages/PdfLocalAcceptance.ets"
python3 - "$root" <<'PY'
from pathlib import Path
import sys,json
root=Path(sys.argv[1]);p=root/'entry/src/main/ets/ability/EntryAbility.ets';p.write_text(p.read_text().replace("loadContent('pages/Index'", "loadContent('pages/PdfLocalAcceptance'"))
p=root/'entry/src/main/resources/base/profile/main_pages.json';s=json.loads(p.read_text());s['src'].append('pages/PdfLocalAcceptance');p.write_text(json.dumps(s))
PY
/Users/pankang/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 "$root/tests/create_pdf_fixtures.py" "$root/entry/src/main/resources/rawfile"
cd "$root"
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw --mode module -p module=entry@default -p product=default assembleHap --no-daemon
cp "$root/entry/build/default/outputs/default/entry-default-signed.hap" /private/tmp/pdf-local-acceptance.hap
