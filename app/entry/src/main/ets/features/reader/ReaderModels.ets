export type BookOrigin = 'source' | 'remote' | 'local';
export type BookFormat = 'txt' | 'epub' | 'pdf' | 'mobi' | 'azw3' | 'cbz' | 'cbr' | 'unknown';

export interface BookSourceAlternative {
  sourceId: string;
  sourceName: string;
  resourceUri: string;
  coverUrl: string;
  remoteCoverUrl?: string;
  intro: string;
  lastChapter: string;
}

export interface Book {
  id: string;
  name: string;
  author: string;
  coverUrl: string;
  remoteCoverUrl?: string;
  origin: BookOrigin;
  format: BookFormat;
  resourceUri: string;
  sourceId: string;
  progress: number;
  sourceAlternatives?: BookSourceAlternative[];
  updatedAt?: number;
  revision?: number;
}

export interface ShelfBookTombstone {
  id: string;
  name: string;
  author: string;
  origin: 'source' | 'remote';
  format: BookFormat;
  resourceUri: string;
  sourceId: string;
  remoteCoverUrl: string;
  updatedAt: number;
  revision: number;
}

export interface BookSource {
  bookSourceUrl: string;
  bookSourceName: string;
  bookSourceGroup: string;
  bookSourceType: number;
  enabled: boolean;
  enabledExplore: boolean;
  searchUrl: string;
  exploreUrl: string;
  header: string;
  ruleSearch: Record<string, Object>;
  ruleExplore: Record<string, Object>;
  ruleBookInfo: Record<string, Object>;
  ruleToc: Record<string, Object>;
  ruleContent: Record<string, Object>;
  requiresScriptRuntime: boolean;
  healthStatus?: 'unknown' | 'available' | 'partial' | 'unavailable' | 'unsupported';
  healthLatencyMs?: number;
  healthCheckedAt?: number;
  healthMessage?: string;
  healthHistory?: BookSourceHealthSample[];
  healthSuccessRate?: number;
  healthAverageLatencyMs?: number;
  healthConsecutiveFailures?: number;
  healthDegraded?: boolean;
  syncUpdatedAt?: number;
  syncRevision?: number;
}

export interface BookSourceTombstone {
  sourceUrl: string;
  snapshotJson: string;
  updatedAt: number;
  revision: number;
}

export interface BookSourceHealthSample {
  status: 'available' | 'partial' | 'unavailable' | 'unsupported';
  latencyMs: number;
  checkedAt: number;
}

export interface BookSourceSearchResult {
  name: string;
  author: string;
  intro: string;
  lastChapter: string;
  coverUrl: string;
  remoteCoverUrl?: string;
  bookUrl: string;
  sourceUrl: string;
  sourceName: string;
}

export interface BookSourceExploreCategory {
  title: string;
  url: string;
}

export interface BookSourceImportResult {
  sources: BookSource[];
  rejectedCount: number;
  scriptedCount: number;
}

export type ReaderTheme = 'paper' | 'white' | 'night' | 'green';
export type PageTurnMode = 'slide' | 'cover' | 'scroll';
export type ComicDirection = 'ltr' | 'rtl';
export type ComicPageMode = 'single' | 'double' | 'long';
export type ComicFitMode = 'contain' | 'cover';
export type ReaderOrientation = 'system' | 'portrait' | 'landscape';
export type ReaderFontFamily = 'system' | 'sans' | 'serif' | 'monospace';

export interface ReaderSettings {
  fontFamily: ReaderFontFamily;
  fontSize: number;
  lineHeight: number;
  paragraphSpacing: number;
  orientation: ReaderOrientation;
  horizontalPadding: number;
  brightness: number;
  theme: ReaderTheme;
  pageTurnMode: PageTurnMode;
  comicDirection: ComicDirection;
  comicPageMode: ComicPageMode;
  comicFitMode: ComicFitMode;
  comicScale: number;
  comicPreload: number;
}

export interface ReadingProgress {
  bookId: string;
  chapterTitle: string;
  locator: number;
  percentage: number;
  updatedAt: number;
  revision?: number;
  deleted?: boolean;
}

export interface ReadingProgressTombstone {
  bookId: string;
  chapterTitle: string;
  locator: number;
  percentage: number;
  updatedAt: number;
  revision: number;
  deleted: boolean;
}

export interface ReaderBookmark {
  id: string;
  bookId: string;
  chapterTitle: string;
  locator: number;
  note: string;
  createdAt: number;
  updatedAt?: number;
  revision?: number;
}

export interface ReaderAnnotation {
  id: string;
  bookId: string;
  chapterTitle: string;
  locator: number;
  note: string;
  createdAt: number;
  updatedAt?: number;
  revision?: number;
}

export interface ReaderMarkerTombstone {
  id: string;
  kind: 'BOOKMARK' | 'ANNOTATION';
  bookId: string;
  chapterTitle: string;
  locator: number;
  note: string;
  createdAt: number;
  updatedAt: number;
  revision: number;
}

export interface ReadingStatistic {
  bookId: string;
  totalMillis: number;
  sessionCount: number;
  lastReadAt: number;
}

export interface ReaderChapter {
  title: string;
  content: string;
  resourceUri?: string;
  blocks?: ReaderBlock[];
}

export interface ReaderBlock {
  kind: 'text' | 'paragraph' | 'heading' | 'quote' | 'footnote' | 'image' | 'code' | 'list' | 'math' |
    'diagram';
  text: string;
  uri: string;
  level?: number;
}

export interface ReaderDocument {
  kind: 'text' | 'comic';
  chapters: ReaderChapter[];
  imageUris: string[];
  encoding?: string;
  coverUri?: string;
}

export interface SourceChapterContent {
  kind: 'text' | 'comic';
  text: string;
  imageUris: string[];
  remoteImageUrls: string[];
}

/**
 * 返回阅读器的默认显示配置。
 */
export function defaultReaderSettings(): ReaderSettings {
  return {
    fontFamily: 'system',
    fontSize: 19,
    lineHeight: 1.8,
    paragraphSpacing: 14,
    orientation: 'system',
    horizontalPadding: 24,
    brightness: 1,
    theme: 'paper',
    pageTurnMode: 'slide',
    comicDirection: 'ltr',
    comicPageMode: 'single',
    comicFitMode: 'contain',
    comicScale: 1,
    comicPreload: 2
  };
}

/**
 * 根据文件名识别阅读格式，CBZ 与 CBR 作为漫画容器处理。
 */
export function resolveBookFormat(name: string): BookFormat {
  const lowerName = name.toLowerCase();
  if (lowerName.endsWith('.txt')) return 'txt';
  if (lowerName.endsWith('.epub')) return 'epub';
  if (lowerName.endsWith('.pdf')) return 'pdf';
  if (lowerName.endsWith('.mobi')) return 'mobi';
  if (lowerName.endsWith('.azw3')) return 'azw3';
  if (lowerName.endsWith('.cbz') || lowerName.endsWith('.zip')) return 'cbz';
  if (lowerName.endsWith('.cbr')) return 'cbr';
  return 'unknown';
}

/**
 * 从 URI 或远程路径中提取可展示的文件名。
 */
export function bookNameFromUri(uri: string): string {
  const normalized = uri.split('?')[0];
  const slash = normalized.lastIndexOf('/');
  const encodedName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
  try {
    return decodeURIComponent(encodedName);
  } catch (_) {
    return encodedName;
  }
}
