import { RemoteDirectory, RemoteMediaFilterOptions, RemoteMediaItem, RemoteMediaKind, RemoteMediaSource,
  RemoteMediaTag, RemotePlaybackDescriptor, RemotePlaybackMetrics } from './RemoteMediaModels';

/**
 * 将MyTools远程媒体响应归一化为有界、可安全交给页面使用的领域对象。
 */
export class RemoteMediaResponseNormalizer {
  /** 将新 Reader 已发布书库索引投影为 App 远程书库。 */
  normalizeLibraryIndex(value: Object | undefined, keyword: string, excludeAdult: boolean): RemoteDirectory {
    if (!Array.isArray(value) || value.length > 10000) throw new Error('电子书索引响应无效');
    const query = keyword.trim().toLocaleLowerCase().substring(0, 100);
    const items: RemoteMediaItem[] = [];
    value.forEach((entry: Object) => {
      const record = this.record(entry);
      const assetId = this.uuid(record?.['assetId']);
      const title = this.text(record?.['title'], 500);
      const author = this.text(record?.['author'], 300, true) ?? '';
      const format = this.text(record?.['format'], 32, true) ?? '';
      const metadata = this.record(record?.['metadata']);
      const adult = metadata?.['adult'] === true;
      if (record === undefined || assetId === undefined || title === undefined || (excludeAdult && adult) ||
        (query.length > 0 && !`${title} ${author}`.toLocaleLowerCase().includes(query))) return;
      const name = format.length > 0 && !title.toLocaleLowerCase().endsWith(`.${format.toLocaleLowerCase()}`) ?
        `${title}.${format.toLocaleLowerCase()}` : title;
      items.push({ id: assetId, name, path: `/asset/${assetId}`, kind: 'other',
        contentType: this.ebookContentType(format), size: 0, lastModified: '', bookTitle: title,
        bookAuthor: author, bookMetadataStatus: 'READY' });
    });
    return { path: '/', items, total: items.length, page: 1, pageSize: items.length };
  }
  /** 将新媒体库分页结果投影为 App 统一目录。 */
  normalizeLibraryPage(value: Object | undefined, keyword: string, fileType: string,
    tagNames: string[], matchAllTags: boolean, excludeAdult: boolean): RemoteDirectory {
    const record = this.record(value);
    const rawItems = record?.['items'];
    if (!Array.isArray(rawItems)) throw new Error('媒体库响应格式无效');
    const query = keyword.trim().toLocaleLowerCase().substring(0, 100);
    const requiredTags = tagNames.slice(0, 20);
    const items: RemoteMediaItem[] = [];
    rawItems.slice(0, 100).forEach((item: Object) => {
      const child = this.record(item);
      const id = this.uuid(child?.['id']);
      const name = this.text(child?.['displayName'], 1024);
      const contentType = this.text(child?.['mimeType'], 256, true) ?? '';
      const size = child?.['sizeBytes'];
      const tags = this.plainTags(child?.['tags']);
      if (child === undefined || id === undefined || name === undefined || /[\\/]/.test(name) ||
        typeof size !== 'number' || !Number.isSafeInteger(size) || size < 0) return;
      const kind = this.kind(name, contentType, false);
      if (query.length > 0 && !name.toLocaleLowerCase().includes(query)) return;
      if (fileType.length > 0 && kind !== fileType) return;
      if (excludeAdult && tags.some((tag: RemoteMediaTag): boolean => tag.name.toLocaleLowerCase() === 'r18')) return;
      const matches = requiredTags.filter((tag: string): boolean =>
        tags.some((entry: RemoteMediaTag): boolean => entry.name === tag)).length;
      if (requiredTags.length > 0 && (matchAllTags ? matches !== requiredTags.length : matches === 0)) return;
      items.push({ id, name, path: `/media/${id}`, kind, contentType, size,
        lastModified: '', tags });
    });
    return { path: '/', items, total: items.length, page: 1, pageSize: 100 };
  }
  /** 将媒体库中已迁移的电子书文件投影为远程书库。 */
  normalizeLibraryBooks(values: Object[], keyword: string, excludeAdult: boolean,
    page: number = 1, pageSize: number = 40): RemoteDirectory {
    const query = keyword.trim().toLocaleLowerCase().substring(0, 100);
    const items: RemoteMediaItem[] = [];
    values.slice(0, 1000).forEach((item: Object) => {
      const child = this.record(item);
      const id = this.uuid(child?.['id']);
      const name = this.text(child?.['displayName'], 1024);
      const contentType = this.text(child?.['mimeType'], 256, true) ?? '';
      const size = child?.['sizeBytes'];
      const tags = this.plainTags(child?.['tags']);
      const normalizedName = name?.toLocaleLowerCase() ?? '';
      const extension = normalizedName.split('.').pop() ?? '';
      if (child === undefined || id === undefined || name === undefined || /[\\/]/.test(name) ||
        typeof size !== 'number' || !Number.isSafeInteger(size) || size < 0 ||
        normalizedName === 'summary.txt' ||
        !['txt', 'epub', 'pdf', 'mobi', 'azw3', 'cbz', 'cbr'].includes(extension) ||
        query.length > 0 && !name.toLocaleLowerCase().includes(query) ||
        excludeAdult && tags.some((tag: RemoteMediaTag): boolean => tag.name.toLocaleLowerCase() === 'r18')) return;
      items.push({ id, name, path: `/media/${id}`, kind: 'other', contentType, size,
        lastModified: '', tags, bookTitle: name.substring(0, name.length - extension.length - 1),
        bookAuthor: '', bookMetadataStatus: 'READY' });
    });
    // 媒体库电子书必须分页返回，避免真机一次渲染大量卡片触发前台线程阻塞保护。
    const safePage = Math.max(1, Math.floor(page));
    const safePageSize = Math.max(1, Math.min(100, Math.floor(pageSize)));
    const offset = (safePage - 1) * safePageSize;
    return { path: '/', items: items.slice(offset, offset + safePageSize), total: items.length,
      page: safePage, pageSize: safePageSize };
  }
  /** 将MyTools本地远程文件目录转换为App数据源。 */
  normalizeLocalSources(value: Object | undefined): RemoteMediaSource[] {
    if (!Array.isArray(value)) return [];
    const sources: RemoteMediaSource[] = [];
    value.slice(0, 100).forEach((item: Object) => {
      const record = this.record(item);
      const id = this.identifier(record?.['id']);
      const originalName = this.text(record?.['directoryName'], 300);
      const rootPath = this.path(record?.['directoryPath']) ?? '';
      const directoryType = this.text(record?.['directoryType'], 32);
      if (record === undefined || id === undefined || !/^[1-9]\d{0,18}$/.test(id) || originalName === undefined ||
        (directoryType !== 'MULTIMEDIA' && directoryType !== 'LARGE_MEDIA')) return;
      // App使用面向用户的稳定名称，避免暴露服务端历史目录名Multimedia和Large Media。
      const name = directoryType === 'MULTIMEDIA' ? '远程文件' : '大文件';
      sources.push({ id: `local:${id}`, name, type: 'MYTOOLS_LOCAL', url: rootPath,
        isDefault: false, isActive: true, localDirectoryType: directoryType });
    });
    // 普通远程文件优先，大文件目录作为同级来源紧随其后。
    sources.sort((left: RemoteMediaSource, right: RemoteMediaSource) =>
      left.localDirectoryType === right.localDirectoryType ? 0 :
        (left.localDirectoryType === 'MULTIMEDIA' ? -1 : 1));
    if (sources.length > 0) sources[0].isDefault = true;
    return sources;
  }

  /** 将MyTools电子书目录转换为远程书库来源，避免与多媒体来源混用。 */
  normalizeLocalBookSources(value: Object | undefined): RemoteMediaSource[] {
    if (!Array.isArray(value)) return [];
    const sources: RemoteMediaSource[] = [];
    value.slice(0, 100).forEach((item: Object) => {
      const record = this.record(item);
      const id = this.identifier(record?.['id']);
      const rootPath = this.path(record?.['directoryPath']) ?? '';
      const directoryType = this.text(record?.['directoryType'], 32);
      if (record === undefined || id === undefined || !/^[1-9]\d{0,18}$/.test(id) || directoryType !== 'EBOOK') {
        return;
      }
      sources.push({ id: `local:${id}`, name: 'MyTools 电子书', type: 'MYTOOLS_LOCAL', url: rootPath,
        isDefault: false, isActive: true, localDirectoryType: directoryType });
    });
    if (sources.length > 0) sources[0].isDefault = true;
    return sources;
  }

  /** 将MyTools分页本地文件转换为可查看媒体项。 */
  normalizeLocalFiles(value: Object | undefined, rootPath: string = '', page: number = 1,
    pageSize: number = 24): RemoteDirectory {
    const record = this.record(value);
    const rawItems = record?.['list'];
    if (!Array.isArray(rawItems)) return { path: '/', items: [], total: 0, page, pageSize };
    const total = this.nonNegativeInteger(record?.['total']);
    const items: RemoteMediaItem[] = [];
    rawItems.slice(0, 1000).forEach((item: Object) => {
      const child = this.record(item);
      const id = this.identifier(child?.['id']);
      const name = this.text(child?.['filename'], 1024);
      const contentType = this.text(child?.['mimeType'], 256, true);
      const lastModified = this.text(child?.['updateTime'], 256, true) ?? '';
      const filePath = this.path(child?.['filePath']);
      const size = child?.['fileSize'];
      if (child === undefined || id === undefined || !/^[1-9]\d{0,18}$/.test(id) || name === undefined ||
        /[\\/]/.test(name) || contentType === undefined || typeof size !== 'number' || !Number.isFinite(size) ||
        size < 0 || size > 9007199254740991) return;
      items.push({ id, name, path: `/local/${id}`, directoryName: this.localDirectoryName(filePath, rootPath),
        kind: this.kind(name, contentType, false), contentType, size: Math.floor(size), lastModified,
        tags: this.tags(child['tags']) });
    });
    return { path: '/', items, total, page, pageSize };
  }

  /** 将电子书领域分页结果转换为兼容现有远程书库页面的数据。 */
  normalizeEbookCatalog(value: Object | undefined, page: number = 1, pageSize: number = 40): RemoteDirectory {
    const record = this.record(value);
    const rawItems = record?.['list'];
    if (!Array.isArray(rawItems)) return { path: '/', items: [], total: 0, page, pageSize };
    const total = this.nonNegativeInteger(record?.['total']);
    const items: RemoteMediaItem[] = [];
    rawItems.slice(0, 1000).forEach((item: Object) => {
      const child = this.record(item);
      const id = this.identifier(child?.['localFileId']);
      const name = this.text(child?.['filename'], 1024);
      const filePath = this.path(child?.['filePath']);
      const extension = this.text(child?.['extension'], 32, true) ?? '';
      const size = child?.['fileSize'];
      const title = this.text(child?.['title'], 500, true) ?? name;
      const author = this.text(child?.['author'], 300, true) ?? '';
      const description = this.text(child?.['description'], 5000, true) ?? '';
      const category = this.text(child?.['category'], 100, true) ?? '';
      const completionStatus = this.text(child?.['completionStatus'], 32, true) ?? '';
      const fileHash = this.text(child?.['fileHash'], 128, true) ?? '';
      const coverAvailable = this.flag(child?.['coverAvailable']);
      const metadataStatus = this.text(child?.['metadataStatus'], 20, true) ?? 'PENDING';
      const lastModified = this.text(child?.['updateTime'], 256, true) ?? '';
      if (child === undefined || id === undefined || !/^[1-9]\d{0,18}$/.test(id) || name === undefined ||
        /[\\/]/.test(name) || typeof size !== 'number' || !Number.isFinite(size) || size < 0 ||
        size > 9007199254740991) return;
      const chapterCount = this.nonNegativeInteger(child['chapterCount']);
      const wordCount = this.nonNegativeInteger(child['wordCount']);
      const normalized: RemoteMediaItem = { id, name, path: `/local/${id}`,
        directoryName: filePath === undefined ? undefined : filePath,
        kind: 'other', contentType: this.ebookContentType(extension), size: Math.floor(size), lastModified,
        bookTitle: title, bookAuthor: author, bookDescription: description, bookCategory: category,
        bookCompletionStatus: completionStatus, bookChapterCount: chapterCount, bookWordCount: wordCount,
        bookCoverAvailable: coverAvailable, bookMetadataStatus: metadataStatus, tags: this.tags(child['tags']) };
      if (/^[a-fA-F0-9]{16,128}$/.test(fileHash)) normalized.bookFileHash = fileHash.toLowerCase();
      items.push(normalized);
    });
    return { path: '/', items, total, page, pageSize };
  }

  /** 将服务端完整目录与标签集合转换为有界筛选选项。 */
  normalizeLocalFilters(value: Object | undefined): RemoteMediaFilterOptions {
    const record = this.record(value);
    const directories = new Set<string>();
    const tags = new Set<string>();
    if (Array.isArray(record?.['directories'])) {
      record!['directories'].slice(0, 1000).forEach((item: Object) => {
        const name = this.text(item, 4096, true);
        const segments = name?.split('/') ?? [];
        if (name !== undefined && !name.includes('\\') && !name.startsWith('/') && !name.includes('//') &&
          !segments.some((segment: string) => segment === '.' || segment === '..')) {
          directories.add(name.length === 0 ? '根目录' : name);
        }
      });
    }
    if (Array.isArray(record?.['tags'])) {
      record!['tags'].slice(0, 500).forEach((item: Object) => {
        const name = this.text(item, 32);
        if (name !== undefined) tags.add(name);
      });
    }
    return { directories: Array.from(directories), tags: Array.from(tags) };
  }
  /**
   * 最多恢复100个启用的数据源，并按服务端数字ID去重。
   */
  normalizeSources(value: Object | undefined): RemoteMediaSource[] {
    if (!Array.isArray(value)) return [];
    const sources = new Map<string, RemoteMediaSource>();
    value.slice(0, 200).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined || !this.flag(record['isActive'])) return;
      const id = this.identifier(record['id']);
      const name = this.text(record['name'], 300);
      const type = this.text(record['type'], 32);
      const url = this.text(record['url'], 4096);
      if (id === undefined || !/^[1-9]\d{0,18}$/.test(id) || name === undefined || type === undefined ||
        !/^[A-Za-z0-9_-]+$/.test(type) || url === undefined) return;
      sources.set(id, { id, name, type, url, isDefault: this.flag(record['isDefault']), isActive: true });
    });
    return Array.from(sources.values()).slice(-100);
  }

  /**
   * 最多恢复10000个合法目录项，按规范绝对路径去重。
   */
  normalizeDirectory(value: Object | undefined, requestedPath: string): RemoteDirectory {
    const record = this.record(value);
    const fallbackPath = this.path(requestedPath) ?? '/';
    if (record === undefined) return { path: fallbackPath, items: [] };
    const directoryPath = this.path(record['path']) ?? fallbackPath;
    const rawItems = record['items'];
    if (!Array.isArray(rawItems)) return { path: directoryPath, items: [] };
    const items = new Map<string, RemoteMediaItem>();
    rawItems.slice(0, 20000).forEach((item: Object) => {
      const child = this.record(item);
      if (child === undefined) return;
      const name = this.text(child['name'], 1024);
      const path = this.path(child['path']);
      const contentType = this.text(child['contentType'], 256, true);
      const lastModified = this.text(child['lastModified'], 256, true);
      const size = child['size'];
      if (name === undefined || /[\\/]/.test(name) || path === undefined || contentType === undefined ||
        lastModified === undefined || typeof size !== 'number' || !Number.isFinite(size) || size < 0 ||
        size > 9007199254740991) return;
      const directory = child['isDirectory'] === true;
      items.set(path, { name, path, directoryName: directory ? undefined : this.directoryLabel(directoryPath),
        kind: this.kind(name, contentType, directory), contentType, size: Math.floor(size), lastModified });
    });
    return { path: directoryPath, items: Array.from(items.values()).slice(-10000) };
  }

  /**
   * 校验随机票据、固定流路径和合理过期时间，并构造播放器描述。
   */
  normalizePlayback(value: Object | undefined, baseUrl: string, sourceType: string,
    now: number = Date.now()): RemotePlaybackDescriptor {
    const record = this.record(value);
    if (record === undefined) throw new Error('服务端未返回远程播放票据');
    const ticket = this.text(record['ticket'], 32);
    const streamPath = this.text(record['streamPath'], 128);
    const expiresAtText = this.text(record['expiresAt'], 64);
    const expiresAt = expiresAtText === undefined ? NaN : Date.parse(expiresAtText);
    const expectedRemotePath = `/api/app/v1/media/tickets/${ticket}`;
    const expectedLocalPath = `/api/app/v1/local-media/tickets/${ticket}`;
    if (ticket === undefined || !/^[a-f0-9]{32}$/.test(ticket) ||
      (streamPath !== expectedRemotePath && streamPath !== expectedLocalPath) || !Number.isFinite(expiresAt) ||
      expiresAt < now - 5 * 60 * 1000 || expiresAt > now + 24 * 60 * 60 * 1000) {
      throw new Error('服务端远程播放票据格式无效');
    }
    return { url: `${baseUrl}${streamPath}`, ticket, expiresAt, sourceType, supportsRange: true };
  }

  /**
   * 校验播放代理累计字节、活动流数量和最后传输时间。
   */
  normalizeMetrics(value: Object | undefined): RemotePlaybackMetrics {
    const record = this.record(value);
    if (record === undefined) throw new Error('播放指标响应为空');
    const transferredBytes = this.safeInteger(record['transferredBytes'], 0, 9007199254740991);
    const activeStreams = this.safeInteger(record['activeStreams'], 0, 10000);
    const lastTransferTime = this.safeInteger(record['lastTransferTime'], 0, 9007199254740991);
    if (transferredBytes === undefined || activeStreams === undefined || lastTransferTime === undefined) {
      throw new Error('播放指标响应格式无效');
    }
    return { transferredBytes, activeStreams, lastTransferTime };
  }

  private kind(name: string, contentType: string, directory: boolean): RemoteMediaKind {
    if (directory) return 'directory';
    const lowerName = name.toLowerCase();
    const lowerType = contentType.toLowerCase();
    if (lowerType.startsWith('image/') || /\.(jpg|jpeg|png|gif|webp|bmp)$/.test(lowerName)) return 'image';
    if (lowerType.startsWith('audio/') || /\.(mp3|flac|aac|m4a|ogg|wav)$/.test(lowerName)) return 'audio';
    if (lowerType.startsWith('video/') || /\.(mp4|mkv|mov|webm|m4v|avi)$/.test(lowerName)) return 'video';
    return 'other';
  }

  private ebookContentType(extension: string): string {
    const normalized = extension.toLowerCase();
    if (normalized === 'pdf') return 'application/pdf';
    if (normalized === 'epub') return 'application/epub+zip';
    if (normalized === 'txt' || normalized === 'md') return 'text/plain';
    return 'application/octet-stream';
  }

  private tags(value: Object | undefined): RemoteMediaTag[] {
    if (!Array.isArray(value)) return [];
    const tags = new Map<string, RemoteMediaTag>();
    value.slice(0, 64).forEach((item: Object) => {
      if (typeof item === 'string') {
        const plainName = this.text(item, 32);
        if (plainName !== undefined) tags.set(plainName, { name: plainName, type: '', confidence: 1 });
        return;
      }
      const record = this.record(item);
      const name = this.text(record?.['tagName'], 32);
      const type = this.text(record?.['tagType'], 32, true) ?? '';
      const confidence = record?.['confidence'];
      if (name === undefined || typeof confidence !== 'number' || !Number.isFinite(confidence) ||
        confidence < 0 || confidence > 1) return;
      tags.set(name, { name, type, confidence });
    });
    return Array.from(tags.values());
  }

  private plainTags(value: Object | undefined): RemoteMediaTag[] {
    if (!Array.isArray(value)) return [];
    const tags: RemoteMediaTag[] = [];
    value.slice(0, 64).forEach((item: Object) => {
      const name = this.text(item, 32);
      if (name !== undefined) tags.push({ name, type: '', confidence: 1 });
    });
    return tags;
  }

  private uuid(value: Object | undefined): string | undefined {
    if (typeof value !== 'string' ||
      !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/.test(value)) {
      return undefined;
    }
    return value.toLowerCase();
  }

  private localDirectoryName(filePath: string | undefined, rootPath: string): string {
    const root = this.path(rootPath);
    if (filePath === undefined || root === undefined) return '根目录';
    const prefix = root === '/' ? '/' : `${root}/`;
    if (!filePath.startsWith(prefix)) return '根目录';
    const relativePath = filePath.substring(prefix.length);
    const separator = relativePath.lastIndexOf('/');
    return separator > 0 ? relativePath.substring(0, separator) : '根目录';
  }

  private directoryLabel(path: string): string {
    return path === '/' ? '根目录' : path.substring(1);
  }

  private record(value: Object | undefined): Record<string, Object> | undefined {
    return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value) ?
      value as Record<string, Object> : undefined;
  }

  private text(value: Object | undefined, maximum: number, allowEmpty: boolean = false): string | undefined {
    if (typeof value !== 'string') return undefined;
    const text = value;
    if (text.length > maximum || (!allowEmpty && text.length === 0) || /[\u0000-\u001F\u007F]/.test(text)) {
      return undefined;
    }
    return text;
  }

  private identifier(value: Object | undefined): string | undefined {
    if (typeof value !== 'string' && typeof value !== 'number') return undefined;
    const text = `${value}`;
    return text.length <= 20 && !/[\u0000-\u001F\u007F]/.test(text) ? text : undefined;
  }

  private nonNegativeInteger(value: Object | undefined): number | undefined {
    if (typeof value === 'number') {
      return Number.isSafeInteger(value) && value >= 0 ? value : undefined;
    }
    if (typeof value !== 'string' || !/^\d{1,16}$/.test(value)) return undefined;
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) ? parsed : undefined;
  }

  private path(value: Object | undefined): string | undefined {
    let path = this.text(value, 4096);
    if (path === undefined || !path.startsWith('/') || path.includes('\\') || path.includes('//')) return undefined;
    if (path.length > 1 && path.endsWith('/')) path = path.substring(0, path.length - 1);
    const segments = path.split('/');
    if (segments.some((segment: string, index: number) => index > 0 &&
      (segment.length === 0 || segment === '.' || segment === '..'))) return undefined;
    return path;
  }

  private flag(value: Object | undefined): boolean {
    return value === true || value === 1;
  }

  private safeInteger(value: Object | undefined, minimum: number, maximum: number): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum ?
      value : undefined;
  }
}
