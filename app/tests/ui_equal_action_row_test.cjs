const assert = require('node:assert/strict');
const { EqualActionRowLayout } = require(process.argv[2]);
let checks = 0;
function equal(actual, expected) { assert.deepEqual(actual, expected); checks++; }
function run(heights, options = {}) {
  const row = new EqualActionRowLayout();
  Object.assign(row, options.props ?? {});
  const calls = heights.map(() => []);
  const positions = [];
  const children = heights.map((natural, index) => ({
    measure(c) {
      calls[index].push(c);
      // 第一遍只收自然高度；这里不会模拟Flex偷偷提供共同minHeight。
      const height = calls[index].length === 1 && !Number.isFinite(natural) ? natural :
        Math.min(Number(c.maxHeight), Math.max(Number(c.minHeight), natural));
      return { width: Number(c.maxWidth), height };
    },
    layout(position) { positions[index] = position; }
  }));
  const constraint = { minWidth: 0, maxWidth: 320, minHeight: 0, maxHeight: 1200, ...(options.constraint ?? {}) };
  const result = row.onMeasureSize(options.self ?? { width: 0, height: 0 }, children, constraint);
  row.onPlaceChildren(result, children, constraint);
  for (const call of calls) {
    equal(call.length, 2);
    equal(call[0].minWidth, call[0].maxWidth);
    equal(call[1].minHeight, result.height);
    equal(call[1].maxHeight, result.height);
    equal(call[1].minWidth, call[1].maxWidth);
  }
  assert(Number.isFinite(result.width) && Number.isFinite(result.height)); checks++;
  for (const position of positions) {
    assert(Number.isFinite(position.x) && position.x >= 0 && position.x <= result.width); checks++;
    equal(position.y, 0);
  }
  return { result, calls, positions };
}
// 实机曾有44/54.2/46的三种高度；父容器必须显式强制相同精确高度。
const observed = run([44, 54.2, 46]);
equal(observed.result, { width: 320, height: 55 });
equal(observed.calls.map(call => call[1].minHeight), [55, 55, 55]);
equal(observed.calls.map(call => call[0].minHeight), [44, 44, 44]);
equal(observed.calls.map(call => call[0].maxHeight), [1200, 1200, 1200]);
// 不借用整个有限父高，不依赖子节点个数固定；每次重新测量允许缩回单行。
for (const count of [1, 2, 3, 5, 8]) {
  const equalHeight = run(Array(count).fill(44));
  equal(equalHeight.result.height, 44);
  equal(equalHeight.calls[0][0].maxWidth, (320 - 8 * (count - 1)) / count);
  if (count > 1) {
    const width = equalHeight.calls[0][0].maxWidth;
    equal(equalHeight.positions[count - 1].x + width, 320);
  }
}
equal(run([90, 44]).result.height, 90);
equal(run([44, 44]).result.height, 44);
equal(run([]).result, { width: 320, height: 0 });
// 窄的非全宽双按钮通过构造属性约束，调用处不需要链式.width。
const limited = run([44, 44], { props: { widthLimit: 200 } });
equal(limited.result.width, 200);
equal(limited.calls.map(call => call[0].maxWidth), [96, 96]);
const threeNarrow = run([44, 65, 44], { constraint: { maxWidth: 280 } });
equal(threeNarrow.calls.map(call => call[0].maxWidth), [88, 88, 88]);
equal(threeNarrow.result.height, 65);
// 字段组合最小48；共同高度由内容决定，有限父高仍需服从。
equal(run([44, 44], { props: { rowMinHeight: 48 } }).result.height, 48);
equal(run([120, 44], { constraint: { maxHeight: 72 } }).result.height, 72);
equal(run([44, 44], { constraint: { minHeight: 64 } }).result.height, 64);
equal(run([44, 44], { constraint: { maxHeight: 20 } }).result.height, 20);
// 异常约束、缺省约束、超过总宽的间距不产生Infinity、NaN或负数位置。
equal(run([44, 44, 44], { constraint: { maxWidth: 8 } }).calls[0][0].maxWidth, 0);
equal(run([44, 44], { constraint: { maxWidth: Infinity } }).result.width, 0);
equal(run([44], { constraint: { maxWidth: NaN }, self: { width: 180, height: 0 } }).result.width, 0);
equal(run([44], { props: { widthLimit: 200 }, constraint: { maxWidth: Infinity } }).result.width, 200);
equal(run([44, 44], { props: { gap: Infinity } }).calls[0][0].maxWidth, 160);
equal(run([44, Infinity]).result.height, 44);
console.log(`Equal action row: ${checks} two-pass measurement and placement checks passed; native geometry remains a separate device check`);
