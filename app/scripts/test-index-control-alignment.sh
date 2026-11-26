#!/usr/bin/env bash
set -euo pipefail
app_dir="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-index-control-alignment.XXXXXX)"
trap 'rm -rf "$work"' EXIT
python3 - "$app_dir/entry/src/main/ets/pages/Index.ets" "$work/FieldHeight.ts" <<'PY'
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
start=source.index('  private BookSourceFilterControlHeight(): number {')
end=source.index('\n  }', start)+4
method=source[start:end]
Path(sys.argv[2]).write_text('const AppTheme = { inputHeight: 48 };\nexport class FieldHeight {\n  systemFontScale = 1;\n'+method.replace('private ', '', 1)+'\n}\n')
PY
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc \
  "$work/FieldHeight.ts" --target ES2020 --module commonjs --outDir "$work/output" --skipLibCheck
node "$app_dir/tests/index_control_alignment_test.cjs" \
  "$app_dir/entry/src/main/ets/pages/Index.ets" "$work/output/FieldHeight.js" \
  "$app_dir/entry/src/main/ets/components/AppControls.ets"
