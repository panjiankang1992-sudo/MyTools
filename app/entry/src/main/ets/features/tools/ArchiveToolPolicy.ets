/**
 * 定义创建ZIP时可独立回归的选择、大小与文件名策略。
 */
export class ArchiveToolPolicy {
  static readonly MAX_FILES: number = 20;
  static readonly MAX_FILE_BYTES: number = 100 * 1024 * 1024;
  static readonly MAX_TOTAL_BYTES: number = 500 * 1024 * 1024;

  /**
   * 校验用户选择数量。
   */
  validateSelectionCount(count: number): void {
    if (count <= 0 || count > ArchiveToolPolicy.MAX_FILES) throw new Error('请选择1至20个普通文件');
  }

  /**
   * 校验单文件与累计大小并返回新累计值。
   */
  addFileSize(size: number, currentTotal: number): number {
    if (!Number.isSafeInteger(size) || size <= 0 || size > ArchiveToolPolicy.MAX_FILE_BYTES) {
      throw new Error('单个文件必须在1字节至100 MB之间');
    }
    const next = currentTotal + size;
    if (!Number.isSafeInteger(next) || next > ArchiveToolPolicy.MAX_TOTAL_BYTES) {
      throw new Error('所选文件总大小不能超过500 MB');
    }
    return next;
  }

  /**
   * 将选择器URI末段转换为不含路径控制字符的暂存文件名。
   */
  safeFileName(uri: string, index: number): string {
    let raw = uri.substring(uri.lastIndexOf('/') + 1);
    try {
      raw = decodeURIComponent(raw);
    } catch (_) {
    }
    const cleaned = raw.replace(/[\\/:*?"<>|\u0000-\u001F]/g, '_').trim().substring(0, 160);
    return `${index + 1}-${cleaned.length > 0 ? cleaned : 'file'}`;
  }
}
