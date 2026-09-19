import { RemoteMediaItem } from '../media/RemoteMediaModels';

export interface ReaderDirectorySnapshot {
  items: RemoteMediaItem[];
  page: number;
  total: number;
  loadedCount: number;
  hasMore: boolean;
  savedAt: number;
}

/** 有界会话目录缓存；仅存列表元数据，不存凭证、下载票据或正文。 */
export class ReaderDirectoryCache {
  private entries: Map<string, ReaderDirectorySnapshot> = new Map();

  /** 完整查询维度共同决定缓存身份，禁止跨账号或筛选复用。 */
  key(owner: string, endpoint: string, source: string, path: string, query: string, filtered: boolean): string {
    return JSON.stringify([owner, endpoint, source, path, query.trim(), filtered]);
  }

  /** 超过十分钟的快照不再展示；一分钟内无需重新请求。 */
  get(key: string, now: number): ReaderDirectorySnapshot | undefined {
    const value = this.entries.get(key);
    if (value === undefined) return undefined;
    if (now < value.savedAt || now - value.savedAt > 600000) { this.entries.delete(key); return undefined; }
    this.entries.delete(key); this.entries.set(key, value);
    return this.copy(value);
  }

  /** 限制十二个目录和每项两百条；过大的分页列表不缓存，避免分页位置丢失。 */
  put(key: string, value: ReaderDirectorySnapshot): void {
    this.entries.delete(key);
    if (value.items.length > 200) return;
    this.entries.set(key, this.copy(value));
    if (this.entries.size > 12) this.entries.delete(Array.from(this.entries.keys())[0]);
  }

  /** 账号切换、退出和强制刷新时丢弃旧快照。 */
  clear(): void { this.entries.clear(); }

  private copy(value: ReaderDirectorySnapshot): ReaderDirectorySnapshot {
    return { items: value.items.slice(), page: value.page, total: value.total,
      loadedCount: value.loadedCount, hasMore: value.hasMore, savedAt: value.savedAt };
  }
}
