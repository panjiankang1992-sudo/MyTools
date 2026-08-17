import { OpdsBookEntry, OpdsCatalog, OpdsNavigationEntry } from './OpdsModels';
import { OpdsAcquisitionFormatPolicy } from './OpdsAcquisitionFormatPolicy';
import { OpdsLinkPolicy } from './OpdsLinkPolicy';

/**
 * 对不可信OPDS 2 JSON目录执行有界结构解析和逐项隔离。
 */
export class OpdsJsonCatalogNormalizer {
  private static readonly MAX_SCAN_ENTRIES: number = 2000;
  private static readonly MAX_RESULT_ENTRIES: number = 1000;
  private readonly formatPolicy: OpdsAcquisitionFormatPolicy = new OpdsAcquisitionFormatPolicy();
  private readonly linkPolicy: OpdsLinkPolicy = new OpdsLinkPolicy();

  /** 解析OPDS 2目录并只保留可安全展示或获取的条目。 */
  normalize(baseUrl: string, text: string): OpdsCatalog {
    const parsed = JSON.parse(text) as Object;
    const root = this.record(parsed);
    if (root === undefined) throw new Error('OPDS 2目录根对象无效');
    const metadata = this.record(root['metadata']);
    const title = this.optionalText(metadata?.['title'], 300) ?? 'OPDS书库';
    const books = this.books(baseUrl, root['publications']);
    const navigation = this.navigation(baseUrl, root['navigation']);
    return { title: title.length > 0 ? title : 'OPDS书库', books: books, navigation: navigation };
  }

  private books(baseUrl: string, value: Object | undefined): OpdsBookEntry[] {
    if (value !== undefined && !Array.isArray(value)) throw new Error('OPDS 2出版物列表无效');
    const raw = value === undefined ? [] : value as Object[];
    if (raw.length > OpdsJsonCatalogNormalizer.MAX_SCAN_ENTRIES) throw new Error('OPDS 2出版物超过2000项限制');
    const selected = new Map<string, OpdsBookEntry>();
    raw.forEach((item: Object) => {
      const publication = this.record(item);
      const metadata = publication === undefined ? undefined : this.record(publication['metadata']);
      const links = publication === undefined ? undefined : this.records(publication['links'], 100);
      if (publication === undefined || metadata === undefined || links === undefined) return;
      const acquisition = links.find((link: Record<string, Object>) => this.relations(link['rel']).some(
        (relation: string) => relation.includes('acquisition')));
      if (acquisition === undefined) return;
      const acquisitionUrl = this.linkPolicy.resolve(baseUrl, acquisition['href']);
      const mediaType = this.optionalText(acquisition['type'], 200) ?? '';
      if (acquisitionUrl === undefined) return;
      const format = this.formatPolicy.resolve(acquisitionUrl, mediaType);
      const bookTitle = this.optionalText(metadata['title'], 300);
      if (format === 'unknown' || bookTitle === undefined || bookTitle.length === 0) return;
      const identifier = this.optionalText(metadata['identifier'], 1000) ?? acquisitionUrl;
      const summary = this.optionalText(metadata['description'], 10000, true) ?? '';
      const images = this.records(publication['images'], 20) ?? [];
      const coverUrl = this.cover(baseUrl, images.length > 0 ? images : links);
      const entry: OpdsBookEntry = { id: identifier.length > 0 ? identifier : acquisitionUrl,
        title: bookTitle, author: this.author(metadata['author']), summary: summary, coverUrl: coverUrl,
        acquisitionUrl: acquisitionUrl, format: format };
      if (!selected.has(entry.id) && selected.size < OpdsJsonCatalogNormalizer.MAX_RESULT_ENTRIES) {
        selected.set(entry.id, entry);
      }
    });
    return Array.from(selected.values());
  }

  private navigation(baseUrl: string, value: Object | undefined): OpdsNavigationEntry[] {
    if (value !== undefined && !Array.isArray(value)) throw new Error('OPDS 2导航列表无效');
    const raw = value === undefined ? [] : value as Object[];
    if (raw.length > OpdsJsonCatalogNormalizer.MAX_SCAN_ENTRIES) throw new Error('OPDS 2导航超过2000项限制');
    const selected = new Map<string, OpdsNavigationEntry>();
    raw.forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const title = this.optionalText(record['title'], 300);
      const url = this.linkPolicy.resolve(baseUrl, record['href']);
      if (title !== undefined && title.length > 0 && url !== undefined && !selected.has(url) &&
        selected.size < OpdsJsonCatalogNormalizer.MAX_RESULT_ENTRIES) selected.set(url, { title: title, url: url });
    });
    return Array.from(selected.values());
  }

  private author(value: Object | undefined): string {
    if (typeof value === 'string') return this.boundedAuthor([value]);
    if (!Array.isArray(value)) {
      const record = this.record(value);
      return record === undefined ? '未知作者' : this.boundedAuthor([this.optionalText(record['name'], 200) ?? '']);
    }
    const names: string[] = [];
    value.slice(0, 20).forEach((item: Object) => {
      if (typeof item === 'string') {
        const name = this.optionalText(item, 200);
        if (name !== undefined && name.length > 0) names.push(name);
      } else {
        const record = this.record(item);
        const name = record === undefined ? undefined : this.optionalText(record['name'], 200);
        if (name !== undefined && name.length > 0) names.push(name);
      }
    });
    return this.boundedAuthor(names);
  }

  private boundedAuthor(names: string[]): string {
    const joined = names.filter((name: string) => name.length > 0).join('、');
    return joined.length === 0 ? '未知作者' : joined.substring(0, 500);
  }

  private cover(baseUrl: string, links: Record<string, Object>[]): string {
    const cover = links.find((link: Record<string, Object>) => this.relations(link['rel']).some(
      (relation: string) => relation.includes('cover') || relation.includes('image')));
    return cover === undefined ? '' : this.linkPolicy.resolve(baseUrl, cover['href']) ?? '';
  }

  private relations(value: Object | undefined): string[] {
    if (typeof value === 'string') return value.length <= 500 ? [value] : [];
    if (!Array.isArray(value) || value.length > 20) return [];
    return value.filter((item: Object) => typeof item === 'string' && item.length <= 500) as string[];
  }

  private records(value: Object | undefined, maximum: number): Record<string, Object>[] | undefined {
    if (value === undefined) return [];
    if (!Array.isArray(value) || value.length > maximum) return undefined;
    const output: Record<string, Object>[] = [];
    value.forEach((item: Object) => {
      const record = this.record(item);
      if (record !== undefined) output.push(record);
    });
    return output;
  }

  private record(value: Object | undefined): Record<string, Object> | undefined {
    return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value) ?
      value as Record<string, Object> : undefined;
  }

  private optionalText(value: Object | undefined, maximum: number, allowLineBreaks: boolean = false): string | undefined {
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || value.length > maximum ||
      (allowLineBreaks ? /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/ : /[\u0000-\u001F\u007F]/).test(value)) {
      return undefined;
    }
    return value;
  }
}
