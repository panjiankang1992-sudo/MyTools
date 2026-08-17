import { BookSourceExploreCategory } from './ReaderModels';

/**
 * 解析无需脚本执行的书源发现分类配置。
 */
export class BookSourceExploreParser {
  private static readonly MAX_CATEGORIES = 100;
  private static readonly MAX_TEXT_LENGTH = 16 * 1024;

  /**
   * 支持直接地址、逐行“标题::地址”和标题/地址JSON数组。
   */
  parse(raw: string): BookSourceExploreCategory[] {
    const text = raw.trim();
    if (text.length === 0) return [];
    if (text.length > BookSourceExploreParser.MAX_TEXT_LENGTH) throw new Error('书源发现配置超过16 KB');
    if (text.startsWith('[')) return this.parseJson(text);
    const lines = text.split(/\r?\n/).map((value: string) => value.trim())
      .filter((value: string) => value.length > 0);
    if (lines.length === 1 && !lines[0].includes('::')) return [this.category('发现', lines[0])];
    if (lines.length > 200) throw new Error('书源发现分类候选超过200项');
    const values: BookSourceExploreCategory[] = [];
    lines.forEach((line: string) => {
      const marker = line.indexOf('::');
      if (marker <= 0) throw new Error('书源发现分类必须使用“标题::地址”格式');
      const title = line.substring(0, marker).trim();
      const url = line.substring(marker + 2).trim();
      // 允许书源使用空地址作为分组标题，但不把标题误当成可请求分类。
      if (url.length === 0) return;
      values.push(this.category(title, url));
      if (values.length > BookSourceExploreParser.MAX_CATEGORIES) throw new Error('书源发现分类超过100项');
    });
    return values;
  }

  private parseJson(text: string): BookSourceExploreCategory[] {
    let parsed: Object;
    try {
      parsed = JSON.parse(text) as Object;
    } catch (_) {
      throw new Error('书源发现分类JSON格式不正确');
    }
    if (!Array.isArray(parsed) || parsed.length > 200) throw new Error('书源发现分类JSON必须是有界数组');
    const values: BookSourceExploreCategory[] = [];
    parsed.forEach((item: Object) => {
      if (item === null || typeof item !== 'object' || Array.isArray(item)) {
        throw new Error('书源发现分类JSON项必须是对象');
      }
      const record = item as Record<string, Object>;
      const title = typeof record['title'] === 'string' ? record['title'] as string : '';
      const url = typeof record['url'] === 'string' ? record['url'] as string : '';
      if (url.trim().length === 0) return;
      values.push(this.category(title, url));
      if (values.length > BookSourceExploreParser.MAX_CATEGORIES) throw new Error('书源发现分类超过100项');
    });
    return values;
  }

  private category(title: string, url: string): BookSourceExploreCategory {
    const safeTitle = title.trim();
    const safeUrl = url.trim();
    if (safeTitle.length === 0 || safeTitle.length > 80 || /[\u0000-\u001F\u007F]/.test(safeTitle)) {
      throw new Error('书源发现分类标题无效或超过80字符');
    }
    if (safeUrl.length === 0 || safeUrl.length > 16 * 1024 || /[\u0000-\u001F\u007F]/.test(safeUrl)) {
      throw new Error('书源发现分类地址无效');
    }
    return { title: safeTitle, url: safeUrl };
  }
}
