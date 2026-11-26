#!/usr/bin/env bash
set -euo pipefail
app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
python3 - "$app_dir/entry/src/main/ets/components/AppControls.ets" "$test_dir/layout.ts" <<'PY'
import re,sys
from pathlib import Path
source=Path(sys.argv[1]).read_text()
def method(block,name):
    match=re.search(r'(?m)^  (?:private )?'+name+r'\(',block)
    start=match.start(); opening=block.index('{',match.end());depth=1;i=opening+1
    while depth:
        depth+=(block[i]=='{')-(block[i]=='}');i+=1
    return block[start:i]
button=source.split('export struct AppButton {',1)[1].split('\n}\n',1)[0]
group=source.split('export struct AppActionGroup {',1)[1].split('\n}\n',1)[0]
header=source.split('export struct AppSheetHeader {',1)[1].split('\n}\n',1)[0]
# 原生固定操作槽与标题的剩余空间隔离，避免清除动作挤出圆角卡片。
assert header.count('.width(64).flexShrink(0)') == 2
assert header.count('.width(64).flexShrink(0).justifyContent(FlexAlign.Center)') == 2
assert '.padding({ left: 16, right: 16 })' in header
assert '.maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })' in header
assert "if (this.rightLabel !== '')" in header
# 自定义组件不是两个可索引的原生布局节点；组必须由单个原生Flex承载。
assert 'onMeasureSize(' not in group and 'onPlaceChildren(' not in group
assert 'AppEqualActionRow({ widthLimit:' in group
assert 'Flex(' not in group and 'Blank()' not in group
assert '.layoutWeight(' not in group and '.width(' not in group
assert group.count('AppGroupedButton({') == 2
native_group=source.split('export struct AppGroupedButton {',1)[1].split('\n}\n',1)[0]
assert 'onMeasureSize(' not in native_group and 'onPlaceChildren(' not in native_group
assert native_group.count('Button({ type: ButtonType.Normal })') == 1
assert '.backgroundColor(this.fill()).borderRadius(this.radius())' in native_group
assert '.wordBreak(WordBreak.BREAK_WORD).flexShrink(1)' in native_group
assert '@Prop minWidth: number = 0;' in native_group
assert '@BuilderParam private builder: () => void = this.ButtonChildren;' in button
assert 'this.builder()' in method(button,'build')
assert 'Row({ space: 6 })' in method(button,'ButtonChildren')
assert 'Button(this.label, { type: ButtonType.Normal })' in method(button,'ButtonChildren')
prelude='''type Length = number | string;
interface ConstraintSizeOptions { minWidth?: Length; maxWidth?: Length; minHeight?: Length; maxHeight?: Length; }
interface SizeResult { width: number; height: number; }
interface GeometryInfo extends SizeResult {}
interface Measurable { measure(constraint: ConstraintSizeOptions): SizeResult; }
interface Layoutable { layout(position: {x:number;y:number}): void; }
const AppTheme = { touchTarget: 44, groupMinWidth: 96, inputHeight: 48 };
'''
text=prelude+'export class ButtonLayout { variant="primary"; grouped=false; fieldAligned=false; fullWidth=false; minWidth=0;\n'
text+='\n'.join(method(button,n) for n in ['isText','isFilled','inset','targetHeight','dimension','onMeasureSize','onPlaceChildren'])+'\n}\n'
Path(sys.argv[2]).write_text(text)
PY
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc \
  "$test_dir/layout.ts" --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/ui_action_layout_test.cjs" "$test_dir/output/layout.js"
