export interface ReaderTextMetadata {
  content: string;
  originalFilename: string;
}

/** 识别电子书维护流程写入的内部文本元数据，避免其进入正文。 */
export class ReaderTextMetadataPolicy {
  private static readonly ORIGINAL_FILENAME_PREFIX: string = '[Original filename] ';
  private static readonly MAX_METADATA_LINE_LENGTH: number = 2048;

  /** 从TXT首行剥离受信任的原文件名标记，同时返回可选的原文件名。 */
  sanitize(text: string): ReaderTextMetadata {
    const normalized = text.replace(/^\uFEFF/, '');
    const firstLineEnd = normalized.indexOf('\n');
    const firstLine = (firstLineEnd < 0 ? normalized : normalized.substring(0, firstLineEnd)).replace(/\r$/, '');
    if (!firstLine.startsWith(ReaderTextMetadataPolicy.ORIGINAL_FILENAME_PREFIX) ||
      firstLine.length > ReaderTextMetadataPolicy.MAX_METADATA_LINE_LENGTH) {
      return { content: normalized, originalFilename: '' };
    }
    const originalFilename = firstLine.substring(ReaderTextMetadataPolicy.ORIGINAL_FILENAME_PREFIX.length)
      .replace(/[\u0000-\u001F\u007F]/g, '').trim();
    const content = firstLineEnd < 0 ? '' : normalized.substring(firstLineEnd + 1).replace(/^\uFEFF/, '');
    return { content: content, originalFilename: originalFilename };
  }
}
