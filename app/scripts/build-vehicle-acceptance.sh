#!/usr/bin/env bash
set -euo pipefail

# 临时使用独立验收入口，构建完成后恢复生产入口与页面清单。
app_dir="$(cd "$(dirname "$0")/.." && pwd)"
vehicle_python="${MYTOOLS_VEHICLE_PYTHON:-python3}"
vehicle_build_dir="$(mktemp -d /private/tmp/mytools-vehicle-acceptance.XXXXXX)"
vehicle_ability="$app_dir/entry/src/main/ets/ability/EntryAbility.ets"
vehicle_pages="$app_dir/entry/src/main/resources/base/profile/main_pages.json"
vehicle_entry="$app_dir/entry/src/main/ets/pages/VehicleAcceptance.ets"
if [[ -e "$vehicle_entry" ]]; then
  printf '%s\n' 'Vehicle acceptance entry already exists; refusing to overwrite.' >&2
  exit 1
fi
cp "$vehicle_ability" "$vehicle_build_dir/ability"
cp "$vehicle_pages" "$vehicle_build_dir/pages"
cleanup() {
  # 只恢复本脚本生成的内容，避免覆盖同时发生的其他修改。
  if [[ -f "$vehicle_build_dir/generated-ability" ]] && cmp -s "$vehicle_ability" "$vehicle_build_dir/generated-ability"; then
    cp "$vehicle_build_dir/ability" "$vehicle_ability"
  elif ! cmp -s "$vehicle_ability" "$vehicle_build_dir/ability"; then
    printf '%s\n' 'EntryAbility changed during acceptance; retained changes and backup.' >&2
  fi
  if [[ -f "$vehicle_build_dir/generated-pages" ]] && cmp -s "$vehicle_pages" "$vehicle_build_dir/generated-pages"; then
    cp "$vehicle_build_dir/pages" "$vehicle_pages"
  elif ! cmp -s "$vehicle_pages" "$vehicle_build_dir/pages"; then
    printf '%s\n' 'Page list changed during acceptance; retained changes and backup.' >&2
  fi
  if cmp -s "$vehicle_entry" "$app_dir/tests/device/VehicleAcceptance.ets"; then
    rm -f "$vehicle_entry"
  fi
}
trap cleanup EXIT
cp "$app_dir/tests/device/VehicleAcceptance.ets" "$vehicle_entry"
"$vehicle_python" - "$app_dir" "$vehicle_build_dir" <<'PY'
import json
from pathlib import Path
import sys

app_dir, build_dir = map(Path, sys.argv[1:])
ability = app_dir / 'entry/src/main/ets/ability/EntryAbility.ets'
pages = app_dir / 'entry/src/main/resources/base/profile/main_pages.json'
source = ability.read_text()
assert source.count("loadContent('pages/Index'") == 1
generated = source.replace("loadContent('pages/Index'", "loadContent('pages/VehicleAcceptance'")
(build_dir / 'generated-ability').write_text(generated)
ability.write_text(generated)
data = json.loads(pages.read_text())
data['src'].append('pages/VehicleAcceptance')
generated_pages = json.dumps(data, indent=2) + '\n'
(build_dir / 'generated-pages').write_text(generated_pages)
pages.write_text(generated_pages)
PY
cd "$app_dir"
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  --mode module -p module=entry@default -p product=default assembleHap --no-daemon
cp "$app_dir/entry/build/default/outputs/default/entry-default-signed.hap" \
  "$vehicle_build_dir/vehicle-acceptance.hap"
printf 'Acceptance HAP: %s/vehicle-acceptance.hap\n' "$vehicle_build_dir"
