import { ReaderChapter, ReaderDocument, SourceChapterContent } from './ReaderModels';

/** 本地书源目录缓存记录。 */
export interface BookSourceCatalogCacheRecord {
  schemaVersion: number;
  sourceUrl: string;
  bookUrl: string;
  cachedAt: number;
  chapters: ReaderChapter[];
}

/** 本地书源正文缓存记录。 */
export interface BookSourceChapterCacheRecord {
  schemaVersion: number;
  sourceUrl: string;
  bookUrl: string;
  chapterUrl: string;
  chapterIndex: number;
  cachedAt: number;
  content: SourceChapterContent;
}

/**
 * 校验书源本地缓存，损坏的单个文件不会影响其他图书。
 */
export class BookSourceLocalCachePolicy {
  private static readonly SCHEMA_VERSION: number = 1;
  private static readonly MAX_CHAPTERS: number = 10000;
  private static readonly MAX_TEXT_CHARACTERS: number = 2 * 1024 * 1024;
  private static readonly CATALOG_REFRESH_MILLIS: number = 6 * 60 * 60 * 1000;

  /** 创建只包含目录的缓存记录。 */
  catalogRecord(sourceUrl: string, bookUrl: string, document: ReaderDocument,
    cachedAt: number): BookSourceCatalogCacheRecord {
    return {
      schemaVersion: BookSourceLocalCachePolicy.SCHEMA_VERSION,
      sourceUrl,
      bookUrl,
      cachedAt,
      chapters: document.chapters.slice(0, BookSourceLocalCachePolicy.MAX_CHAPTERS).map(
        (chapter: ReaderChapter): ReaderChapter => ({
          title: chapter.title,
          content: '',
          resourceUri: chapter.resourceUri
        }))
    };
  }

  /** 创建单章正文缓存记录。 */
  chapterRecord(sourceUrl: string, bookUrl: string, chapter: ReaderChapter, chapterIndex: number,
    content: SourceChapterContent, cachedAt: number): BookSourceChapterCacheRecord {
    return {
      schemaVersion: BookSourceLocalCachePolicy.SCHEMA_VERSION,
      sourceUrl,
      bookUrl,
      chapterUrl: `${chapter.resourceUri ?? ''}`.trim(),
      chapterIndex,
      cachedAt,
      content: { kind: 'text', text: content.text, imageUris: [], remoteImageUrls: [] }
    };
  }

  /** 恢复合法目录，禁止缓存文件替换请求身份。 */
  catalog(value: Object, sourceUrl: string, bookUrl: string): ReaderDocument | undefined {
    const record = this.record(value);
    if (record === undefined || record['schemaVersion'] !== BookSourceLocalCachePolicy.SCHEMA_VERSION ||
      record['sourceUrl'] !== sourceUrl || record['bookUrl'] !== bookUrl ||
      !this.validTimestamp(record['cachedAt']) || !Array.isArray(record['chapters'])) return undefined;
    const rawChapters = record['chapters'] as Object[];
    if (rawChapters.length === 0 || rawChapters.length > BookSourceLocalCachePolicy.MAX_CHAPTERS) return undefined;
    const chapters: ReaderChapter[] = [];
    for (const value of rawChapters) {
      const chapter = this.record(value);
      if (chapter === undefined) return undefined;
      const title = this.text(chapter['title'], 500);
      const resourceUri = this.url(chapter['resourceUri']);
      if (title === undefined || resourceUri === undefined) return undefined;
      chapters.push({ title, content: '', resourceUri });
    }
    return { kind: 'text', chapters, imageUris: [] };
  }

  /** 恢复与当前章节身份完全一致的文本正文。 */
  chapter(value: Object, sourceUrl: string, bookUrl: string, chapter: ReaderChapter,
    chapterIndex: number): SourceChapterContent | undefined {
    const record = this.record(value);
    const chapterUrl = `${chapter.resourceUri ?? ''}`.trim();
    if (record === undefined || record['schemaVersion'] !== BookSourceLocalCachePolicy.SCHEMA_VERSION ||
      record['sourceUrl'] !== sourceUrl || record['bookUrl'] !== bookUrl || record['chapterUrl'] !== chapterUrl ||
      record['chapterIndex'] !== chapterIndex || !this.validTimestamp(record['cachedAt'])) return undefined;
    const content = this.record(record['content']);
    if (content === undefined || content['kind'] !== 'text') return undefined;
    const text = this.text(content['text'], BookSourceLocalCachePolicy.MAX_TEXT_CHARACTERS);
    if (text === undefined) return undefined;
    return { kind: 'text', text, imageUris: [], remoteImageUrls: [] };
  }

  /** 判断目录是否应该在不阻塞打开的前提下后台刷新。 */
  shouldRefreshCatalog(cachedAt: number, now: number): boolean {
    return !this.validTimestamp(cachedAt) || now - cachedAt >= BookSourceLocalCachePolicy.CATALOG_REFRESH_MILLIS;
  }

  private record(value: Object | undefined): Record<string, Object> | undefined {
    return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value) ?
      value as Record<string, Object> : undefined;
  }

  private text(value: Object | undefined, maxLength: number): string | undefined {
    if (typeof value !== 'string') return undefined;
    const normalized = value.trim();
    return normalized.length > 0 && normalized.length <= maxLength ? normalized : undefined;
  }

  private url(value: Object | undefined): string | undefined {
    const normalized = this.text(value, 4096);
    return normalized !== undefined && /^https?:\/\//i.test(normalized) ? normalized : undefined;
  }

  private validTimestamp(value: Object | undefined): boolean {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
  }
}
