/**
 * 远程字幕票据下载的路径与响应头安全策略。
 */
export class RemoteSubtitleNetworkPolicy {
  /** 仅允许规范绝对路径中的VTT和SRT普通文件。 */
  path(value: string): string {
    if (typeof value !== 'string' || value.length < 5 || value.length > 4096 || !value.startsWith('/') ||
      !/\.(?:vtt|srt)$/i.test(value) || value.includes('\\') || value.includes('//') ||
      /[\u0000-\u001F\u007F]/.test(value) || value.split('/').some((part: string) => part === '.' || part === '..')) {
      throw new Error('远程字幕路径无效或格式不受支持');
    }
    return value;
  }

  /** 校验字幕响应不得重定向、压缩或伪造长度与媒体类型。 */
  headerError(headers: Object): string {
    const values = headers as Record<string, Object>;
    let contentType = '';
    for (const key of Object.keys(values)) {
      const lower = key.toLowerCase();
      if (lower === 'location') return '远程字幕不允许重定向';
      if (lower === 'content-length') {
        const length = Number(values[key]);
        if (!Number.isSafeInteger(length) || length < 0 || length > 1024 * 1024) {
          return '远程字幕长度无效或超过1 MB限制';
        }
      } else if (lower === 'content-type') {
        contentType = `${values[key]}`.toLowerCase();
      } else if (lower === 'content-encoding') {
        const encoding = `${values[key]}`.trim().toLowerCase();
        if (encoding.length > 0 && encoding !== 'identity') return '远程字幕不接受压缩传输';
      }
    }
    if (contentType.length > 0 && !contentType.includes('text/vtt') && !contentType.includes('text/plain') &&
      !contentType.includes('application/x-subrip') && !contentType.includes('application/octet-stream')) {
      return '远程字幕响应类型无效';
    }
    return '';
  }
}
