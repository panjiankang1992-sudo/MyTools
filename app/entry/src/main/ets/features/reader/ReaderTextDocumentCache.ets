import { ReaderChapter, ReaderDocument } from './ReaderModels';

interface CachedTextDocument {
  document: ReaderDocument;
  characters: number;
}

/** 有界的纯文本解析缓存；只保存不可变字符串，不持有临时文件或图片路径。 */
export class ReaderTextDocumentCache {
  private readonly entries: Map<string, CachedTextDocument> = new Map();
  private characters: number = 0;

  /** 返回独立章节对象，阅读器对正文和目录的修改不会污染缓存。 */
  get(key: string): ReaderDocument | undefined {
    const value = this.entries.get(key);
    if (value === undefined) return undefined;
    this.entries.delete(key);
    this.entries.set(key, value);
    return this.copy(value.document);
  }

  /** 最多保存四本、合计十二兆字符；超限按最近使用顺序淘汰。 */
  put(key: string, document: ReaderDocument): void {
    // 磁盘索引和富文本依赖加载器工作区，禁止用此缓存延长失效路径的寿命。
    if (document.kind !== 'text' || document.imageUris.length > 0 || document.coverUri !== undefined ||
      document.chapters.some(chapter => chapter.resourceUri !== undefined || chapter.blocks !== undefined)) return;
    const characters = document.chapters.reduce((total, chapter) => total + chapter.title.length + chapter.content.length, 0);
    if (characters <= 0 || characters > 8 * 1024 * 1024) return;
    const previous = this.entries.get(key);
    if (previous !== undefined) this.characters -= previous.characters;
    this.entries.delete(key);
    this.entries.set(key, { document: this.copy(document), characters });
    this.characters += characters;
    while (this.entries.size > 4 || this.characters > 12 * 1024 * 1024) {
      const oldest = Array.from(this.entries.keys())[0];
      this.characters -= this.entries.get(oldest)?.characters ?? 0;
      this.entries.delete(oldest);
    }
  }

  /** 账号切换和清理缓存时立即释放所有解析快照。 */
  clear(): void {
    this.entries.clear();
    this.characters = 0;
  }

  private copy(document: ReaderDocument): ReaderDocument {
    return { kind: 'text', encoding: document.encoding, imageUris: [],
      chapters: document.chapters.map((chapter: ReaderChapter): ReaderChapter =>
        ({ title: chapter.title, content: chapter.content })) };
  }
}
