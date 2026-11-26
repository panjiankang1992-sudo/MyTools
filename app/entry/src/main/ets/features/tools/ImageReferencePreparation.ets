import image from '@ohos.multimedia.image';
import fs from '@ohos.file.fs';
import { ImageReferencePolicy } from './ImageReferencePolicy';

export interface PreparedImageReference {
  bytes: ArrayBuffer;
  resized: boolean;
}

/** 只生成内存副本，保留用户原图；无损 PNG 编码保留透明区域。 */
export class ImageReferencePreparation {
  /** 检测底稿并自动适配服务端与模型的输入规格。 */
  static async prepare(fd: number): Promise<PreparedImageReference> {
    try { return await ImageReferencePreparation.prepareInternal(fd); }
    catch (error) { throw new Error(error instanceof Error ? error.message : '底稿处理失败，请尝试其他图片'); }
  }
  private static async prepareInternal(fd: number): Promise<PreparedImageReference> {
    try {
    const length = fs.statSync(fd).size;
    if (!ImageReferencePolicy.validSource(length)) throw new Error('请选择有效的 PNG 或 JPEG 图片，原图最大 64 MB');
    const buffer = new ArrayBuffer(length);
    let offset = 0;
    // 分块读取完整文件后解码，避免图片解码器与文件读取共享游标。
    while (offset < length) {
      const chunk = new ArrayBuffer(Math.min(1024 * 1024, length - offset));
      const read = fs.readSync(fd, chunk);
      if (read <= 0) throw new Error('底稿读取不完整');
      new Uint8Array(buffer).set(new Uint8Array(chunk, 0, read), offset);
      offset += read;
    }
    const source = image.createImageSource(buffer);
    try {
      const info = await source.getImageInfo();
      if (!['image/png', 'image/jpeg'].includes(info.mimeType)) throw new Error('底稿仅支持 PNG 或 JPEG');
      if (!ImageReferencePolicy.validDimensions(info.size.width, info.size.height)) throw new Error('图片尺寸无效或超过 2 亿像素');
      if (ImageReferencePolicy.passthrough(length, info.size.width, info.size.height)) {
        return { bytes: buffer, resized: false };
      }
      const packer = image.createImagePacker();
      try {
        for (let attempt = 0; attempt < 6; attempt++) {
          const size = ImageReferencePolicy.dimensions(info.size.width, info.size.height, attempt);
          const pixels = await source.createPixelMap({ desiredSize: { width: size[0], height: size[1] } });
          try {
            const bytes = await packer.packToData(pixels, { format: 'image/png', quality: 100 });
            if (bytes.byteLength > 0 && bytes.byteLength <= ImageReferencePolicy.MAX_UPLOAD_BYTES) {
              return { bytes: bytes, resized: true };
            }
          } finally { await pixels.release(); }
        }
        throw new Error('图片自动适配失败，请尝试其他图片');
      } finally { await packer.release(); }
    } finally { await source.release(); }
    } catch (error) { throw new Error(error instanceof Error ? error.message : '底稿处理失败，请尝试其他图片'); }
  }
}
