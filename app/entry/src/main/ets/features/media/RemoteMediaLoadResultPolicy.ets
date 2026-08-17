import { RemoteMediaItem } from './RemoteMediaModels';

/**
 * 远程媒体分页结果在页面落库前的筛选一致性策略。
 */
export interface RemoteMediaLoadResult {
  directoryItems: RemoteMediaItem[];
  mediaItems: RemoteMediaItem[];
  total: number;
  hasMore: boolean;
  inconsistent: boolean;
}

/**
 * 防止较早的媒体类型请求覆盖用户刚选择的新筛选条件。
 */
export class RemoteMediaLoadResultPolicy {
  /**
   * 按请求时固定的媒体类型收敛分页结果，并在响应类型不一致时丢弃错误统计。
   */
  resolve(items: RemoteMediaItem[], serverTotal: number, pageSize: number,
    requestedKind: string): RemoteMediaLoadResult {
    const normalizedTotal = Number.isFinite(serverTotal) && serverTotal > 0 ? Math.floor(serverTotal) : 0;
    const normalizedPageSize = Number.isFinite(pageSize) && pageSize > 0 ? Math.floor(pageSize) : 40;
    const specificKind = requestedKind === 'image' || requestedKind === 'video' || requestedKind === 'audio';
    const mismatched = specificKind && items.some((item: RemoteMediaItem) =>
      item.kind !== 'directory' && item.kind !== 'other' && item.kind !== requestedKind);
    // 指定类型时只接收同类型结果，避免旧响应污染列表和加载统计。
    const directoryItems = items.filter((item: RemoteMediaItem) => item.kind === 'directory' ||
      (!specificKind || item.kind === requestedKind));
    const mediaItems = directoryItems.filter((item: RemoteMediaItem) => item.kind !== 'directory' &&
      item.kind !== 'other');
    const total = mismatched ? mediaItems.length : normalizedTotal;
    const hasMore = !mismatched &&
      (directoryItems.length < total || (total === 0 && directoryItems.length >= normalizedPageSize));
    return { directoryItems, mediaItems, total, hasMore, inconsistent: mismatched };
  }
}
