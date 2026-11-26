/**
 * 图片输入的前置校验与工作图计算。
 *
 * 契约见 docs/design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md §4.1/§4.3。
 * 本模块只做纯计算：格式识别、数量与体积边界、像素边界、工作图缩放。
 * 真正的识别在浏览器里由 Tesseract 完成，本模块不依赖任何 OCR 实现，便于在 Node 中验证。
 */

/** 首期支持的图片格式。 */
export type ImageFormat = 'png' | 'jpeg' | 'webp';

/** 图片输入限额（契约 §1.2）。 */
export const IMAGE_LIMITS = Object.freeze({
  /** 单张最大字节数。 */
  fileBytes: 10 * 1024 * 1024,
  /** 全部图片合计最大字节数。 */
  totalBytes: 30 * 1024 * 1024,
  /** 最多张数。 */
  fileCount: 5,
  /** 原图最大像素数。 */
  originalPixels: 24 * 1024 * 1024,
  /** 原图最长边上限。 */
  originalLongestSide: 8192,
  /** 工作图最大像素数。 */
  workingPixels: 6 * 1024 * 1024,
  /** 工作图最长边上限。 */
  workingLongestSide: 4096,
  /** 模型与静态资产加载超时（毫秒）。 */
  assetTimeoutMs: 60_000,
  /** 识别本身超时（毫秒），从 recognize 开始计。 */
  recognizeTimeoutMs: 120_000
});

/** 校验问题。 */
export interface ImageIssue {
  code: string;
  detail?: string;
}

/**
 * 按字节签名识别图片格式。
 *
 * 不能只看扩展名：伪装成 `.png` 的非图片必须被拒绝。
 *
 * @param bytes 文件前缀字节
 * @returns 识别出的格式；不支持或无法识别时为 `null`
 */
export function sniffImageFormat(bytes: Uint8Array): ImageFormat | null {
  if (bytes.length >= 8
    && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47
    && bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a) {
    return 'png';
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return 'jpeg';
  }
  // WebP：RIFF????WEBP
  if (bytes.length >= 12
    && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46
    && bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) {
    return 'webp';
  }
  return null;
}

/** 待校验的文件描述。 */
export interface ImageFileDescriptor {
  name: string;
  /** 浏览器给出的 MIME，仅作参考。 */
  type: string;
  bytes: number;
  /** 文件前缀字节，用于签名校验。 */
  header: Uint8Array;
  width: number;
  height: number;
}

/**
 * 校验单个文件。
 *
 * 实际 MIME/签名与解码结果共同校验：扩展名与声明类型都不能单独作为依据。
 *
 * @param file 文件描述
 * @returns 问题列表；为空表示可以进入识别
 */
export function validateImageFile(file: ImageFileDescriptor): ImageIssue[] {
  const issues: ImageIssue[] = [];
  const format = sniffImageFormat(file.header);
  if (!format) {
    // 伪装扩展名的情况会落到这里。
    issues.push({ code: 'WORKSHEET_IMAGE_UNSUPPORTED_FORMAT', detail: file.name });
    return issues;
  }
  // 声明类型与实际格式不符时以实际为准，但要提示。
  const declared = file.type.toLowerCase();
  const declaredOk = declared === `image/${format}` || (format === 'jpeg' && declared === 'image/jpg');
  if (declared && !declaredOk) {
    issues.push({ code: 'WORKSHEET_IMAGE_TYPE_MISMATCH', detail: `${declared} != image/${format}` });
  }
  if (file.bytes > IMAGE_LIMITS.fileBytes) {
    issues.push({ code: 'WORKSHEET_IMAGE_TOO_LARGE', detail: `bytes=${file.bytes} limit=${IMAGE_LIMITS.fileBytes}` });
  }
  const pixels = file.width * file.height;
  if (pixels > IMAGE_LIMITS.originalPixels) {
    issues.push({ code: 'WORKSHEET_IMAGE_TOO_MANY_PIXELS', detail: `pixels=${pixels} limit=${IMAGE_LIMITS.originalPixels}` });
  }
  const longest = Math.max(file.width, file.height);
  if (longest > IMAGE_LIMITS.originalLongestSide) {
    issues.push({ code: 'WORKSHEET_IMAGE_SIDE_TOO_LONG', detail: `side=${longest} limit=${IMAGE_LIMITS.originalLongestSide}` });
  }
  if (!file.width || !file.height) {
    issues.push({ code: 'WORKSHEET_IMAGE_DECODE_FAILED', detail: file.name });
  }
  return issues;
}

/**
 * 校验整批图片。
 *
 * 合计按原文件实际字节计；等于上限可以接受，超过才拒绝。
 *
 * @param files 已解码的文件描述
 * @returns 问题列表
 */
export function validateImageBatch(files: readonly ImageFileDescriptor[]): ImageIssue[] {
  const issues: ImageIssue[] = [];
  if (files.length > IMAGE_LIMITS.fileCount) {
    issues.push({ code: 'WORKSHEET_IMAGE_TOO_MANY_FILES', detail: `count=${files.length} limit=${IMAGE_LIMITS.fileCount}` });
  }
  const total = files.reduce((sum, file) => sum + file.bytes, 0);
  if (total > IMAGE_LIMITS.totalBytes) {
    issues.push({ code: 'WORKSHEET_IMAGE_TOTAL_TOO_LARGE', detail: `bytes=${total} limit=${IMAGE_LIMITS.totalBytes}` });
  }
  for (const file of files) issues.push(...validateImageFile(file));
  return issues;
}

/** 工作图尺寸。 */
export interface WorkingSize {
  width: number;
  height: number;
  /** 相对原图的缩放比例。 */
  scale: number;
  /** 是否发生了缩小。 */
  downscaled: boolean;
}

/**
 * 计算送入识别的工作图尺寸。
 *
 * 契约 §1.2：工作图缩小到不超过 6 MP 且最长边不超过 4096，**原图保留以便重选区域**。
 * 缩小只为了识别，不得用缩小后的尺寸掩盖原图超限（原图超限在上面已经拒绝）。
 *
 * @param width 原图宽
 * @param height 原图高
 * @returns 工作图尺寸
 */
export function planWorkingImage(width: number, height: number): WorkingSize {
  if (width <= 0 || height <= 0) return { width: 0, height: 0, scale: 0, downscaled: false };
  const bySide = IMAGE_LIMITS.workingLongestSide / Math.max(width, height);
  const byPixels = Math.sqrt(IMAGE_LIMITS.workingPixels / (width * height));
  const scale = Math.min(1, bySide, byPixels);
  if (scale >= 1) return { width, height, scale: 1, downscaled: false };
  return {
    // 至少保留 1 像素，避免极端比例被压成 0。
    width: Math.max(1, Math.floor(width * scale)),
    height: Math.max(1, Math.floor(height * scale)),
    scale,
    downscaled: true
  };
}

/**
 * 判断一次识别结果是否仍然适用。
 *
 * 契约 §4.1：开始识别时冻结 imageId/jobId/inputRevision；
 * 晚到结果按 requestId、会话有效性与编辑修订判废。
 *
 * @param started 识别开始时的冻结标识
 * @param current 当前状态
 * @returns 是否可以采用该结果
 */
export function isOcrResultCurrent(
  started: { jobId: string; imageId: string; inputRevision: number },
  current: { jobId: string | null; imageId: string | null; inputRevision: number; revisionChanged: boolean }
): boolean {
  if (current.jobId !== started.jobId) return false;
  if (current.imageId !== started.imageId) return false;
  // 用户已经改过原文：必须重新确认应用方式，不得自动覆盖。
  if (current.revisionChanged || current.inputRevision !== started.inputRevision) return false;
  return true;
}

/** 用户看到的旋转方向（顺时针角度）。 */
export type Orientation = 0 | 90 | 180 | 270;

/** 裁选区域，坐标以「已纠正方向后的显示图」像素为准。 */
export interface CropRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 原图像素尺寸。 */
export interface ImageSize {
  width: number;
  height: number;
}

/** 裁选问题。 */
export interface CropIssue {
  code: string;
  detail?: string;
}

/** 旋转后的显示尺寸：90/270 度时宽高互换。 */
export function displaySize(image: ImageSize, orientation: Orientation): ImageSize {
  return orientation === 90 || orientation === 270
    ? { width: image.height, height: image.width }
    : { width: image.width, height: image.height };
}

/**
 * 规范化裁选区域：裁剪到显示图范围内，并保证面积为正。
 *
 * 契约 §4.1：裁剪边界不得漏选或串列，因此越界一律夹紧而不是丢弃。
 *
 * @param crop 用户框选区域（显示图坐标）
 * @param image 原图尺寸
 * @param orientation 旋转方向
 * @returns 夹紧后的区域与问题
 */
export function normalizeCrop(
  crop: CropRect,
  image: ImageSize,
  orientation: Orientation = 0
): { crop: CropRect; issues: CropIssue[] } {
  const display = displaySize(image, orientation);
  const issues: CropIssue[] = [];
  const left = Math.max(0, Math.min(crop.x, crop.x + crop.width));
  const top = Math.max(0, Math.min(crop.y, crop.y + crop.height));
  const right = Math.min(display.width, Math.max(crop.x, crop.x + crop.width));
  const bottom = Math.min(display.height, Math.max(crop.y, crop.y + crop.height));
  const width = Math.max(0, right - left);
  const height = Math.max(0, bottom - top);
  if (crop.x < 0 || crop.y < 0 || right > display.width || bottom > display.height) {
    issues.push({ code: 'WORKSHEET_CROP_CLAMPED', detail: `${crop.x},${crop.y},${crop.width},${crop.height}` });
  }
  if (width < 1 || height < 1) {
    issues.push({ code: 'WORKSHEET_CROP_EMPTY' });
  }
  return { crop: { x: left, y: top, width, height }, issues };
}

/**
 * 把显示坐标的裁选区域映射回原图像素坐标。
 *
 * 必须先纠正方向再建立裁剪坐标，否则 90/270 度时会把行列搞反。
 *
 * @param crop 已规范化的显示坐标区域
 * @param image 原图尺寸
 * @param orientation 旋转方向
 * @returns 原图坐标系下的区域
 */
export function cropToOriginal(crop: CropRect, image: ImageSize, orientation: Orientation): CropRect {
  switch (orientation) {
    case 90:
      // 顺时针 90 度：显示左上角对应原图左下角。
      return { x: crop.y, y: image.height - (crop.x + crop.width), width: crop.height, height: crop.width };
    case 180:
      return { x: image.width - (crop.x + crop.width), y: image.height - (crop.y + crop.height), width: crop.width, height: crop.height };
    case 270:
      return { x: image.width - (crop.y + crop.height), y: crop.x, width: crop.height, height: crop.width };
    default:
      return { ...crop };
  }
}

/** 裁剪后的工作图计划。 */
export interface CropWorkingPlan {
  /** 送入识别的尺寸。 */
  width: number;
  height: number;
  scale: number;
  /** 在原图上的取样区域，用于 canvas drawImage。 */
  source: CropRect;
}

/**
 * 规划裁剪区域的工作图尺寸。
 *
 * 只对**所选区域**做缩小，原图本身不变，因此用户仍可重选区域。
 *
 * @param crop 已规范化的显示坐标区域
 * @param image 原图尺寸
 * @param orientation 旋转方向
 * @returns 工作图计划
 */
export function planCropWorkingImage(crop: CropRect, image: ImageSize, orientation: Orientation = 0): CropWorkingPlan {
  const source = cropToOriginal(crop, image, orientation);
  // 90/270 度时输出宽高是原取样的高宽。
  const rotated = orientation === 90 || orientation === 270
    ? { width: source.height, height: source.width }
    : { width: source.width, height: source.height };
  const working = planWorkingImage(rotated.width, rotated.height);
  return { width: working.width, height: working.height, scale: working.scale, source };
}

/** 判断一次识别结果是否仍对应当前裁选与图片。 */
export function isCropResultCurrent(
  started: { imageId: string; cropKey: string; orientation: Orientation },
  current: { imageId: string | null; cropKey: string | null; orientation: Orientation }
): boolean {
  return current.imageId === started.imageId
    && current.cropKey === started.cropKey
    && current.orientation === started.orientation;
}
