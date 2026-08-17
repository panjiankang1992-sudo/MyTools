import { SourceUrlResolver } from './SourceUrlResolver';

/**
 * 将不可信书源规则结果规范化为有界HTTP(S)漫画页候选。
 */
export class SourceComicPageProjector {
  private static readonly MAX_PAGES = 500;
  private readonly resolver = new SourceUrlResolver();

  /**
   * 接受单个URL或URL数组，解析相对地址、稳定去重并移除片段。
   */
  project(baseUrl: string, value: Object | undefined): string[] {
    if (value === undefined || value === null) return [];
    const rawValues: Object[] = Array.isArray(value) ? value as Object[] : [value];
    const candidates: string[] = [];
    for (let index = 0; index < rawValues.length; index++) {
      const raw = rawValues[index];
      if (raw === null || raw === undefined) continue;
      if (typeof raw !== 'string') throw new Error('书源漫画页地址必须为字符串');
      raw.split(/\r?\n/).forEach((part: string) => {
        const reference = part.trim();
        if (reference.length === 0) return;
        if (reference.includes(',{')) throw new Error('书源漫画页不支持内联请求配置');
        const resolved = this.resolver.resolve(baseUrl, reference);
        const fragment = resolved.indexOf('#');
        const normalized = fragment < 0 ? resolved : resolved.substring(0, fragment);
        if (!/^https?:\/\//i.test(normalized)) throw new Error('书源漫画页仅支持HTTP或HTTPS');
        if (!candidates.includes(normalized)) candidates.push(normalized);
        if (candidates.length > SourceComicPageProjector.MAX_PAGES) {
          throw new Error('书源漫画章节超过500页');
        }
      });
    }
    return candidates;
  }
}
