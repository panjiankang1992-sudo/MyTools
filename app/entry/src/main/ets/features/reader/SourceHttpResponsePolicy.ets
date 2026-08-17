/** 校验声明式书源规则请求的不可信响应头。 */
export class SourceHttpResponsePolicy {
  private static readonly MAX_BYTES: number = 5 * 1024 * 1024;

  /** 返回失败原因；空字符串表示响应头可继续接收。 */
  headerError(headers: Object): string {
    const values = headers as Record<string, Object>;
    let contentType = '';
    for (const key of Object.keys(values)) {
      const lower = key.toLowerCase();
      if (lower === 'location') return '书源重定向已阻止，请配置最终公开地址';
      if (lower === 'content-length') {
        const length = Number(values[key]);
        if (!Number.isSafeInteger(length) || length < 0 || length > SourceHttpResponsePolicy.MAX_BYTES) {
          return '书源响应长度无效或超过5 MB';
        }
      } else if (lower === 'content-type') {
        contentType = `${values[key]}`.toLowerCase().split(';')[0].trim();
      } else if (lower === 'content-encoding') {
        const encoding = `${values[key]}`.trim().toLowerCase();
        if (encoding.length > 0 && encoding !== 'identity') return '书源响应不接受压缩传输';
      }
    }
    if (contentType.length > 0 && !this.supportedContentType(contentType)) return '书源响应类型无效';
    return '';
  }

  /** 校验实际规则正文的UTF-8字节数，阻止多字节文本绕过字符长度检查。 */
  bodyError(value: string): string {
    if (value.length === 0) return '书源响应为空或超过5 MB';
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
      if (bytes > SourceHttpResponsePolicy.MAX_BYTES) return '书源响应为空或超过5 MB';
    }
    return '';
  }

  private supportedContentType(value: string): boolean {
    return value.startsWith('text/') || value === 'application/json' || value.endsWith('+json') ||
      value === 'application/xml' || value.endsWith('+xml') || value === 'application/xhtml+xml' ||
      value === 'application/octet-stream';
  }
}
