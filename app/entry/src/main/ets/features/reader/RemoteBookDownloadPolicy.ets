export type RemoteBookPayloadKind = 'text' | 'archive' | 'pdf' | 'mobi';

export interface RemoteBookHeaderResult {
  error: string;
  contentLength?: number;
}

/**
 * 远程图书正文与容器下载的URL和响应头安全策略。
 */
export class RemoteBookDownloadPolicy {
  /** 只允许不含用户信息、片段和控制字符的HTTP(S)最终地址。 */
  url(value: string): string {
    if (value.length < 8 || value.length > 4096 ||
      (!value.startsWith('https://') && !value.startsWith('http://')) || value.includes('\\') ||
      value.includes('#') || /[\u0000-\u001F\u007F]/.test(value) || /^(?:https?:\/\/)[^/?#]*@/i.test(value)) {
      throw new Error('远程图书地址无效');
    }
    return value;
  }

  /** 仅允许凭据存储层产生的三种敏感请求头，禁止覆盖网络控制头。 */
  requestHeaders(value?: Record<string, string>): Record<string, string> {
    // 明确要求服务端返回未压缩字节，确保长度校验、指纹校验与落盘内容使用同一份数据。
    const output: Record<string, string> = { 'Accept-Encoding': 'identity' };
    if (value === undefined) return output;
    const keys = Object.keys(value);
    if (keys.length > 3) throw new Error('远程图书请求头数量无效');
    keys.forEach((key: string) => {
      const normalized = key.toLowerCase();
      if (normalized !== 'authorization' && normalized !== 'cookie' && normalized !== 'x-api-key') {
        throw new Error('远程图书请求头不在允许范围');
      }
      const headerName = normalized === 'authorization' ? 'Authorization' :
        (normalized === 'cookie' ? 'Cookie' : 'X-API-Key');
      const headerValue = value[key];
      if (typeof headerValue !== 'string' || headerValue.length < 1 || headerValue.length > 4096 ||
        /[\u0000\u000A\u000D]/.test(headerValue) || output[headerName] !== undefined) {
        throw new Error('远程图书请求头值无效');
      }
      output[headerName] = headerValue;
    });
    return output;
  }

  /** 拒绝重定向、压缩传输、非法长度和与目标格式不符的媒体类型。 */
  headerError(headers: Object, maximumBytes: number, kind: RemoteBookPayloadKind): string {
    return this.headers(headers, maximumBytes, kind).error;
  }

  /** 校验响应头并返回可选的严格内容长度。 */
  headers(headers: Object, maximumBytes: number, kind: RemoteBookPayloadKind): RemoteBookHeaderResult {
    if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 1) return { error: '远程图书大小限制无效' };
    const values = headers as Record<string, Object>;
    const keys = Object.keys(values);
    if (keys.length > 64) return { error: '远程图书响应头数量超过限制' };
    let contentType = '';
    let contentLength: number | undefined = undefined;
    let transferEncoding = '';
    let headerCharacters = 0;
    const seen = new Set<string>();
    for (const key of keys) {
      const rawValue = values[key];
      const lower = key.toLowerCase();
      if (lower === 'location') return { error: '远程图书重定向已阻止，请使用最终地址' };
      const critical = lower === 'content-length' || lower === 'content-type' ||
        lower === 'content-encoding' || lower === 'transfer-encoding';
      if (critical && (seen.has(lower) || !this.singleHeaderValue(rawValue))) {
        return { error: '远程图书关键响应头重复或存在歧义' };
      }
      if (key.length < 1 || key.length > 128 || /[^!#$%&'*+.^_`|~0-9A-Za-z-]/.test(key) ||
        (typeof rawValue !== 'string' && typeof rawValue !== 'number')) {
        return { error: '远程图书响应头格式无效' };
      }
      const value = `${rawValue}`;
      headerCharacters += key.length + value.length;
      if (value.length > 8192 || headerCharacters > 32768 || /[\u0000\u000A\u000D]/.test(value)) {
        return { error: '远程图书响应头大小超过限制' };
      }
      seen.add(lower);
      if (lower === 'content-length') {
        const length = Number(`${values[key]}`.trim());
        if (!Number.isSafeInteger(length) || length < 0 || length > maximumBytes) {
          return { error: '远程图书长度无效或超过格式限制' };
        }
        contentLength = length;
      } else if (lower === 'content-encoding') {
        const encoding = `${values[key]}`.trim().toLowerCase();
        if (encoding.length > 0 && encoding !== 'identity') return { error: '远程图书不接受压缩传输' };
      } else if (lower === 'content-type') {
        const normalizedType = this.contentType(`${values[key]}`);
        if (normalizedType.length === 0) return { error: '远程图书响应类型格式无效' };
        contentType = normalizedType;
      } else if (lower === 'transfer-encoding') {
        transferEncoding = `${values[key]}`.trim().toLowerCase();
      }
    }
    if (transferEncoding.length > 0 && transferEncoding !== 'identity' && transferEncoding !== 'chunked') {
      return { error: '远程图书传输编码无效' };
    }
    if (transferEncoding === 'chunked' && contentLength !== undefined) {
      return { error: '远程图书传输编码与内容长度冲突' };
    }
    if (contentType.length > 0 && !this.acceptsType(contentType, kind)) {
      return { error: '远程图书响应类型与格式不符' };
    }
    return contentLength === undefined ? { error: '' } : { error: '', contentLength: contentLength };
  }

  private singleHeaderValue(value: Object): boolean {
    if (typeof value !== 'string' && typeof value !== 'number') return false;
    const normalized = `${value}`.trim();
    return normalized.length > 0 && normalized.indexOf(',') < 0 &&
      !/[\u0000\u000A\u000D]/.test(normalized);
  }

  private contentType(value: string): string {
    const parts = value.split(';');
    const mediaType = parts[0].trim().toLowerCase();
    const token = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    if (!new RegExp(`^${token}/${token}$`).test(mediaType)) return '';
    const parameters = new Set<string>();
    for (let index = 1; index < parts.length; index++) {
      const parameter = parts[index].trim();
      const separator = parameter.indexOf('=');
      if (separator <= 0 || separator === parameter.length - 1) return '';
      const name = parameter.substring(0, separator).trim().toLowerCase();
      const rawValue = parameter.substring(separator + 1).trim();
      if (!new RegExp(`^${token}$`).test(name) || parameters.has(name) ||
        !this.contentTypeParameterValue(rawValue)) return '';
      parameters.add(name);
    }
    return mediaType;
  }

  private contentTypeParameterValue(value: string): boolean {
    if (/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(value)) return true;
    return value.length >= 2 && value.startsWith('"') && value.endsWith('"') &&
      !/["\\\u0000-\u001F\u007F]/.test(value.substring(1, value.length - 1));
  }

  /** 校验下载完成后的容器头，防止仅依赖扩展名或媒体类型。 */
  payloadError(bytes: Uint8Array, kind: RemoteBookPayloadKind): string {
    if (kind === 'text') return '';
    if (kind === 'pdf') {
      return bytes.length >= 5 && bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 &&
        bytes[3] === 0x46 && bytes[4] === 0x2D ? '' : 'PDF文件签名无效';
    }
    if (kind === 'mobi') {
      return bytes.length >= 68 && bytes[60] === 0x42 && bytes[61] === 0x4F && bytes[62] === 0x4F &&
        bytes[63] === 0x4B && bytes[64] === 0x4D && bytes[65] === 0x4F && bytes[66] === 0x42 &&
        bytes[67] === 0x49 ? '' : 'MOBI文件签名无效';
    }
    return bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4B && bytes[2] === 0x03 &&
      bytes[3] === 0x04 ? '' : '图书容器签名无效';
  }

  private acceptsType(contentType: string, kind: RemoteBookPayloadKind): boolean {
    if (contentType === 'application/octet-stream' || contentType === 'binary/octet-stream') return true;
    if (kind === 'text') return contentType === 'text/plain' || contentType === 'text/markdown';
    if (kind === 'pdf') return contentType === 'application/pdf';
    if (kind === 'mobi') return contentType === 'application/x-mobipocket-ebook' ||
      contentType === 'application/vnd.amazon.ebook';
    return contentType === 'application/epub+zip' || contentType === 'application/zip' ||
      contentType === 'application/x-zip-compressed' || contentType === 'application/vnd.comicbook+zip';
  }
}
