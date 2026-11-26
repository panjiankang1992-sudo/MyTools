#!/usr/bin/env bash
set -euo pipefail
app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
python3 - "$app_dir/entry/src/main/ets/components/AppControls.ets" "$app_dir/entry/src/main/ets/theme/AppTheme.ets" "$test_dir" <<'PY'
import re,sys
from pathlib import Path
source=Path(sys.argv[1]).read_text()
out=Path(sys.argv[3])
def block(name):
    return source.split('export struct '+name+' {',1)[1].split('\n}\n',1)[0]
def body(text, opening):
    depth=1;i=opening+1
    while depth:
        depth+=(text[i]=='{')-(text[i]=='}');i+=1
    return text[opening:i]
def method(text,name):
    match=re.search(r'(?m)^  (?:private )?'+name+r'\(',text)
    return text[match.start():text.index('{',match.end())]+body(text,text.index('{',match.end()))
button=block('AppButton'); icon=block('AppIconButton'); grouped=block('AppGroupedButton')
native=method(button,'ButtonChildren')
click=native.index('.onClick(() => {')
click_body=body(native,native.index('{',click))
for name in ['AppPrimaryPillButton','AppSecondaryPillButton','AppDangerPillButton']:
    wrapper=block(name)
    assert '@Prop grouped: boolean = false;' in wrapper
    assert '@Prop fieldAligned: boolean = false;' in wrapper
    assert 'if (this.grouped)' in wrapper and 'AppGroupedButton({' in wrapper
    assert 'AppButton({' in wrapper and wrapper.count('fieldAligned: this.fieldAligned') == 2
    assert 'grouped: this.grouped' not in wrapper
assert '.width(this.plateSize()).height(this.plateSize())' in icon
assert '.width(this.targetSize()).height(this.targetSize()).padding(this.targetPadding())' in icon
assert '.enabled(this.available).accessibilityText(this.description)' in icon
prelude='''import { AppTheme } from './AppTheme';
type Length = number | string;
type ResourceColor = string;
type ResourceStr = string | {id:number};
interface ConstraintSizeOptions { minWidth?: Length; maxWidth?: Length; minHeight?: Length; maxHeight?: Length; }
interface SizeResult { width: number; height: number; }
interface GeometryInfo extends SizeResult {}
interface Measurable { measure(constraint: ConstraintSizeOptions): SizeResult; }
interface Layoutable { layout(position: {x:number;y:number}): void; }
const Color = { White: '#FFFFFF', Transparent: 'transparent' };
'''
text=prelude+'''export class ButtonLayout {
  variant='primary'; grouped=false; fieldAligned=false; fullWidth=false; minWidth=0;
  available=true; loading=false; darkMode=false; pressed=false; action=():void=>{};
'''
text+='\n'.join(method(button,n) for n in ['isText','isFilled','inset','targetHeight','radius',
    'dimension','foreground','fill','onMeasureSize','onPlaceChildren'])
text+='\n  click():void '+click_body+'\n}\n'
text+='''export class IconLayout {
  fieldAligned=false; selected=false; pressed=false; darkMode=false;
'''
text+='\n'.join(method(icon,n) for n in ['targetSize','plateSize','plateRadius','targetPadding','fill'])+'\n}\n'
# 执行组件当前真实逻辑，去掉私有访问限制只为测试入口，不复制测量实现。
text=text.replace('  private ', '  ')
native=method(grouped,'build')
opening=native.index('{',native.index('.onClick(() => {'))
text+='export class NativeGroupedButtonLogic { variant="primary"; fieldAligned=false; available=true; loading=false; darkMode=false; pressed=false; action=():void=>{}; label:ResourceStr=""; labelText:ResourceStr=""; description=""; testHost?:{resourceManager:{getStringSync:(id:number)=>string}}; getUIContext(){return {getHostContext:()=>this.testHost}};\n'
text+='\n'.join(method(grouped,n) for n in ['isText','isFilled','targetHeight','radius','contentMinHeight','foreground','fill','accessibilityLabel'])
text+='\n  click():void '+body(native,opening)+'\n}\n'
text=text.replace('  private ', '  ')
(out/'layout.ts').write_text(text)
(out/'AppTheme.ts').write_text(Path(sys.argv[2]).read_text())
PY
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc \
  "$test_dir/layout.ts" "$test_dir/AppTheme.ts" --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/ui_field_control_layout_test.cjs" "$test_dir/output/layout.js" "$test_dir/output/AppTheme.js"
