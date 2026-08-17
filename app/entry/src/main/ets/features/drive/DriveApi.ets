import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { DriveAccount, DriveDirectory, DriveOpenDescriptor } from './DriveModels';
import { DriveResponseNormalizer } from './DriveResponseNormalizer';

/**
 * App 统一网盘 API。
 */
export class DriveApi {
  private readonly client: AuthorizedApiClient;
  private readonly baseUrl: string;
  private readonly normalizer: DriveResponseNormalizer = new DriveResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl);
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 查询当前用户的网盘。 */
  async listDrives(cancellation?: DownloadCancellationToken): Promise<DriveAccount[]> {
    const envelope = await this.client.get('/api/app/v1/drives', cancellation);
    return this.normalizer.normalizeAccounts(envelope.data);
  }

  /** 浏览目录或对服务器索引执行模糊搜索。 */
  async listItems(driveId: string, itemId: string, keyword: string, sort: string, direction: string,
    cancellation?: DownloadCancellationToken): Promise<DriveDirectory> {
    if (!/^[1-9]\d{0,18}$/.test(driveId) || (itemId.length > 0 && !/^[1-9]\d{0,18}$/.test(itemId))) {
      throw new Error('网盘标识无效');
    }
    const path = `/api/app/v1/drives/${encodeURIComponent(driveId)}/items` +
      `?itemId=${encodeURIComponent(itemId)}&keyword=${encodeURIComponent(keyword.trim().substring(0, 100))}` +
      `&sort=${encodeURIComponent(sort)}&direction=${encodeURIComponent(direction)}`;
    const envelope = await this.client.get(path, cancellation);
    return this.normalizer.normalizeDirectory(envelope.data);
  }

  /** 签发绑定当前用户和文件的短期只读票据。 */
  async openTicket(driveId: string, itemId: string,
    cancellation?: DownloadCancellationToken): Promise<DriveOpenDescriptor> {
    if (!/^[1-9]\d{0,18}$/.test(driveId) || !/^[1-9]\d{0,18}$/.test(itemId)) {
      throw new Error('网盘文件标识无效');
    }
    const envelope = await this.client.post(`/api/app/v1/drives/${encodeURIComponent(driveId)}` +
      `/items/${encodeURIComponent(itemId)}/open-ticket`, cancellation);
    return this.normalizer.normalizeOpenDescriptor(envelope.data, this.baseUrl);
  }
}
