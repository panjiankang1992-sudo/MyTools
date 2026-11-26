/** 下载输入框的剪贴板时效和去重规则，不持久化剪贴板内容。 */
export class DownloadClipboardPolicy {
  static permissionRequested: boolean = false;
  private static acceptedTimestamp: number = -1;

  /** 返回近期复制的下载链接或分享文本，时间参数统一使用开机纳秒。 */
  static recentLink(text: string, timestamp: number, now: number): string {
    const age = now - timestamp;
    // 无效、过期、未来和已采用的数据均不再自动填入。
    if (!Number.isFinite(timestamp) || !Number.isFinite(now) || timestamp <= 0 ||
      age < 0 || age > 60 * 1000000000 || timestamp === DownloadClipboardPolicy.acceptedTimestamp) return '';
    const value = text.trim();
    // 保留分享文案，但只自动填入包含可下载链接的短文本。
    if (value.length === 0 || value.length > 20000 || !/(https?:\/\/|magnet:\?)/i.test(value)) return '';
    return value;
  }

  /** 记录实际填入的剪贴板版本，清空输入框或重新进入页面不重复填入。 */
  static accept(timestamp: number): void {
    DownloadClipboardPolicy.acceptedTimestamp = timestamp;
  }
}
