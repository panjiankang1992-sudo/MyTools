const assert = require('node:assert/strict');
const { ButtonLayout, IconLayout, NativeGroupedButtonLogic } = require(process.argv[2]);
const { AppTheme } = require(process.argv[3]);
let assertions = 0;
function equal(actual, expected) { assert.deepEqual(actual, expected); assertions++; }
function measure(layout, naturalHeight = 30, maxWidth = 160, minHeight = 0, maxHeight = 1000) {
  const history = { plate: [], target: [], positions: [] };
  const children = [
    { measure(c) {
      history.plate.push(c);
      return { width: 280, height: Math.min(Number(c.maxHeight), Math.max(Number(c.minHeight), naturalHeight)) };
    }, layout(p) { history.positions.push(p); } },
    { measure(c) { history.target.push(c); return { width: Number(c.maxWidth), height: Number(c.maxHeight) }; },
      layout(p) { history.positions.push(p); } }
  ];
  const result = layout.onMeasureSize({ width: 0, height: 0 }, children,
    { minWidth: 0, maxWidth, minHeight, maxHeight });
  layout.onPlaceChildren(result, children, {});
  equal(history.plate.length, 2);
  equal(history.target.length, 1);
  return { result, plate: history.plate[1], target: history.target[0], positions: history.positions };
}
// 紧凑模式的可见底板和命中区保持原合同。
for (const [variant, inset] of [['primary', 2], ['secondary', 4], ['danger', 4], ['text', 6]]) {
  const button = new ButtonLayout(); button.variant = variant;
  const row = measure(button, 20);
  equal(row.result.height, 44);
  equal(row.plate.minHeight, 44 - inset * 2);
  equal(row.target.minHeight, 44);
  equal(row.positions, [{ x: 0, y: inset }, { x: 0, y: 0 }]);
  equal(button.radius(), 10);
}
// 输入行主次动作的实际可见框均为48，不再扣除隐藏的上下内缩。
for (const variant of ['primary', 'secondary', 'danger', 'text', 'destructive']) {
  const button = new ButtonLayout(); button.variant = variant; button.fieldAligned = true;
  const row = measure(button);
  equal(row.result.height, AppTheme.inputHeight);
  equal(row.plate.minHeight, row.target.minHeight);
  equal(row.plate.maxHeight, row.target.maxHeight);
  equal(row.positions, [{ x: 0, y: 0 }, { x: 0, y: 0 }]);
  equal(button.radius(), AppTheme.inputRadius);
}
// 原生Flex给出共同高度后，填满同一可见矩形；放大字体可自然增高。
for (const forced of [48, 72, 120]) {
  for (const variant of ['primary', 'secondary', 'destructive']) {
    const button = new ButtonLayout(); button.variant = variant; button.fieldAligned = true; button.grouped = true;
    const row = measure(button, 30, 86, forced, forced);
    equal(row.result, { width: 86, height: forced });
    equal(row.plate.minHeight, forced);
    equal(row.target.minHeight, forced);
  }
}
const large = new ButtonLayout(); large.fieldAligned = true;
equal(measure(large, 84).result.height, 84);
equal(measure(large, 30).result.height, 48);
// 即使原生内容自然宽度较大，也不得冲破窄卡片和有限父高。
for (const width of [32, 48, 64, 86, 160]) {
  const button = new ButtonLayout(); button.fieldAligned = true; button.minWidth = 96;
  const row = measure(button, 80, width, 0, 64);
  equal(row.result, { width, height: 64 });
  equal(row.plate.maxWidth, width);
  equal(row.target.maxWidth, width);
}
const constrained = new ButtonLayout(); constrained.fieldAligned = true;
equal(measure(constrained, 80, 48, 0, 32).result.height, 32);
// 加载或禁用时复用实际原生点击闭包，不能重复提交；视觉尺寸保持稳定。
let clicks = 0;
const busy = new ButtonLayout(); busy.fieldAligned = true; busy.action = () => clicks++;
for (const [available, loading] of [[false, false], [true, true], [false, true]]) {
  busy.available = available; busy.loading = loading; busy.click();
  equal(clicks, 0);
  equal(measure(busy).result.height, 48);
}
busy.available = false; busy.loading = false;
equal(busy.foreground(), AppTheme.disabledText);
busy.loading = true;
equal(busy.foreground(), '#FFFFFF');
busy.available = true; busy.loading = false; busy.click(); equal(clicks, 1);
// 图标模式保持原44/32外内框，输入行模式只有一个48可见框。
const icon = new IconLayout();
equal([icon.targetSize(), icon.plateSize(), icon.targetPadding(), icon.plateRadius()], [44, 32, 6, 8]);
equal(icon.fill(), 'transparent');
icon.fieldAligned = true;
equal([icon.targetSize(), icon.plateSize(), icon.targetPadding(), icon.plateRadius()], [48, 48, 0, 12]);
equal(icon.fill(), AppTheme.surfaceTint);
icon.selected = true; equal(icon.fill(), AppTheme.accentSoft);
icon.darkMode = true; equal(icon.fill(), AppTheme.darkAccentSoft);
icon.selected = false; equal(icon.fill(), AppTheme.darkSurfaceTint);
icon.pressed = true; equal(icon.fill(), AppTheme.darkAccentSoft);
// 原生分组按钮仅校验角色与回调合同，不用模拟约束冒充平台Stretch实测。
const native = new NativeGroupedButtonLogic();
equal(native.targetHeight(), 44); equal(native.radius(), 10);
native.fieldAligned = true; equal(native.targetHeight(), 48); equal(native.radius(), 12);
native.fieldAligned = false; native.variant = 'text';
equal(native.fill(), 'transparent'); equal(native.foreground(), AppTheme.primary);
native.variant = 'dangerText'; equal(native.foreground(), AppTheme.danger);
let nativeClicks = 0; native.action = () => nativeClicks++;
native.available = false; native.click(); equal(nativeClicks, 0);
native.available = true; native.loading = true; native.click(); equal(nativeClicks, 0);
native.loading = false; native.click(); equal(nativeClicks, 1);
native.variant = 'primary'; equal(native.contentMinHeight(), 36);
native.variant = 'secondary'; equal(native.contentMinHeight(), 34);
native.available = false; equal(native.contentMinHeight(), 36);
native.fieldAligned = true; equal(native.contentMinHeight(), 40);
native.available = true; equal(native.contentMinHeight(), 38);
native.label = 'Cancel'; equal(native.accessibilityLabel(), 'Cancel');
native.labelText = 'Create folder'; equal(native.accessibilityLabel(), 'Create folder');
native.labelText = ''; native.label = {id:7}; native.description = 'Fallback';
equal(native.accessibilityLabel(), 'Fallback');
native.testHost = {resourceManager:{getStringSync:id => `Resource ${id}`}};
equal(native.accessibilityLabel(), 'Resource 7');
native.testHost = {resourceManager:{getStringSync:()=>{throw new Error('unavailable')}}};
equal(native.accessibilityLabel(), 'Fallback');
console.log(`UI field controls: ${assertions} measurements and state checks passed`);
