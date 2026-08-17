/** 规范化OPDS目录请求URL与HTTP Basic认证头。 */
export class OpdsRequestInputPolicy {
  /** 返回网络请求与目录解析共同使用的规范HTTPS URL。 */
  url(value: string): string {
    const trimmed = value.trim();
    if (trimmed.length < 9 || trimmed.length > 4096 || !/^https:\/\//i.test(trimmed) ||
      /[\u0000-\u0020\u007F\\]/.test(trimmed) || /^https:\/\/[^/?#]*@/i.test(trimmed)) {
      throw new Error('OPDS仅允许安全的HTTPS地址');
    }
    return `https://${trimmed.substring(trimmed.indexOf('://') + 3)}`;
  }

  /** 只允许应用生成的有界Basic认证值进入HTTP栈。 */
  authorization(value?: string): string | undefined {
    if (value === undefined || value.length === 0) return undefined;
    if (value.length > 2048 || !/^Basic [A-Za-z0-9+/]+={0,2}$/.test(value)) {
      throw new Error('OPDS认证头无效');
    }
    const payload = value.substring('Basic '.length);
    if (payload.length % 4 !== 0 || payload.indexOf('=') >= 0 && !/=+$/.test(payload)) {
      throw new Error('OPDS认证头无效');
    }
    return value;
  }
}
