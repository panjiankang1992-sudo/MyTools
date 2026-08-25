import { ReadingProgress } from './ReaderModels';
import { BookSourceSyncItem, BookSourceSyncResult, ProgressSyncResult, ReaderDataSummary,
  ReaderMarkerSyncItem, ReaderMarkerSyncResult } from './ReaderSyncModels';

/**
 * 对阅读同步接口的不可信服务端数据执行类型、配额和协议校验。
 */
export class ReaderSyncResponseNormalizer {
  private static readonly MAX_SCAN_ITEMS: number = 10000;
  private static readonly MAX_RESULT_ITEMS: number = 5000;

  /** 解析阅读进度列表，损坏项逐条隔离并按本地图书标识去重。 */
  progressList(value: Object | undefined, keyMap: Map<string, string>): ReadingProgress[] {
    if (!Array.isArray(value)) return [];
    const selected = new Map<string, ReadingProgress>();
    value.slice(0, ReaderSyncResponseNormalizer.MAX_SCAN_ITEMS).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const remoteBookId = this.syncKey(record['bookKey'] ?? record['bookId']);
      const localBookId = remoteBookId === undefined ? undefined : keyMap.get(remoteBookId);
      if (localBookId === undefined) return;
      const progress = this.progress(record, localBookId);
      if (progress === undefined) return;
      const previous = selected.get(localBookId);
      if (previous === undefined || this.isNewer(progress.revision ?? 0, progress.updatedAt,
        previous.revision ?? 0, previous.updatedAt)) selected.set(localBookId, progress);
    });
    return Array.from(selected.values()).slice(0, ReaderSyncResponseNormalizer.MAX_RESULT_ITEMS);
  }

  /** 解析保存阅读进度的权威回执。 */
  progressSave(value: Object | undefined, localBookId: string): ProgressSyncResult {
    const record = this.requiredRecord(value, '阅读进度保存回执无效');
    if (record['position'] !== undefined) {
      const direct = this.progress(record, localBookId);
      if (direct === undefined) throw new Error('阅读进度保存回执无效');
      return { accepted: true, progress: direct };
    }
    const accepted = this.requiredBoolean(record['accepted'], '阅读进度保存回执无效');
    const remoteRecord = this.record(record['progress']);
    const progress = remoteRecord === undefined ? undefined : this.progress(remoteRecord, localBookId);
    if ((accepted && progress === undefined) || (remoteRecord !== undefined && progress === undefined)) {
      throw new Error('阅读进度保存回执无效');
    }
    return { accepted: accepted, progress: progress };
  }

  /** 解析按图书标识查询的单条阅读进度。 */
  progressOne(value: Object | undefined, localBookId: string): ReadingProgress | undefined {
    if (value === undefined || value === null) return undefined;
    const record = this.record(value);
    if (record === undefined) throw new Error('单本阅读进度响应无效');
    const progress = this.progress(record, localBookId);
    if (progress === undefined) throw new Error('单本阅读进度响应无效');
    return progress;
  }

  /** 从新 Reader 列表中按书籍键读取单条进度。 */
  progressByKey(value: Object | undefined, key: string, localBookId: string): ReadingProgress | undefined {
    if (!Array.isArray(value)) throw new Error('阅读进度列表响应无效');
    const match = value.map((item: Object): Record<string, Object> | undefined => this.record(item))
      .find((item: Record<string, Object> | undefined): boolean => item?.['bookKey'] === key);
    if (match === undefined) return undefined;
    const progress = this.progress(match, localBookId);
    if (progress === undefined) throw new Error('单本阅读进度响应无效');
    return progress;
  }

  /** 解析书源快照列表，损坏项逐条隔离并按来源地址去重。 */
  sourceList(value: Object | undefined): BookSourceSyncItem[] {
    if (!Array.isArray(value)) return [];
    const selected = new Map<string, BookSourceSyncItem>();
    value.slice(0, ReaderSyncResponseNormalizer.MAX_SCAN_ITEMS).forEach((item: Object) => {
      const record = this.record(item);
      const source = record === undefined ? undefined : this.source(record);
      if (source === undefined) return;
      const previous = selected.get(source.sourceUrl);
      if (previous === undefined || this.isNewer(source.revision, source.updatedAt,
        previous.revision, previous.updatedAt)) selected.set(source.sourceUrl, source);
    });
    return Array.from(selected.values()).slice(0, ReaderSyncResponseNormalizer.MAX_RESULT_ITEMS);
  }

  /** 解析保存书源的权威回执。 */
  sourceSave(value: Object | undefined): BookSourceSyncResult {
    const record = this.requiredRecord(value, '书源保存回执无效');
    const accepted = this.requiredBoolean(record['accepted'], '书源保存回执无效');
    const remoteRecord = this.record(record['source']);
    const source = remoteRecord === undefined ? undefined : this.source(remoteRecord);
    if ((accepted && source === undefined) || (remoteRecord !== undefined && source === undefined)) {
      throw new Error('书源保存回执无效');
    }
    return { accepted: accepted, source: source };
  }

  /** 解析书签和批注列表，损坏项逐条隔离并按标记ID去重。 */
  markerList(value: Object | undefined, keyMap: Map<string, string>): ReaderMarkerSyncItem[] {
    if (!Array.isArray(value)) return [];
    const selected = new Map<string, ReaderMarkerSyncItem>();
    value.slice(0, ReaderSyncResponseNormalizer.MAX_SCAN_ITEMS).forEach((item: Object) => {
      const record = this.record(item);
      if (record === undefined) return;
      const remoteBookId = this.syncKey(record['bookKey'] ?? record['bookId']);
      const localBookId = remoteBookId === undefined ? undefined : keyMap.get(remoteBookId);
      if (localBookId === undefined) return;
      const marker = this.marker(record, localBookId);
      if (marker === undefined) return;
      const previous = selected.get(marker.id);
      if (previous === undefined || this.isNewer(marker.revision, marker.updatedAt,
        previous.revision, previous.updatedAt)) selected.set(marker.id, marker);
    });
    return Array.from(selected.values()).slice(0, ReaderSyncResponseNormalizer.MAX_RESULT_ITEMS);
  }

  /** 解析保存阅读标记的权威回执。 */
  markerSave(value: Object | undefined, localBookId: string): ReaderMarkerSyncResult {
    const record = this.requiredRecord(value, '阅读标记保存回执无效');
    if (record['position'] !== undefined) {
      const direct = this.marker(record, localBookId);
      if (direct === undefined) throw new Error('阅读标记保存回执无效');
      return { accepted: true, marker: direct };
    }
    const accepted = this.requiredBoolean(record['accepted'], '阅读标记保存回执无效');
    const remoteRecord = this.record(record['marker']);
    const marker = remoteRecord === undefined ? undefined : this.marker(remoteRecord, localBookId);
    if ((accepted && marker === undefined) || (remoteRecord !== undefined && marker === undefined)) {
      throw new Error('阅读标记保存回执无效');
    }
    return { accepted: accepted, marker: marker };
  }

  /** 解析云端阅读数据摘要。 */
  summary(value: Object | undefined): ReaderDataSummary {
    const record = this.requiredRecord(value, '阅读数据摘要无效');
    return {
      shelfRecords: this.integer(record['shelfRecords'], 10000000, '阅读数据摘要无效'),
      sourceRecords: this.integer(record['sourceRecords'], 10000000, '阅读数据摘要无效'),
      progressRecords: this.integer(record['progressRecords'], 10000000, '阅读数据摘要无效'),
      markerRecords: this.integer(record['markerRecords'], 10000000, '阅读数据摘要无效')
    };
  }

  /** 解析云端阅读数据删除数量。 */
  deletedRecords(value: Object | undefined): number {
    const record = this.requiredRecord(value, '阅读数据删除回执无效');
    return this.integer(record['deletedRecords'], 40000000, '阅读数据删除回执无效');
  }

  private progress(value: Record<string, Object>, bookId: string): ReadingProgress | undefined {
    const position = this.record(value['position']);
    const source = position ?? value;
    const chapterTitle = this.text(source['chapterTitle'], 500, false);
    const locator = this.optionalInteger(source['locator'], 1000000000);
    const percentage = this.optionalInteger(source['percentage'], 100);
    const updatedAt = this.optionalInteger(source['clientUpdatedAt']);
    const revision = this.optionalInteger(value['version'] ?? value['revision']);
    const deleted = value['deleted'];
    if (chapterTitle === undefined || locator === undefined || percentage === undefined || updatedAt === undefined ||
      revision === undefined || typeof deleted !== 'boolean') return undefined;
    return { bookId: bookId, chapterTitle: chapterTitle, locator: locator, percentage: percentage,
      updatedAt: updatedAt, revision: revision, deleted: deleted };
  }

  private source(value: Record<string, Object>): BookSourceSyncItem | undefined {
    const sourceUrl = this.text(value['sourceUrl'], 4096, false);
    const snapshotJson = this.text(value['snapshotJson'], 524288, false, true);
    const updatedAt = this.optionalInteger(value['clientUpdatedAt']);
    const revision = this.optionalInteger(value['revision']);
    const deleted = value['deleted'];
    if (sourceUrl === undefined || snapshotJson === undefined || updatedAt === undefined || revision === undefined ||
      typeof deleted !== 'boolean') return undefined;
    return { sourceUrl: sourceUrl, snapshotJson: snapshotJson, updatedAt: updatedAt,
      deleted: deleted, revision: revision };
  }

  private marker(value: Record<string, Object>, bookId: string): ReaderMarkerSyncItem | undefined {
    const position = this.record(value['position']);
    const source = position ?? value;
    const id = this.text(value['id'] ?? value['markerId'], 100, false);
    const kind = value['markerType'] ?? value['kind'];
    const chapterTitle = this.text(source['chapterTitle'], 500, false);
    const note = this.text(value['note'], 2000, true, true);
    const locator = this.optionalInteger(source['locator'], 1000000000);
    const createdAt = this.optionalInteger(source['createdAt']);
    const updatedAt = this.optionalInteger(source['clientUpdatedAt']);
    const revision = this.optionalInteger(value['version'] ?? value['revision']);
    const deleted = value['deleted'];
    if (id === undefined || !/^[A-Za-z0-9-]{1,100}$/.test(id) ||
      (kind !== 'BOOKMARK' && kind !== 'ANNOTATION') || chapterTitle === undefined || note === undefined ||
      locator === undefined || createdAt === undefined || updatedAt === undefined || revision === undefined ||
      typeof deleted !== 'boolean') return undefined;
    const normalizedKind: 'BOOKMARK' | 'ANNOTATION' = kind === 'ANNOTATION' ? 'ANNOTATION' : 'BOOKMARK';
    return { id: id, kind: normalizedKind, bookId: bookId, chapterTitle: chapterTitle, locator: locator, note: note,
      createdAt: createdAt, updatedAt: updatedAt, deleted: deleted, revision: revision };
  }

  private syncKey(value: Object | undefined): string | undefined {
    return typeof value === 'string' && /^sha256:[a-f0-9]{64}$/.test(value) ? value : undefined;
  }

  private record(value: Object | undefined): Record<string, Object> | undefined {
    return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value) ?
      value as Record<string, Object> : undefined;
  }

  private requiredRecord(value: Object | undefined, message: string): Record<string, Object> {
    const record = this.record(value);
    if (record === undefined) throw new Error(message);
    return record;
  }

  private requiredBoolean(value: Object | undefined, message: string): boolean {
    if (typeof value !== 'boolean') throw new Error(message);
    return value;
  }

  private text(value: Object | undefined, maximum: number, allowEmpty: boolean,
    allowLineBreaks: boolean = false): string | undefined {
    if (typeof value !== 'string' || value.length > maximum || (!allowEmpty && value.length === 0) ||
      (allowLineBreaks ? /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/ : /[\u0000-\u001F\u007F]/).test(value)) {
      return undefined;
    }
    return value;
  }

  private integer(value: Object | undefined, maximum: number, message: string): number {
    const result = this.optionalInteger(value, maximum);
    if (result === undefined) throw new Error(message);
    return result;
  }

  private optionalInteger(value: Object | undefined, maximum: number = Number.MAX_SAFE_INTEGER): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= maximum ? value : undefined;
  }

  private isNewer(revision: number, updatedAt: number, previousRevision: number, previousUpdatedAt: number): boolean {
    return revision > previousRevision || (revision === previousRevision && updatedAt > previousUpdatedAt);
  }
}
