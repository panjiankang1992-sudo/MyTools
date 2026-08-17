/** 为OPDS目录中的导航、封面和acquisition链接提供统一静态安全规范化。 */
export class OpdsLinkPolicy {
  /** 解析相对引用并只返回无片段、无用户信息的HTTPS绝对URL。 */
  resolve(baseUrl: string, value: Object | undefined): string | undefined {
    if (typeof value !== 'string') return undefined;
    const reference = value.trim();
    if (reference.length < 1 || reference.length > 4096 || /[\u0000-\u0020\u007F\\]/.test(reference) ||
      reference.startsWith('//') || /%2f|%5c/i.test(reference) || reference.includes('#')) return undefined;
    if (/^[A-Za-z][A-Za-z0-9+.-]*:/.test(reference)) {
      if (!/^https:\/\//i.test(reference)) return undefined;
      return this.absolute(reference);
    }
    return this.relative(baseUrl, reference);
  }

  private absolute(value: string): string | undefined {
    const normalized = `https://${value.substring(value.indexOf('://') + 3)}`;
    if (/^https:\/\/[^/?#]*@/i.test(normalized) || normalized.includes('\\') || normalized.includes('#')) {
      return undefined;
    }
    return normalized;
  }

  private relative(baseUrl: string, value: string): string | undefined {
    const originMatch = /^(https:\/\/[^/?#]+)/i.exec(baseUrl.trim());
    if (originMatch === null || /^https:\/\/[^/?#]*@/i.test(originMatch[1])) return undefined;
    const origin = `https://${originMatch[1].substring(originMatch[1].indexOf('://') + 3)}`;
    const queryIndex = value.indexOf('?');
    const relativePath = queryIndex < 0 ? value : value.substring(0, queryIndex);
    const query = queryIndex < 0 ? '' : value.substring(queryIndex);
    const basePath = baseUrl.substring(originMatch[1].length).split('?')[0].split('#')[0];
    const combined = relativePath.startsWith('/') ? relativePath :
      `${basePath.substring(0, basePath.lastIndexOf('/') + 1)}${relativePath}`;
    const segments: string[] = [];
    for (const segment of combined.split('/')) {
      if (segment.length === 0 || segment === '.') continue;
      if (segment === '..') {
        if (segments.length === 0) return undefined;
        segments.pop();
      } else segments.push(segment);
    }
    const output = `${origin}/${segments.join('/')}${query}`;
    return output.length <= 4096 ? output : undefined;
  }
}
