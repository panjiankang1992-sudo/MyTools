import { BookFormat } from './ReaderModels';
import { ShelfSyncItem, ShelfSyncResult } from './ShelfSyncModels';

/**
 * 对书架同步请求与不可信服务端响应执行相同字段边界校验。
 */
export class ShelfSyncResponseNormalizer {
  /**
   * 最多检查10000项，按图书ID保留最后版本并输出5000项。
   */
  list(value: Object | undefined): ShelfSyncItem[] {
    if (!Array.isArray(value)) throw new Error('服务端书架列表格式无效');
    const items = new Map<string, ShelfSyncItem>();
    (value as Object[]).slice(0, 10000).forEach((raw: Object) => {
      try {
        const item = this.remoteItem(raw);
        const previous = items.get(item.id);
        if (previous === undefined || item.revision > previous.revision ||
          (item.revision === previous.revision && item.updatedAt >= previous.updatedAt)) items.set(item.id, item);
      } catch (_) {
        // 单个损坏同步项隔离丢弃。
      }
    });
    return Array.from(items.values()).slice(-5000);
  }

  /**
   * 校验保存前的本地同步项，阻止损坏快照进入请求。
   */
  localItem(value: ShelfSyncItem): ShelfSyncItem {
    return this.item(value.id, value.name, value.author, value.origin, value.format, value.resourceUri,
      value.sourceId, value.remoteCoverUrl, value.updatedAt, value.deleted, value.revision);
  }

  /**
   * 解析保存回执；接受成功时必须携带权威图书。
   */
  saveResult(value: Object | undefined): ShelfSyncResult {
    const record = this.record(value);
    if (record === undefined || typeof record['accepted'] !== 'boolean') {
      throw new Error('服务端书架保存回执无效');
    }
    const accepted = record['accepted'] === true;
    const rawBook = record['book'];
    const book = rawBook === undefined || rawBook === null ? undefined : this.remoteItem(rawBook);
    if (accepted && book === undefined) throw new Error('服务端书架保存回执缺少权威图书');
    return { accepted: accepted, book: book };
  }

  private remoteItem(value: Object): ShelfSyncItem {
    const record = this.record(value);
    if (record === undefined) throw new Error('服务端书架项格式无效');
    return this.item(record['bookId'], record['name'], record['author'], record['origin'], record['format'],
      record['resourceUri'], record['sourceId'], record['remoteCoverUrl'], record['clientUpdatedAt'],
      record['deleted'], record['revision']);
  }

  private item(idValue: Object, nameValue: Object, authorValue: Object, originValue: Object,
    formatValue: Object, resourceValue: Object, sourceValue: Object, coverValue: Object,
    updatedValue: Object, deletedValue: Object, revisionValue: Object): ShelfSyncItem {
    const id = this.text(idValue, 1000, false, '图书标识');
    if (id.startsWith('local:')) throw new Error('本地图书不能同步');
    const name = this.text(nameValue, 300, false, '书名');
    const author = this.text(authorValue, 200, false, '作者');
    if (originValue !== 'source' && originValue !== 'remote') throw new Error('书架来源协议无效');
    const origin = originValue as 'source' | 'remote';
    const format = this.format(formatValue);
    const resourceUri = this.text(resourceValue, 4096, false, '资源地址');
    const sourceId = this.text(sourceValue, 4096, false, '来源标识');
    const remoteCoverUrl = this.text(coverValue, 4096, true, '封面地址');
    if (!this.identity(origin, resourceUri, sourceId) ||
      (remoteCoverUrl.length > 0 && !this.httpUrl(remoteCoverUrl))) throw new Error('书架来源协议无效');
    if (typeof deletedValue !== 'boolean') throw new Error('书架删除状态无效');
    return {
      id: id, name: name, author: author, origin: origin, format: format,
      resourceUri: resourceUri, sourceId: sourceId, remoteCoverUrl: remoteCoverUrl,
      updatedAt: this.integer(updatedValue, '客户端更新时间'), deleted: deletedValue,
      revision: this.integer(revisionValue, '书架修订号')
    };
  }

  private identity(origin: 'source' | 'remote', resourceUri: string, sourceId: string): boolean {
    if (origin === 'source') return this.httpUrl(resourceUri) && this.httpUrl(sourceId);
    return this.remotePath(resourceUri) && /^\d{1,20}$/.test(sourceId) && sourceId !== '0';
  }

  private remotePath(value: string): boolean {
    return value.startsWith('/') && value.length > 1 && !value.endsWith('/') && !value.includes('//') &&
      !/[\\\u0000-\u001F\u007F]/.test(value) &&
      !value.split('/').some((part: string) => part === '.' || part === '..');
  }

  private httpUrl(value: string): boolean {
    return (value.startsWith('https://') || value.startsWith('http://')) &&
      !/[\u0000-\u001F\u007F]/.test(value);
  }

  private format(value: Object): BookFormat {
    if (value === 'txt' || value === 'epub' || value === 'pdf' || value === 'mobi' || value === 'azw3' ||
      value === 'cbz' || value === 'cbr' || value === 'unknown') return value as BookFormat;
    throw new Error('书架图书格式无效');
  }

  private text(value: Object | undefined, maximum: number, optional: boolean, label: string): string {
    if (optional && (value === undefined || value === null)) return '';
    if (typeof value !== 'string' || value.length > maximum || (!optional && value.length === 0) ||
      /[\u0000-\u001F\u007F]/.test(value)) throw new Error(`${label}无效`);
    return value;
  }

  private integer(value: Object, label: string): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error(`${label}无效`);
    return value;
  }

  private record(value: Object | undefined): Record<string, Object> | undefined {
    return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value) ?
      value as Record<string, Object> : undefined;
  }
}
