import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { ReaderChapter, ReaderDocument, SourceChapterContent } from './ReaderModels';

interface RuntimeCatalogRequest {
  sourceUrl: string;
  bookUrl: string;
}

interface RuntimeContentRequest {
  sourceUrl: string;
  chapterUrl: string;
  chapterIndex: number;
}

/** 后端书源规则执行后的图书目录。 */
export interface BookSourceRuntimeCatalog {
  name: string;
  author: string;
  intro: string;
  coverUrl: string;
  latestChapter: string;
  document: ReaderDocument;
}

/**
 * 调用MyTools后端的书源详情、目录与正文执行服务。
 */
export class BookSourceRuntimeReaderApi {
  private readonly client: AuthorizedApiClient;

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 加载书源图书的详情与完整目录。 */
  async catalog(sourceUrl: string, bookUrl: string): Promise<BookSourceRuntimeCatalog> {
    const request: RuntimeCatalogRequest = { sourceUrl: sourceUrl, bookUrl: bookUrl };
    const value = (await this.client.postJson('/api/app/v1/reader/source-runtime/catalog', request)).data;
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('书源目录响应无效');
    }
    const data = value as Record<string, Object>;
    const rawChapters = Array.isArray(data['chapters']) ? data['chapters'] as Object[] : [];
    const chapters: ReaderChapter[] = [];
    rawChapters.forEach((item: Object) => {
      const chapter = this.chapter(item);
      if (chapter !== undefined) chapters.push(chapter);
    });
    if (chapters.length === 0) throw new Error('书源没有返回可阅读章节');
    return {
      name: `${data['name'] ?? ''}`.trim().substring(0, 300),
      author: `${data['author'] ?? ''}`.trim().substring(0, 200),
      intro: `${data['intro'] ?? ''}`.trim().substring(0, 10000),
      coverUrl: `${data['coverUrl'] ?? ''}`.trim().substring(0, 4096),
      latestChapter: `${data['latestChapter'] ?? ''}`.trim().substring(0, 500),
      document: { kind: 'text', chapters: chapters, imageUris: [] }
    };
  }

  /** 加载一个书源章节的正文。 */
  async content(sourceUrl: string, chapter: ReaderChapter, chapterIndex: number,
    cancellation?: DownloadCancellationToken): Promise<SourceChapterContent> {
    const chapterUrl = `${chapter.resourceUri ?? ''}`.trim();
    if (chapterUrl.length === 0) throw new Error('章节地址无效');
    const request: RuntimeContentRequest = {
      sourceUrl: sourceUrl,
      chapterUrl: chapterUrl,
      chapterIndex: chapterIndex
    };
    const value = (await this.client.postJson('/api/app/v1/reader/source-runtime/content', request,
      cancellation)).data;
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('书源正文响应无效');
    }
    const data = value as Record<string, Object>;
    const text = `${data['text'] ?? ''}`.trim();
    if (text.length === 0) throw new Error('书源没有返回章节正文');
    const rawImages = Array.isArray(data['imageUrls']) ? data['imageUrls'] as Object[] : [];
    const remoteImageUrls = rawImages.map((item: Object) => `${item}`.trim())
      .filter((item: string) => item.startsWith('https://') || item.startsWith('http://'));
    return { kind: remoteImageUrls.length > 0 ? 'comic' : 'text', text: text,
      imageUris: [], remoteImageUrls: remoteImageUrls };
  }

  private chapter(value: Object): ReaderChapter | undefined {
    if (value === null || Array.isArray(value) || typeof value !== 'object') return undefined;
    const data = value as Record<string, Object>;
    const title = `${data['title'] ?? ''}`.trim().substring(0, 500);
    const resourceUri = `${data['resourceUri'] ?? ''}`.trim().substring(0, 4096);
    if (title.length === 0 || resourceUri.length === 0) return undefined;
    return { title: title, content: '', resourceUri: resourceUri };
  }
}
