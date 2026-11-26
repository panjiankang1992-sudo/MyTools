import { IMAGE_LIMITS } from './image-preprocess';

/** 彩色印刷文字掩码；黑色手写圈线不会成为增强输入。 */
export interface OcrInkMask { width: number; height: number; pixels: Uint8Array }

/** 从蓝青色印刷文字生成掩码；黑白图和大面积彩色背景回到原图识别。 */
export function createOcrInkMask(data: Uint8ClampedArray, width: number, height: number): OcrInkMask | null {
  const count = width * height;
  if (!Number.isSafeInteger(count) || count < 1 || count > IMAGE_LIMITS.workingPixels || data.length !== count * 4) return null;
  const pixels = new Uint8Array(count);
  let ink = 0;
  for (let index = 0; index < count; index += 1) {
    const offset = index * 4;
    const red = data[offset]!;
    const green = data[offset + 1]!;
    const blue = data[offset + 2]!;
    // 使用相对色差保留阴影中的蓝字，透明区域与灰黑色圈线均排除。
    if (data[offset + 3]! >= 128 && Math.min(green, blue) - red > 18 && Math.max(green, blue) - red > 30 && Math.max(green, blue) > 45) {
      pixels[index] = 1;
      ink += 1;
    }
  }
  return ink >= 80 && ink / count >= 0.0002 && ink / count <= 0.18 ? { width, height, pixels } : null;
}

/** 修补纵向短断笔，等价于垂直闭运算；不扩张字形外边界。 */
export function closeOcrInkGaps(mask: OcrInkMask, radius: number): Uint8Array {
  const output = mask.pixels.slice();
  const gapLimit = Math.max(0, Math.min(24, Math.round(radius))) * 2;
  if (!gapLimit) return output;
  for (let x = 0; x < mask.width; x += 1) {
    let previous = -1;
    for (let y = 0; y < mask.height; y += 1) {
      if (!mask.pixels[y * mask.width + x]) continue;
      // 只连接两端都有墨迹的短间隔，不能在空白区凭空补字。
      if (previous >= 0 && y - previous - 1 <= gapLimit) {
        for (let fill = previous + 1; fill < y; fill += 1) output[fill * mask.width + x] = 1;
      }
      previous = y;
    }
  }
  return output;
}

/** 增强图仍与原图共用方向和裁剪坐标，仅缩放以规范过大的字高。 */
export interface PreparedOcrInkImage { image: Blob; scaleX: number; scaleY: number }

/** 给取消、绘制与进度更新一次事件循环机会。 */
async function yieldToBrowser(): Promise<void> { await new Promise<void>(resolve => setTimeout(resolve, 0)); }

/** 按顺序生成原掩码和两种有限断笔修补图，不将增强图持久化。 */
export async function prepareOcrInkImages(image: Blob, cancelled: () => boolean): Promise<PreparedOcrInkImage[]> {
  let bitmap: ImageBitmap | null = null;
  const canvas = document.createElement('canvas');
  const output = document.createElement('canvas');
  try {
    bitmap = await createImageBitmap(image);
    if (cancelled()) return [];
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    if (canvas.width * canvas.height > IMAGE_LIMITS.workingPixels) return [];
    const context = canvas.getContext('2d');
    const outputContext = output.getContext('2d');
    if (!context || !outputContext) return [];
    context.drawImage(bitmap, 0, 0);
    bitmap.close();
    bitmap = null;
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
    const mask = createOcrInkMask(pixels.data, canvas.width, canvas.height);
    if (!mask) return [];
    const longest = Math.max(mask.width, mask.height);
    const scale = Math.min(1, 1600 / longest);
    output.width = Math.max(1, Math.round(mask.width * scale));
    output.height = Math.max(1, Math.round(mask.height * scale));
    outputContext.imageSmoothingQuality = 'high';
    const results: PreparedOcrInkImage[] = [];
    const radii = [...new Set([0, Math.max(1, Math.round(longest * 0.00175)), Math.max(1, Math.round(longest * 0.003))])];
    for (const radius of radii) {
      await yieldToBrowser();
      if (cancelled()) return [];
      const closed = radius ? closeOcrInkGaps(mask, radius) : mask.pixels;
      for (let index = 0; index < closed.length; index += 1) {
        const offset = index * 4;
        pixels.data[offset] = pixels.data[offset + 1] = pixels.data[offset + 2] = closed[index] ? 0 : 255;
        pixels.data[offset + 3] = 255;
      }
      context.putImageData(pixels, 0, 0);
      outputContext.drawImage(canvas, 0, 0, output.width, output.height);
      const blob = await new Promise<Blob | null>(resolve => output.toBlob(resolve, 'image/png'));
      if (blob) results.push({ image: blob, scaleX: output.width / mask.width, scaleY: output.height / mask.height });
    }
    return cancelled() ? [] : results;
  } finally {
    bitmap?.close();
    canvas.width = canvas.height = output.width = output.height = 0;
  }
}
