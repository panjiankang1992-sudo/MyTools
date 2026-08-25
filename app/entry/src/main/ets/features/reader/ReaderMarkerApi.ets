import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DigestTool } from '../tools/DigestTool';
import { ReaderMarkerSyncItem, ReaderMarkerSyncResult } from './ReaderSyncModels';
import { ReaderSyncResponseNormalizer } from './ReaderSyncResponseNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

export { ReaderMarkerSyncItem, ReaderMarkerSyncResult } from './ReaderSyncModels';

interface SaveMarkerRequest {
  markerId: string;
  markerType: string;
  bookKey: string;
  chapterIndex: number;
  position: MarkerPosition;
  note: string;
  deleted: boolean;
  expectedVersion?: number;
}

interface MarkerPosition {
  chapterTitle: string;
  locator: number;
  createdAt: number;
  clientUpdatedAt: number;
}

/**
 * 同步书签、批注及删除墓碑。
 */
export class ReaderMarkerApi {
  private readonly client: AuthorizedApiClient;
  private readonly digestTool: DigestTool = new DigestTool();
  private readonly normalizer: ReaderSyncResponseNormalizer = new ReaderSyncResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /**
   * 拉取服务端阅读标记，并只映射当前设备已知的图书。
   */
  async list(bookIds: string[], knownMarkers: ReaderMarkerSyncItem[],
    cancellation?: DownloadCancellationToken): Promise<ReaderMarkerSyncItem[]> {
    const envelope = await this.client.get('/api/app/v1/reader/markers?includeDeleted=true', cancellation);
    this.throwIfCancelled(cancellation);
    const keyMap = new Map<string, string>();
    for (const bookId of bookIds) {
      this.throwIfCancelled(cancellation);
      keyMap.set(await this.syncKey(bookId), bookId);
    }
    for (const marker of knownMarkers) {
      this.throwIfCancelled(cancellation);
      keyMap.set(await this.syncKey(marker.bookId), marker.bookId);
    }
    this.throwIfCancelled(cancellation);
    return this.normalizer.markerList(envelope.data, keyMap);
  }

  /**
   * 保存活动标记或删除墓碑。
   */
  async save(marker: ReaderMarkerSyncItem): Promise<ReaderMarkerSyncResult> {
    const position: MarkerPosition = { chapterTitle: marker.chapterTitle,
      locator: Math.max(0, Math.min(1000000000, Math.round(marker.locator))),
      createdAt: marker.createdAt, clientUpdatedAt: marker.updatedAt };
    const request: SaveMarkerRequest = {
      markerId: marker.id,
      markerType: marker.kind,
      bookKey: await this.syncKey(marker.bookId),
      chapterIndex: 0,
      position,
      note: marker.note.substring(0, 2000),
      deleted: marker.deleted
    };
    if (marker.revision > 0) request.expectedVersion = marker.revision;
    const envelope = await this.client.postJson('/api/app/v1/reader/markers', request);
    return this.normalizer.markerSave(envelope.data, marker.bookId);
  }

  private async syncKey(bookId: string): Promise<string> {
    return `sha256:${await this.digestTool.digestText(bookId, 'SHA256')}`;
  }

  private throwIfCancelled(cancellation?: DownloadCancellationToken): void {
    if (cancellation?.isCancelled() === true) throw new Error('阅读标记拉取已取消');
  }
}
