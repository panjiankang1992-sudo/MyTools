import { OpdsBookEntry, OpdsCatalog, OpdsNavigationEntry } from './OpdsModels';
import { OpdsAcquisitionFormatPolicy } from './OpdsAcquisitionFormatPolicy';
import { OpdsLinkPolicy } from './OpdsLinkPolicy';

/**
 * 对不可信OPDS 1 Atom目录执行有界文本解析和逐项隔离。
 */
export class OpdsAtomCatalogNormalizer {
  private static readonly MAX_SCAN_ENTRIES: number = 2000;
  private static readonly MAX_RESULT_ENTRIES: number = 1000;
  private readonly formatPolicy: OpdsAcquisitionFormatPolicy = new OpdsAcquisitionFormatPolicy();
  private readonly linkPolicy: OpdsLinkPolicy = new OpdsLinkPolicy();

  /** 解析Atom目录并仅输出有界HTTPS图书与导航条目。 */
  normalize(baseUrl: string, text: string): OpdsCatalog {
    const rawTitle = this.decodeXml(this.firstMatch(text, /<title(?:\s[^>]*)?>([\s\S]*?)<\/title>/i));
    const title = this.text(rawTitle, 300) ?? 'OPDS书库';
    const entries = text.match(/<entry(?:\s[^>]*)?>[\s\S]*?<\/entry>/gi) ?? [];
    if (entries.length > OpdsAtomCatalogNormalizer.MAX_SCAN_ENTRIES) {
      throw new Error('OPDS 1条目超过2000项限制');
    }
    const books = new Map<string, OpdsBookEntry>();
    const navigation = new Map<string, OpdsNavigationEntry>();
    entries.forEach((entry: string) => this.entry(baseUrl, entry, books, navigation));
    return { title: title.length > 0 ? title : 'OPDS书库', books: Array.from(books.values()),
      navigation: Array.from(navigation.values()) };
  }

  private entry(baseUrl: string, entry: string, books: Map<string, OpdsBookEntry>,
    navigation: Map<string, OpdsNavigationEntry>): void {
    const rawTitle = this.decodeXml(this.firstMatch(entry, /<title(?:\s[^>]*)?>([\s\S]*?)<\/title>/i));
    const title = this.text(rawTitle, 300);
    if (title === undefined || title.length === 0) return;
    const links = entry.match(/<link\s[^>]*\/?\s*>/gi) ?? [];
    if (links.length > 100) return;
    const acquisition = links.find((link: string) => this.attribute(link, 'rel').includes('acquisition'));
    if (acquisition !== undefined) {
      if (books.size >= OpdsAtomCatalogNormalizer.MAX_RESULT_ENTRIES) return;
      const href = this.linkPolicy.resolve(baseUrl, this.attribute(acquisition, 'href'));
      if (href === undefined) return;
      const format = this.formatPolicy.resolve(href, this.attribute(acquisition, 'type'));
      if (format === 'unknown') return;
      const rawId = this.decodeXml(this.firstMatch(entry, /<id(?:\s[^>]*)?>([\s\S]*?)<\/id>/i));
      const id = this.text(rawId, 1000) ?? href;
      const authorBlock = this.firstMatch(entry, /<author(?:\s[^>]*)?>([\s\S]*?)<\/author>/i, false);
      const rawAuthor = this.decodeXml(this.firstMatch(authorBlock,
        /<name(?:\s[^>]*)?>([\s\S]*?)<\/name>/i));
      const author = this.text(rawAuthor, 500) ?? '';
      const rawSummary = this.decodeXml(this.firstMatch(entry,
        /<(?:summary|content)(?:\s[^>]*)?>([\s\S]*?)<\/(?:summary|content)>/i));
      const summary = this.text(rawSummary, 10000, true) ?? '';
      if (!books.has(id)) books.set(id, { id: id.length > 0 ? id : href, title: title,
        author: author.length > 0 ? author : '未知作者', summary: summary,
        coverUrl: this.cover(baseUrl, links), acquisitionUrl: href, format: format });
      return;
    }
    if (navigation.size >= OpdsAtomCatalogNormalizer.MAX_RESULT_ENTRIES) return;
    const catalogLink = links.find((link: string) => {
      const type = this.attribute(link, 'type').toLowerCase();
      return type.includes('atom+xml') || type.includes('opds');
    });
    if (catalogLink === undefined) return;
    const url = this.linkPolicy.resolve(baseUrl, this.attribute(catalogLink, 'href'));
    if (url !== undefined && !navigation.has(url)) navigation.set(url, { title: title, url: url });
  }

  private cover(baseUrl: string, links: string[]): string {
    const link = links.find((value: string) => {
      const relation = this.attribute(value, 'rel').toLowerCase();
      return relation.includes('cover') || relation.includes('image');
    });
    return link === undefined ? '' : this.linkPolicy.resolve(baseUrl, this.attribute(link, 'href')) ?? '';
  }

  private attribute(tag: string, name: string): string {
    const match = tag.match(new RegExp(`${name}\\s*=\\s*["']([^"']*)["']`, 'i'));
    return match === null ? '' : this.decodeXml(match[1]);
  }

  private firstMatch(value: string, pattern: RegExp, stripTags: boolean = true): string {
    const match = value.match(pattern);
    if (match === null) return '';
    const content = stripTags ? match[1].replace(/<[^>]+>/g, ' ') : match[1];
    return content.replace(/\s+/g, ' ').trim();
  }

  private decodeXml(value: string): string {
    return value.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1').replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, '&');
  }

  private text(value: string, maximum: number, allowLineBreaks: boolean = false): string | undefined {
    if (value.length > maximum ||
      (allowLineBreaks ? /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/ : /[\u0000-\u001F\u007F]/).test(value)) {
      return undefined;
    }
    return value;
  }
}
