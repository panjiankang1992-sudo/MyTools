import assert from 'node:assert/strict';
import test from 'node:test';
import { closeOcrInkGaps, createOcrInkMask } from '../src/lib/ocr-ink';

test('蓝字与黑色圈线分开，透明底、黑白图及大面积彩色背景不触发增强', () => {
  const pixels = new Uint8ClampedArray(100 * 100 * 4).fill(255);
  for (let index = 0; index < 200; index += 1) pixels.set([55, 132, 166, 255], index * 4);
  pixels.set([20, 20, 20, 255], 500 * 4);
  pixels.set([55, 132, 166, 0], 501 * 4);
  const mask = createOcrInkMask(pixels, 100, 100)!;
  assert.equal(mask.pixels.reduce((sum, value) => sum + value, 0), 200);
  assert.equal(mask.pixels[500], 0);
  assert.equal(mask.pixels[501], 0);
  assert.equal(createOcrInkMask(new Uint8ClampedArray(40000).fill(255), 100, 100), null);
  for (let index = 0; index < 10000; index += 1) pixels.set([55, 132, 166, 255], index * 4);
  assert.equal(createOcrInkMask(pixels, 100, 100), null);
  assert.equal(createOcrInkMask(pixels, 100, 99), null);
});

test('断笔修补只填两段墨迹之间的短竖直空隙，不增加外围或跨列笔画', () => {
  const pixels = new Uint8Array(3 * 12);
  for (const y of [2, 5, 10]) pixels[y * 3 + 1] = 1;
  const output = closeOcrInkGaps({ width: 3, height: 12, pixels }, 1);
  assert.deepEqual([...output].map((value, index) => value ? index : -1).filter(index => index >= 0), [7, 10, 13, 16, 31]);
  assert.equal(pixels[10], 0, '原掩码必须保留，较强修补不能污染较轻版本');
});
