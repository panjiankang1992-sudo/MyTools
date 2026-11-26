#!/usr/bin/env bash
set -euo pipefail
app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
python3 - "$app_dir/entry/src/main/ets/components/AppControls.ets" "$test_dir/layout.ts" <<'PY'
import re,sys
from pathlib import Path
source=Path(sys.argv[1]).read_text()
row=source.split('export struct AppEqualActionRow {',1)[1].split('\n}\n',1)[0]
def method(name):
    match=re.search(r'(?m)^  (?:private )?'+name+r'\(',row)
    opening=row.index('{',match.end());depth=1;i=opening+1
    while depth:
        depth+=(row[i]=='{')-(row[i]=='}');i+=1
    return row[match.start():i].replace('  private ','  ')
assert '@BuilderParam content: () => void;' in row
# 原生验收已证实直接构建器会漏掉末尾动作；稳定语法节点必须保留全部直接子节点。
build=method('build')
assert 'ForEach([0], () => {' in build
assert 'this.content()' in build
assert '(token: number) => token.toString()' in build
assert not any(container in build for container in ['Column(', 'Row(', 'Stack(', 'Flex('])
assert 'children[' not in row
assert 'onSizeChange' not in row and '@State' not in row
assert 'children.forEach' in method('onMeasureSize')
assert 'children.forEach' in method('onPlaceChildren')
group=source.split('export struct AppActionGroup {',1)[1].split('\n}\n',1)[0]
assert 'AppEqualActionRow({ widthLimit:' in group
assert group.count('AppGroupedButton({') == 2
assert 'Blank()' not in group and '.layoutWeight(' not in group
assert 'Flex(' not in group and '.width(' not in group
prelude='''type Length = number | string;
interface ConstraintSizeOptions { minWidth?: Length; maxWidth?: Length; minHeight?: Length; maxHeight?: Length; }
interface SizeResult { width: number; height: number; }
interface GeometryInfo extends SizeResult {}
interface Measurable { measure(constraint: ConstraintSizeOptions): SizeResult; }
interface Layoutable { layout(position: {x:number;y:number}): void; }
const AppTheme={touchTarget:44};
'''
text=prelude+'export class EqualActionRowLayout { gap=8; widthLimit=0; rowMinHeight=44;\n'
text+='\n'.join(method(name) for name in ['finite','rowWidth','spacing','onMeasureSize','onPlaceChildren'])+'\n}\n'
Path(sys.argv[2]).write_text(text)
PY
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc \
  "$test_dir/layout.ts" --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/ui_equal_action_row_test.cjs" "$test_dir/output/layout.js"
