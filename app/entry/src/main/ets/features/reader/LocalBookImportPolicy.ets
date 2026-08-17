import { Book, BookFormat, resolveBookFormat } from './ReaderModels';

/**
 * 将系统文件选择器返回的不可信URI归一化为本地图书。
 */
export class LocalBookImportPolicy {
  /**
   * 最多检查50个URI，过滤重复、危险名称与不支持格式。
   */
  normalize(uris: string[]): Book[] {
    const seen: Record<string, boolean> = {};
    const books: Book[] = [];
    uris.slice(0, 50).forEach((uri: string) => {
      try {
        const safeUri = this.uri(uri);
        if (seen[safeUri] === true) return;
        const filename = this.filename(safeUri);
        const format = resolveBookFormat(filename);
        // CBR依赖RAR解码器，尚未接入前不把无法打开的文件加入书架。
        if (format === 'unknown' || format === 'cbr') return;
        const title = filename.substring(0, filename.length - format.length - 1).trim();
        if (title.length < 1 || title.length > 500) return;
        seen[safeUri] = true;
        books.push(this.book(safeUri, title, format));
      } catch (_) {
        // 单个异常选择结果隔离丢弃，不影响同批其他图书。
      }
    });
    return books;
  }

  private uri(value: string): string {
    if (typeof value !== 'string' || value.length < 8 || value.length > 4000 ||
      !value.startsWith('file://') || /[\u0000-\u001F\u007F\\]/.test(value) || value.includes('#') ||
      /%(?:00|0a|0d|2f|5c)/i.test(value)) throw new Error('本地图书URI无效');
    return value;
  }

  private filename(uri: string): string {
    const withoutQuery = uri.split('?')[0];
    const slash = withoutQuery.lastIndexOf('/');
    const encoded = slash >= 0 ? withoutQuery.substring(slash + 1) : '';
    if (encoded.length < 1 || encoded.length > 1500) throw new Error('本地图书文件名无效');
    let decoded = '';
    try {
      decoded = decodeURIComponent(encoded);
    } catch (_) {
      throw new Error('本地图书文件名编码无效');
    }
    if (decoded.length < 3 || decoded.length > 512 || decoded === '.' || decoded === '..' ||
      /[\u0000-\u001F\u007F/\\]/.test(decoded)) throw new Error('本地图书文件名无效');
    return decoded;
  }

  private book(uri: string, title: string, format: BookFormat): Book {
    return {
      id: `local:${uri}`,
      name: title,
      author: '本地图书',
      coverUrl: '',
      origin: 'local',
      format: format,
      resourceUri: uri,
      sourceId: '',
      progress: 0
    };
  }
}
