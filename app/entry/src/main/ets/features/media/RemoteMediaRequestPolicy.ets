/**
 * 远程媒体和文件操作的账号、路径、名称与系统URI请求策略。
 */
export class RemoteMediaRequestPolicy {
  /** 仅接受后端雪花数字账号标识。 */
  accountId(value: string): string {
    if (!/^[1-9]\d{0,19}$/.test(value)) throw new Error('远程账号标识无效');
    return value;
  }

  /** 校验最长4096字符的规范远程绝对路径。 */
  path(value: string, allowRoot: boolean = true): string {
    if (typeof value !== 'string' || value.length < 1 || value.length > 4096 || !value.startsWith('/') ||
      value.includes('\\') || value.includes('//') || /[\u0000-\u001F\u007F]/.test(value) ||
      value.split('/').some((part: string) => part === '.' || part === '..') || (!allowRoot && value === '/') ||
      (value.length > 1 && value.endsWith('/'))) throw new Error('远程路径无效');
    return value;
  }

  /** 校验单个远程文件或目录名称。 */
  name(value: string): string {
    const normalized = value.trim();
    if (normalized.length < 1 || normalized.length > 255 || normalized === '.' || normalized === '..' ||
      normalized.includes('/') || normalized.includes('\\') || /[\u0000-\u001F\u007F]/.test(normalized)) {
      throw new Error('远程文件名称无效');
    }
    return normalized;
  }

  /** 仅接受系统文件选件授予的有界file URI。 */
  fileUri(value: string): string {
    if (value.length < 8 || value.length > 8192 || !value.startsWith('file://') ||
      /[\u0000-\u001F\u007F]/.test(value)) throw new Error('系统文件URI无效');
    return value;
  }

  /** 校验服务端签发的固定格式播放票据。 */
  ticket(value: string): string {
    if (!/^[a-f0-9]{32}$/.test(value)) throw new Error('播放票据格式无效');
    return value;
  }
}
