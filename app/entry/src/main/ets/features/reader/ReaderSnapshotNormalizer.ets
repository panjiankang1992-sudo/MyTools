import { Book, BookFormat, BookOrigin, BookSourceAlternative, ComicDirection, ComicFitMode, ComicPageMode,
  PageTurnMode, ReaderAnnotation, ReaderBookmark, ReaderFontFamily, ReaderMarkerTombstone, ReaderOrientation,
  ReaderSettings, ReaderTheme,
  ReadingProgress, ReadingProgressTombstone, ReadingStatistic, ShelfBookTombstone, defaultReaderSettings } from './ReaderModels';

interface NormalizedReaderMarker {
  id: string;
  bookId: string;
  chapterTitle: string;
  locator: number;
  note: string;
  createdAt: number;
  updatedAt: number;
  revision: number;
}

/**
 * 对不可信的持久化阅读快照执行字段校验、配额限制和逐项损坏隔离。
 */
export class ReaderSnapshotNormalizer {
  private static readonly MAX_BOOKS: number = 5000;
  private static readonly MAX_PROGRESS: number = 5000;
  private static readonly MAX_MARKERS: number = 10000;
  private static readonly MAX_STATISTICS: number = 5000;
  private static readonly MAX_SAFE_VALUE: number = 9007199254740991;

  /**
   * 恢复最多5000本合法图书，并按图书ID保留快照中最后一个合法版本。
   */
  normalizeBooks(value: Object | undefined): Book[] {
    if (!Array.isArray(value)) return [];
    const books = new Map<string, Book>();
    value.slice(0, ReaderSnapshotNormalizer.MAX_BOOKS * 2).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const id = this.string(record['id'], 1000);
      const name = this.string(record['name'], 300);
      const author = this.string(record['author'], 200, true);
      const resourceUri = this.string(record['resourceUri'], 4096);
      const sourceId = this.string(record['sourceId'], 4096, true);
      const origin = this.origin(record['origin']);
      const format = this.format(record['format']);
      if (id === undefined || name === undefined || author === undefined || resourceUri === undefined ||
        sourceId === undefined || origin === undefined || format === undefined) return;
      const book: Book = {
        id, name, author,
        coverUrl: this.string(record['coverUrl'], 4096, true) ?? '',
        origin, format, resourceUri, sourceId,
        tags: this.tags(record['tags']),
        progress: this.number(record['progress'], 0, 100),
        sourceAlternatives: this.alternatives(record['sourceAlternatives'])
      };
      const remoteCover = this.string(record['remoteCoverUrl'], 4096, true);
      if (remoteCover !== undefined) book.remoteCoverUrl = remoteCover;
      const cacheVersion = this.string(record['cacheVersion'], 128, true);
      if (cacheVersion !== undefined && /^[a-f0-9]{16,128}$/.test(cacheVersion)) book.cacheVersion = cacheVersion;
      book.updatedAt = this.integer(record['updatedAt'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE);
      book.revision = this.integer(record['revision'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE);
      books.set(id, book);
    });
    return Array.from(books.values()).slice(-ReaderSnapshotNormalizer.MAX_BOOKS);
  }

  /**
   * 恢复书架删除墓碑，拒绝设备本地图书墓碑。
   */
  normalizeShelfTombstones(value: Object | undefined): ShelfBookTombstone[] {
    if (!Array.isArray(value)) return [];
    const tombstones = new Map<string, ShelfBookTombstone>();
    value.slice(0, ReaderSnapshotNormalizer.MAX_BOOKS * 2).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const id = this.string(record['id'], 1000);
      const name = this.string(record['name'], 300);
      const author = this.string(record['author'], 200, true);
      const origin = this.origin(record['origin']);
      const format = this.format(record['format']);
      const resourceUri = this.string(record['resourceUri'], 4096);
      const sourceId = this.string(record['sourceId'], 4096);
      if (id === undefined || name === undefined || author === undefined ||
        (origin !== 'source' && origin !== 'remote') || format === undefined || resourceUri === undefined ||
        sourceId === undefined) return;
      tombstones.set(id, {
        id, name, author, origin, format, resourceUri, sourceId,
        remoteCoverUrl: this.string(record['remoteCoverUrl'], 4096, true) ?? '',
        updatedAt: this.integer(record['updatedAt'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE),
        revision: this.integer(record['revision'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE)
      });
    });
    return Array.from(tombstones.values()).slice(-ReaderSnapshotNormalizer.MAX_BOOKS);
  }

  /**
   * 恢复阅读进度并夹紧定位、百分比、时间和修订号。
   */
  normalizeProgress(value: Object | undefined): ReadingProgress[] {
    if (!Array.isArray(value)) return [];
    const progress = new Map<string, ReadingProgress>();
    value.slice(0, ReaderSnapshotNormalizer.MAX_PROGRESS * 2).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const bookId = this.string(record['bookId'], 1000);
      const chapterTitle = this.string(record['chapterTitle'], 500);
      if (bookId === undefined || chapterTitle === undefined) return;
      progress.set(bookId, {
        bookId, chapterTitle,
        locator: this.integer(record['locator'], 0, 1000000000),
        percentage: this.number(record['percentage'], 0, 100),
        updatedAt: this.integer(record['updatedAt'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE),
        revision: this.integer(record['revision'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE),
        deleted: record['deleted'] === true
      });
    });
    return Array.from(progress.values()).slice(-ReaderSnapshotNormalizer.MAX_PROGRESS);
  }

  /**
   * 恢复阅读进度墓碑并强制维持删除语义。
   */
  normalizeProgressTombstones(value: Object | undefined): ReadingProgressTombstone[] {
    const tombstones: ReadingProgressTombstone[] = [];
    this.normalizeProgress(value).forEach((item: ReadingProgress) => {
      const tombstone: ReadingProgressTombstone = {
        bookId: item.bookId,
        chapterTitle: item.chapterTitle,
        locator: item.locator,
        percentage: item.percentage,
        updatedAt: item.updatedAt,
        revision: item.revision ?? 0,
        deleted: true
      };
      tombstones.push(tombstone);
    });
    return tombstones;
  }

  /**
   * 恢复书签；调用方应与批注和墓碑共同应用10000项总预算。
   */
  normalizeBookmarks(value: Object | undefined, limit: number = ReaderSnapshotNormalizer.MAX_MARKERS): ReaderBookmark[] {
    return this.markers(value, 'BOOKMARK', limit) as ReaderBookmark[];
  }

  /**
   * 恢复批注；调用方应与书签和墓碑共同应用10000项总预算。
   */
  normalizeAnnotations(value: Object | undefined, limit: number = ReaderSnapshotNormalizer.MAX_MARKERS): ReaderAnnotation[] {
    return this.markers(value, 'ANNOTATION', limit) as ReaderAnnotation[];
  }

  /**
   * 恢复阅读标记删除墓碑。
   */
  normalizeMarkerTombstones(value: Object | undefined,
    limit: number = ReaderSnapshotNormalizer.MAX_MARKERS): ReaderMarkerTombstone[] {
    if (!Array.isArray(value) || limit <= 0) return [];
    const tombstones = new Map<string, ReaderMarkerTombstone>();
    value.slice(0, Math.min(value.length, limit * 2)).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const id = this.markerId(record['id']);
      const kind = record['kind'];
      const bookId = this.string(record['bookId'], 1000);
      const chapterTitle = this.string(record['chapterTitle'], 500);
      const note = this.string(record['note'], 2000, true, true);
      if (id === undefined || (kind !== 'BOOKMARK' && kind !== 'ANNOTATION') || bookId === undefined ||
        chapterTitle === undefined || note === undefined) return;
      tombstones.set(id, {
        id, kind: kind as 'BOOKMARK' | 'ANNOTATION', bookId, chapterTitle, note,
        locator: this.integer(record['locator'], 0, 1000000000),
        createdAt: this.integer(record['createdAt'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE),
        updatedAt: this.integer(record['updatedAt'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE),
        revision: this.integer(record['revision'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE)
      });
    });
    return Array.from(tombstones.values()).slice(-limit);
  }

  /**
   * 恢复有界阅读统计并按图书ID去重。
   */
  normalizeStatistics(value: Object | undefined): ReadingStatistic[] {
    if (!Array.isArray(value)) return [];
    const statistics = new Map<string, ReadingStatistic>();
    value.slice(0, ReaderSnapshotNormalizer.MAX_STATISTICS * 2).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const bookId = this.string(record['bookId'], 1000);
      if (bookId === undefined) return;
      statistics.set(bookId, {
        bookId,
        totalMillis: this.integer(record['totalMillis'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE),
        sessionCount: this.integer(record['sessionCount'], 0, 1000000000),
        lastReadAt: this.integer(record['lastReadAt'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE)
      });
    });
    return Array.from(statistics.values()).slice(-ReaderSnapshotNormalizer.MAX_STATISTICS);
  }

  /**
   * 恢复阅读设置，未知枚举回退默认值，数值按界面有效范围夹紧。
   */
  normalizeSettings(value: Object | undefined): ReaderSettings {
    const defaults = defaultReaderSettings();
    const record = this.record(value);
    if (record === undefined) return defaults;
    const theme = record['theme'];
    const pageTurnMode = record['pageTurnMode'];
    const comicDirection = record['comicDirection'];
    const comicPageMode = record['comicPageMode'];
    const comicFitMode = record['comicFitMode'];
    const orientation = record['orientation'];
    const fontFamily = record['fontFamily'];
    return {
      fontFamily: fontFamily === 'system' || fontFamily === 'sans' || fontFamily === 'serif' ||
        fontFamily === 'monospace' ? fontFamily as ReaderFontFamily : defaults.fontFamily,
      fontSize: this.number(record['fontSize'], 14, 32, defaults.fontSize),
      lineHeight: this.number(record['lineHeight'], 1.2, 2.4, defaults.lineHeight),
      paragraphSpacing: this.number(record['paragraphSpacing'], 0, 32, defaults.paragraphSpacing),
      orientation: orientation === 'system' || orientation === 'portrait' || orientation === 'landscape' ?
        orientation as ReaderOrientation : defaults.orientation,
      horizontalPadding: this.number(record['horizontalPadding'], 12, 48, defaults.horizontalPadding),
      brightness: this.number(record['brightness'], 0.35, 1, defaults.brightness),
      theme: theme === 'paper' || theme === 'white' || theme === 'night' || theme === 'green' ?
        theme as ReaderTheme : defaults.theme,
      pageTurnMode: pageTurnMode === 'slide' || pageTurnMode === 'cover' || pageTurnMode === 'scroll' ?
        pageTurnMode as PageTurnMode : defaults.pageTurnMode,
      comicDirection: comicDirection === 'ltr' || comicDirection === 'rtl' ?
        comicDirection as ComicDirection : defaults.comicDirection,
      comicPageMode: comicPageMode === 'single' || comicPageMode === 'double' || comicPageMode === 'long' ?
        comicPageMode as ComicPageMode : defaults.comicPageMode,
      comicFitMode: comicFitMode === 'contain' || comicFitMode === 'cover' ?
        comicFitMode as ComicFitMode : defaults.comicFitMode,
      comicScale: this.number(record['comicScale'], 1, 3, defaults.comicScale),
      comicPreload: this.integer(record['comicPreload'], 1, 5, defaults.comicPreload)
    };
  }

  private markers(value: Object | undefined, kind: 'BOOKMARK' | 'ANNOTATION', limit: number): NormalizedReaderMarker[] {
    if (!Array.isArray(value) || limit <= 0) return [];
    const markers = new Map<string, NormalizedReaderMarker>();
    value.slice(0, Math.min(value.length, limit * 2)).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const id = this.markerId(record['id']);
      const bookId = this.string(record['bookId'], 1000);
      const chapterTitle = this.string(record['chapterTitle'], 500);
      const note = this.string(record['note'], 2000, true, true);
      if (id === undefined || bookId === undefined || chapterTitle === undefined || note === undefined) return;
      const marker: NormalizedReaderMarker = {
        id, bookId, chapterTitle, note,
        locator: this.integer(record['locator'], 0, 1000000000),
        createdAt: this.integer(record['createdAt'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE),
        updatedAt: this.integer(record['updatedAt'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE),
        revision: this.integer(record['revision'], 0, ReaderSnapshotNormalizer.MAX_SAFE_VALUE)
      };
      markers.set(id, marker);
    });
    return Array.from(markers.values()).slice(-limit);
  }

  private alternatives(value: Object | undefined): BookSourceAlternative[] {
    if (!Array.isArray(value)) return [];
    const alternatives: BookSourceAlternative[] = [];
    value.slice(0, 32).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const sourceId = this.string(record['sourceId'], 4096);
      const sourceName = this.string(record['sourceName'], 300);
      const resourceUri = this.string(record['resourceUri'], 4096);
      if (sourceId === undefined || sourceName === undefined || resourceUri === undefined) return;
      const alternative: BookSourceAlternative = {
        sourceId, sourceName, resourceUri,
        coverUrl: this.string(record['coverUrl'], 4096, true) ?? '',
        intro: this.string(record['intro'], 10000, true, true) ?? '',
        lastChapter: this.string(record['lastChapter'], 500, true) ?? ''
      };
      const remoteCover = this.string(record['remoteCoverUrl'], 4096, true);
      if (remoteCover !== undefined) alternative.remoteCoverUrl = remoteCover;
      alternatives.push(alternative);
    });
    return alternatives;
  }

  private tags(value: Object | undefined): string[] {
    if (!Array.isArray(value)) return [];
    const tags: string[] = [];
    value.slice(0, 24).forEach((item: Object) => {
      const tag = this.string(item, 40, false);
      if (tag !== undefined && !tags.includes(tag)) tags.push(tag);
    });
    return tags.slice(0, 12);
  }

  private record(value: Object | undefined): Record<string, Object> | undefined {
    return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value) ?
      value as Record<string, Object> : undefined;
  }

  private string(value: Object | undefined, maximum: number, allowEmpty: boolean = false,
    multiline: boolean = false): string | undefined {
    if (typeof value !== 'string' || value.length > maximum || (!allowEmpty && value.length === 0)) return undefined;
    const invalid = multiline ? /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/ : /[\u0000-\u001F\u007F]/;
    return invalid.test(value) ? undefined : value;
  }

  private markerId(value: Object | undefined): string | undefined {
    return typeof value === 'string' && /^[A-Za-z0-9-]{1,100}$/.test(value) ? value : undefined;
  }

  private origin(value: Object | undefined): BookOrigin | undefined {
    return value === 'source' || value === 'remote' || value === 'local' ? value as BookOrigin : undefined;
  }

  private format(value: Object | undefined): BookFormat | undefined {
    return value === 'txt' || value === 'epub' || value === 'pdf' || value === 'mobi' || value === 'azw3' ||
      value === 'cbz' || value === 'cbr' || value === 'unknown' ? value as BookFormat : undefined;
  }

  private integer(value: Object | undefined, minimum: number, maximum: number, fallback: number = minimum): number {
    if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
    return Math.min(maximum, Math.max(minimum, Math.floor(value)));
  }

  private number(value: Object | undefined, minimum: number, maximum: number, fallback: number = minimum): number {
    if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
    return Math.min(maximum, Math.max(minimum, value));
  }
}
