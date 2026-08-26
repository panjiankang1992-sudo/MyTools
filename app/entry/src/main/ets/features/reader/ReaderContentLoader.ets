import fs from '@ohos.file.fs';
import http from '@ohos.net.http';
import zlib from '@ohos.zlib';
import common from '@ohos.app.ability.common';
import util from '@ohos.util';
import { ReaderBlock, ReaderChapter, ReaderDocument } from './ReaderModels';
import { MobiReaderNative } from './MobiReaderNative';
import { RemoteBookDownloadPolicy, RemoteBookHeaderResult, RemoteBookPayloadKind } from './RemoteBookDownloadPolicy';
import { TextPayloadPolicy } from './TextPayloadPolicy';
import { ReaderTextMetadataPolicy } from './ReaderTextMetadataPolicy';

interface DecodedText {
  text: string;
  encoding: string;
}

interface TextChunkReference {
  path: string;
  fileSize: number;
  offset: number;
  length: number;
  encoding: string;
  searchIndexPath?: string;
  fingerprint?: string;
}

interface TextSearchIndex {
  version: number;
  fingerprint: string;
  chapterCount: number;
  bloomBytes: number;
  chapterBlooms: string[];
}

interface TextSearchIndexContext {
  indexPath: string;
  fingerprint: string;
}

interface ZipEntryRange {
  start: number;
  end: number;
}

interface ZipArchiveManifest {
  files: Map<string, number>;
}

interface TicketContentRange {
  total: number;
}

export interface IndexedTextSearchHit {
  chapterIndex: number;
  characterIndex: number;
  chapterTitle: string;
  excerpt: string;
}

/**
 * 加载文本阅读内容，限制单次读取大小以避免大文件占满内存。
 */
export class ReaderContentLoader {
  private static readonly MAX_TEXT_BYTES: number = 20 * 1024 * 1024;
  private static readonly MAX_LARGE_TEXT_BYTES: number = 500 * 1024 * 1024;
  private static readonly TEXT_CHUNK_BYTES: number = 1024 * 1024;
  private static readonly MAX_TEXT_CHAPTERS: number = 5000;
  private static readonly FALLBACK_CHAPTER_CHARS: number = 24000;
  private static readonly SEARCH_BLOOM_BYTES: number = 16384;
  private static readonly SEARCH_INDEX_VERSION: number = 1;
  private static readonly MAX_ARCHIVE_BYTES: number = 100 * 1024 * 1024;
  private static readonly MAX_EXTRACTED_BYTES: number = 300 * 1024 * 1024;
  private static readonly MAX_ARCHIVE_FILES: number = 5000;
  private static readonly MAX_REMOTE_ARCHIVE_CACHE_FILES: number = 8;
  private static readonly REMOTE_ARCHIVE_CACHE_VERSION: string = 'v3';
  private static readonly MAX_CENTRAL_DIRECTORY_BYTES: number = 16 * 1024 * 1024;
  private static readonly MAX_COMPRESSION_RATIO: number = 1000;
  private static readonly MAX_IMAGE_DIMENSION: number = 20000;
  private static readonly MAX_IMAGE_PIXELS: number = 80 * 1000 * 1000;
  private static readonly MAX_IMAGE_HEADER_BYTES: number = 1024 * 1024;
  private static readonly MAX_EPUB_CHAPTER_BYTES: number = 5 * 1024 * 1024;
  private static readonly MAX_EPUB_TEXT_BYTES: number = 50 * 1024 * 1024;
  private static readonly MAX_EPUB_TEXT_CHARACTERS: number = 50 * 1024 * 1024;
  private static readonly MAX_EPUB_BLOCKS: number = 10000;
  private static readonly MAX_EPUB_TAGS: number = 20000;
  private static readonly MAX_EPUB_ATTRIBUTES: number = 8192;
  private static readonly MAX_EPUB_MANIFEST_ITEMS: number = 5000;
  private static readonly MAX_EPUB_SPINE_ITEMS: number = 1000;
  private activeDirectory: string = '';
  private searchIndexDirectory: string = '';
  private extractedBytes: number = 0;
  private readonly mobiReader: MobiReaderNative = new MobiReaderNative();
  private readonly remoteDownloadPolicy: RemoteBookDownloadPolicy = new RemoteBookDownloadPolicy();
  private readonly textPayloadPolicy: TextPayloadPolicy = new TextPayloadPolicy();
  private readonly textMetadataPolicy: ReaderTextMetadataPolicy = new ReaderTextMetadataPolicy();
  private activeRequest?: http.HttpRequest = undefined;
  private cancelled: boolean = false;
  private searchIndexWriteRevision: number = 0;
  private extractedFilePaths: Set<string> = new Set<string>();

  /**
   * 取消当前加载器拥有的远程请求，临时工作区由加载流程退出后统一清理。
   */
  cancel(): void {
    this.cancelled = true;
    this.activeRequest?.destroy();
    this.activeRequest = undefined;
  }

  /**
   * 从用户授权的 URI 读取 TXT 文本。
   */
  async loadLocalText(context: common.UIAbilityContext, uri: string): Promise<ReaderDocument> {
    try {
      const stat = fs.statSync(uri);
      if (stat.size === 0 || stat.size > ReaderContentLoader.MAX_LARGE_TEXT_BYTES) {
        throw new Error('TXT为空或超过500 MB');
      }
      if (stat.size > ReaderContentLoader.MAX_TEXT_BYTES) {
        const cachePath = this.prepareWorkspace(context, 'txt');
        await this.copyLocalBookFile(uri, cachePath, stat.size, 'TXT');
        return await this.indexLargeText(cachePath, stat.size, context);
      }
      const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      try {
        const buffer = new ArrayBuffer(stat.size);
        const readBytes = fs.readSync(file.fd, buffer);
        if (readBytes !== stat.size) throw new Error('TXT读取长度不完整');
        return this.parseTextBytes(new Uint8Array(buffer));
      } finally {
        fs.closeSync(file);
      }
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('无法读取本地图书');
    }
  }

  /**
   * 从短期播放票据地址读取远程 TXT 文本。
   */
  async loadRemoteText(context: common.UIAbilityContext, url: string,
    requestHeaders?: Record<string, string>): Promise<ReaderDocument> {
    const cachePath = this.prepareWorkspace(context, 'txt');
    // MyTools票据流经过网关和代理时按固定范围读取，避免设备网络栈长连接丢失结束事件。
    const size = this.isMyToolsTicketUrl(url) ?
      await this.downloadTicketTextRanges(url, cachePath, requestHeaders) :
      await this.downloadTextStream(url, cachePath, requestHeaders);
    if (size > ReaderContentLoader.MAX_TEXT_BYTES) return await this.indexLargeText(cachePath, size, context);
    return this.loadTextFile(cachePath, size);
  }

  /**
   * 从磁盘索引按需读取一个TXT章节。
   */
  loadIndexedTextChapter(resourceUri: string): string {
    if (!resourceUri.startsWith('txt-index:')) throw new Error('TXT章节索引无效');
    try {
      const reference = JSON.parse(decodeURIComponent(resourceUri.substring('txt-index:'.length))) as
        TextChunkReference;
      this.validateTextChunkReference(reference);
      const file = fs.openSync(reference.path, fs.OpenMode.READ_ONLY);
      try {
        const buffer = new ArrayBuffer(reference.length);
        const readBytes = fs.readSync(file.fd, buffer, { offset: reference.offset, length: reference.length });
        if (readBytes !== reference.length) throw new Error('TXT章节读取不完整');
        const decoded = this.decodeWithEncoding(new Uint8Array(buffer), reference.encoding).replace(/^\uFEFF/, '');
        // 只有首个磁盘分块可能包含维护流程写入的原文件名元数据。
        return (reference.offset === 0 ? this.textMetadataPolicy.sanitize(decoded).content : decoded).trim();
      } finally {
        fs.closeSync(file);
      }
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('TXT章节索引解析失败');
    }
  }

  /**
   * 逐块扫描TXT章节，未加载分块只在搜索期间解码并及时释放。
   */
  async searchIndexedTextChapters(chapters: ReaderChapter[], keyword: string, maxResults: number,
    onProgress: (completed: number, total: number) => void,
    isCancelled: () => boolean = (): boolean => false): Promise<IndexedTextSearchHit[]> {
    const normalized = keyword.trim().toLowerCase();
    if (normalized.length === 0) return [];
    if (isCancelled()) throw new Error('全文搜索已取消');
    const results: IndexedTextSearchHit[] = [];
    const indexContext = await this.resolveSearchIndexContext(chapters, isCancelled);
    const storedIndex = indexContext === undefined ? undefined : this.loadSearchIndex(
      indexContext.indexPath, indexContext.fingerprint, chapters.length);
    const buildingBlooms: string[] = [];
    for (let chapterIndex = 0; chapterIndex < chapters.length; chapterIndex++) {
      if (isCancelled()) throw new Error('全文搜索已取消');
      if (storedIndex !== undefined && normalized.length >= 3 &&
        !this.bloomMayContain(storedIndex.chapterBlooms[chapterIndex], normalized)) {
        onProgress(chapterIndex + 1, chapters.length);
        continue;
      }
      const chapter = chapters[chapterIndex];
      const content = chapter.content.length > 0 ? chapter.content :
        (chapter.resourceUri?.startsWith('txt-index:') ? this.loadIndexedTextChapter(chapter.resourceUri) : '');
      const lower = content.toLowerCase();
      if (storedIndex === undefined && indexContext !== undefined) {
        buildingBlooms.push(await this.buildBloomHex(lower, isCancelled));
      }
      let from = 0;
      while (from < lower.length && results.length < maxResults) {
        const characterIndex = lower.indexOf(normalized, from);
        if (characterIndex < 0) break;
        const excerptStart = Math.max(0, characterIndex - 35);
        const excerptEnd = Math.min(content.length, characterIndex + normalized.length + 55);
        results.push({
          chapterIndex: chapterIndex,
          characterIndex: characterIndex,
          chapterTitle: chapter.title,
          excerpt: content.substring(excerptStart, excerptEnd).replace(/\s+/g, ' ').trim()
        });
        from = characterIndex + Math.max(1, normalized.length);
      }
      onProgress(chapterIndex + 1, chapters.length);
      if ((chapterIndex + 1) % 4 === 0) {
        await new Promise<void>((resolve: () => void) => setTimeout(resolve, 0));
        if (isCancelled()) throw new Error('全文搜索已取消');
      }
    }
    if (!isCancelled() && storedIndex === undefined && indexContext !== undefined &&
      buildingBlooms.length === chapters.length) {
      this.saveSearchIndex(indexContext.indexPath, indexContext.fingerprint, buildingBlooms);
    }
    return results;
  }

  private async downloadTextStream(url: string, path: string,
    requestHeaders?: Record<string, string>): Promise<number> {
    const safeUrl = this.remoteDownloadPolicy.url(url);
    const headers = this.remoteDownloadPolicy.requestHeaders(requestHeaders);
    let request: http.HttpRequest | undefined = undefined;
    let file: fs.File | undefined = undefined;
    let received = 0;
    let receivedHash = 2166136261;
    let declaredLength: number | undefined = undefined;
    let streamError = '';
    let completed = false;
    try {
      request = http.createHttp();
      this.assertNotCancelled();
      this.activeRequest = request;
      file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      request.on('headersReceive', (headers: Object) => {
        const result: RemoteBookHeaderResult = this.remoteDownloadPolicy.headers(headers,
          ReaderContentLoader.MAX_LARGE_TEXT_BYTES, 'text');
        streamError = result.error;
        declaredLength = result.contentLength;
        if (streamError.length > 0) request?.destroy();
      });
      request.on('dataReceive', (buffer: ArrayBuffer) => {
        if (this.cancelled || streamError.length > 0 || file === undefined) return;
        received += buffer.byteLength;
        if (received > ReaderContentLoader.MAX_LARGE_TEXT_BYTES) {
          streamError = '远程TXT超过500 MB';
          request?.destroy();
          return;
        }
        const written = this.writeRemoteBookChunk(file, buffer);
        if (written !== buffer.byteLength) {
          streamError = '远程TXT缓存写入不完整';
          request?.destroy();
          return;
        }
        const bytes = new Uint8Array(buffer);
        for (let index = 0; index < bytes.length; index++) {
          receivedHash = Math.imul((receivedHash ^ bytes[index]) >>> 0, 16777619);
        }
      });
      const responseCode = await request.requestInStream(safeUrl, {
        method: http.RequestMethod.GET,
        header: headers,
        connectTimeout: 15000,
        readTimeout: 120000
      });
      this.assertNotCancelled();
      if (responseCode !== 200) throw new Error(`远程文本读取失败（${responseCode}）`);
      if (streamError.length > 0) throw new Error(streamError);
      if (received === 0) throw new Error('远程TXT为空');
      if (declaredLength !== undefined && declaredLength !== received) throw new Error('远程TXT响应长度不一致');
      const cachedFingerprint = await this.fileFingerprintAsync(path, received,
        (): boolean => this.cancelled, '图书加载已取消');
      if (cachedFingerprint !== `${received}-${receivedHash >>> 0}`) throw new Error('远程TXT缓存内容校验失败');
      completed = true;
      return received;
    } catch (error) {
      if (streamError.length > 0) throw new Error(streamError);
      if (error instanceof Error) throw error;
      throw new Error('无法读取远程图书');
    } finally {
      if (file !== undefined) {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
      request?.off('headersReceive');
      request?.off('dataReceive');
      request?.destroy();
      if (!completed) this.removePartialDownload(path);
      if (this.activeRequest === request) this.activeRequest = undefined;
    }
  }

  private isMyToolsTicketUrl(url: string): boolean {
    return /^https?:\/\/[^/?#]+\/api\/app\/v1\/(?:media\/tickets|local-media\/tickets|drive-tickets)\/[a-f0-9]{32}$/.test(url);
  }

  private async downloadTicketTextRanges(url: string, path: string,
    requestHeaders?: Record<string, string>): Promise<number> {
    const safeUrl = this.remoteDownloadPolicy.url(url);
    const baseHeaders = this.remoteDownloadPolicy.requestHeaders(requestHeaders);
    let file: fs.File | undefined = undefined;
    let received = 0;
    let receivedHash = 2166136261;
    let totalSize: number | undefined = undefined;
    let completed = false;
    try {
      file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      while (totalSize === undefined || received < totalSize) {
        this.assertNotCancelled();
        const request = http.createHttp();
        this.activeRequest = request;
        try {
          const rangeEnd = received + ReaderContentLoader.TEXT_CHUNK_BYTES - 1;
          baseHeaders['Range'] = `bytes=${received}-${rangeEnd}`;
          const response = await request.request(safeUrl, {
            method: http.RequestMethod.GET,
            header: baseHeaders,
            expectDataType: http.HttpDataType.ARRAY_BUFFER,
            connectTimeout: 15000,
            readTimeout: 30000
          });
          this.assertNotCancelled();
          if (response.responseCode !== 206) throw new Error(`远程文本分段读取失败（${response.responseCode}）`);
          const headerResult = this.remoteDownloadPolicy.headers(response.header,
            ReaderContentLoader.TEXT_CHUNK_BYTES, 'text');
          if (headerResult.error.length > 0) throw new Error(headerResult.error);
          if (!(response.result instanceof ArrayBuffer)) throw new Error('远程TXT分段响应格式无效');
          const buffer = response.result as ArrayBuffer;
          if (buffer.byteLength === 0 || buffer.byteLength > ReaderContentLoader.TEXT_CHUNK_BYTES) {
            throw new Error('远程TXT分段长度无效');
          }
          const contentRange = this.ticketContentRange(response.header, received, buffer.byteLength);
          if (totalSize !== undefined && totalSize !== contentRange.total) {
            throw new Error('远程TXT总长度发生变化');
          }
          totalSize = contentRange.total;
          if (totalSize > ReaderContentLoader.MAX_LARGE_TEXT_BYTES) throw new Error('远程TXT超过500 MB');
          const written = this.writeRemoteBookChunk(file, buffer);
          if (written !== buffer.byteLength) throw new Error('远程TXT缓存写入不完整');
          const bytes = new Uint8Array(buffer);
          for (let index = 0; index < bytes.length; index++) {
            receivedHash = Math.imul((receivedHash ^ bytes[index]) >>> 0, 16777619);
          }
          received += buffer.byteLength;
          await new Promise<void>((resolve: () => void) => setTimeout(resolve, 0));
        } finally {
          request.destroy();
          if (this.activeRequest === request) this.activeRequest = undefined;
        }
      }
      if (received === 0 || totalSize !== received) throw new Error('远程TXT响应长度不一致');
      const cachedFingerprint = await this.fileFingerprintAsync(path, received,
        (): boolean => this.cancelled, '图书加载已取消');
      if (cachedFingerprint !== `${received}-${receivedHash >>> 0}`) throw new Error('远程TXT缓存内容校验失败');
      completed = true;
      return received;
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('无法读取远程图书');
    } finally {
      if (file !== undefined) {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
      if (!completed) this.removePartialDownload(path);
    }
  }

  private ticketContentRange(headers: Object, expectedStart: number, chunkLength: number):
    TicketContentRange {
    const values = headers as Record<string, Object>;
    const key = Object.keys(values).find((value: string) => value.toLowerCase() === 'content-range');
    if (key === undefined || typeof values[key] !== 'string') throw new Error('远程TXT缺少分段范围');
    const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(`${values[key]}`.trim());
    if (match === null) throw new Error('远程TXT分段范围无效');
    const start = Number(match[1]);
    const end = Number(match[2]);
    const total = Number(match[3]);
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || !Number.isSafeInteger(total) ||
      start !== expectedStart || end < start || end - start + 1 !== chunkLength || total <= end) {
      throw new Error('远程TXT分段范围不一致');
    }
    const result: TicketContentRange = { total: total };
    return result;
  }

  /** 分块复制本地图书，在切书或关闭时停止并清理不完整副本。 */
  private async copyLocalBookFile(sourcePath: string, targetPath: string, expectedSize: number,
    label: string): Promise<void> {
    let source: fs.File | undefined = undefined;
    let target: fs.File | undefined = undefined;
    let completed = false;
    try {
      source = this.openLocalBookSource(sourcePath, label);
      target = this.openLocalBookTarget(targetPath, label);
      let offset = 0;
      let copiedHash = 2166136261;
      while (offset < expectedSize) {
        this.assertNotCancelled();
        const length = Math.min(ReaderContentLoader.TEXT_CHUNK_BYTES, expectedSize - offset);
        const buffer = new ArrayBuffer(length);
        const readBytes = this.readLocalBookChunk(source, buffer, offset, length, label);
        if (readBytes !== length) throw new Error(`本地${label}复制时源文件发生变化`);
        const written = this.writeLocalBookChunk(target, buffer, label);
        if (written !== length) throw new Error(`本地${label}缓存写入不完整`);
        const bytes = new Uint8Array(buffer);
        for (let index = 0; index < bytes.length; index++) {
          copiedHash = Math.imul((copiedHash ^ bytes[index]) >>> 0, 16777619);
        }
        offset += length;
        await new Promise<void>((resolve: () => void) => setTimeout(resolve, 0));
      }
      this.assertNotCancelled();
      if (this.fileSize(sourcePath, label) !== expectedSize || this.fileSize(targetPath, label) !== expectedSize) {
        throw new Error(`本地${label}复制后大小不一致`);
      }
      const sourceFingerprint = await this.fileFingerprintAsync(sourcePath, expectedSize,
        (): boolean => this.cancelled, `本地${label}复制已取消`);
      const copiedFingerprint = `${expectedSize}-${copiedHash >>> 0}`;
      if (sourceFingerprint !== copiedFingerprint) {
        throw new Error(`本地${label}复制期间源文件内容发生变化`);
      }
      const targetFingerprint = await this.fileFingerprintAsync(targetPath, expectedSize,
        (): boolean => this.cancelled, `本地${label}复制已取消`);
      if (targetFingerprint !== copiedFingerprint) throw new Error(`本地${label}缓存内容校验失败`);
      completed = true;
    } finally {
      if (source !== undefined) {
        try { fs.closeSync(source); } catch (_) {}
      }
      if (target !== undefined) {
        try { fs.closeSync(target); } catch (_) {}
      }
      if (!completed) this.removePartialDownload(targetPath);
    }
  }

  private openLocalBookSource(path: string, label: string): fs.File {
    try { return fs.openSync(path, fs.OpenMode.READ_ONLY); } catch (_) { throw new Error(`无法打开本地${label}`); }
  }

  private openLocalBookTarget(path: string, label: string): fs.File {
    try {
      return fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
    } catch (_) {
      throw new Error(`无法创建本地${label}缓存`);
    }
  }

  private readLocalBookChunk(file: fs.File, buffer: ArrayBuffer, offset: number, length: number,
    label: string): number {
    try { return fs.readSync(file.fd, buffer, { offset: offset, length: length }); }
    catch (_) { throw new Error(`本地${label}缓存读取失败`); }
  }

  private writeLocalBookChunk(file: fs.File, buffer: ArrayBuffer, label: string): number {
    try { return fs.writeSync(file.fd, buffer); } catch (_) { throw new Error(`本地${label}缓存写入失败`); }
  }

  private writeRemoteBookChunk(file: fs.File, buffer: ArrayBuffer): number {
    try { return fs.writeSync(file.fd, buffer); } catch (_) { return -1; }
  }

  private fileSize(path: string, label: string): number {
    try { return fs.statSync(path).size; } catch (_) { throw new Error(`无法检查本地${label}大小`); }
  }

  private async downloadBinaryStream(url: string, path: string, maxBytes: number, label: string,
    kind: RemoteBookPayloadKind,
    requestHeaders?: Record<string, string>): Promise<number> {
    const safeUrl = this.remoteDownloadPolicy.url(url);
    const headers = this.remoteDownloadPolicy.requestHeaders(requestHeaders);
    let request: http.HttpRequest | undefined = undefined;
    let file: fs.File | undefined = undefined;
    let received = 0;
    let receivedHash = 2166136261;
    let declaredLength: number | undefined = undefined;
    let streamError = '';
    let completed = false;
    try {
      request = http.createHttp();
      this.assertNotCancelled();
      this.activeRequest = request;
      file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      request.on('headersReceive', (headers: Object) => {
        const result: RemoteBookHeaderResult = this.remoteDownloadPolicy.headers(headers, maxBytes, kind);
        streamError = result.error;
        declaredLength = result.contentLength;
        if (streamError.length > 0) request?.destroy();
      });
      request.on('dataReceive', (buffer: ArrayBuffer) => {
        if (this.cancelled || streamError.length > 0 || file === undefined) return;
        received += buffer.byteLength;
        if (received > maxBytes) {
          streamError = `${label}超过100 MB`;
          request?.destroy();
          return;
        }
        const written = this.writeRemoteBookChunk(file, buffer);
        if (written !== buffer.byteLength) {
          streamError = `${label}缓存写入不完整`;
          request?.destroy();
          return;
        }
        const bytes = new Uint8Array(buffer);
        for (let index = 0; index < bytes.length; index++) {
          receivedHash = Math.imul((receivedHash ^ bytes[index]) >>> 0, 16777619);
        }
      });
      const responseCode = await request.requestInStream(safeUrl, {
        method: http.RequestMethod.GET,
        header: headers,
        connectTimeout: 15000,
        readTimeout: 120000
      });
      this.assertNotCancelled();
      if (streamError.length > 0) throw new Error(streamError);
      if (responseCode !== 200) throw new Error(`${label}下载失败（${responseCode}）`);
      if (received === 0) throw new Error(`${label}为空`);
      if (declaredLength !== undefined && declaredLength !== received) throw new Error(`${label}响应长度不一致`);
      const cachedFingerprint = await this.fileFingerprintAsync(path, received,
        (): boolean => this.cancelled, '图书加载已取消');
      if (cachedFingerprint !== `${received}-${receivedHash >>> 0}`) throw new Error(`${label}缓存内容校验失败`);
      const signatureError = this.remoteDownloadPolicy.payloadError(
        this.readFileRange(path, 0, Math.min(received, 68)), kind);
      if (signatureError.length > 0) throw new Error(signatureError);
      completed = true;
      return received;
    } catch (error) {
      if (streamError.length > 0) throw new Error(streamError);
      if (error instanceof Error) throw error;
      throw new Error(`${label}读取失败`);
    } finally {
      if (file !== undefined) {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
      request?.off('headersReceive');
      request?.off('dataReceive');
      request?.destroy();
      if (!completed) this.removePartialDownload(path);
      if (this.activeRequest === request) this.activeRequest = undefined;
    }
  }

  /** 删除失败、取消或超限下载留下的不完整文件。 */
  private removePartialDownload(path: string): void {
    try {
      if (fs.accessSync(path)) fs.unlinkSync(path);
    } catch (_) {
    }
  }

  /** 使本地导入和远程下载共享相同的格式签名边界。 */
  private validatePayloadSignature(path: string, size: number, kind: RemoteBookPayloadKind): void {
    const error = this.remoteDownloadPolicy.payloadError(this.readFileRange(path, 0, Math.min(size, 68)), kind);
    if (error.length > 0) throw new Error(error);
  }

  private loadTextFile(path: string, size: number): ReaderDocument {
    try {
      const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
      try {
        const buffer = new ArrayBuffer(size);
        const readBytes = fs.readSync(file.fd, buffer);
        if (readBytes !== size) throw new Error('TXT缓存读取长度不完整');
        return this.parseTextBytes(new Uint8Array(buffer));
      } finally {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('TXT缓存读取失败');
    }
  }

  private parseTextBytes(bytes: Uint8Array): ReaderDocument {
    const decoded = this.decodeText(bytes);
    const sanitized = this.textMetadataPolicy.sanitize(decoded.text).content;
    this.validateDecodedText(sanitized);
    const chapters = this.splitTextChapters(sanitized);
    if (chapters.length === 0) throw new Error('TXT中没有可显示的正文');
    return { kind: 'text', chapters: chapters, imageUris: [], encoding: decoded.encoding };
  }

  private async indexLargeText(path: string, size: number,
    context: common.UIAbilityContext): Promise<ReaderDocument> {
    const sampleLength = Math.min(size, 4096);
    const sample = this.readFileRange(path, 0, sampleLength);
    const encoding = this.detectTextEncoding(sample);
    const isCancelled = (): boolean => this.cancelled;
    const fingerprint = await this.fileFingerprintAsync(path, size, isCancelled, '图书加载已取消');
    const searchIndexPath = this.searchIndexPath(context, size, fingerprint);
    const chapters: ReaderChapter[] = [];
    let offset = 0;
    while (offset < size && chapters.length < ReaderContentLoader.MAX_TEXT_CHAPTERS) {
      this.assertNotCancelled();
      const preferredEnd = Math.min(size, offset + ReaderContentLoader.TEXT_CHUNK_BYTES);
      const end = preferredEnd >= size ? size : this.findNextLineBoundary(path, preferredEnd, size, encoding);
      const length = Math.max(1, end - offset);
      const rawChunk = this.decodeWithEncoding(this.readFileRange(path, offset, length), encoding)
        .replace(/^\uFEFF/, '');
      // 大型TXT按磁盘分块读取，首块同样不能把内部追溯标记带入标题和正文。
      const decodedChunk = offset === 0 ? this.textMetadataPolicy.sanitize(rawChunk).content : rawChunk;
      this.validateDecodedText(decodedChunk);
      const preview = decodedChunk.substring(0, 8192);
      const title = this.firstHeading(preview, chapters.length);
      const reference: TextChunkReference = { path: path, fileSize: size, offset: offset, length: length,
        encoding: encoding,
        searchIndexPath: searchIndexPath, fingerprint: fingerprint };
      chapters.push({
        title: title,
        content: chapters.length === 0 ? decodedChunk.trim() : '',
        resourceUri: `txt-index:${encodeURIComponent(JSON.stringify(reference))}`
      });
      offset = end;
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 0));
    }
    this.assertNotCancelled();
    if (offset < size) throw new Error('超大TXT章节数量超过5000');
    if (chapters.length === 0) throw new Error('TXT中没有可显示的正文');
    return { kind: 'text', chapters: chapters, imageUris: [], encoding: `${this.encodingLabel(encoding)} · 磁盘按需` };
  }

  private detectTextEncoding(bytes: Uint8Array): string {
    if (bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) return 'utf-8';
    if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE) return 'utf-16le';
    if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF) return 'utf-16be';
    const utf16 = this.detectUtf16WithoutBom(bytes);
    if (utf16.length > 0) return utf16;
    for (let trim = 0; trim <= Math.min(3, bytes.length - 1); trim++) {
      try {
        util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes.subarray(0, bytes.length - trim));
        return 'utf-8';
      } catch (_) {
      }
    }
    return 'gb18030';
  }

  private decodeWithEncoding(bytes: Uint8Array, encoding: string): string {
    try {
      return util.TextDecoder.create(encoding, { fatal: true }).decodeToString(bytes);
    } catch (_) {
      throw new Error(`TXT章节无法按${this.encodingLabel(encoding)}解码`);
    }
  }

  /** 防止可被字符集解码器勉强接受的二进制内容伪装为TXT。 */
  private validateDecodedText(text: string): void {
    const error = this.textPayloadPolicy.error(text);
    if (error.length > 0) throw new Error(error);
  }

  private encodingLabel(encoding: string): string {
    if (encoding === 'utf-16le') return 'UTF-16LE';
    if (encoding === 'utf-16be') return 'UTF-16BE';
    if (encoding === 'gb18030') return 'GB18030/GBK';
    return 'UTF-8';
  }

  private readFileRange(path: string, offset: number, length: number): Uint8Array {
    try {
      const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
      try {
        const buffer = new ArrayBuffer(length);
        const readBytes = fs.readSync(file.fd, buffer, { offset: offset, length: length });
        if (readBytes !== length) throw new Error('TXT索引读取长度不完整');
        return new Uint8Array(buffer);
      } finally {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('TXT索引读取失败');
    }
  }

  /** 校验按需章节引用，拒绝路径替换、数值溢出、未知编码和缓存截断。 */
  private validateTextChunkReference(reference: TextChunkReference): void {
    const maximumLength = ReaderContentLoader.TEXT_CHUNK_BYTES + 16384;
    if (this.activeDirectory.length === 0 || reference.path !== `${this.activeDirectory}/book.txt` ||
      !Number.isSafeInteger(reference.fileSize) || reference.fileSize <= ReaderContentLoader.MAX_TEXT_BYTES ||
      reference.fileSize > ReaderContentLoader.MAX_LARGE_TEXT_BYTES || !Number.isSafeInteger(reference.offset) ||
      reference.offset < 0 || !Number.isSafeInteger(reference.length) || reference.length <= 0 ||
      reference.length > maximumLength || reference.offset > reference.fileSize - reference.length ||
      (reference.encoding !== 'utf-8' && reference.encoding !== 'utf-16le' &&
        reference.encoding !== 'utf-16be' && reference.encoding !== 'gb18030')) {
      throw new Error('TXT章节索引越界');
    }
    try {
      const stat = fs.statSync(reference.path);
      if (!stat.isFile() || stat.size !== reference.fileSize) throw new Error('TXT缓存文件已发生变化');
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('TXT缓存文件不可用');
    }
  }

  private async resolveSearchIndexContext(chapters: ReaderChapter[],
    isCancelled: () => boolean): Promise<TextSearchIndexContext | undefined> {
    if (chapters.length === 0 || this.searchIndexDirectory.length === 0) return undefined;
    const resourceUri = chapters[0].resourceUri;
    if (resourceUri === undefined || !resourceUri.startsWith('txt-index:')) return undefined;
    try {
      const reference = JSON.parse(decodeURIComponent(resourceUri.substring('txt-index:'.length))) as
        TextChunkReference;
      this.validateTextChunkReference(reference);
      if (reference.searchIndexPath === undefined || reference.fingerprint === undefined ||
        !/^[0-9]+-[0-9]+$/.test(reference.fingerprint) ||
        reference.searchIndexPath !== `${this.searchIndexDirectory}/txt-${reference.fileSize}-${reference.fingerprint}.json` ||
        await this.fileFingerprintAsync(reference.path, reference.fileSize, isCancelled,
          '全文搜索已取消') !== reference.fingerprint) {
        return undefined;
      }
      return { indexPath: reference.searchIndexPath, fingerprint: reference.fingerprint };
    } catch (_) {
      return undefined;
    }
  }

  private loadSearchIndex(path: string, fingerprint: string, chapterCount: number): TextSearchIndex | undefined {
    try {
      if (!fs.accessSync(path)) return undefined;
      const stat = fs.statSync(path);
      if (stat.size <= 0 || stat.size > 24 * 1024 * 1024) return undefined;
      const parsed = JSON.parse(fs.readTextSync(path)) as TextSearchIndex;
      if (parsed.version !== ReaderContentLoader.SEARCH_INDEX_VERSION || parsed.fingerprint !== fingerprint ||
        parsed.chapterCount !== chapterCount || parsed.bloomBytes !== ReaderContentLoader.SEARCH_BLOOM_BYTES ||
        !Array.isArray(parsed.chapterBlooms) || parsed.chapterBlooms.length !== chapterCount ||
        parsed.chapterBlooms.some((value: string) =>
          value.length !== ReaderContentLoader.SEARCH_BLOOM_BYTES * 2 || !/^[0-9a-f]+$/.test(value))) return undefined;
      return parsed;
    } catch (_) {
      return undefined;
    }
  }

  private saveSearchIndex(path: string, fingerprint: string, chapterBlooms: string[]): void {
    const index: TextSearchIndex = {
      version: ReaderContentLoader.SEARCH_INDEX_VERSION,
      fingerprint: fingerprint,
      chapterCount: chapterBlooms.length,
      bloomBytes: ReaderContentLoader.SEARCH_BLOOM_BYTES,
      chapterBlooms: chapterBlooms
    };
    const temporaryPath = `${path}.tmp-${++this.searchIndexWriteRevision}`;
    try {
      const file = fs.openSync(temporaryPath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(file.fd, JSON.stringify(index));
      } finally {
        fs.closeSync(file);
      }
      if (fs.accessSync(path)) fs.unlinkSync(path);
      fs.renameSync(temporaryPath, path);
      this.pruneSearchIndexes(path);
    } catch (_) {
      // 索引写入失败不影响本次精确搜索结果。
    } finally {
      try {
        if (fs.accessSync(temporaryPath)) fs.unlinkSync(temporaryPath);
      } catch (_) {
      }
    }
  }

  private async buildBloomHex(text: string, isCancelled: () => boolean): Promise<string> {
    const bloom = new Uint8Array(ReaderContentLoader.SEARCH_BLOOM_BYTES);
    if (text.length < 3) return this.bytesToHex(bloom);
    for (let index = 0; index <= text.length - 3; index++) {
      const gram = text.substring(index, index + 3);
      this.setBloomBit(bloom, this.hashText(gram, 2166136261));
      this.setBloomBit(bloom, this.hashText(gram, 2246822519));
      this.setBloomBit(bloom, this.hashText(gram, 3266489917));
      if (index > 0 && index % 32768 === 0) {
        await new Promise<void>((resolve: () => void) => setTimeout(resolve, 0));
        if (isCancelled()) throw new Error('全文搜索已取消');
      }
    }
    if (isCancelled()) throw new Error('全文搜索已取消');
    return this.bytesToHex(bloom);
  }

  private bloomMayContain(hex: string, keyword: string): boolean {
    for (let index = 0; index <= keyword.length - 3; index++) {
      const gram = keyword.substring(index, index + 3);
      if (!this.hexBloomHasBit(hex, this.hashText(gram, 2166136261)) ||
        !this.hexBloomHasBit(hex, this.hashText(gram, 2246822519)) ||
        !this.hexBloomHasBit(hex, this.hashText(gram, 3266489917))) return false;
    }
    return true;
  }

  private setBloomBit(bloom: Uint8Array, hash: number): void {
    const bit = hash % (bloom.length * 8);
    bloom[Math.floor(bit / 8)] |= 1 << (bit % 8);
  }

  private hexBloomHasBit(hex: string, hash: number): boolean {
    const bit = hash % (ReaderContentLoader.SEARCH_BLOOM_BYTES * 8);
    const byteOffset = Math.floor(bit / 8) * 2;
    const value = Number.parseInt(hex.substring(byteOffset, byteOffset + 2), 16);
    return (value & (1 << (bit % 8))) !== 0;
  }

  private hashText(value: string, seed: number): number {
    let hash = seed >>> 0;
    for (let index = 0; index < value.length; index++) {
      hash ^= value.charCodeAt(index);
      hash = Math.imul(hash, 16777619) >>> 0;
    }
    return hash;
  }

  private bytesToHex(bytes: Uint8Array): string {
    let value = '';
    for (let index = 0; index < bytes.length; index++) value += bytes[index].toString(16).padStart(2, '0');
    return value;
  }

  /** 搜索前异步复核全文指纹，每个磁盘块后响应页面生命周期取消。 */
  private async fileFingerprintAsync(path: string, size: number,
    isCancelled: () => boolean, cancellationMessage: string): Promise<string> {
    let hash = 2166136261;
    let offset = 0;
    while (offset < size) {
      if (isCancelled()) throw new Error(cancellationMessage);
      const bytes = this.readFileRange(path, offset, Math.min(ReaderContentLoader.TEXT_CHUNK_BYTES, size - offset));
      for (let index = 0; index < bytes.length; index++) {
        hash = Math.imul((hash ^ bytes[index]) >>> 0, 16777619);
      }
      offset += bytes.length;
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 0));
    }
    if (isCancelled()) throw new Error(cancellationMessage);
    return `${size}-${hash >>> 0}`;
  }

  private searchIndexPath(context: common.UIAbilityContext, size: number, fingerprint: string): string {
    try {
      this.searchIndexDirectory = `${context.filesDir}/reader-search-index`;
      if (!fs.accessSync(this.searchIndexDirectory)) fs.mkdirSync(this.searchIndexDirectory, true);
      return `${this.searchIndexDirectory}/txt-${size}-${fingerprint}.json`;
    } catch (_) {
      throw new Error('无法创建TXT搜索索引目录');
    }
  }

  private pruneSearchIndexes(currentPath: string): void {
    try {
      const paths = fs.listFileSync(this.searchIndexDirectory)
        .filter((name: string) => name.endsWith('.json'))
        .map((name: string) => `${this.searchIndexDirectory}/${name}`).sort();
      while (paths.length > 16) {
        const candidate = paths.shift();
        if (candidate === undefined) break;
        if (candidate === currentPath) {
          paths.push(candidate);
        } else if (candidate.startsWith(`${this.searchIndexDirectory}/`)) {
          fs.unlinkSync(candidate);
        }
      }
    } catch (_) {
      // 淘汰失败不影响当前索引和搜索结果。
    }
  }

  private findNextLineBoundary(path: string, preferred: number, size: number, encoding: string): number {
    const searchLength = Math.min(8192, size - preferred);
    if (searchLength <= 0) return size;
    const bytes = this.readFileRange(path, preferred, searchLength);
    if (encoding === 'utf-16le' || encoding === 'utf-16be') {
      const evenPreferred = preferred % 2 === 0 ? preferred : preferred + 1;
      const little = encoding === 'utf-16le';
      for (let index = evenPreferred - preferred; index + 1 < bytes.length; index += 2) {
        if ((little && bytes[index] === 0x0A && bytes[index + 1] === 0) ||
          (!little && bytes[index] === 0 && bytes[index + 1] === 0x0A)) return preferred + index + 2;
      }
      return Math.min(size, evenPreferred + searchLength - searchLength % 2);
    }
    for (let index = 0; index < bytes.length; index++) {
      if (bytes[index] === 0x0A) return preferred + index + 1;
    }
    if (encoding === 'gb18030') {
      for (let index = 0; index < bytes.length; index++) {
        if (bytes[index] < 0x80) return preferred + index + 1;
      }
    } else if (encoding === 'utf-8') {
      for (let index = 0; index < Math.min(4, bytes.length); index++) {
        if ((bytes[index] & 0xC0) !== 0x80) return preferred + index;
      }
    }
    return Math.min(size, preferred + searchLength);
  }

  private firstHeading(preview: string, index: number): string {
    const lines = preview.replace(/\r\n?/g, '\n').split('\n').slice(0, 30);
    const headingPattern = /^\s*(?:第[0-9零一二三四五六七八九十百千万两〇]+[章节卷回部篇集](?:\s+.{0,60})?|chapter\s+[0-9ivxlcdm]+(?:\s+.{0,60})?|序章|楔子|前言|序言|后记|尾声)\s*$/i;
    const heading = lines.find((line: string) => headingPattern.test(line.trim()));
    return heading === undefined ? `第${index + 1}节` : heading.trim().substring(0, 80);
  }

  private decodeText(bytes: Uint8Array): DecodedText {
    try {
      if (bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) {
        return { text: util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes.subarray(3)),
          encoding: 'UTF-8 BOM' };
      }
      if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE) {
        return { text: util.TextDecoder.create('utf-16le', { fatal: true }).decodeToString(bytes.subarray(2)),
          encoding: 'UTF-16LE' };
      }
      if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF) {
        return { text: util.TextDecoder.create('utf-16be', { fatal: true }).decodeToString(bytes.subarray(2)),
          encoding: 'UTF-16BE' };
      }
      const utf16 = this.detectUtf16WithoutBom(bytes);
      if (utf16.length > 0) {
        return { text: util.TextDecoder.create(utf16, { fatal: true }).decodeToString(bytes),
          encoding: utf16 === 'utf-16le' ? 'UTF-16LE（无BOM）' : 'UTF-16BE（无BOM）' };
      }
      try {
        return { text: util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes), encoding: 'UTF-8' };
      } catch (_) {
        return { text: util.TextDecoder.create('gb18030', { fatal: true }).decodeToString(bytes),
          encoding: 'GB18030/GBK' };
      }
    } catch (_) {
      throw new Error('TXT字符编码无法识别，仅支持UTF-8、UTF-16、GBK和GB18030');
    }
  }

  private detectUtf16WithoutBom(bytes: Uint8Array): string {
    const sampleLength = Math.min(bytes.length, 4096);
    if (sampleLength < 8) return '';
    let evenZeros = 0;
    let oddZeros = 0;
    for (let index = 0; index < sampleLength; index++) {
      if (bytes[index] !== 0) continue;
      if (index % 2 === 0) evenZeros++;
      else oddZeros++;
    }
    const pairs = Math.floor(sampleLength / 2);
    if (oddZeros > pairs * 0.3 && evenZeros < pairs * 0.05) return 'utf-16le';
    if (evenZeros > pairs * 0.3 && oddZeros < pairs * 0.05) return 'utf-16be';
    return '';
  }

  private splitTextChapters(text: string): ReaderChapter[] {
    const normalized = text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').trim();
    if (normalized.length === 0) return [];
    const lines = normalized.split('\n');
    const headingPattern = /^\s*(?:第[0-9零一二三四五六七八九十百千万两〇]+[章节卷回部篇集](?:\s+.{0,60})?|chapter\s+[0-9ivxlcdm]+(?:\s+.{0,60})?|序章|楔子|前言|序言|后记|尾声)\s*$/i;
    const chapters: ReaderChapter[] = [];
    let title = '';
    let contentLines: string[] = [];
    const flush = (): void => {
      const content = contentLines.join('\n').trim();
      if (content.length > 0) {
        this.appendBoundedTextChapters(chapters, title, content);
      }
      contentLines = [];
    };
    for (let index = 0; index < lines.length && chapters.length < ReaderContentLoader.MAX_TEXT_CHAPTERS; index++) {
      const line = lines[index].trim();
      if (headingPattern.test(line)) {
        flush();
        title = line.substring(0, 80);
        contentLines.push(line);
      } else {
        contentLines.push(lines[index]);
      }
    }
    flush();
    if (chapters.length > 1) return chapters;
    return this.splitLargeText(normalized);
  }

  /**
   * 将过长章节分成可稳定渲染的文本块，避免单个Text组件丢失后半部正文。
   */
  private appendBoundedTextChapters(chapters: ReaderChapter[], title: string, content: string): void {
    let offset = 0;
    let part = 0;
    while (offset < content.length && chapters.length < ReaderContentLoader.MAX_TEXT_CHAPTERS) {
      let end = Math.min(content.length, offset + ReaderContentLoader.FALLBACK_CHAPTER_CHARS);
      if (end < content.length) {
        const paragraph = content.lastIndexOf('\n\n', end);
        if (paragraph > offset + ReaderContentLoader.FALLBACK_CHAPTER_CHARS / 2) end = paragraph + 2;
      }
      const chunk = content.substring(offset, end).trim();
      if (chunk.length > 0) {
        const baseTitle = title.length > 0 ? title : `第${chapters.length + 1}节`;
        chapters.push({ title: part === 0 ? baseTitle : `${baseTitle}（续${part + 1}）`, content: chunk });
      }
      offset = end;
      part++;
    }
    if (offset < content.length) throw new Error('TXT分段数量超过5000');
  }

  private splitLargeText(text: string): ReaderChapter[] {
    const chapters: ReaderChapter[] = [];
    let offset = 0;
    while (offset < text.length && chapters.length < ReaderContentLoader.MAX_TEXT_CHAPTERS) {
      let end = Math.min(text.length, offset + ReaderContentLoader.FALLBACK_CHAPTER_CHARS);
      if (end < text.length) {
        const paragraph = text.lastIndexOf('\n\n', end);
        if (paragraph > offset + ReaderContentLoader.FALLBACK_CHAPTER_CHARS / 2) end = paragraph + 2;
      }
      const content = text.substring(offset, end).trim();
      if (content.length > 0) chapters.push({ title: `第${chapters.length + 1}节`, content: content });
      offset = end;
    }
    return chapters;
  }

  /**
   * 解压并解析本地EPUB或CBZ容器。
   */
  async loadLocalArchive(context: common.UIAbilityContext, uri: string,
    format: string): Promise<ReaderDocument> {
    try {
      const stat = fs.statSync(uri);
      if (stat.size > ReaderContentLoader.MAX_ARCHIVE_BYTES) throw new Error('图书容器不能超过100 MB');
      const archivePath = this.prepareWorkspace(context, format);
      await this.copyLocalBookFile(uri, archivePath, stat.size, format.toUpperCase());
      this.validatePayloadSignature(archivePath, stat.size, 'archive');
      return this.extractAndParse(archivePath, format);
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('本地图书容器读取失败');
    }
  }

  /**
   * 下载到应用缓存后解析远程EPUB或CBZ，不把远程凭证写入文件名。
   */
  async loadRemoteArchive(context: common.UIAbilityContext, url: string,
    format: string, requestHeaders?: Record<string, string>, cacheIdentity?: string,
    cacheVersion?: string): Promise<ReaderDocument> {
    const archivePath = this.prepareWorkspace(context, format);
    const cachePath = this.remoteArchiveCachePath(context, format, cacheIdentity, cacheVersion);
    try {
      if (cachePath.length > 0 && this.validRemoteArchiveCache(cachePath, format)) {
        return await this.extractAndParse(cachePath, format);
      }
      await this.downloadBinaryStream(url, archivePath, ReaderContentLoader.MAX_ARCHIVE_BYTES, '远程图书',
        'archive', requestHeaders);
      const persistedPath = cachePath.length > 0 ? this.persistRemoteArchiveCache(archivePath, cachePath) : '';
      return await this.extractAndParse(persistedPath.length > 0 ? persistedPath : archivePath, format);
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('远程图书容器读取失败');
    }
  }

  private remoteArchiveCachePath(context: common.UIAbilityContext, format: string,
    cacheIdentity?: string, cacheVersion?: string): string {
    const identity = /^remote:sha256:([a-f0-9]{64})$/.exec(cacheIdentity ?? '');
    if (identity === null || !/^[a-f0-9]{16,128}$/.test(cacheVersion ?? '') ||
      (format !== 'epub' && format !== 'cbz')) return '';
    try {
      const directory = `${context.cacheDir}/remote-ebook-archives`;
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
      return `${directory}/${ReaderContentLoader.REMOTE_ARCHIVE_CACHE_VERSION}-${identity[1]}-${cacheVersion}.${format}`;
    } catch (_) {
      return '';
    }
  }

  private validRemoteArchiveCache(path: string, format: string): boolean {
    try {
      const size = fs.statSync(path).size;
      if (size <= 0 || size > ReaderContentLoader.MAX_ARCHIVE_BYTES) throw new Error('缓存大小无效');
      this.validatePayloadSignature(path, size, 'archive');
      this.inspectZipArchive(path, format);
      return true;
    } catch (_) {
      try { if (fs.accessSync(path)) fs.unlinkSync(path); } catch (_) {}
      return false;
    }
  }

  private persistRemoteArchiveCache(source: string, target: string): string {
    const temporary = `${target}.part`;
    try {
      if (fs.accessSync(temporary)) fs.unlinkSync(temporary);
      fs.copyFileSync(source, temporary);
      if (fs.accessSync(target)) fs.unlinkSync(target);
      fs.renameSync(temporary, target);
      this.pruneRemoteArchiveCache(target);
      return target;
    } catch (_) {
      try { if (fs.accessSync(temporary)) fs.unlinkSync(temporary); } catch (_) {}
      return '';
    }
  }

  private pruneRemoteArchiveCache(currentPath: string): void {
    try {
      const separator = currentPath.lastIndexOf('/');
      const directory = currentPath.substring(0, separator);
      const paths = fs.listFileSync(directory)
        .filter((name: string) => /^(?:v[1-9]\d*-)?[a-f0-9]{64}-[a-f0-9]{16,128}\.(?:epub|cbz)$/.test(name))
        .map((name: string) => `${directory}/${name}`)
        .sort((left: string, right: string) => fs.statSync(left).mtime - fs.statSync(right).mtime);
      while (paths.length > ReaderContentLoader.MAX_REMOTE_ARCHIVE_CACHE_FILES) {
        const index = paths.findIndex((path: string) => path !== currentPath);
        if (index < 0) break;
        const candidate = paths.splice(index, 1)[0];
        if (candidate !== undefined && fs.accessSync(candidate)) fs.unlinkSync(candidate);
      }
    } catch (_) {
      // 缓存回收失败不能影响当前阅读。
    }
  }

  /**
   * 将用户选择的PDF复制到当前阅读缓存，供PDFKit按路径加载。
   */
  async prepareLocalPdf(context: common.UIAbilityContext, uri: string): Promise<string> {
    try {
      const stat = fs.statSync(uri);
      if (stat.size === 0 || stat.size > ReaderContentLoader.MAX_ARCHIVE_BYTES) {
        throw new Error('PDF为空或超过100 MB');
      }
      const path = this.prepareWorkspace(context, 'pdf');
      await this.copyLocalBookFile(uri, path, stat.size, 'PDF');
      this.validatePayloadSignature(path, stat.size, 'pdf');
      return path;
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('本地PDF读取失败');
    }
  }

  /**
   * 将远程PDF暂存到当前阅读缓存，关闭阅读器后立即清理。
   */
  async prepareRemotePdf(context: common.UIAbilityContext, url: string,
    requestHeaders?: Record<string, string>): Promise<string> {
    const path = this.prepareWorkspace(context, 'pdf');
    try {
      await this.downloadBinaryStream(url, path, ReaderContentLoader.MAX_ARCHIVE_BYTES, '远程PDF', 'pdf',
        requestHeaders);
      return path;
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('远程PDF读取失败');
    }
  }

  /**
   * 将本地MOBI或AZW3复制到隔离缓存后交给原生解析器。
   */
  async loadLocalMobi(context: common.UIAbilityContext, uri: string, format: string): Promise<ReaderDocument> {
    try {
      const stat = fs.statSync(uri);
      if (stat.size === 0 || stat.size > ReaderContentLoader.MAX_ARCHIVE_BYTES) {
        throw new Error('MOBI为空或超过100 MB');
      }
      const path = this.prepareWorkspace(context, format);
      await this.copyLocalBookFile(uri, path, stat.size, format.toUpperCase());
      this.validatePayloadSignature(path, stat.size, 'mobi');
      return this.mobiReader.parse(path, `${this.activeDirectory}/resources`);
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('本地MOBI读取失败');
    }
  }

  /**
   * 将远程MOBI或AZW3暂存到隔离缓存，解析完成后由阅读器统一清理。
   */
  async loadRemoteMobi(context: common.UIAbilityContext, url: string, format: string,
    requestHeaders?: Record<string, string>): Promise<ReaderDocument> {
    const path = this.prepareWorkspace(context, format);
    try {
      await this.downloadBinaryStream(url, path, ReaderContentLoader.MAX_ARCHIVE_BYTES, '远程MOBI', 'mobi',
        requestHeaders);
      return this.mobiReader.parse(path, `${this.activeDirectory}/resources`);
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('远程MOBI读取失败');
    }
  }

  /**
   * 清理当前阅读容器产生的临时文件。
   */
  cleanup(): void {
    this.cancelled = true;
    this.activeRequest?.destroy();
    this.activeRequest = undefined;
    this.cleanupDirectory();
  }

  private cleanupDirectory(): void {
    this.extractedFilePaths.clear();
    if (this.activeDirectory.length === 0) return;
    try {
      fs.rmdirSync(this.activeDirectory);
    } catch (_) {
    }
    this.activeDirectory = '';
  }

  private prepareWorkspace(context: common.UIAbilityContext, format: string): string {
    try {
      this.cleanupDirectory();
      this.cancelled = false;
      this.activeDirectory = `${context.cacheDir}/reader-${Date.now()}`;
      fs.mkdirSync(this.activeDirectory, true);
      return `${this.activeDirectory}/book.${format}`;
    } catch (error) {
      throw new Error(error instanceof Error ? error.message : '无法创建阅读缓存目录');
    }
  }

  private assertNotCancelled(): void {
    if (this.cancelled) throw new Error('图书加载已取消');
  }

  private async extractAndParse(archivePath: string, format: string): Promise<ReaderDocument> {
    try {
      const archiveManifest = this.inspectZipArchive(archivePath, format);
      const outputDirectory = `${this.activeDirectory}/content`;
      fs.mkdirSync(outputDirectory, true);
      await zlib.decompressFile(archivePath, outputDirectory);
      const files: string[] = [];
      this.extractedBytes = 0;
      this.collectFiles(outputDirectory, outputDirectory, files, new Map<string, number>(archiveManifest.files));
      this.extractedFilePaths = new Set<string>(files);
      this.validateExtractedImages(files);
      if (format === 'cbz') return this.parseComic(files);
      return this.parseEpub(outputDirectory, files);
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('图书容器解压失败');
    }
  }

  /**
   * 校验ZIP中央目录、本地头、路径、算法、压缩比与声明展开配额。
   */
  validateZipArchive(path: string, format: string = ''): Map<string, number> {
    return new Map<string, number>(this.inspectZipArchive(path, format).files);
  }

  private inspectZipArchive(path: string, format: string = ''): ZipArchiveManifest {
    let archiveSize = 0;
    try {
      archiveSize = fs.statSync(path).size;
    } catch (_) {
      throw new Error('无法读取图书容器元数据');
    }
    if (archiveSize < 22 || archiveSize > ReaderContentLoader.MAX_ARCHIVE_BYTES) {
      throw new Error('图书容器为空或超过100 MB');
    }
    const tailLength = Math.min(archiveSize, 65557);
    const tailOffset = archiveSize - tailLength;
    const tail = this.readFileRange(path, tailOffset, tailLength);
    const eocdIndex = this.findZipSignatureBackward(tail, 0x06054B50);
    if (eocdIndex < 0 || eocdIndex + 22 > tail.length) throw new Error('图书容器缺少ZIP目录');
    const entryCount = this.readUInt16LE(tail, eocdIndex + 10);
    const diskNumber = this.readUInt16LE(tail, eocdIndex + 4);
    const centralDisk = this.readUInt16LE(tail, eocdIndex + 6);
    const diskEntryCount = this.readUInt16LE(tail, eocdIndex + 8);
    const centralSize = this.readUInt32LE(tail, eocdIndex + 12);
    const centralOffset = this.readUInt32LE(tail, eocdIndex + 16);
    const commentLength = this.readUInt16LE(tail, eocdIndex + 20);
    if (entryCount === 0xFFFF || centralSize === 0xFFFFFFFF || centralOffset === 0xFFFFFFFF) {
      throw new Error('暂不支持ZIP64图书容器');
    }
    if (diskNumber !== 0 || centralDisk !== 0 || diskEntryCount !== entryCount) {
      throw new Error('暂不支持分卷ZIP图书容器');
    }
    if (tailOffset + eocdIndex + 22 + commentLength !== archiveSize) throw new Error('ZIP目录尾部长度异常');
    if (entryCount === 0 || entryCount > ReaderContentLoader.MAX_ARCHIVE_FILES) {
      throw new Error('压缩包文件数量无效或超过5000');
    }
    if (centralSize > ReaderContentLoader.MAX_CENTRAL_DIRECTORY_BYTES ||
      centralOffset + centralSize > tailOffset + eocdIndex) throw new Error('ZIP中央目录越界');
    const central = this.readFileRange(path, centralOffset, centralSize);
    const names = new Set<string>();
    const foldedNames = new Set<string>();
    const fileNames = new Set<string>();
    const directoryNames = new Set<string>();
    const entryRanges: ZipEntryRange[] = [];
    const manifestFiles = new Map<string, number>();
    let cursor = 0;
    let totalSize = 0;
    let epubMimetypeValid = false;
    for (let index = 0; index < entryCount; index++) {
      if (cursor + 46 > central.length || this.readUInt32LE(central, cursor) !== 0x02014B50) {
        throw new Error('ZIP中央目录条目损坏');
      }
      const flags = this.readUInt16LE(central, cursor + 8);
      const compression = this.readUInt16LE(central, cursor + 10);
      const crc32 = this.readUInt32LE(central, cursor + 16);
      const compressedSize = this.readUInt32LE(central, cursor + 20);
      const uncompressedSize = this.readUInt32LE(central, cursor + 24);
      const nameLength = this.readUInt16LE(central, cursor + 28);
      const extraLength = this.readUInt16LE(central, cursor + 30);
      const entryCommentLength = this.readUInt16LE(central, cursor + 32);
      const entryDisk = this.readUInt16LE(central, cursor + 34);
      const externalAttributes = this.readUInt32LE(central, cursor + 38);
      const localOffset = this.readUInt32LE(central, cursor + 42);
      const next = cursor + 46 + nameLength + extraLength + entryCommentLength;
      if (nameLength === 0 || next > central.length) throw new Error('ZIP条目名称或长度无效');
      if (entryDisk !== 0) throw new Error('暂不支持分卷ZIP图书容器');
      if ((flags & 0x0001) !== 0) throw new Error('不支持加密图书容器');
      if (compression !== 0 && compression !== 8) throw new Error('图书容器包含不支持的压缩算法');
      this.validateZipFlags(flags, compression);
      if (((externalAttributes >>> 16) & 0xF000) === 0xA000) throw new Error('图书容器包含符号链接');
      this.validateZipExtraFields(central.subarray(cursor + 46 + nameLength,
        cursor + 46 + nameLength + extraLength));
      const name = this.decodeZipEntryName(central.subarray(cursor + 46, cursor + 46 + nameLength), flags);
      const normalized = this.normalizeZipEntryName(name);
      if (names.has(normalized)) throw new Error('图书容器包含重复路径');
      const foldedName = normalized.toLocaleLowerCase('en-US');
      if (foldedNames.has(foldedName)) throw new Error('图书容器包含大小写冲突路径');
      names.add(normalized);
      foldedNames.add(foldedName);
      const isDirectory = normalized.endsWith('/');
      const structuralName = isDirectory ? normalized.substring(0, normalized.length - 1) : normalized;
      const structuralFolded = structuralName.toLocaleLowerCase('en-US');
      const segments = structuralFolded.split('/');
      for (let segmentIndex = 1; segmentIndex < segments.length; segmentIndex++) {
        if (fileNames.has(segments.slice(0, segmentIndex).join('/'))) {
          throw new Error('图书容器父路径已被声明为文件');
        }
      }
      if (isDirectory) {
        if (fileNames.has(structuralFolded)) throw new Error('图书容器目录与文件路径冲突');
        directoryNames.add(structuralFolded);
      } else {
        if (directoryNames.has(structuralFolded) || Array.from(directoryNames)
          .some((value: string) => value.startsWith(`${structuralFolded}/`))) {
          throw new Error('图书容器文件与目录路径冲突');
        }
        fileNames.add(structuralFolded);
      }
      if (!isDirectory) {
        manifestFiles.set(normalized, uncompressedSize);
        totalSize += uncompressedSize;
        if (totalSize > ReaderContentLoader.MAX_EXTRACTED_BYTES) throw new Error('解压内容超过300 MB');
        if (uncompressedSize > 0 && (compressedSize === 0 ||
          uncompressedSize / compressedSize > ReaderContentLoader.MAX_COMPRESSION_RATIO)) {
          throw new Error('图书容器压缩比异常');
        }
      }
      if (localOffset + 30 > centralOffset) {
        throw new Error('ZIP本地文件头越界或损坏');
      }
      const localHeader = this.readFileRange(path, localOffset, 30);
      const localFlags = this.readUInt16LE(localHeader, 6);
      const localCompression = this.readUInt16LE(localHeader, 8);
      const localCrc32 = this.readUInt32LE(localHeader, 14);
      const localCompressedSize = this.readUInt32LE(localHeader, 18);
      const localUncompressedSize = this.readUInt32LE(localHeader, 22);
      const localNameLength = this.readUInt16LE(localHeader, 26);
      const localExtraLength = this.readUInt16LE(localHeader, 28);
      if (this.readUInt32LE(localHeader, 0) !== 0x04034B50 || localFlags !== flags ||
        localCompression !== compression || localOffset + 30 + localNameLength + localExtraLength > centralOffset) {
        throw new Error('ZIP本地文件头与中央目录不一致');
      }
      const localNameBytes = this.readFileRange(path, localOffset + 30, localNameLength);
      if (this.normalizeZipEntryName(this.decodeZipEntryName(localNameBytes, localFlags)) !== normalized) {
        throw new Error('ZIP本地文件路径与中央目录不一致');
      }
      this.validateZipExtraFields(this.readFileRange(path, localOffset + 30 + localNameLength, localExtraLength));
      const dataOffset = localOffset + 30 + localNameLength + localExtraLength;
      if (compressedSize > centralOffset - dataOffset) throw new Error('ZIP条目数据越界');
      const dataEnd = dataOffset + compressedSize;
      let entryEnd = dataEnd;
      if ((flags & 0x0008) === 0) {
        if (localCrc32 !== crc32 || localCompressedSize !== compressedSize ||
          localUncompressedSize !== uncompressedSize) {
          throw new Error('ZIP本地文件头CRC或尺寸与中央目录不一致');
        }
      } else {
        if (localCrc32 !== 0 || localCompressedSize !== 0 || localUncompressedSize !== 0) {
          throw new Error('ZIP流式条目本地头必须使用空CRC和尺寸');
        }
        entryEnd = this.validateZipDataDescriptor(path, dataEnd, centralOffset, crc32, compressedSize,
          uncompressedSize);
      }
      if (entryRanges.some((range: ZipEntryRange) => localOffset < range.end && entryEnd > range.start)) {
        throw new Error('ZIP条目数据区重叠');
      }
      entryRanges.push({ start: localOffset, end: entryEnd });
      if (normalized === 'mimetype') {
        epubMimetypeValid = localOffset === 0 && compression === 0 && localExtraLength === 0 &&
          compressedSize === 20 && uncompressedSize === 20 &&
          this.isEpubMimetype(this.readFileRange(path, dataOffset, 20));
      }
      cursor = next;
    }
    if (cursor !== central.length) throw new Error('ZIP中央目录长度不一致');
    if (format === 'epub' && !epubMimetypeValid) throw new Error('EPUB容器mimetype无效');
    return { files: manifestFiles };
  }

  private validateZipDataDescriptor(path: string, offset: number, centralOffset: number, crc32: number,
    compressedSize: number, uncompressedSize: number): number {
    if (centralOffset - offset < 12) throw new Error('ZIP流式条目缺少数据描述符');
    const available = Math.min(16, centralOffset - offset);
    const descriptor = this.readFileRange(path, offset, available);
    if (descriptor.length >= 16 && this.readUInt32LE(descriptor, 0) === 0x08074B50 &&
      this.readUInt32LE(descriptor, 4) === crc32 && this.readUInt32LE(descriptor, 8) === compressedSize &&
      this.readUInt32LE(descriptor, 12) === uncompressedSize) return offset + 16;
    if (this.readUInt32LE(descriptor, 0) === crc32 && this.readUInt32LE(descriptor, 4) === compressedSize &&
      this.readUInt32LE(descriptor, 8) === uncompressedSize) return offset + 12;
    throw new Error('ZIP数据描述符与中央目录不一致');
  }

  private validateZipFlags(flags: number, compression: number): void {
    const commonFlags = 0x0008 | 0x0800;
    const deflateFlags = compression === 8 ? 0x0006 : 0;
    if ((flags & ~(commonFlags | deflateFlags)) !== 0) throw new Error('ZIP条目包含不支持的通用标志位');
  }

  private validateZipExtraFields(bytes: Uint8Array): void {
    let cursor = 0;
    while (cursor < bytes.length) {
      if (cursor + 4 > bytes.length) throw new Error('ZIP扩展字段结构损坏');
      const headerId = this.readUInt16LE(bytes, cursor);
      const dataSize = this.readUInt16LE(bytes, cursor + 2);
      if (dataSize > bytes.length - cursor - 4) throw new Error('ZIP扩展字段长度越界');
      if (headerId === 0x0001) throw new Error('暂不支持ZIP64扩展字段');
      cursor += 4 + dataSize;
    }
  }

  private isEpubMimetype(bytes: Uint8Array): boolean {
    const expected = 'application/epub+zip';
    if (bytes.length !== expected.length) return false;
    for (let index = 0; index < expected.length; index++) {
      if (bytes[index] !== expected.charCodeAt(index)) return false;
    }
    return true;
  }

  private findZipSignatureBackward(bytes: Uint8Array, signature: number): number {
    for (let index = bytes.length - 4; index >= 0; index--) {
      if (this.readUInt32LE(bytes, index) === signature) return index;
    }
    return -1;
  }

  private readUInt16LE(bytes: Uint8Array, offset: number): number {
    return bytes[offset] | (bytes[offset + 1] << 8);
  }

  private readUInt32LE(bytes: Uint8Array, offset: number): number {
    return (bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) |
      (bytes[offset + 3] << 24)) >>> 0;
  }

  private decodeZipEntryName(bytes: Uint8Array, flags: number): string {
    try {
      const encoding = (flags & 0x0800) !== 0 ? 'utf-8' : 'gb18030';
      return util.TextDecoder.create(encoding, { fatal: true }).decodeToString(bytes);
    } catch (_) {
      throw new Error('ZIP条目名称编码无效');
    }
  }

  private normalizeZipEntryName(name: string): string {
    if (name.indexOf('\0') >= 0 || name.startsWith('/') || name.startsWith('\\') || /^[A-Za-z]:/.test(name)) {
      throw new Error('图书容器包含绝对路径');
    }
    if (name.normalize('NFC') !== name) throw new Error('图书容器路径未使用NFC规范化');
    const replaced = name.replace(/\\/g, '/');
    const directory = replaced.endsWith('/');
    const segments = replaced.split('/');
    const safe: string[] = [];
    for (let index = 0; index < segments.length; index++) {
      const segment = segments[index];
      if (segment.length === 0) continue;
      if (segment === '.' || segment === '..') throw new Error('图书容器包含目录穿越路径');
      if (/[. ]$/.test(segment) || segment.includes(':') || /[\u0000-\u001F\u007F]/.test(segment) ||
        /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(segment)) {
        throw new Error('图书容器包含跨平台保留路径');
      }
      safe.push(segment);
    }
    if (safe.length === 0) throw new Error('ZIP条目路径为空');
    return `${safe.join('/')}${directory ? '/' : ''}`;
  }

  private collectFiles(root: string, directory: string, output: string[], expectedFiles: Map<string, number>): void {
    try {
      const names = fs.listFileSync(directory);
      for (let index = 0; index < names.length; index++) {
        const path = `${directory}/${names[index]}`;
        const stat = fs.lstatSync(path);
        if (stat.isSymbolicLink()) throw new Error('解压内容包含符号链接');
        if (stat.isDirectory()) {
          this.collectFiles(root, path, output, expectedFiles);
        } else if (stat.isFile()) {
          if (!path.startsWith(`${root}/`)) throw new Error('压缩包包含非法路径');
          const relative = path.substring(root.length + 1);
          const expectedSize = expectedFiles.get(relative);
          if (expectedSize === undefined) throw new Error('解压产生中央目录未声明的文件');
          if (stat.size !== expectedSize) throw new Error('解压文件尺寸与中央目录不一致');
          expectedFiles.delete(relative);
          output.push(path);
          if (output.length > ReaderContentLoader.MAX_ARCHIVE_FILES) throw new Error('压缩包文件数量超过5000');
          this.extractedBytes += stat.size;
          if (this.extractedBytes > ReaderContentLoader.MAX_EXTRACTED_BYTES) throw new Error('解压内容超过300 MB');
        } else {
          throw new Error('解压内容包含不支持的文件类型');
        }
      }
      if (directory === root && expectedFiles.size > 0) throw new Error('中央目录声明的文件未被完整解压');
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('无法检查解压后的图书文件');
    }
  }

  private validateExtractedImages(files: string[]): void {
    const images = files.filter((path: string) => /\.(jpg|jpeg|png|gif|webp|bmp)$/i.test(path));
    try {
      for (let index = 0; index < images.length; index++) {
        const size = fs.statSync(images[index]).size;
        const bytes = this.readFileRange(images[index], 0,
          Math.min(size, ReaderContentLoader.MAX_IMAGE_HEADER_BYTES));
        const dimensions = this.readImageDimensions(bytes, images[index]);
        if (dimensions[0] <= 0 || dimensions[1] <= 0 ||
          dimensions[0] > ReaderContentLoader.MAX_IMAGE_DIMENSION ||
          dimensions[1] > ReaderContentLoader.MAX_IMAGE_DIMENSION ||
          dimensions[0] * dimensions[1] > ReaderContentLoader.MAX_IMAGE_PIXELS) {
          throw new Error('图书容器包含尺寸异常的图片');
        }
      }
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('无法检查图书容器图片');
    }
  }

  private readImageDimensions(bytes: Uint8Array, _path: string): number[] {
    // EPUB资源的扩展名经常与真实编码不一致，必须按文件头识别后再校验尺寸。
    if (bytes.length >= 24 && this.readUInt32BE(bytes, 0) === 0x89504E47 &&
      this.readUInt32BE(bytes, 4) === 0x0D0A1A0A) {
      return [this.readUInt32BE(bytes, 16), this.readUInt32BE(bytes, 20)];
    }
    if (bytes.length >= 10 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 &&
      bytes[3] === 0x38 && (bytes[4] === 0x37 || bytes[4] === 0x39) && bytes[5] === 0x61) {
      return [this.readUInt16LE(bytes, 6), this.readUInt16LE(bytes, 8)];
    }
    if (bytes.length >= 26 && bytes[0] === 0x42 && bytes[1] === 0x4D) {
      return [this.readUInt32LE(bytes, 18), Math.abs(this.readInt32LE(bytes, 22))];
    }
    if (bytes.length >= 4 && bytes[0] === 0xFF && bytes[1] === 0xD8) {
      return this.readJpegDimensions(bytes);
    }
    if (bytes.length >= 30 && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 &&
      bytes[3] === 0x46 && bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 &&
      bytes[11] === 0x50) return this.readWebpDimensions(bytes);
    throw new Error('图书容器图片头损坏或格式不支持');
  }

  private readJpegDimensions(bytes: Uint8Array): number[] {
    if (bytes[0] !== 0xFF || bytes[1] !== 0xD8) throw new Error('JPEG图片头无效');
    let cursor = 2;
    while (cursor + 4 <= bytes.length) {
      while (cursor < bytes.length && bytes[cursor] !== 0xFF) cursor++;
      while (cursor < bytes.length && bytes[cursor] === 0xFF) cursor++;
      if (cursor >= bytes.length) break;
      const marker = bytes[cursor++];
      if (marker === 0xD8 || marker === 0xD9 || (marker >= 0xD0 && marker <= 0xD7)) continue;
      if (cursor + 2 > bytes.length) break;
      const segmentLength = (bytes[cursor] << 8) | bytes[cursor + 1];
      if (segmentLength < 2 || cursor + segmentLength > bytes.length) break;
      const isSof = (marker >= 0xC0 && marker <= 0xC3) || (marker >= 0xC5 && marker <= 0xC7) ||
        (marker >= 0xC9 && marker <= 0xCB) || (marker >= 0xCD && marker <= 0xCF);
      if (isSof && segmentLength >= 7) {
        return [(bytes[cursor + 5] << 8) | bytes[cursor + 6],
          (bytes[cursor + 3] << 8) | bytes[cursor + 4]];
      }
      cursor += segmentLength;
    }
    throw new Error('无法在1 MB头部内读取JPEG尺寸');
  }

  private readWebpDimensions(bytes: Uint8Array): number[] {
    const decoder = util.TextDecoder.create('us-ascii');
    if (decoder.decodeToString(bytes.subarray(0, 4)) !== 'RIFF' ||
      decoder.decodeToString(bytes.subarray(8, 12)) !== 'WEBP') throw new Error('WebP图片头无效');
    const kind = decoder.decodeToString(bytes.subarray(12, 16));
    if (kind === 'VP8X') {
      return [1 + this.readUInt24LE(bytes, 24), 1 + this.readUInt24LE(bytes, 27)];
    }
    if (kind === 'VP8L' && bytes.length >= 25 && bytes[20] === 0x2F) {
      return [1 + bytes[21] + ((bytes[22] & 0x3F) << 8),
        1 + (bytes[22] >> 6) + (bytes[23] << 2) + ((bytes[24] & 0x0F) << 10)];
    }
    if (kind === 'VP8 ' && bytes.length >= 30 && bytes[23] === 0x9D && bytes[24] === 0x01 && bytes[25] === 0x2A) {
      return [this.readUInt16LE(bytes, 26) & 0x3FFF, this.readUInt16LE(bytes, 28) & 0x3FFF];
    }
    throw new Error('WebP图片尺寸信息无效');
  }

  private readUInt24LE(bytes: Uint8Array, offset: number): number {
    return bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16);
  }

  private readUInt32BE(bytes: Uint8Array, offset: number): number {
    return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) |
      bytes[offset + 3]) >>> 0;
  }

  private readInt32LE(bytes: Uint8Array, offset: number): number {
    return bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24);
  }

  private parseComic(files: string[]): ReaderDocument {
    const images = files.filter((path: string) => /\.(jpg|jpeg|png|gif|webp|bmp)$/i.test(path))
      .sort((left: string, right: string) => left.localeCompare(right));
    if (images.length === 0) throw new Error('CBZ中没有可显示的图片');
    return { kind: 'comic', chapters: [], imageUris: images.map((path: string) => `file://${path}`) };
  }

  private async parseEpub(root: string, files: string[]): Promise<ReaderDocument> {
    try {
      const containerPath = `${root}/META-INF/container.xml`;
      if (!fs.accessSync(containerPath) || !files.includes(containerPath) ||
        fs.statSync(containerPath).size <= 0 || fs.statSync(containerPath).size > 256 * 1024) {
        throw new Error('EPUB缺少有效container.xml');
      }
      const container = await fs.readText(containerPath);
    this.assertSafeEpubMarkup(container, 'container.xml');
    const rootfileMatch = /<rootfile\b([^>]*)\/?\s*>/i.exec(container);
    const packageMatch = rootfileMatch === null ? null : /full-path\s*=\s*["']([^"']+)["']/i.exec(rootfileMatch[1]);
    if (packageMatch === null) throw new Error('EPUB缺少OPF入口');
    const packagePath = this.safeJoin(root, packageMatch[1]);
    if (!/\.opf$/i.test(packagePath) || !files.includes(packagePath) || !fs.statSync(packagePath).isFile() ||
      fs.statSync(packagePath).size <= 0 || fs.statSync(packagePath).size > 2 * 1024 * 1024) {
      throw new Error('EPUB的OPF入口无效');
    }
    const packageXml = await fs.readText(packagePath);
    this.assertSafeEpubMarkup(packageXml, 'OPF');
    const packageDirectory = packagePath.substring(0, packagePath.lastIndexOf('/'));
    const manifest = new Map<string, string>();
    const imageManifest = new Map<string, string>();
    let epub3CoverId = '';
    const itemPattern = /<item\b([^>]+)>?/gi;
    let itemMatch: RegExpExecArray | null = itemPattern.exec(packageXml);
    let manifestItems = 0;
    while (itemMatch !== null) {
      manifestItems++;
      if (manifestItems > ReaderContentLoader.MAX_EPUB_MANIFEST_ITEMS) {
        throw new Error('EPUB manifest条目超过5000');
      }
      if (itemMatch[1].length > ReaderContentLoader.MAX_EPUB_ATTRIBUTES) {
        throw new Error('EPUB manifest单条属性超过8 KB');
      }
      const id = this.attribute(itemMatch[1], 'id');
      const href = this.attribute(itemMatch[1], 'href');
      const mediaType = this.attribute(itemMatch[1], 'media-type');
      if ((id.length > 0 && !this.validEpubIdentifier(id)) ||
        (href.length > 0 && !this.validEpubRelativeReference(href))) {
        throw new Error('EPUB manifest标识或路径无效');
      }
      if (id.length > 0 && (manifest.has(id) || imageManifest.has(id))) throw new Error('EPUB manifest包含重复ID');
      if (id.length > 0 && href.length > 0 && /xhtml|html/i.test(mediaType)) manifest.set(id, href);
      if (id.length > 0 && href.length > 0 && /^image\/(jpeg|png|gif|webp|bmp)$/i.test(mediaType)) {
        imageManifest.set(id, href);
        if (/\bcover-image\b/i.test(this.attribute(itemMatch[1], 'properties'))) epub3CoverId = id;
      }
      itemMatch = itemPattern.exec(packageXml);
    }
    const ordered: string[] = [];
    const spinePattern = /<itemref\b([^>]+)>?/gi;
    let spineMatch: RegExpExecArray | null = spinePattern.exec(packageXml);
    let spineItems = 0;
    const spineIds = new Set<string>();
    while (spineMatch !== null) {
      spineItems++;
      if (spineItems > ReaderContentLoader.MAX_EPUB_SPINE_ITEMS) throw new Error('EPUB spine条目超过1000');
      if (spineMatch[1].length > ReaderContentLoader.MAX_EPUB_ATTRIBUTES) {
        throw new Error('EPUB spine单条属性超过8 KB');
      }
      const idref = this.attribute(spineMatch[1], 'idref');
      if (!this.validEpubIdentifier(idref) || spineIds.has(idref)) throw new Error('EPUB spine引用重复或无效');
      spineIds.add(idref);
      const href = manifest.get(idref);
      if (idref.length === 0 || href === undefined) throw new Error('EPUB spine引用无效');
      const chapterPath = this.safeJoin(packageDirectory, href);
      if (!files.includes(chapterPath) || !fs.statSync(chapterPath).isFile()) throw new Error('EPUB章节文件不存在');
      ordered.push(chapterPath);
      spineMatch = spinePattern.exec(packageXml);
    }
    if (ordered.length === 0) throw new Error('EPUB spine为空');
    const chapters: ReaderChapter[] = [];
    let totalChapterBytes = 0;
    let totalCharacters = 0;
    for (let index = 0; index < ordered.length; index++) {
      this.assertNotCancelled();
      const chapterSize = fs.statSync(ordered[index]).size;
      if (chapterSize <= 0 || chapterSize > ReaderContentLoader.MAX_EPUB_CHAPTER_BYTES) {
        throw new Error('EPUB单章节为空或超过5 MB');
      }
      totalChapterBytes += chapterSize;
      if (totalChapterBytes > ReaderContentLoader.MAX_EPUB_TEXT_BYTES) throw new Error('EPUB章节总量超过50 MB');
      const html = await fs.readText(ordered[index]);
      totalCharacters += html.length;
      if (totalCharacters > ReaderContentLoader.MAX_EPUB_TEXT_CHARACTERS) {
        throw new Error('EPUB解码正文超过50 MB字符配额');
      }
      this.assertSafeEpubMarkup(html, '章节');
      const blocks = this.epubHtmlToBlocks(html, ordered[index]);
      const content = blocks.filter((block: ReaderBlock) => block.kind !== 'image')
        .map((block: ReaderBlock) => block.text).join('\n\n').trim();
      const hasImage = blocks.some((block: ReaderBlock) => block.kind === 'image');
      if (content.length === 0 && !hasImage) continue;
      const titleMatch = /<(?:title|h1|h2)[^>]*>([\s\S]*?)<\/(?:title|h1|h2)>/i.exec(html);
      chapters.push({
        title: titleMatch === null ? `第${chapters.length + 1}章` : this.htmlToText(titleMatch[1]).substring(0, 80),
        content: content.length > 0 ? content : '图片章节',
        blocks: blocks
      });
      // 大型EPUB不应为每一章都切换事件循环；每16章让出一次即可维持取消响应。
      if ((index + 1) % 16 === 0) {
        await new Promise<void>((resolve: () => void) => setTimeout(resolve, 0));
      }
    }
    this.assertNotCancelled();
    if (chapters.length === 0) throw new Error('EPUB中没有可阅读章节');
      const imageUris: string[] = [];
      chapters.forEach((chapter: ReaderChapter) => chapter.blocks?.forEach((block: ReaderBlock) => {
        if (block.kind === 'image' && imageUris.indexOf(block.uri) < 0) imageUris.push(block.uri);
      }));
      const legacyCover = /<meta\b[^>]*name\s*=\s*["']cover["'][^>]*content\s*=\s*["']([^"']+)["']/i
        .exec(packageXml);
      const coverId = epub3CoverId.length > 0 ? epub3CoverId : (legacyCover === null ? '' : legacyCover[1]);
      const coverHref = imageManifest.get(coverId) ?? '';
      const coverUri = coverHref.length === 0 ? '' : this.epubImageUri(packagePath, coverHref);
      const document: ReaderDocument = { kind: 'text', chapters: chapters, imageUris: imageUris };
      if (coverUri.length > 0) document.coverUri = coverUri;
      return document;
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('EPUB章节解析失败');
    }
  }

  private epubHtmlToBlocks(html: string, chapterPath: string): ReaderBlock[] {
    this.validateEpubHtmlShape(html);
    let safeHtml = html.replace(/<script\b[\s\S]*?<\/script\s*>/gi, '')
      .replace(/<style\b[\s\S]*?<\/style\s*>/gi, '')
      .replace(/<(iframe|object|embed|form|video|audio)\b[\s\S]*?<\/\1\s*>/gi, '')
      .replace(/<foreignObject\b[\s\S]*?<\/foreignObject\s*>/gi, '');
    safeHtml = this.markOrderedListItems(safeHtml);
    const blocks: ReaderBlock[] = [];
    const pattern = /<(h[1-6]|p|blockquote|pre|code|li|aside|figcaption|dt|dd)\b([^>]*)>([\s\S]*?)<\/\1\s*>|<img\b([^>]*)\/?\s*>|<svg\b[^>]*>([\s\S]*?)<\/svg\s*>|<math\b[^>]*>([\s\S]*?)<\/math\s*>/gi;
    let match: RegExpExecArray | null = pattern.exec(safeHtml);
    while (match !== null && blocks.length < ReaderContentLoader.MAX_EPUB_BLOCKS) {
      if (match[4] !== undefined) {
        this.appendEpubImageBlock(blocks, chapterPath, match[4]);
      } else if (match[5] !== undefined) {
        const text = this.svgFallbackText(match[5], '插图');
        if (text.length > 0) blocks.push({ kind: 'diagram', text: text, uri: '' });
      } else if (match[6] !== undefined) {
        const text = this.mathFallbackText(match[6]);
        if (text.length > 0) blocks.push({ kind: 'math', text: text, uri: '' });
      } else {
        const tag = match[1].toLowerCase();
        const attributes = match[2] ?? '';
        this.appendEpubMixedBlocks(blocks, chapterPath, tag, attributes, match[3]);
      }
      if (blocks.length > ReaderContentLoader.MAX_EPUB_BLOCKS) throw new Error('EPUB章节内容块超过10000');
      match = pattern.exec(safeHtml);
    }
    if (match !== null) throw new Error('EPUB章节内容块超过10000');
    if (blocks.length === 0) {
      const text = this.htmlToText(safeHtml);
      if (text.length > 0) blocks.push({ kind: 'text', text: text, uri: '' });
    }
    return blocks;
  }

  /** 限制章节标签数量和单标签属性长度，避免复杂标记放大转块成本。 */
  private validateEpubHtmlShape(html: string): void {
    const tagPattern = /<([A-Za-z][A-Za-z0-9:-]*)([^>]*)>/g;
    let count = 0;
    let match: RegExpExecArray | null = tagPattern.exec(html);
    while (match !== null) {
      count++;
      if (count > ReaderContentLoader.MAX_EPUB_TAGS) throw new Error('EPUB章节标签数量超过20000');
      if (match[2].length > ReaderContentLoader.MAX_EPUB_ATTRIBUTES) throw new Error('EPUB章节单标签属性超过8 KB');
      match = tagPattern.exec(html);
    }
  }

  /** 拒绝实体及内部DTD；章节仅兼容不参与解析的标准XHTML文档声明。 */
  private assertSafeEpubMarkup(value: string, label: string): void {
    if (/<!ENTITY\b/i.test(value) || /<!DOCTYPE\b[^>]*[\[\]]/i.test(value)) {
      throw new Error(`EPUB${label}不允许DTD或实体声明`);
    }
    const doctypes = value.match(/<!DOCTYPE\b[^>]*>/gi) ?? [];
    if (doctypes.length === 0) return;
    const doctype = doctypes[0] ?? '';
    if (label !== '章节' || doctypes.length > 1 ||
      !/^<!DOCTYPE\s+html(?:\s+(?:PUBLIC|SYSTEM)\s+["'][^"']+["'](?:\s+["'][^"']+["'])?)?\s*>$/i
        .test(doctype)) {
      throw new Error(`EPUB${label}包含不受支持的DTD声明`);
    }
  }

  private appendEpubMixedBlocks(blocks: ReaderBlock[], chapterPath: string, tag: string, attributes: string,
    html: string): void {
    const embedded = /<img\b([^>]*)\/?\s*>|<svg\b[^>]*>([\s\S]*?)<\/svg\s*>|<math\b[^>]*>([\s\S]*?)<\/math\s*>/gi;
    let offset = 0;
    let match: RegExpExecArray | null = embedded.exec(html);
    while (match !== null) {
      if (blocks.length >= ReaderContentLoader.MAX_EPUB_BLOCKS) throw new Error('EPUB章节内容块超过10000');
      this.appendEpubTextBlock(blocks, tag, attributes, html.substring(offset, match.index));
      if (match[1] !== undefined) this.appendEpubImageBlock(blocks, chapterPath, match[1]);
      if (match[2] !== undefined) {
        const text = this.svgFallbackText(match[2], '插图');
        if (text.length > 0) blocks.push({ kind: 'diagram', text: text, uri: '' });
      }
      if (match[3] !== undefined) {
        const text = this.mathFallbackText(match[3]);
        if (text.length > 0) blocks.push({ kind: 'math', text: text, uri: '' });
      }
      offset = embedded.lastIndex;
      match = embedded.exec(html);
    }
    this.appendEpubTextBlock(blocks, tag, attributes, html.substring(offset));
  }

  private appendEpubTextBlock(blocks: ReaderBlock[], tag: string, attributes: string, html: string): void {
    const text = this.htmlToText(html);
    if (text.length === 0) return;
    if (tag.startsWith('h')) {
      blocks.push({ kind: 'heading', text: text, uri: '', level: Number(tag.substring(1)) });
    } else if (tag === 'blockquote') {
      blocks.push({ kind: 'quote', text: text, uri: '' });
    } else if (tag === 'pre' || tag === 'code') {
      blocks.push({ kind: 'code', text: text, uri: '' });
    } else if (tag === 'aside' || /(?:epub:type|role|class)\s*=\s*["'][^"']*(?:footnote|doc-footnote)/i
      .test(attributes)) {
      blocks.push({ kind: 'footnote', text: text, uri: '' });
    } else if (tag === 'li') {
      const order = this.attribute(attributes, 'data-reader-order');
      blocks.push({ kind: 'list', text: order.length > 0 ? `${order}. ${text}` : `• ${text}`, uri: '' });
    } else {
      blocks.push({ kind: 'paragraph', text: text, uri: '' });
    }
  }

  private appendEpubImageBlock(blocks: ReaderBlock[], chapterPath: string, attributes: string): void {
    const source = this.attribute(attributes, 'src');
    const alternative = this.attribute(attributes, 'alt');
    if (/\.svg(?:[?#]|$)/i.test(source)) {
      const text = this.externalSvgFallbackText(chapterPath, source, alternative);
      if (text.length > 0) blocks.push({ kind: 'diagram', text: text, uri: '' });
      return;
    }
    const uri = this.epubImageUri(chapterPath, source);
    if (uri.length > 0) blocks.push({ kind: 'image', text: alternative, uri: uri });
  }

  private markOrderedListItems(html: string): string {
    return html.replace(/<ol\b([^>]*)>([\s\S]*?)<\/ol\s*>/gi,
      (_list: string, attributes: string, content: string) => {
        let order = Number.parseInt(this.attribute(attributes, 'start'), 10);
        if (!Number.isFinite(order)) order = 1;
        return content.replace(/<li\b([^>]*)>/gi, (_item: string, itemAttributes: string) => {
          const value = Number.parseInt(this.attribute(itemAttributes, 'value'), 10);
          if (Number.isFinite(value)) order = value;
          const result = `<li ${itemAttributes} data-reader-order="${order}">`;
          order++;
          return result;
        });
      });
  }

  private externalSvgFallbackText(chapterPath: string, source: string, fallback: string): string {
    try {
      const directory = chapterPath.substring(0, chapterPath.lastIndexOf('/'));
      const path = this.safeJoin(directory, source.split('?')[0]);
      if (!this.extractedFilePaths.has(path)) return fallback;
      const stat = fs.statSync(path);
      if (!stat.isFile() || stat.size <= 0 || stat.size > 1024 * 1024) return fallback;
      return this.svgFallbackText(fs.readTextSync(path), fallback);
    } catch (_) {
      return fallback;
    }
  }

  private svgFallbackText(svg: string, fallback: string): string {
    const safe = svg.replace(/<script\b[\s\S]*?<\/script\s*>/gi, '')
      .replace(/<foreignObject\b[\s\S]*?<\/foreignObject\s*>/gi, '');
    const values: string[] = [];
    const pattern = /<(title|desc|text)\b[^>]*>([\s\S]*?)<\/\1\s*>/gi;
    let match: RegExpExecArray | null = pattern.exec(safe);
    while (match !== null && values.length < 100) {
      const text = this.htmlToText(match[2]);
      if (text.length > 0 && !values.includes(text)) values.push(text);
      match = pattern.exec(safe);
    }
    return values.length > 0 ? values.join(' · ') : fallback;
  }

  private mathFallbackText(math: string): string {
    const annotation = /<annotation\b[^>]*encoding\s*=\s*["'](?:application\/x-tex|text\/x-tex)["'][^>]*>([\s\S]*?)<\/annotation\s*>/i
      .exec(math);
    if (annotation !== null) return this.htmlToText(annotation[1]);
    let value = math;
    value = value.replace(/<mfrac\b[^>]*>([\s\S]*?)<\/mfrac\s*>/gi, '($1) / ')
      .replace(/<msup\b[^>]*>([\s\S]*?)<\/msup\s*>/gi, '$1 ^ ')
      .replace(/<msub\b[^>]*>([\s\S]*?)<\/msub\s*>/gi, '$1 _ ')
      .replace(/<mo\b[^>]*>([\s\S]*?)<\/mo\s*>/gi, ' $1 ');
    return this.htmlToText(value).replace(/\s+/g, ' ').trim();
  }

  private epubImageUri(chapterPath: string, source: string): string {
    if (source.length === 0 || source.startsWith('data:') || source.startsWith('http://') ||
      source.startsWith('https://') || source.startsWith('//')) return '';
    try {
      const directory = chapterPath.substring(0, chapterPath.lastIndexOf('/'));
      const path = this.safeJoin(directory, source.split('?')[0]);
      if (!this.extractedFilePaths.has(path) || !/\.(jpg|jpeg|png|gif|webp|bmp)$/i.test(path) ||
        !fs.accessSync(path) || !fs.lstatSync(path).isFile() || fs.lstatSync(path).isSymbolicLink()) return '';
      return `file://${path}`;
    } catch (_) {
      return '';
    }
  }

  private safeJoin(base: string, relative: string): string {
    const rawPath = relative.split('#')[0];
    let decoded = '';
    try {
      decoded = decodeURIComponent(rawPath);
    } catch (_) {
      throw new Error('EPUB包含非法URL编码');
    }
    if (decoded.includes('\\') || decoded.includes('%') || /[\u0000-\u001F\u007F]/.test(decoded)) {
      throw new Error('EPUB包含非法编码路径');
    }
    if (decoded.normalize('NFC') !== decoded) throw new Error('EPUB引用路径未使用NFC规范化');
    const baseSegments = base.split('/').filter((part: string) => part.length > 0);
    const parts = decoded.split('/');
    const normalized: string[] = baseSegments.slice();
    const minimumDepth = this.activeDirectory.split('/').filter((part: string) => part.length > 0).length;
    parts.forEach((part: string) => {
      if (part === '..') {
        if (normalized.length <= minimumDepth) throw new Error('EPUB包含越界路径');
        normalized.pop();
      }
      else if (part.length > 0 && part !== '.') normalized.push(part);
    });
    const path = `/${normalized.join('/')}`;
    if (!path.startsWith(`${this.activeDirectory}/`)) throw new Error('EPUB包含越界路径');
    return path;
  }

  private validEpubIdentifier(value: string): boolean {
    return value.length >= 1 && value.length <= 256 && !/[\u0000-\u0020\u007F]/.test(value);
  }

  private validEpubRelativeReference(value: string): boolean {
    if (value.length < 1 || value.length > 2048 || value.startsWith('/') || value.startsWith('\\') ||
      value.startsWith('//') || value.includes('\\') || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(value) ||
      /[\u0000-\u001F\u007F]/.test(value) || /%(?![0-9A-Fa-f]{2})/.test(value)) return false;
    try {
      decodeURIComponent(value.split('#')[0]);
      return decodeURIComponent(value.split('#')[0]).normalize('NFC') === decodeURIComponent(value.split('#')[0]);
    } catch (_) {
      return false;
    }
  }

  private attribute(source: string, name: string): string {
    const match = new RegExp(`${name}\\s*=\\s*["']([^"']+)["']`, 'i').exec(source);
    return match === null ? '' : match[1];
  }

  private htmlToText(html: string): string {
    return html.replace(/<script[\s\S]*?<\/script>/gi, '').replace(/<style[\s\S]*?<\/style>/gi, '')
      .replace(/<(br|\/p|\/div|\/h[1-6]|\/li)>/gi, '\n').replace(/<[^>]+>/g, '')
      .replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
      .replace(/&quot;/gi, '"').replace(/&#39;/gi, "'").replace(/\r/g, '').replace(/\n{3,}/g, '\n\n').trim();
  }
}
