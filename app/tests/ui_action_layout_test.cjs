const assert = require('node:assert/strict');
const { ButtonLayout } = require(process.argv[2]);
// 直接执行当前按钮的测量方法；原生Flex负责测出组高后，将同一高度约束传入两侧。
function measure(naturalHeight, width, forcedHeight, variant = 'secondary') {
  const layout = new ButtonLayout();
  layout.grouped = true; layout.fullWidth = true; layout.variant = variant;
  const record = { visual: undefined, target: undefined, visualPosition: undefined, targetPosition: undefined };
  const children = [
    { measure(c) { record.visual = c; return { width: Math.min(120, Number(c.maxWidth)),
      height: Math.max(Number(c.minHeight), Math.min(naturalHeight, Number(c.maxHeight))) }; },
      layout(p) { record.visualPosition = p; } },
    { measure(c) { record.target = c; return { width: Number(c.maxWidth), height: Number(c.maxHeight) }; },
      layout(p) { record.targetPosition = p; } }
  ];
  const result = layout.onMeasureSize({ width: 0, height: 0 }, children,
    { minWidth: width, maxWidth: width, minHeight: forcedHeight ?? 0, maxHeight: forcedHeight ?? 1000 });
  layout.onPlaceChildren(result, children, {});
  assert.equal(record.target.minHeight, result.height);
  assert.equal(record.visual.minHeight + 4, result.height);
  assert.deepEqual(record.visualPosition, { x: 0, y: 2 });
  assert.deepEqual(record.targetPosition, { x: 0, y: 0 });
  return { result, record };
}
assert.equal(measure(30, 156).result.height, 44);
assert.equal(measure(66, 118.8).result.height, 70);
for (const commonHeight of [44, 70, 144]) {
  const secondary = measure(30, 136, commonHeight);
  const primary = measure(commonHeight - 4, 136, commonHeight, 'destructive');
  assert.equal(secondary.result.width, primary.result.width);
  assert.equal(secondary.result.height, primary.result.height);
  assert.equal(secondary.record.visual.minHeight, primary.record.visual.minHeight);
}
assert.equal(measure(30, 86).result.width, 86);
assert.equal(measure(140, 136).result.height, 144);
assert.equal(measure(30, 156).result.height, 44); // 上次大字或长文案不污染下一轮。
// 原生内容在自然测量时返回较大宽度，组件也不能把父级有限边界撑开。
for (const maxWidth of [48, 64, 96]) {
  const layout = new ButtonLayout();
  layout.variant = 'text';
  let calls = 0;
  let plateConstraint;
  let targetConstraint;
  const result = layout.onMeasureSize({ width: 0, height: 0 }, [
    { measure(c) { plateConstraint = c; return { width: ++calls === 1 ? 320 : Number(c.maxWidth), height: 32 }; } },
    { measure(c) { targetConstraint = c; return { width: Number(c.maxWidth), height: Number(c.maxHeight) }; } }
  ], { minWidth: 0, maxWidth, maxHeight: 1000 });
  assert.equal(result.width, maxWidth);
  assert.equal(plateConstraint.maxWidth, maxWidth);
  assert.equal(targetConstraint.maxWidth, maxWidth);
}
console.log('UI action contracts: native grouped-button root and fixed gap; legacy compact-button measurement, constrained width and fresh measurement passed (native group geometry requires device verification)');
