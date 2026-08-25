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
    const list = await this.client.get('/api/app/v1/identity/sessions', cancellation);
    return this.normalizer.identitySessions(list.data);
  }

  /**
   * 下线指定设备会话。
   */
  async revoke(sessionId: string): Promise<void> {
    const safeId = this.normalizer.revokeId(sessionId);
    await this.client.delete(`/api/app/v1/identity/sessions/${safeId}`);
  }

  /**
   * 下线当前设备之外的全部会话。
   */
  async revokeOthers(): Promise<void> {
    const sessions = await this.list();
    for (const session of sessions) {
      if (!session.current) await this.revoke(session.id);
    }
  }
}
