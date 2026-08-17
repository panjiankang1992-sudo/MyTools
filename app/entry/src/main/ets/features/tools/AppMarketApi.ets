import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { ApiEnvelope } from '../../shared/network/AuthorizedApiModels';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AppMarketDetail, AppMarketPage } from './AppMarketModels';
import { AppMarketResponseNormalizer } from './AppMarketResponseNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

export { AppMarketDetail, AppMarketItem, AppMarketPage } from './AppMarketModels';

/**
 * MyTools 应用市场只读客户端。
 */
export class AppMarketApi {
  private readonly client: AuthorizedApiClient;
  private readonly normalizer: AppMarketResponseNormalizer = new AppMarketResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /**
   * 按名称读取已认证应用市场分页列表。
   */
  async list(page: number, pageSize: number, name: string,
    cancellation?: DownloadCancellationToken): Promise<AppMarketPage> {
    if (!Number.isSafeInteger(page) || page < 1 || page > 1000000 ||
      !Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 100 || name.length > 200 ||
      /[\u0000-\u001F\u007F]/.test(name)) throw new Error('应用市场查询参数无效');
    let path = `/api/market/apps?page=${page}&pageSize=${pageSize}`;
    if (name.trim().length > 0) path += `&name=${encodeURIComponent(name.trim())}`;
    const envelope = await this.client.get(path, cancellation);
    return this.normalizer.page(envelope.data, page, pageSize);
  }

  /**
   * 读取应用市场条目的只读详情，不执行安装命令或下载地址。
   */
  async detail(id: string, cancellation?: DownloadCancellationToken): Promise<AppMarketDetail> {
    if (id.length < 1 || id.length > 100 || /[\u0000-\u001F\u007F]/.test(id)) {
      throw new Error('应用标识无效');
    }
    const envelope: ApiEnvelope = await this.client.get(`/api/market/apps/${encodeURIComponent(id)}`, cancellation);
    return this.normalizer.detail(envelope.data);
  }
}
