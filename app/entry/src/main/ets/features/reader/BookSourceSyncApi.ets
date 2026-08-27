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
  deleted: boolean;
}

interface BatchSaveSourceRequest {
  sources: SaveSourceRequest[];
}

/**
 * 与MyTools后端同步不含凭据的声明式书源快照。
 */
export class BookSourceSyncApi {
  private static readonly MAX_BATCH_ITEMS: number = 50;
  private static readonly MAX_BATCH_ESTIMATED_BYTES: number = 256 * 1024;
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
    const request = await this.createSaveRequest(source);
    const envelope = await this.client.putJson('/api/app/v1/reader/sources', request);
    return this.normalizer.sourceSave(envelope.data);
  }

  /** 在运行时搜索前一次性同步全部本地书源，避免逐条网络往返。 */
  async saveBatch(sources: BookSourceSyncItem[]): Promise<number> {
    let accepted = 0;
    let requests: SaveSourceRequest[] = [];
    let estimatedBytes = 0;
    for (const source of sources) {
      if (source.sourceUrl.length > 2000 || source.snapshotJson.length > 524288) continue;
      const request = await this.createSaveRequest(source);
      const requestBytes = (request.snapshotJson.length + request.sourceUrl.length) * 3 + 256;
      if (requests.length > 0 && (requests.length >= BookSourceSyncApi.MAX_BATCH_ITEMS ||
        estimatedBytes + requestBytes > BookSourceSyncApi.MAX_BATCH_ESTIMATED_BYTES)) {
        accepted += await this.sendBatch(requests);
        requests = [];
        estimatedBytes = 0;
      }
      requests.push(request);
      estimatedBytes += requestBytes;
    }
    if (requests.length > 0) accepted += await this.sendBatch(requests);
    return accepted;
  }

  private async sendBatch(sources: SaveSourceRequest[]): Promise<number> {
    const request: BatchSaveSourceRequest = { sources: sources };
    await this.client.putJson('/api/app/v1/reader/sources/batch', request);
    return sources.length;
  }

  private async createSaveRequest(source: BookSourceSyncItem): Promise<SaveSourceRequest> {
    return {
      syncKey: `sha256:${await this.digestTool.digestText(source.sourceUrl, 'SHA256')}`,
      sourceUrl: source.sourceUrl,
      snapshotJson: source.snapshotJson,
      deleted: source.deleted
    };
  }
}
