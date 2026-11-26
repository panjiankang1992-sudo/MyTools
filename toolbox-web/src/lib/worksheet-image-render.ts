import { displaySize, normalizeCrop, planCropWorkingImage, type CropRect, type ImageSize, type Orientation } from './image-preprocess';

/** 预览和 OCR 共用的有方向裁剪计划；裁剪坐标始终属于旋转后的显示图。 */
export function imageRenderPlan(size: ImageSize, orientation: Orientation, crop: CropRect | null) {
  const selected = normalizeCrop(crop ?? { x: 0, y: 0, ...displaySize(size, orientation) }, size, orientation).crop;
  if (selected.width < 1 || selected.height < 1) throw new Error('WORKSHEET_CROP_EMPTY');
  return planCropWorkingImage(selected, size, orientation);
}

/** 将原图采样区域旋转后绘入目标画布，预览与 OCR 不再各自定义旋转语义。 */
export function drawWorksheetImage(
  context: CanvasRenderingContext2D,
  image: CanvasImageSource,
  size: ImageSize,
  orientation: Orientation,
  crop: CropRect | null,
  output: ImageSize
): void {
  const { source } = imageRenderPlan(size, orientation, crop);
  const sideways = orientation === 90 || orientation === 270;
  const width = sideways ? output.height : output.width;
  const height = sideways ? output.width : output.height;
  context.save();
  context.translate(output.width / 2, output.height / 2);
  context.rotate(orientation * Math.PI / 180);
  context.drawImage(image, source.x, source.y, source.width, source.height, -width / 2, -height / 2, width, height);
  context.restore();
}

/** 为单张图片生成已转正、已裁剪的识别输入；不会读取其他图片的预览位图。 */
export async function encodeWorksheetImage(bitmap: ImageBitmap, orientation: Orientation, crop: CropRect | null): Promise<Blob> {
  const plan = imageRenderPlan(bitmap, orientation, crop);
  const canvas = document.createElement('canvas');
  canvas.width = plan.width;
  canvas.height = plan.height;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('WORKSHEET_IMAGE_ENCODE_FAILED');
  context.imageSmoothingQuality = 'high';
  drawWorksheetImage(context, bitmap, bitmap, orientation, crop, plan);
  try {
    return await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('WORKSHEET_IMAGE_ENCODE_FAILED')), 'image/png');
    });
  } finally {
    // 编码结束后释放工作画布的像素存储，原文件仍可再次框选。
    canvas.width = 0;
    canvas.height = 0;
  }
}
