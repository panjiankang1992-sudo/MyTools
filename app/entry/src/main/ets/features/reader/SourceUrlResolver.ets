interface ParsedSourceBase {
  scheme: string;
  authority: string;
  path: string;
  query: string;
}

/**
 * 按HTTP URL相对引用语义解析书源返回的地址。
 */
export class SourceUrlResolver {
  /**
   * 解析带`,{...}`内联请求配置的书源引用，只转换实际URL部分。
   */
  resolveRequest(baseUrl: string, value: string): string {
    const marker = value.indexOf(',{');
    if (marker < 0) return this.resolve(baseUrl, value);
    const address = value.substring(0, marker);
    const config = value.substring(marker + 1);
    if (config.length > 64 * 1024) throw new Error('书源内联请求配置超过64 KB');
    return `${this.resolve(baseUrl, address)},${config}`;
  }

  /**
   * 将相对、根相对、协议相对、查询或片段引用解析为绝对HTTP(S)地址。
   */
  resolve(baseUrl: string, value: string): string {
    const reference = value.trim();
    if (reference.length > 4096 || /[\u0000-\u001F\u007F\\]/.test(reference)) {
      throw new Error('书源地址过长或包含不安全字符');
    }
    if (/^https?:\/\//i.test(reference)) return reference;
    if (/^[a-z][a-z0-9+.-]*:/i.test(reference)) throw new Error('书源地址仅支持HTTP或HTTPS');
    const base = this.parseBase(baseUrl);
    const origin = `${base.scheme}://${base.authority}`;
    if (reference.startsWith('//')) return `${base.scheme}:${reference}`;
    if (reference.length === 0) return `${origin}${base.path}${base.query}`;
    if (reference.startsWith('#')) return `${origin}${base.path}${base.query}${reference}`;
    if (reference.startsWith('?')) return `${origin}${base.path}${reference}`;
    const split = /^([^?#]*)(\?[^#]*)?(#.*)?$/.exec(reference);
    if (split === null) throw new Error('书源相对地址格式无效');
    const rawPath = split[1];
    const query = split[2] ?? '';
    const fragment = split[3] ?? '';
    const combined = rawPath.startsWith('/') ? rawPath : `${this.baseDirectory(base.path)}${rawPath}`;
    return `${origin}${this.normalizePath(combined)}${query}${fragment}`;
  }

  private parseBase(value: string): ParsedSourceBase {
    const match = /^(https?):\/\/([^/?#]+)([^?#]*)(\?[^#]*)?(?:#.*)?$/i.exec(value.trim());
    if (match === null || match[2].length === 0) throw new Error('书源基础地址格式无效');
    return {
      scheme: match[1].toLowerCase(),
      authority: match[2],
      path: match[3].length === 0 ? '/' : match[3],
      query: match[4] ?? ''
    };
  }

  private baseDirectory(path: string): string {
    if (path.endsWith('/')) return path;
    const slash = path.lastIndexOf('/');
    return slash < 0 ? '/' : path.substring(0, slash + 1);
  }

  private normalizePath(path: string): string {
    const trailingSlash = path.endsWith('/') || path.endsWith('/.') || path.endsWith('/..');
    const output: string[] = [];
    path.split('/').forEach((segment: string) => {
      if (segment.length === 0 || segment === '.') return;
      if (segment === '..') {
        if (output.length > 0) output.pop();
        return;
      }
      output.push(segment);
    });
    const normalized = `/${output.join('/')}`;
    return trailingSlash && normalized !== '/' ? `${normalized}/` : normalized;
  }
}
