import { SourceUrlResolver } from './SourceUrlResolver';

/**
 * 书源目录解析产生的原始章节候选。
 */
export interface SourceChapterCandidate {
  title: string;
  url: string;
}

/**
 * 经过地址解析和稳定去重的章节链接。
 */
export interface NormalizedSourceChapter {
  title: string;
  requestUrl: string;
}

/**
 * 统一归一化JSON与HTML书源返回的章节目录。
 */
export class SourceCatalogNormalizer {
  private static readonly MAX_INPUTS = 20000;
  private static readonly MAX_CHAPTERS = 10000;
  private readonly urlResolver = new SourceUrlResolver();

  /**
   * 保留首次出现顺序，过滤空地址并按最终请求引用去重。
   */
  normalize(baseUrl: string, candidates: SourceChapterCandidate[]): NormalizedSourceChapter[] {
    const chapters: NormalizedSourceChapter[] = [];
    const seen = new Set<string>();
    const inputCount = Math.min(candidates.length, SourceCatalogNormalizer.MAX_INPUTS);
    for (let index = 0; index < inputCount && chapters.length < SourceCatalogNormalizer.MAX_CHAPTERS; index++) {
      const rawUrl = candidates[index].url.trim();
      if (rawUrl.length === 0) continue;
      const requestUrl = this.urlResolver.resolveRequest(baseUrl, rawUrl);
      if (seen.has(requestUrl)) continue;
      seen.add(requestUrl);
      const title = candidates[index].title.trim();
      chapters.push({
        title: title.length > 0 ? title : `第${chapters.length + 1}章`,
        requestUrl: requestUrl
      });
    }
    return chapters;
  }
}
