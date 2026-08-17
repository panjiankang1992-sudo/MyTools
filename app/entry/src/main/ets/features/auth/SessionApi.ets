import { AuthSessionManager } from './AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DeviceSession } from './SessionModels';
import { SessionResponseNormalizer } from './SessionResponseNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

/**
 * MyTools设备会话管理API。
 */
export class SessionApi {
  private readonly client: AuthorizedApiClient;
  private readonly normalizer: SessionResponseNormalizer = new SessionResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /**
   * 查询全部会话并标记当前设备。
   */
  async list(cancellation?: DownloadCancellationToken): Promise<DeviceSession[]> {
    const current = await this.client.get('/api/tokens/current', cancellation);
    const currentId = this.normalizer.currentId(current.data);
    const list = await this.client.get('/api/tokens', cancellation);
    return this.normalizer.sessions(list.data, currentId);
  }

  /**
   * 下线指定设备会话。
   */
  async revoke(sessionId: string): Promise<void> {
    const safeId = this.normalizer.revokeId(sessionId);
    await this.client.delete(`/api/tokens/${safeId}`);
  }

  /**
   * 下线当前设备之外的全部会话。
   */
  async revokeOthers(): Promise<void> {
    await this.client.delete('/api/tokens/others');
  }
}
