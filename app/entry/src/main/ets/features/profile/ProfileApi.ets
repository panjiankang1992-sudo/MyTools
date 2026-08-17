import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { ProfileIdentity } from './ProfileModels';
import { ProfileResponseNormalizer } from './ProfileResponseNormalizer';

/** 读取当前已登录用户的资料身份字段。 */
export class ProfileApi {
  private readonly client: AuthorizedApiClient;
  private readonly normalizer: ProfileResponseNormalizer = new ProfileResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager, allowPublicFallback: boolean = true) {
    this.client = new AuthorizedApiClient(baseUrl, authManager, allowPublicFallback);
  }

  /** 获取当前用户昵称和头像。 */
  async current(cancellation?: DownloadCancellationToken): Promise<ProfileIdentity> {
    const envelope = await this.client.get('/api/user/info', cancellation);
    return this.normalizer.normalize(envelope.data);
  }
}
