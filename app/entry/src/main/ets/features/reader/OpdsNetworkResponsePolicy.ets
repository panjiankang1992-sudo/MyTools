/** 校验OPDS目录的不可信响应头与正文配额。 */
export class OpdsNetworkResponsePolicy {
  private static readonly MAX_BYTES: number = 5 * 1024 * 1024;

  /** 返回响应头错误；空字符串表示可继续接收。 */
  headerError(headers: Object): string {
    const values = headers as Record<string, Object>;
    let contentType = '';
    for (const key of Object.keys(values)) {
      const lower = key.toLowerCase();
      if (lower === 'location') return 'OPDS目录重定向已阻止，请配置最终公开地址';
      if (lower === 'content-length') {
        const length = Number(values[key]);
        if (!Number.isSafeInteger(length) || length < 0 || length > OpdsNetworkResponsePolicy.MAX_BYTES) {
          return 'OPDS目录长度无效或超过5 MB限制';
        }
      } else if (lower === 'content-type') {
        contentType = `${values[key]}`.toLowerCase().split(';')[0].trim();
      } else if (lower === 'content-encoding') {
        const encoding = `${values[key]}`.trim().toLowerCase();
        if (encoding.length > 0 && encoding !== 'identity') return 'OPDS目录不接受压缩传输';
      }
    }
    if (contentType.length > 0 && !this.supportedType(contentType)) return 'OPDS目录响应类型无效';
    return '';
  }

  /** 按UTF-8字节校验最终正文，并在超限时尽早停止。 */
  bodyError(value: string): string {
    if (value.length === 0) return 'OPDS目录为空';
    let bytes = 0;
    for (let index = 0; index < value.length; index++) {
      const code = value.charCodeAt(index);
      if (code <= 0x7F) bytes++;
      else if (code <= 0x7FF) bytes += 2;
      else if (code >= 0xD800 && code <= 0xDBFF && index + 1 < value.length &&
        value.charCodeAt(index + 1) >= 0xDC00 && value.charCodeAt(index + 1) <= 0xDFFF) {
        bytes += 4;
        index++;
      } else bytes += 3;
      if (bytes > OpdsNetworkResponsePolicy.MAX_BYTES) return 'OPDS目录超过5 MB限制';
    }
    return '';
  }

  private supportedType(value: string): boolean {
    return value === 'application/opds+json' || value === 'application/json' || value.endsWith('+json') ||
      value === 'application/atom+xml' || value === 'application/xml' || value === 'text/xml';
  }
}
