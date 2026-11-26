/** 底稿允许常见大照片，传输前按模型需求缩放，不修改原文件。 */
export class ImageReferencePolicy {
  static readonly MAX_SOURCE_BYTES: number = 64 * 1024 * 1024;
  static readonly MAX_UPLOAD_BYTES: number = 5 * 1024 * 1024;
  /** 拒绝空文件或异常大文件，避免移动端内存耗尽。 */
  static validSource(bytes: number): boolean {
    return Number.isSafeInteger(bytes) && bytes > 0 && bytes <= ImageReferencePolicy.MAX_SOURCE_BYTES;
  }
  /** 保留可直接上传的底稿，不因超过旧的一兆限制重复编码。 */
  static passthrough(bytes: number, width: number, height: number): boolean {
    return ImageReferencePolicy.validSource(bytes) && bytes <= ImageReferencePolicy.MAX_UPLOAD_BYTES &&
      ImageReferencePolicy.validDimensions(width, height) && width * height <= 16000000;
  }
  /** 限定解码尺寸，支持高像素手机照片。 */
  static validDimensions(width: number, height: number): boolean {
    return Number.isSafeInteger(width) && Number.isSafeInteger(height) &&
      width > 0 && height > 0 && width * height <= 200000000;
  }
  /** 等比例缩放且不放大，长边随编码重试逐步缩小。 */
  static dimensions(width: number, height: number, attempt: number): number[] {
    const edge = Math.max(512, Math.floor(2048 * Math.pow(0.75, attempt)));
    const scale = Math.min(1, edge / Math.max(width, height));
    return [Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))];
  }
}
