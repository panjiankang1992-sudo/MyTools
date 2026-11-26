import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import fileUri from '@ohos.file.fileuri';
import image from '@ohos.multimedia.image';

/** 相册原图交给网页前按长边缩放，避免 ArkWeb 读取和上传十几 MB 的原图。 */
export class DshImageUploadPreparation {
  private static readonly MAX_EDGE: number = 2560;
  private static readonly PASSTHROUGH_BYTES: number = 2 * 1024 * 1024;
  private static readonly JPEG_QUALITY: number = 88;

  /**
   * 返回可直接交给网页输入控件的文件 URI。
   *
   * @param context 当前 UIAbility 上下文，用于写入应用缓存。
   * @param uri 系统选择器返回的图片 URI。
   * @param index 本次选择内的序号，用于生成不冲突的缓存文件名。
   * @param onResize 开始解码缩放时通知调用方，便于先给出等待提示。
   */
  static async prepare(context: common.UIAbilityContext, uri: string, index: number,
    onResize?: () => void): Promise<string> {
    let file: fs.File | undefined = undefined;
    let source: image.ImageSource | undefined = undefined;
    try {
      file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      const byteLength = fs.statSync(file.fd).size;
      source = image.createImageSource(file.fd);
      const info = await source.getImageInfo();
      // 只处理可重新编码的静态位图；其他格式保持原样，交给网页自己的流程。
      if (!DshImageUploadPreparation.needsResize(byteLength, info.size.width, info.size.height, info.mimeType)) {
        return uri;
      }
      onResize?.();
      const size = DshImageUploadPreparation.targetSize(info.size.width, info.size.height);
      const pixels = await source.createPixelMap({ desiredSize: { width: size[0], height: size[1] } });
      try {
        const png = info.mimeType === 'image/png';
        const packer = image.createImagePacker();
        try {
          const bytes = await packer.packToData(pixels, {
            format: png ? 'image/png' : 'image/jpeg',
            quality: png ? 100 : DshImageUploadPreparation.JPEG_QUALITY
          });
          if (bytes.byteLength === 0) return uri;
          const path = `${context.cacheDir}/dsh-upload-${Date.now()}-${index}.${png ? 'png' : 'jpg'}`;
          const output = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
          try { fs.writeSync(output.fd, bytes); } finally { fs.closeSync(output); }
          return fileUri.getUriFromPath(path);
        } finally { await packer.release(); }
      } finally { await pixels.release(); }
    } catch (_) {
      // 解码或写入失败时保留原图，不能因为优化阻断用户的附件。
      return uri;
    } finally {
      if (source !== undefined) { try { await source.release(); } catch (_) { /* 解码器可能已释放。 */ } }
      if (file !== undefined) { try { fs.closeSync(file); } catch (_) { /* 文件可能已关闭。 */ } }
    }
  }

  /** 只有可重新编码的静态位图且超过尺寸或体积阈值时才重新编码。 */
  private static needsResize(byteLength: number, width: number, height: number, mimeType: string): boolean {
    if (!['image/jpeg', 'image/png', 'image/heic', 'image/heif'].includes(mimeType)) return false;
    if (width <= 0 || height <= 0 || width * height > 200000000) return false;
    return byteLength > DshImageUploadPreparation.PASSTHROUGH_BYTES ||
      Math.max(width, height) > DshImageUploadPreparation.MAX_EDGE;
  }

  /** 等比缩放到长边上限，至少保留 1 像素。 */
  private static targetSize(width: number, height: number): number[] {
    const longest = Math.max(width, height);
    if (longest <= DshImageUploadPreparation.MAX_EDGE) return [width, height];
    const scale = DshImageUploadPreparation.MAX_EDGE / longest;
    return [Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))];
  }
}
