import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { ReaderDataSummary } from './ReaderSyncModels';
import { ReaderSyncResponseNormalizer } from './ReaderSyncResponseNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

export { ReaderDataSummary } from './ReaderSyncModels';

/**
 * 查询和删除当前账户的阅读同步数据。
 */
export class ReaderDataApi {
  private readonly client: AuthorizedApiClient;
  private readonly normalizer: ReaderSyncResponseNormalizer = new ReaderSyncResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 获取云端阅读数据摘要。 */
  async summary(cancellation?: DownloadCancellationToken): Promise<ReaderDataSummary> {
    const envelope = await this.client.get('/api/app/v1/reader/data/summary', cancellation);
    return this.normalizer.summary(envelope.data);
  }

  /** 删除当前账户的全部云端阅读同步数据。 */
  async deleteAll(): Promise<number> {
    const envelope = await this.client.delete('/api/app/v1/reader/data');
    return this.normalizer.deletedRecords(envelope.data);
  }
}
