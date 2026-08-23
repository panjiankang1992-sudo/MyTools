import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { DigestTool } from '../tools/DigestTool';
import { ReaderChapter, ReaderDocument, SourceChapterContent } from './ReaderModels';
import { BookSourceLocalCachePolicy } from './BookSourceLocalCachePolicy';

interface CachedCatalogResult {
  document: ReaderDocument;
  cachedAt: number;
}

/**
 * 将书架中网络小说的目录和正文持久化到应用私有目录。
 */
export class BookSourceLocalCache {
  private static readonly ROOT_NAME: string = 'reader-source-cache-v1';
  private static readonly MAX_RECORD_BYTES: number = 8 * 1024 * 1024;
  private static readonly MAX_CHAPTER_FILES_PER_BOOK: number = 64;
  private readonly digestTool: DigestTool = new DigestTool();
  private readonly policy: BookSourceLocalCachePolicy = new BookSourceLocalCachePolicy();

  /** 读取本地目录缓存。 */
  async catalog(context: common.UIAbilityContext, accountScope: string, sourceUrl: string,
    bookUrl: string): Promise<CachedCatalogResult | undefined> {
    const path = `${await this.bookDirectory(context, accountScope, sourceUrl, bookUrl)}/catalog.json`;
    const parsed = this.readRecord(path);
    if (parsed === undefined) return undefined;
    const document = this.policy.catalog(parsed, sourceUrl, bookUrl);
    if (document === undefined) {
      this.deleteQuietly(path);
      return undefined;
    }
    const cachedAt = (parsed as Record<string, Object>)['cachedAt'] as number;
    return { document, cachedAt };
  }

  /** 原子保存目录，正文不会混入目录文件。 */
  async putCatalog(context: common.UIAbilityContext, accountScope: string, sourceUrl: string,
    bookUrl: string, document: ReaderDocument): Promise<void> {
    const directory = await this.bookDirectory(context, accountScope, sourceUrl, bookUrl);
    const record = this.policy.catalogRecord(sourceUrl, bookUrl, document, Date.now());
    this.writeRecord(directory, `${directory}/catalog.json`, record);
  }

  /** 读取当前图书的单章文本缓存。 */
  async chapter(context: common.UIAbilityContext, accountScope: string, sourceUrl: string, bookUrl: string,
    chapter: ReaderChapter, chapterIndex: number): Promise<SourceChapterContent | undefined> {
    const path = await this.chapterPath(context, accountScope, sourceUrl, bookUrl, chapter, chapterIndex);
    const parsed = this.readRecord(path);
    if (parsed === undefined) return undefined;
    const content = this.policy.chapter(parsed, sourceUrl, bookUrl, chapter, chapterIndex);
    if (content === undefined) this.deleteQuietly(path);
    return content;
  }

  /** 原子保存文本章节并限制单本图书的缓存数量。 */
  async putChapter(context: common.UIAbilityContext, accountScope: string, sourceUrl: string, bookUrl: string,
    chapter: ReaderChapter, chapterIndex: number, content: SourceChapterContent): Promise<void> {
    if (content.kind !== 'text' || content.text.trim().length === 0) return;
    const directory = await this.bookDirectory(context, accountScope, sourceUrl, bookUrl);
    const path = await this.chapterPath(context, accountScope, sourceUrl, bookUrl, chapter, chapterIndex);
    const record = this.policy.chapterRecord(sourceUrl, bookUrl, chapter, chapterIndex, content, Date.now());
    this.writeRecord(directory, path, record);
    this.trimBook(directory, path);
  }

  /** 判断命中的目录是否需要后台更新。 */
  shouldRefreshCatalog(cachedAt: number): boolean {
    return this.policy.shouldRefreshCatalog(cachedAt, Date.now());
  }

  /** 清除指定账户下单本书的目录和章节缓存。 */
  async clearBook(context: common.UIAbilityContext, accountScope: string, sourceUrl: string,
    bookUrl: string): Promise<void> {
    const directory = await this.bookDirectory(context, accountScope, sourceUrl, bookUrl);
    try {
      if (!fs.accessSync(directory)) return;
      // 缓存目录只允许保存受控的目录文件和章节文件，逐项删除后再移除目录。
      fs.listFileSync(directory).forEach((name: string) => {
        if (name === 'catalog.json' || /^chapter-[0-9]+-[a-f0-9]{64}\.json$/.test(name) ||
          name.endsWith('.part')) {
          this.deleteQuietly(`${directory}/${name}`);
        }
      });
      fs.rmdirSync(directory);
    } catch (_) {
      throw new Error('无法清除书源本地缓存');
    }
  }

  private async chapterPath(context: common.UIAbilityContext, accountScope: string, sourceUrl: string,
    bookUrl: string, chapter: ReaderChapter, chapterIndex: number): Promise<string> {
    const chapterUrl = `${chapter.resourceUri ?? ''}`.trim();
    if (!/^https?:\/\//i.test(chapterUrl) || chapterIndex < 0) throw new Error('章节缓存身份无效');
    const directory = await this.bookDirectory(context, accountScope, sourceUrl, bookUrl);
    const digest = await this.digestTool.digestText(`${chapterUrl}\u0000${chapterIndex}`, 'SHA256');
    return `${directory}/chapter-${chapterIndex}-${digest}.json`;
  }

  private async bookDirectory(context: common.UIAbilityContext, accountScope: string, sourceUrl: string,
    bookUrl: string): Promise<string> {
    const scope = /^account:v2:([a-f0-9]{64})$/.exec(accountScope);
    if (scope === null || !/^https?:\/\//i.test(sourceUrl) || !/^https?:\/\//i.test(bookUrl)) {
      throw new Error('书源缓存账户或图书身份无效');
    }
    const digest = await this.digestTool.digestText(`${sourceUrl}\u0000${bookUrl}`, 'SHA256');
    return `${context.filesDir}/${BookSourceLocalCache.ROOT_NAME}/${scope[1]}/${digest}`;
  }

  private readRecord(path: string): Object | undefined {
    try {
      if (!fs.accessSync(path)) return undefined;
      const size = fs.statSync(path).size;
      if (size <= 2 || size > BookSourceLocalCache.MAX_RECORD_BYTES) return undefined;
      return JSON.parse(fs.readTextSync(path)) as Object;
    } catch (_) {
      return undefined;
    }
  }

  private writeRecord(directory: string, path: string, value: Object): void {
    const serialized = JSON.stringify(value);
    if (serialized.length === 0 || serialized.length > BookSourceLocalCache.MAX_RECORD_BYTES) return;
    const temporary = `${path}.${util.generateRandomUUID(true)}.part`;
    let file: fs.File | undefined = undefined;
    try {
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
      file = fs.openSync(temporary, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      const bytes = new util.TextEncoder().encodeInto(serialized);
      const written = fs.writeSync(file.fd, bytes.buffer);
      if (written !== bytes.byteLength) throw new Error('书源缓存写入不完整');
      fs.closeSync(file);
      file = undefined;
      if (fs.accessSync(path)) fs.unlinkSync(path);
      fs.renameSync(temporary, path);
    } catch (_) {
      throw new Error('无法保存书源本地缓存');
    } finally {
      if (file !== undefined) {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
      this.deleteQuietly(temporary);
    }
  }

  private trimBook(directory: string, protectedPath: string): void {
    try {
      const paths = fs.listFileSync(directory).filter((name: string) =>
        /^chapter-[0-9]+-[a-f0-9]{64}\.json$/.test(name)).map((name: string) => `${directory}/${name}`);
      paths.sort((left: string, right: string) => this.modifiedAt(right) - this.modifiedAt(left));
      for (let index = BookSourceLocalCache.MAX_CHAPTER_FILES_PER_BOOK; index < paths.length; index++) {
        if (paths[index] !== protectedPath) this.deleteQuietly(paths[index]);
      }
    } catch (_) {
    }
  }

  private modifiedAt(path: string): number {
    try {
      return fs.statSync(path).mtime;
    } catch (_) {
      return 0;
    }
  }

  private deleteQuietly(path: string): void {
    try {
      if (fs.accessSync(path)) fs.unlinkSync(path);
    } catch (_) {
    }
  }
}
