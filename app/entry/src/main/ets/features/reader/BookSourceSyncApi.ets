import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DigestTool } from '../tools/DigestTool';
import { BookSourceSyncItem, BookSourceSyncResult } from './ReaderSyncModels';
import { ReaderSyncResponseNormalizer } from './ReaderSyncResponseNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

export { BookSourceSyncItem, BookSourceSyncResult } from './ReaderSyncModels';

interface SaveSourceRequest {
  syncKey: string;
  sourceUrl: string;
  snapshotJson: string;
  updatedAt: number;
  deleted: boolean;
  revision: number;
}

/**
 * 与MyTools后端同步不含凭据的声明式书源快照。
 */
export class BookSourceSyncApi {
  private readonly client: AuthorizedApiClient;
  private readonly digestTool: DigestTool = new DigestTool();
  private readonly normalizer: ReaderSyncResponseNormalizer = new ReaderSyncResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 拉取当前账户的书源快照及墓碑。 */
  async list(cancellation?: DownloadCancellationToken): Promise<BookSourceSyncItem[]> {
    const envelope = await this.client.get('/api/app/v1/reader/sources', cancellation);
    return this.normalizer.sourceList(envelope.data);
  }

  /** 保存书源快照或墓碑。 */
  async save(source: BookSourceSyncItem): Promise<BookSourceSyncResult> {
    const request: SaveSourceRequest = {
      syncKey: `sha256:${await this.digestTool.digestText(source.sourceUrl, 'SHA256')}`,
      sourceUrl: source.sourceUrl,
      snapshotJson: source.snapshotJson,
      updatedAt: source.updatedAt,
      deleted: source.deleted,
      revision: source.revision
    };
    const envelope = await this.client.putJson('/api/app/v1/reader/sources', request);
    return this.normalizer.sourceSave(envelope.data);
  }
}
