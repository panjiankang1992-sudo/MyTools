import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DigestTool } from '../tools/DigestTool';
import { ShelfSyncResponseNormalizer } from './ShelfSyncResponseNormalizer';
import { ShelfSyncItem, ShelfSyncResult } from './ShelfSyncModels';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

interface SaveShelfRequest {
  syncKey: string;
  bookId: string;
  name: string;
  author: string;
  origin: string;
  format: string;
  resourceUri: string;
  sourceId: string;
  remoteCoverUrl: string;
  updatedAt: number;
  deleted: boolean;
  revision: number;
}

/**
 * 同步书源与MyTools远程书架元数据，本地图书永不进入该接口。
 */
export class ShelfSyncApi {
  private readonly client: AuthorizedApiClient;
  private readonly digestTool: DigestTool = new DigestTool();
  private readonly normalizer: ShelfSyncResponseNormalizer = new ShelfSyncResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /**
   * 拉取用户书架及删除墓碑。
   */
  async list(cancellation?: DownloadCancellationToken): Promise<ShelfSyncItem[]> {
    const envelope = await this.client.get('/api/app/v1/reader/shelf', cancellation);
    return this.normalizer.list(envelope.data);
  }

  /**
   * 保存非本地图书或删除墓碑。
   */
  async save(book: ShelfSyncItem): Promise<ShelfSyncResult> {
    const safeBook = this.normalizer.localItem(book);
    const request: SaveShelfRequest = {
      syncKey: `sha256:${await this.digestTool.digestText(safeBook.id, 'SHA256')}`,
      bookId: safeBook.id,
      name: safeBook.name,
      author: safeBook.author,
      origin: safeBook.origin,
      format: safeBook.format,
      resourceUri: safeBook.resourceUri,
      sourceId: safeBook.sourceId,
      remoteCoverUrl: safeBook.remoteCoverUrl,
      updatedAt: safeBook.updatedAt,
      deleted: safeBook.deleted,
      revision: safeBook.revision
    };
    const envelope = await this.client.putJson('/api/app/v1/reader/shelf', request);
    return this.normalizer.saveResult(envelope.data);
  }
}
