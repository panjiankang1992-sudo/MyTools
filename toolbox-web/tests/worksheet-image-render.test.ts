import assert from 'node:assert/strict';
import test from 'node:test';
import { drawWorksheetImage, imageRenderPlan } from '../src/lib/worksheet-image-render';
import type { CropRect, Orientation } from '../src/lib/image-preprocess';

/** 将实际绘制调用投影到离散标记像素，能发现宽高未交换和旋转遗漏。 */
function render(orientation: Orientation, crop: CropRect | null): string[] {
  const source = ['ABC', 'DEF'];
  const size = { width: 3, height: 2 };
  const output = imageRenderPlan(size, orientation, crop);
  const pixels = Array.from({ length: output.height }, () => Array<string>(output.width).fill('.'));
  let translateX = 0;
  let translateY = 0;
  let angle = 0;
  const context = {
    save() {}, restore() {},
    translate(x: number, y: number) { translateX += x; translateY += y; },
    rotate(value: number) { angle += value; },
    drawImage(_image: unknown, sx: number, sy: number, sw: number, sh: number, dx: number, dy: number, dw: number, dh: number) {
      for (let y = 0; y < sh; y += 1) {
        for (let x = 0; x < sw; x += 1) {
          const localX = dx + (x + 0.5) * dw / sw;
          const localY = dy + (y + 0.5) * dh / sh;
          const targetX = Math.floor(translateX + localX * Math.cos(angle) - localY * Math.sin(angle));
          const targetY = Math.floor(translateY + localX * Math.sin(angle) + localY * Math.cos(angle));
          if (pixels[targetY]?.[targetX] !== undefined) pixels[targetY]![targetX] = source[sy + y]![sx + x]!;
        }
      }
    }
  } as unknown as CanvasRenderingContext2D;
  drawWorksheetImage(context, {} as CanvasImageSource, size, orientation, crop, output);
  return pixels.map(row => row.join(''));
}

for (const [orientation, expected] of [
  [0, ['ABC', 'DEF']],
  [90, ['DA', 'EB', 'FC']],
  [180, ['FED', 'CBA']],
  [270, ['CF', 'BE', 'AD']]
] as const) {
  test(`方向标记图 ${orientation} 度：预览与 OCR 共用绘制函数保持所有像素和方向`, () => {
    assert.deepEqual(render(orientation, null), expected);
  });
  test(`方向标记图 ${orientation} 度：按旋转后左侧两行框选，不漏选或串列`, () => {
    assert.deepEqual(render(orientation, { x: 0, y: 0, width: 1, height: 2 }), expected.slice(0, 2).map(row => row[0]));
  });
}

test('非正方形大图旋转与裁剪后仍遵守工作图像素限制', () => {
  const plan = imageRenderPlan({ width: 8000, height: 3000 }, 90, { x: 0, y: 0, width: 3000, height: 8000 });
  assert.ok(plan.width < plan.height);
  assert.ok(plan.height <= 4096);
  assert.ok(plan.width * plan.height <= 6 * 1024 * 1024);
});
