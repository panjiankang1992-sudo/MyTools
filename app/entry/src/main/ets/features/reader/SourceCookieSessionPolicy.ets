/** 规范化书源响应Cookie为可安全回送的有界会话值。 */
export class SourceCookieSessionPolicy {
  private static readonly MAX_COOKIES: number = 32;
  private static readonly MAX_BYTES: number = 4096;

  /** 提取普通name=value对，忽略Set-Cookie属性及畸形项。 */
  normalize(value: string): string {
    return this.merge('', value);
  }

  /** 把新响应Cookie增量合并到当前会话，空值及Max-Age不大于零表示删除。 */
  merge(current: string, value: string): string {
    const cookies = this.parseCurrent(current);
    if (typeof value !== 'string' || value.length === 0 || /[\r\n\u0000\u007F]/.test(value)) {
      return this.serialize(cookies);
    }
    let lastCookie = '';
    let changed = false;
    const attributes = new Set<string>(['path', 'domain', 'expires', 'samesite', 'secure', 'httponly',
      'partitioned', 'priority']);
    value.split(';').slice(0, 128).forEach((part: string) => {
      const trimmed = part.trim();
      const separator = trimmed.indexOf('=');
      if (separator <= 0) return;
      const name = trimmed.substring(0, separator).trim();
      const cookieValue = trimmed.substring(separator + 1).trim();
      const lower = name.toLowerCase();
      if (lower === 'max-age') {
        if (lastCookie.length > 0 && /^-?\d+$/.test(cookieValue) && Number(cookieValue) <= 0) {
          cookies.delete(lastCookie);
          changed = true;
        }
        return;
      }
      if (attributes.has(lower) || !this.validPair(name, cookieValue)) return;
      lastCookie = name;
      changed = true;
      if (cookieValue.length === 0) cookies.delete(name);
      else if (cookies.size < SourceCookieSessionPolicy.MAX_COOKIES || cookies.has(name)) {
        cookies.set(name, cookieValue);
      }
    });
    if (!changed) return this.serialize(this.parseCurrent(current));
    const output = this.serialize(cookies);
    return this.utf8Length(output) <= SourceCookieSessionPolicy.MAX_BYTES ? output : this.serialize(
      this.parseCurrent(current));
  }

  private parseCurrent(value: string): Map<string, string> {
    const cookies: Map<string, string> = new Map<string, string>();
    value.split(';').slice(0, SourceCookieSessionPolicy.MAX_COOKIES).forEach((part: string) => {
      const trimmed = part.trim();
      const separator = trimmed.indexOf('=');
      if (separator <= 0) return;
      const name = trimmed.substring(0, separator).trim();
      const cookieValue = trimmed.substring(separator + 1).trim();
      if (this.validPair(name, cookieValue) && cookieValue.length > 0) cookies.set(name, cookieValue);
    });
    return cookies;
  }

  private validPair(name: string, value: string): boolean {
    return /^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/.test(name) && value.length <= 1024 &&
      !/[;,\s\u0000-\u001F\u007F]/.test(value);
  }

  private serialize(cookies: Map<string, string>): string {
    return Array.from(cookies.entries()).map((entry: [string, string]) => `${entry[0]}=${entry[1]}`).join('; ');
  }

  private utf8Length(value: string): number {
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
      if (bytes > SourceCookieSessionPolicy.MAX_BYTES) return bytes;
    }
    return bytes;
  }
}
