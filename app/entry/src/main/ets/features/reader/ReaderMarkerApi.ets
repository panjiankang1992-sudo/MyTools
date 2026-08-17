import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DigestTool } from '../tools/DigestTool';
import { ReaderMarkerSyncItem, ReaderMarkerSyncResult } from './ReaderSyncModels';
import { ReaderSyncResponseNormalizer } from './ReaderSyncResponseNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

export { ReaderMarkerSyncItem, ReaderMarkerSyncResult } from './ReaderSyncModels';

interface SaveMarkerRequest {
  markerId: string;
  kind: string;
  bookId: string;
  chapterTitle: string;
  locator: number;
  note: string;
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
  revision: number;
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
    const envelope = await this.client.get('/api/app/v1/reader/markers', cancellation);
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
    const request: SaveMarkerRequest = {
      markerId: marker.id,
      kind: marker.kind,
      bookId: await this.syncKey(marker.bookId),
      chapterTitle: marker.chapterTitle,
      locator: Math.max(0, Math.min(1000000000, Math.round(marker.locator))),
      note: marker.note.substring(0, 2000),
      createdAt: marker.createdAt,
      updatedAt: marker.updatedAt,
      deleted: marker.deleted,
      revision: marker.revision
    };
    const envelope = await this.client.putJson('/api/app/v1/reader/markers', request);
    return this.normalizer.markerSave(envelope.data, marker.bookId);
  }

  private async syncKey(bookId: string): Promise<string> {
    return `sha256:${await this.digestTool.digestText(bookId, 'SHA256')}`;
  }

  private throwIfCancelled(cancellation?: DownloadCancellationToken): void {
    if (cancellation?.isCancelled() === true) throw new Error('阅读标记拉取已取消');
  }
}
