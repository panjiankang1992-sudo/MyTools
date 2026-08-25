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
    const envelope = await this.client.get('/api/app/v1/drive/accounts', cancellation);
    return this.normalizer.normalizeAccounts(envelope.data);
  }

  /** 浏览目录或对服务器索引执行模糊搜索。 */
  async listItems(driveId: string, itemId: string, keyword: string, sort: string, direction: string,
    cancellation?: DownloadCancellationToken): Promise<DriveDirectory> {
    if (!this.uuid(driveId) || itemId.length > 2048 || /[\u0000-\u001F\u007F\\]/.test(itemId)) {
      throw new Error('网盘标识无效');
    }
    const path = `/api/app/v1/drive/accounts/${encodeURIComponent(driveId)}/items` +
      `?parentPath=${encodeURIComponent(itemId)}`;
    const envelope = await this.client.get(path, cancellation);
    return this.normalizer.normalizeIndexedDirectory(envelope.data, driveId, itemId, keyword, sort, direction);
  }

  /** 签发绑定当前用户和文件的短期只读票据。 */
  async openTicket(driveId: string, itemId: string,
    cancellation?: DownloadCancellationToken): Promise<DriveOpenDescriptor> {
    if (!this.uuid(driveId) || itemId.length === 0) {
      throw new Error('网盘文件标识无效');
    }
    const request: Record<string, Object> = {};
    request['path'] = itemId;
    const envelope = await this.client.postJson(
      `/api/app/v1/drive/accounts/${encodeURIComponent(driveId)}/open-ticket`, request, cancellation);
    return this.normalizer.normalizeOpenDescriptor(envelope.data, this.baseUrl);
  }

  private uuid(value: string): boolean {
    return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/.test(value);
  }
}
