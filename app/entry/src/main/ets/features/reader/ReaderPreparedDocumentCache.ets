import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { ReaderBlock, ReaderChapter, ReaderDocument } from './ReaderModels';

interface PreparedDocumentRecord {
  version: number;
  sourceSize: number;
  sourceModified: number;
  document: ReaderDocument;
}

/** 持久保存已验证的目录、正文和解包资源，应用重启后不再重复解析整本书。 */
export class ReaderPreparedDocumentCache {
  private static readonly MAX_BYTES: number = 64 * 1024 * 1024;

  /** 只恢复与当前原文件版本一致且引用仍在私有目录内的快照。 */
  async read(source: string): Promise<ReaderDocument | undefined> {
    if (!source) return undefined;
    const root = `${source}.prepared-v1`;
    try {
      const path = `${root}/reader-document.json`;
      if (!fs.accessSync(path)) return undefined;
      const size = fs.statSync(path).size;
      if (size <= 0 || size > ReaderPreparedDocumentCache.MAX_BYTES) return undefined;
      const record = JSON.parse(await fs.readText(path)) as PreparedDocumentRecord;
      const stat = fs.statSync(source);
      if (record.version !== 1 || record.sourceSize !== stat.size || record.sourceModified !== stat.mtime ||
        !this.valid(record.document, root)) return undefined;
      return record.document;
    } catch (_) {
      return undefined;
    }
  }

  /** 将工作区与快照一并原子提交；失败时保持当前正文和临时资源可用。 */
  async save(source: string, document: ReaderDocument, workspace: string,
    isCancelled: () => boolean = (): boolean => false): Promise<ReaderDocument> {
    if (!source || isCancelled()) return document;
    const existing = await this.read(source);
    if (isCancelled()) return document;
    if (existing !== undefined) return existing;
    const root = `${source}.prepared-v1`;
    const temporary = workspace || `${root}.${util.generateRandomUUID(true)}.part`;
    let file: fs.File | undefined = undefined;
    try {
      if (!fs.accessSync(source)) return document;
      if (!fs.accessSync(temporary)) fs.mkdirSync(temporary, true);
      const relocated = this.rebase(document, temporary, root);
      const stat = fs.statSync(source);
      const record: PreparedDocumentRecord = { version: 1, sourceSize: stat.size,
        sourceModified: stat.mtime, document: relocated };
      const serialized = JSON.stringify(record);
      if (serialized.length > ReaderPreparedDocumentCache.MAX_BYTES) return document;
      const bytes = new util.TextEncoder().encodeInto(serialized);
      if (bytes.byteLength > ReaderPreparedDocumentCache.MAX_BYTES) return document;
      file = fs.openSync(`${temporary}/reader-document.json`, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      if (fs.writeSync(file.fd, bytes.buffer) !== bytes.byteLength) return document;
      fs.closeSync(file);
      file = undefined;
      // 只有原子提交后的目录才会被读取，崩溃留下的临时文件不会伪装成可用缓存。
      if (fs.accessSync(root)) {
        const winner = await this.read(source);
        if (isCancelled()) return document;
        if (winner !== undefined) return winner;
        fs.rmdirSync(root);
      }
      fs.renameSync(temporary, root);
      return relocated;
    } catch (_) {
      return document;
    } finally {
      try { if (file !== undefined) fs.closeSync(file); } catch (_) {}
      if (!workspace) {
        try { if (fs.accessSync(temporary)) fs.rmdirSync(temporary); } catch (_) {}
      }
    }
  }

  private rebase(document: ReaderDocument, from: string, to: string): ReaderDocument {
    return { kind: document.kind, encoding: document.encoding,
      coverUri: document.coverUri === undefined ? undefined : this.uri(document.coverUri, from, to),
      imageUris: document.imageUris.map((uri: string): string => this.uri(uri, from, to)),
      chapters: document.chapters.map((chapter: ReaderChapter): ReaderChapter => ({
        title: chapter.title, content: chapter.content,
        resourceUri: chapter.resourceUri === undefined ? undefined : this.uri(chapter.resourceUri, from, to),
        blocks: chapter.blocks?.map((block: ReaderBlock): ReaderBlock => ({
          kind: block.kind, text: block.text, uri: this.uri(block.uri, from, to), level: block.level })) })) };
  }

  private uri(value: string, from: string, to: string): string {
    if (value.startsWith(`file://${from}/`)) return `file://${to}/${value.substring(from.length + 8)}`;
    if (value.startsWith('txt-index:')) {
      const reference = JSON.parse(decodeURIComponent(value.substring(10))) as Record<string, Object>;
      const path = reference['path'];
      if (typeof path !== 'string' || !path.startsWith(`${from}/`)) throw new Error('READER_CACHE_PATH');
      reference['path'] = `${to}/${path.substring(from.length + 1)}`;
      return `txt-index:${encodeURIComponent(JSON.stringify(reference))}`;
    }
    return value;
  }

  private valid(document: ReaderDocument, root: string): boolean {
    if (!document || !['text', 'comic'].includes(document.kind) || !Array.isArray(document.chapters) ||
      !Array.isArray(document.imageUris) || document.chapters.length > 5000 || document.imageUris.length > 5000 ||
      (document.kind === 'text' && document.chapters.length === 0) ||
      (document.kind === 'comic' && document.imageUris.length === 0)) return false;
    if (document.coverUri !== undefined && document.coverUri !== '' && !this.validUri(document.coverUri, root)) return false;
    if (!document.imageUris.every((uri: string) => this.validUri(uri, root))) return false;
    return document.chapters.every((chapter: ReaderChapter): boolean => {
      if (!chapter || typeof chapter.title !== 'string' || typeof chapter.content !== 'string') return false;
      if (chapter.resourceUri !== undefined && !this.validUri(chapter.resourceUri, root)) return false;
      if (chapter.blocks === undefined) return true;
      return Array.isArray(chapter.blocks) && chapter.blocks.length <= 10000 &&
        chapter.blocks.every((block: ReaderBlock): boolean => !!block &&
          ['text', 'paragraph', 'heading', 'quote', 'footnote', 'image', 'code', 'list', 'math', 'diagram'].includes(block.kind) &&
          typeof block.text === 'string' && typeof block.uri === 'string' &&
          (block.uri === '' || this.validUri(block.uri, root)));
    });
  }

  private validUri(uri: string, root: string): boolean {
    if (typeof uri !== 'string') return false;
    let path = '';
    if (uri.startsWith('file://')) path = uri.substring(7);
    else if (uri.startsWith('txt-index:')) {
      const reference = JSON.parse(decodeURIComponent(uri.substring(10))) as Record<string, Object>;
      if (typeof reference['path'] !== 'string') return false;
      path = reference['path'] as string;
    } else return false;
    try { return path.startsWith(`${root}/`) && !path.split('/').includes('..') && fs.accessSync(path); }
    catch (_) { return false; }
  }
}
