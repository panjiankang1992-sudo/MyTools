import assert from 'node:assert/strict';
import test from 'node:test';
import {
  IMAGE_LIMITS, cropToOriginal, displaySize, isCropResultCurrent, isOcrResultCurrent,
  normalizeCrop, planCropWorkingImage, planWorkingImage, sniffImageFormat,
  validateImageBatch, validateImageFile, type ImageFileDescriptor
} from '../src/lib/image-preprocess';

/** 构造带真实签名的文件描述。 */
function file(overrides: Partial<ImageFileDescriptor> & { format?: 'png' | 'jpeg' | 'webp' | 'gif' } = {}): ImageFileDescriptor {
  const { format = 'png', ...rest } = overrides;
  const headers: Record<string, number[]> = {
    png: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a],
    jpeg: [0xff, 0xd8, 0xff, 0xe0],
    webp: [0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50],
    gif: [0x47, 0x49, 0x46, 0x38, 0x39, 0x61]
  };
  return {
    name: 'a.png', type: format === 'jpeg' ? 'image/jpeg' : `image/${format}`,
    bytes: 1024, header: new Uint8Array(headers[format]!), width: 800, height: 600, ...rest
  };
}

test('按字节签名识别三种支持的格式', () => {
  assert.equal(sniffImageFormat(file({ format: 'png' }).header), 'png');
  assert.equal(sniffImageFormat(file({ format: 'jpeg' }).header), 'jpeg');
  assert.equal(sniffImageFormat(file({ format: 'webp' }).header), 'webp');
  assert.equal(sniffImageFormat(file({ format: 'gif' }).header), null);
  assert.equal(sniffImageFormat(new Uint8Array([1, 2, 3])), null);
});

test('伪装成 .png 的非图片被拒绝，不只看扩展名', () => {
  const fake = file({ format: 'gif', name: 'fake.png', type: 'image/png' });
  const issues = validateImageFile(fake);
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMAGE_UNSUPPORTED_FORMAT'));
});

test('声明类型与实际格式不一致时提示，但以实际格式为准', () => {
  const issues = validateImageFile(file({ format: 'png', type: 'image/jpeg' }));
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMAGE_TYPE_MISMATCH'));
  assert.ok(!issues.some(issue => issue.code === 'WORKSHEET_IMAGE_UNSUPPORTED_FORMAT'));
});

test('单张体积超过 10 MiB 被拒绝，等于上限可以接受', () => {
  assert.deepEqual(validateImageFile(file({ bytes: IMAGE_LIMITS.fileBytes })), []);
  const over = validateImageFile(file({ bytes: IMAGE_LIMITS.fileBytes + 1 }));
  assert.ok(over.some(issue => issue.code === 'WORKSHEET_IMAGE_TOO_LARGE'));
});

test('原图像素与最长边在送入识别前就被拒绝', () => {
  const manyPixels = validateImageFile(file({ width: 6000, height: 5000 })); // 30 MP
  assert.ok(manyPixels.some(issue => issue.code === 'WORKSHEET_IMAGE_TOO_MANY_PIXELS'));
  const longSide = validateImageFile(file({ width: 9000, height: 100 }));
  assert.ok(longSide.some(issue => issue.code === 'WORKSHEET_IMAGE_SIDE_TOO_LONG'));
  // 恰好等于上限应通过。
  assert.deepEqual(validateImageFile(file({ width: 8192, height: 100 })), []);
});

test('无法解码的图片被拒绝', () => {
  const issues = validateImageFile(file({ width: 0, height: 0 }));
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMAGE_DECODE_FAILED'));
});

test('张数与合计字节边界：等于上限通过，超过拒绝', () => {
  const five = Array.from({ length: 5 }, () => file({ bytes: 1024 }));
  assert.deepEqual(validateImageBatch(five), []);
  const six = Array.from({ length: 6 }, () => file({ bytes: 1024 }));
  assert.ok(validateImageBatch(six).some(issue => issue.code === 'WORKSHEET_IMAGE_TOO_MANY_FILES'));

  const exact = Array.from({ length: 3 }, () => file({ bytes: IMAGE_LIMITS.totalBytes / 3 }));
  assert.ok(!validateImageBatch(exact).some(issue => issue.code === 'WORKSHEET_IMAGE_TOTAL_TOO_LARGE'));
  const over = Array.from({ length: 4 }, () => file({ bytes: IMAGE_LIMITS.totalBytes / 3 }));
  assert.ok(validateImageBatch(over).some(issue => issue.code === 'WORKSHEET_IMAGE_TOTAL_TOO_LARGE'));
});

test('工作图缩到 6 MP 与 4096 边之内，且保持比例', () => {
  const big = planWorkingImage(8000, 6000); // 48 MP
  assert.equal(big.downscaled, true);
  assert.ok(big.width <= IMAGE_LIMITS.workingLongestSide);
  assert.ok(big.height <= IMAGE_LIMITS.workingLongestSide);
  assert.ok(big.width * big.height <= IMAGE_LIMITS.workingPixels);
  // 比例必须保持（允许取整误差）。
  assert.ok(Math.abs((big.width / big.height) - (8000 / 6000)) < 0.01);
});

test('小于上限的图片不缩小，原图不被替换', () => {
  const small = planWorkingImage(1200, 800);
  assert.deepEqual(small, { width: 1200, height: 800, scale: 1, downscaled: false });
});

test('极端长边按最长边约束缩小，不产生 0 像素', () => {
  const wide = planWorkingImage(20000, 3);
  assert.ok(wide.width <= IMAGE_LIMITS.workingLongestSide);
  assert.ok(wide.height >= 1);
  assert.deepEqual(planWorkingImage(0, 0), { width: 0, height: 0, scale: 0, downscaled: false });
});

test('超时预算：资产 60 秒与识别 120 秒分开计', () => {
  assert.equal(IMAGE_LIMITS.assetTimeoutMs, 60_000);
  assert.equal(IMAGE_LIMITS.recognizeTimeoutMs, 120_000);
});

test('晚到结果按 jobId、imageId 与修订判废', () => {
  const started = { jobId: 'j1', imageId: 'i1', inputRevision: 3 };
  assert.equal(isOcrResultCurrent(started, { jobId: 'j1', imageId: 'i1', inputRevision: 3, revisionChanged: false }), true);
  // 用户已经改过原文：不得自动覆盖，必须重新确认。
  assert.equal(isOcrResultCurrent(started, { jobId: 'j1', imageId: 'i1', inputRevision: 3, revisionChanged: true }), false);
  assert.equal(isOcrResultCurrent(started, { jobId: 'j1', imageId: 'i1', inputRevision: 4, revisionChanged: false }), false);
  // 换了图片或已经取消/删除会话。
  assert.equal(isOcrResultCurrent(started, { jobId: 'j1', imageId: 'i2', inputRevision: 3, revisionChanged: false }), false);
  assert.equal(isOcrResultCurrent(started, { jobId: null, imageId: 'i1', inputRevision: 3, revisionChanged: false }), false);
});

// ---------------------------------------------------------------- 裁选与方向
test('旋转 90/270 度时显示尺寸宽高互换', () => {
  assert.deepEqual(displaySize({ width: 800, height: 600 }, 0), { width: 800, height: 600 });
  assert.deepEqual(displaySize({ width: 800, height: 600 }, 90), { width: 600, height: 800 });
  assert.deepEqual(displaySize({ width: 800, height: 600 }, 180), { width: 800, height: 600 });
  assert.deepEqual(displaySize({ width: 800, height: 600 }, 270), { width: 600, height: 800 });
});

test('裁选越界时夹紧到画面内，不静默丢弃', () => {
  const result = normalizeCrop({ x: -20, y: -10, width: 200, height: 200 }, { width: 100, height: 100 }, 0);
  assert.deepEqual(result.crop, { x: 0, y: 0, width: 100, height: 100 });
  assert.ok(result.issues.some(issue => issue.code === 'WORKSHEET_CROP_CLAMPED'));
});

test('反向框选（宽高为负）也能得到正确区域', () => {
  // 用户从右下往左上拖拽时会出现负宽高。
  const result = normalizeCrop({ x: 80, y: 80, width: -60, height: -60 }, { width: 200, height: 200 }, 0);
  assert.deepEqual(result.crop, { x: 20, y: 20, width: 60, height: 60 });
  assert.equal(result.issues.length, 0);
});

test('空裁选区被明确标记而不是当成有效区域', () => {
  const result = normalizeCrop({ x: 10, y: 10, width: 0, height: 0 }, { width: 200, height: 200 }, 0);
  assert.ok(result.issues.some(issue => issue.code === 'WORKSHEET_CROP_EMPTY'));
});

test('裁选坐标映射回原图：0 度与 180 度', () => {
  const image = { width: 800, height: 600 };
  const crop = { x: 100, y: 50, width: 200, height: 100 };
  assert.deepEqual(cropToOriginal(crop, image, 0), crop);
  assert.deepEqual(cropToOriginal(crop, image, 180), { x: 500, y: 450, width: 200, height: 100 });
});

test('裁选坐标映射回原图：90 度与 270 度必须交换行列', () => {
  const image = { width: 800, height: 600 };
  // 显示图尺寸为 600×800；取显示图左上角 100×100。
  const crop = { x: 0, y: 0, width: 100, height: 100 };
  const at90 = cropToOriginal(crop, image, 90);
  // 顺时针 90 度后，显示左上角来自原图左下角。
  assert.deepEqual(at90, { x: 0, y: 500, width: 100, height: 100 });
  const at270 = cropToOriginal(crop, image, 270);
  assert.deepEqual(at270, { x: 700, y: 0, width: 100, height: 100 });
  // 映射结果必须落在原图范围内。
  for (const rect of [at90, at270]) {
    assert.ok(rect.x >= 0 && rect.y >= 0);
    assert.ok(rect.x + rect.width <= image.width && rect.y + rect.height <= image.height);
  }
});

test('工作图只按裁选区域缩小，原图保留可重选', () => {
  const image = { width: 8000, height: 6000 };
  // 只选一小块：不应因为原图很大就缩小这块。
  const small = planCropWorkingImage({ x: 100, y: 100, width: 400, height: 300 }, image, 0);
  assert.equal(small.scale, 1, '小块不需要缩小');
  assert.deepEqual({ width: small.width, height: small.height }, { width: 400, height: 300 });
  // 选满整张：必须缩到 6 MP 与 4096 边内。
  const full = planCropWorkingImage({ x: 0, y: 0, width: 8000, height: 6000 }, image, 0);
  assert.ok(full.width * full.height <= IMAGE_LIMITS.workingPixels);
  assert.ok(Math.max(full.width, full.height) <= IMAGE_LIMITS.workingLongestSide);
  assert.equal(full.source.width, 8000, '取样区域仍是原图，不被缩小图替换');
});

test('旋转后裁选的工作图尺寸与取样区域一致', () => {
  const image = { width: 800, height: 600 };
  const plan = planCropWorkingImage({ x: 0, y: 0, width: 200, height: 100 }, image, 90);
  // 90 度下取样的宽高互换后作为输出尺寸。
  assert.equal(plan.source.width, 100);
  assert.equal(plan.source.height, 200);
  assert.equal(plan.width, 200);
  assert.equal(plan.height, 100);
});

test('换图片、改裁选或改方向都会使识别结果作废', () => {
  const started = { imageId: 'i1', cropKey: '0,0,100,100@0', orientation: 0 as const };
  assert.equal(isCropResultCurrent(started, { imageId: 'i1', cropKey: '0,0,100,100@0', orientation: 0 }), true);
  assert.equal(isCropResultCurrent(started, { imageId: 'i2', cropKey: '0,0,100,100@0', orientation: 0 }), false, '换图必须作废');
  assert.equal(isCropResultCurrent(started, { imageId: 'i1', cropKey: '10,10,50,50@0', orientation: 0 }), false, '改裁选必须作废');
  assert.equal(isCropResultCurrent(started, { imageId: 'i1', cropKey: '0,0,100,100@0', orientation: 90 }), false, '改方向必须作废');
  assert.equal(isCropResultCurrent(started, { imageId: null, cropKey: null, orientation: 0 }), false, '已删除图片必须作废');
});
