import assert from 'node:assert/strict';
import test from 'node:test';
import { createOcrMeaningMask, prepareOcrMeaningImage } from '../src/lib/ocr-meaning-image';
import type { OcrTextLine } from '../src/domain/worksheet-ocr-text';

/** 构造白纸和指定像素，便于断言两种文字的区域互不干扰。 */
function fixture(width: number, height: number, entries: readonly [number, number, number[]][]) {
  const pixels = new Uint8ClampedArray(width * height * 4).fill(255);
  for (const [x, y, rgba] of entries) pixels.set(rgba, (y * width + x) * 4);
  return pixels;
}

test('独立中文图移除蓝字及英文框，保留右侧中文和下一行中文', () => {
  const pixels = fixture(160, 160, [
    [15, 30, [0, 0, 0, 255]], [70, 30, [0, 0, 0, 255]],
    [20, 65, [0, 0, 0, 255]], [10, 100, [15, 90, 125, 255]]
  ]);
  const lines: OcrTextLine[] = [{ text: 'today', bbox: { x0: 10, y0: 20, x1: 50, y1: 45 } }];
  const mask = createOcrMeaningMask(pixels, 160, 160, lines)!;
  assert.equal(mask.pixels[30 * 160 + 15], 0);
  assert.equal(mask.pixels[100 * 160 + 10], 0);
  assert.equal(mask.pixels[30 * 160 + 70], 1);
  assert.equal(mask.pixels[65 * 160 + 20], 1);
  assert.equal(pixels[(30 * 160 + 15) * 4 + 3], 255, '原图不得被改写');
});

test('黑白图保留深色文字，透明黑底不变成黑色内容', () => {
  const pixels = fixture(10, 10, [
    [1, 1, [0, 0, 0, 255]], [2, 1, [80, 80, 80, 255]],
    [3, 1, [130, 130, 130, 255]], [4, 1, [0, 0, 0, 0]],
    [5, 1, [0, 0, 0, 128]]
  ]);
  const mask = createOcrMeaningMask(pixels, 10, 10, [])!;
  assert.deepEqual([...mask.pixels.slice(11, 16)], [1, 1, 0, 0, 0]);
});

test('单词坐标避免整行误擦中文，中文行不会被当作英文擦掉', () => {
  const pixels = fixture(160, 100, [[15, 30, [0, 0, 0, 255]], [95, 30, [0, 0, 0, 255]]]);
  const lines: OcrTextLine[] = [{ text: 'Monday', bbox: { x0: 10, y0: 20, x1: 150, y1: 40 }, words: [
    { text: 'Monday', bbox: { x0: 10, y0: 20, x1: 60, y1: 40 } },
    { text: '星期一', bbox: { x0: 90, y0: 20, x1: 150, y1: 40 } }
  ] }, { text: '星期一', bbox: { x0: 90, y0: 20, x1: 150, y1: 40 } }];
  const mask = createOcrMeaningMask(pixels, 160, 100, lines)!;
  assert.equal(mask.pixels[30 * 160 + 15], 0);
  assert.equal(mask.pixels[30 * 160 + 95], 1);
});

test('越界英文框裁到图片边界，无效框及超限图片安全退出', () => {
  const pixels = fixture(20, 20, [[0, 0, [0, 0, 0, 255]], [19, 19, [0, 0, 0, 255]]]);
  const lines: OcrTextLine[] = [
    { text: 'left', bbox: { x0: -10, y0: -10, x1: 2, y1: 2 } },
    { text: 'outside', bbox: { x0: 80, y0: 80, x1: 90, y1: 90 } },
    { text: 'invalid', bbox: { x0: NaN, y0: 0, x1: 20, y1: 20 } },
    { text: 'reverse', bbox: { x0: 20, y0: 20, x1: 0, y1: 0 } }
  ];
  const mask = createOcrMeaningMask(pixels, 20, 20, lines)!;
  assert.equal(mask.pixels[0], 0);
  assert.equal(mask.pixels[399], 1);
  assert.equal(createOcrMeaningMask(pixels, 0, 20, []), null);
  assert.equal(createOcrMeaningMask(pixels, 20.5, 20, []), null);
  assert.equal(createOcrMeaningMask(pixels, 20, 19, []), null);
  assert.equal(createOcrMeaningMask(pixels, 8192, 8192, []), null);
});

test('预先取消时不触发 DOM 创建或图片解码', async () => {
  assert.equal(await prepareOcrMeaningImage(new Blob(), [], () => true), null);
});

test('缩放映射、解码超限和中途取消都释放位图及临时画布', async () => {
  const documentDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'document');
  const bitmapDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'createImageBitmap');
  let sourceWidth = 4000;
  let sourceHeight = 1200;
  let cancelled = false;
  let cancelDuringDecode = false;
  let cancelDuringEncode = false;
  let closed = 0;
  let encodedSize: [number, number] | null = null;
  const canvases: { width: number; height: number }[] = [];
  const createCanvas = () => {
    const canvas = {
      width: 0, height: 0,
      getContext: () => ({
        imageSmoothingQuality: 'low', drawImage: () => undefined, putImageData: () => undefined,
        getImageData: () => ({ data: new Uint8ClampedArray(canvas.width * canvas.height * 4).fill(255) })
      }),
      toBlob: (callback: (value: Blob) => void) => {
        encodedSize = [canvas.width, canvas.height];
        if (cancelDuringEncode) cancelled = true;
        callback(new Blob(['encoded'], { type: 'image/png' }));
      }
    };
    canvases.push(canvas);
    return canvas;
  };
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { createElement: createCanvas } });
  Object.defineProperty(globalThis, 'createImageBitmap', { configurable: true, value: async () => {
    if (cancelDuringDecode) cancelled = true;
    return { width: sourceWidth, height: sourceHeight, close: () => { closed += 1; } };
  } });
  try {
    const prepared = await prepareOcrMeaningImage(new Blob(), [], () => cancelled);
    assert.ok(prepared);
    assert.deepEqual(encodedSize, [2048, 614]);
    assert.equal(prepared.scaleX, 2048 / 4000);
    assert.equal(prepared.scaleY, 614 / 1200);
    assert.equal(closed, 1);
    assert.ok(canvases.every(canvas => canvas.width === 0 && canvas.height === 0));

    sourceWidth = sourceHeight = 8192;
    assert.equal(await prepareOcrMeaningImage(new Blob(), [], () => cancelled), null);
    assert.equal(closed, 2);
    assert.ok(canvases.every(canvas => canvas.width === 0 && canvas.height === 0));

    sourceWidth = sourceHeight = 100;
    cancelDuringDecode = true;
    assert.equal(await prepareOcrMeaningImage(new Blob(), [], () => cancelled), null);
    assert.equal(closed, 3);
    assert.ok(canvases.every(canvas => canvas.width === 0 && canvas.height === 0));

    cancelDuringDecode = false;
    cancelDuringEncode = true;
    cancelled = false;
    assert.equal(await prepareOcrMeaningImage(new Blob(), [], () => cancelled), null);
    assert.equal(closed, 4);
    assert.ok(canvases.every(canvas => canvas.width === 0 && canvas.height === 0));
  } finally {
    if (documentDescriptor) Object.defineProperty(globalThis, 'document', documentDescriptor);
    else Reflect.deleteProperty(globalThis, 'document');
    if (bitmapDescriptor) Object.defineProperty(globalThis, 'createImageBitmap', bitmapDescriptor);
    else Reflect.deleteProperty(globalThis, 'createImageBitmap');
  }
});
