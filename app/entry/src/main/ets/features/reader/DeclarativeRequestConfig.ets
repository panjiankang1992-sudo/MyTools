/**
 * 无脚本书源的内联请求配置结果。
 */
export interface DeclarativeRequestOptions {
  method: string;
  body: string;
  headers: Record<string, string>;
}

/**
 * 解析书源URL后的声明式请求配置，并限制可发送的请求元数据。
 */
export class DeclarativeRequestConfig {
  private static readonly MAX_HEADERS = 32;
  private static readonly MAX_HEADER_VALUE_BYTES = 4096;
  private static readonly MAX_CONFIG_BYTES = 128 * 1024;
  private static readonly MAX_BODY_BYTES = 64 * 1024;

  /**
   * 解析JSON配置，字符串正文保持原样，对象正文序列化为JSON。
   */
  parse(configText: string): DeclarativeRequestOptions {
    if (configText.length === 0 || this.utf8Length(configText, DeclarativeRequestConfig.MAX_CONFIG_BYTES) >
      DeclarativeRequestConfig.MAX_CONFIG_BYTES) throw new Error('书源请求配置为空或超过128 KB');
    const parsed = JSON.parse(configText) as Record<string, Object>;
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('书源请求配置必须是JSON对象');
    }
    const rawMethod = `${parsed['method'] ?? 'GET'}`.trim().toUpperCase();
    if (rawMethod !== 'GET' && rawMethod !== 'POST') throw new Error('书源请求仅支持GET或POST');
    const rawBody = parsed['body'];
    const body = rawBody === undefined || rawBody === null ? '' :
      (typeof rawBody === 'string' ? rawBody : JSON.stringify(rawBody));
    if (rawMethod === 'GET' && body.length > 0) throw new Error('书源GET请求不允许携带正文');
    if (this.utf8Length(body, DeclarativeRequestConfig.MAX_BODY_BYTES) > DeclarativeRequestConfig.MAX_BODY_BYTES) {
      throw new Error('书源POST请求正文超过64 KB');
    }
    const headers = this.parseHeaders(parsed['headers']);
    if (rawMethod === 'POST' && this.headerValue(headers, 'content-type').length === 0) {
      headers['Content-Type'] = typeof rawBody === 'string' ?
        'application/x-www-form-urlencoded;charset=UTF-8' : 'application/json';
    }
    if (rawMethod === 'POST') this.validateContentType(this.headerValue(headers, 'content-type'));
    return { method: rawMethod, body: body, headers: headers };
  }

  private validateContentType(value: string): void {
    const mediaType = value.toLowerCase().split(';')[0].trim();
    if (mediaType !== 'application/json' && !mediaType.endsWith('+json') &&
      mediaType !== 'application/x-www-form-urlencoded' && mediaType !== 'text/plain') {
      throw new Error('书源POST请求Content-Type仅支持JSON、表单或纯文本');
    }
  }

  private parseHeaders(value: Object | undefined): Record<string, string> {
    const headers: Record<string, string> = {};
    if (value === undefined || value === null) return headers;
    if (typeof value !== 'object' || Array.isArray(value)) throw new Error('书源请求headers必须是JSON对象');
    const keys = Object.keys(value);
    if (keys.length > DeclarativeRequestConfig.MAX_HEADERS) throw new Error('书源请求头超过32项');
    keys.forEach((key: string) => {
      if (!/^[A-Za-z0-9-]+$/.test(key)) throw new Error('书源请求头名称不合法');
      if (this.isSensitiveHeader(key)) throw new Error('敏感请求头必须通过书源安全凭据配置');
      const raw = (value as Record<string, Object>)[key];
      if (raw !== null && typeof raw === 'object') throw new Error('书源请求头值必须是标量');
      const text = `${raw ?? ''}`;
      if (/\r|\n/.test(text) || this.utf8Length(text, DeclarativeRequestConfig.MAX_HEADER_VALUE_BYTES) >
        DeclarativeRequestConfig.MAX_HEADER_VALUE_BYTES) {
        throw new Error('书源请求头值不合法或超过4096字节');
      }
      const outputKey = key.toLowerCase() === 'content-type' ? 'Content-Type' : key;
      headers[outputKey] = text;
    });
    return headers;
  }

  private headerValue(headers: Record<string, string>, name: string): string {
    const key = Object.keys(headers).find((value: string) => value.toLowerCase() === name);
    return key === undefined ? '' : headers[key];
  }

  private isSensitiveHeader(name: string): boolean {
    const lower = name.trim().toLowerCase();
    return lower === 'authorization' || lower === 'cookie' || lower === 'proxy-authorization' ||
      lower === 'x-api-key';
  }

  private utf8Length(value: string, maximum: number): number {
    let length = 0;
    for (let index = 0; index < value.length; index++) {
      const code = value.charCodeAt(index);
      if (code <= 0x7F) length++;
      else if (code <= 0x7FF) length += 2;
      else if (code >= 0xD800 && code <= 0xDBFF && index + 1 < value.length &&
        value.charCodeAt(index + 1) >= 0xDC00 && value.charCodeAt(index + 1) <= 0xDFFF) {
        length += 4;
        index++;
      } else length += 3;
      if (length > maximum) return length;
    }
    return length;
  }
}
