import type { OcrTextBox, OcrTextLine } from '../domain/worksheet-ocr-text';
import { IMAGE_LIMITS } from './image-preprocess';
import type { OcrInkMask, PreparedOcrInkImage } from './ocr-ink';

/** 验证工作图边界，避免无效尺寸或超限像素进入逐像素处理。 */
function validSize(width: number, height: number): boolean {
  return Number.isSafeInteger(width) && Number.isSafeInteger(height)
    && width > 0 && height > 0 && width * height <= IMAGE_LIMITS.workingPixels;
}

/** 只使用英文单词坐标；存在单词布局时不采用可能包含中文释义的整行框。 */
function englishBoxes(line: OcrTextLine): readonly OcrTextBox[] {
  if (!/^[A-Za-z]/u.test(line.text.trim()) || /\p{Script=Han}/u.test(line.text)) return [];
  if (!line.words?.length) return [line.bbox];
  return line.words.filter(word => /^[A-Za-z]/u.test(word.text.trim())
    && !/\p{Script=Han}/u.test(word.text)).map(word => word.bbox);
}

/** 将低亮度中文保留为黑色，同时去掉蓝青色印刷字和已定位的英文；不生成或补写文字。 */
export function createOcrMeaningMask(
  data: Uint8ClampedArray, width: number, height: number, englishLines: readonly OcrTextLine[]
): OcrInkMask | null {
  if (!validSize(width, height) || data.length !== width * height * 4) return null;
  const pixels = new Uint8Array(width * height);
  for (let index = 0; index < pixels.length; index += 1) {
    const offset = index * 4;
    const red = data[offset]!;
    const green = data[offset + 1]!;
    const blue = data[offset + 2]!;
    const alpha = data[offset + 3]! / 255;
    const colored = Math.min(green, blue) - red > 18 && Math.max(green, blue) - red > 30;
    // 透明像素先合成到白纸，避免透明底的 RGB 黑色成为误识别内容。
    const luminance = (.299 * red + .587 * green + .114 * blue) * alpha + 255 * (1 - alpha);
    pixels[index] = !colored && luminance < 110 ? 1 : 0;
  }
  for (const line of englishLines) {
    for (const box of englishBoxes(line)) {
      if (![box.x0, box.y0, box.x1, box.y1].every(Number.isFinite)
        || box.x1 <= box.x0 || box.y1 <= box.y0) continue;
      // 圈线余量随字高变化，并限制为 1600 像素尺度下最多 10 像素，避免侵入右侧释义。
      const padding = Math.min((box.y1 - box.y0) * .12, Math.max(width, height) / 160);
      const left = Math.max(0, Math.floor(box.x0 - padding));
      const top = Math.max(0, Math.floor(box.y0 - padding));
      const right = Math.min(width, Math.ceil(box.x1 + padding));
      const bottom = Math.min(height, Math.ceil(box.y1 + padding));
      if (left >= right || top >= bottom) continue;
      for (let y = top; y < bottom; y += 1) pixels.fill(0, y * width + left, y * width + right);
    }
  }
  return { width, height, pixels };
}

/** 让取消事件在解码、像素转换和编码之间有机会被处理。 */
async function yieldToBrowser(): Promise<void> { await new Promise<void>(resolve => setTimeout(resolve, 0)); }

/** 为中文释义生成独立识别图；保留原坐标系，仅做同比缩放并释放全部临时画布。 */
export async function prepareOcrMeaningImage(
  image: Blob, englishLines: readonly OcrTextLine[], cancelled: () => boolean
): Promise<PreparedOcrInkImage | null> {
  if (cancelled()) return null;
  let bitmap: ImageBitmap | null = null;
  const canvas = document.createElement('canvas');
  const output = document.createElement('canvas');
  try {
    bitmap = await createImageBitmap(image);
    if (cancelled() || !validSize(bitmap.width, bitmap.height)) return null;
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d');
    const outputContext = output.getContext('2d');
    if (!context || !outputContext) return null;
    context.drawImage(bitmap, 0, 0);
    bitmap.close();
    bitmap = null;
    await yieldToBrowser();
    if (cancelled()) return null;
    const data = context.getImageData(0, 0, canvas.width, canvas.height);
    const mask = createOcrMeaningMask(data.data, canvas.width, canvas.height, englishLines);
    if (!mask || cancelled()) return null;
    for (let index = 0; index < mask.pixels.length; index += 1) {
      const offset = index * 4;
      data.data[offset] = data.data[offset + 1] = data.data[offset + 2] = mask.pixels[index] ? 0 : 255;
      data.data[offset + 3] = 255;
    }
    context.putImageData(data, 0, 0);
    const scale = Math.min(1, 2048 / Math.max(mask.width, mask.height));
    output.width = Math.max(1, Math.round(mask.width * scale));
    output.height = Math.max(1, Math.round(mask.height * scale));
    outputContext.imageSmoothingQuality = 'high';
    outputContext.drawImage(canvas, 0, 0, output.width, output.height);
    await yieldToBrowser();
    if (cancelled()) return null;
    const blob = await new Promise<Blob | null>(resolve => output.toBlob(resolve, 'image/png'));
    return blob && !cancelled()
      ? { image: blob, scaleX: output.width / mask.width, scaleY: output.height / mask.height } : null;
  } finally {
    bitmap?.close();
    canvas.width = canvas.height = output.width = output.height = 0;
  }
}
