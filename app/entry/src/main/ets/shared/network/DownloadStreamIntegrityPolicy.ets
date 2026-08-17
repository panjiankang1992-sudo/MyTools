/** 对流式下载的最终状态、长度与可选JPEG特征执行提交前校验。 */
export class DownloadStreamIntegrityPolicy {
  /** 返回空字符串表示目标内容可以提交，否则返回失败原因。 */
  error(status: number, receivedBytes: number, declaredBytes: number | undefined,
    expectedContentType: string, actualContentType: string, signature: number[]): string {
    if (!Number.isSafeInteger(status) || status < 200 || status >= 300) return `HTTP ${status}`;
    if (!Number.isSafeInteger(receivedBytes) || receivedBytes <= 0) return '远程文件为空';
    if (declaredBytes !== undefined && declaredBytes !== receivedBytes) return '远程文件长度与响应声明不一致';
    if (expectedContentType === 'image/*' && !actualContentType.startsWith('image/')) return '图片响应类型无效';
    if (expectedContentType.length > 0 && expectedContentType !== 'image/*' && actualContentType !== expectedContentType) {
      return '缩略图响应类型无效';
    }
    if (expectedContentType === 'image/jpeg' &&
      (signature.length < 3 || signature[0] !== 0xFF || signature[1] !== 0xD8 || signature[2] !== 0xFF)) {
      return '缩略图内容签名无效';
    }
    if (expectedContentType === 'image/*' && !this.isSupportedImage(actualContentType, signature)) {
      return '图片内容签名无效';
    }
    return '';
  }

  private isSupportedImage(contentType: string, signature: number[]): boolean {
    if (contentType === 'image/jpeg') {
      return signature.length >= 3 && signature[0] === 0xFF && signature[1] === 0xD8 && signature[2] === 0xFF;
    }
    if (contentType === 'image/png') {
      return signature.length >= 8 && signature[0] === 0x89 && signature[1] === 0x50 && signature[2] === 0x4E &&
        signature[3] === 0x47 && signature[4] === 0x0D && signature[5] === 0x0A && signature[6] === 0x1A &&
        signature[7] === 0x0A;
    }
    if (contentType === 'image/gif') {
      return signature.length >= 6 && signature[0] === 0x47 && signature[1] === 0x49 && signature[2] === 0x46 &&
        signature[3] === 0x38 && (signature[4] === 0x37 || signature[4] === 0x39) && signature[5] === 0x61;
    }
    if (contentType === 'image/webp') {
      return signature.length >= 12 && signature[0] === 0x52 && signature[1] === 0x49 && signature[2] === 0x46 &&
        signature[3] === 0x46 && signature[8] === 0x57 && signature[9] === 0x45 && signature[10] === 0x42 &&
        signature[11] === 0x50;
    }
    if (contentType === 'image/bmp') {
      return signature.length >= 2 && signature[0] === 0x42 && signature[1] === 0x4D;
    }
    return false;
  }
}
