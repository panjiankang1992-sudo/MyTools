import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DigestTool } from '../tools/DigestTool';
import { ReadingProgress } from './ReaderModels';
import { ProgressSyncResult } from './ReaderSyncModels';
import { ReaderSyncResponseNormalizer } from './ReaderSyncResponseNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

interface SaveProgressRequest {
  bookId: string;
  chapterTitle: string;
  locator: number;
  percentage: number;
  updatedAt: number;
  deleted: boolean;
  revision: number;
}

/**
 * 与 MyTools 后端同步阅读进度，传输不可逆图书标识以避免泄露本地路径。
 */
export class ReaderProgressApi {
  private readonly client: AuthorizedApiClient;
  private readonly digestTool: DigestTool = new DigestTool();
  private readonly normalizer: ReaderSyncResponseNormalizer = new ReaderSyncResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /**
   * 拉取已知书籍的服务端进度并恢复本地图书标识。
   */
  async list(localProgress: ReadingProgress[], localBookIds: string[],
    cancellation?: DownloadCancellationToken): Promise<ReadingProgress[]> {
    const envelope = await this.client.get('/api/app/v1/reader/progress', cancellation);
    this.throwIfCancelled(cancellation);
    const keyMap = new Map<string, string>();
    for (const bookId of localBookIds) {
      this.throwIfCancelled(cancellation);
      keyMap.set(await this.syncKey(bookId), bookId);
    }
    for (const local of localProgress) {
      this.throwIfCancelled(cancellation);
      keyMap.set(await this.syncKey(local.bookId), local.bookId);
    }
    this.throwIfCancelled(cancellation);
    return this.normalizer.progressList(envelope.data, keyMap);
  }

  /**
   * 保存单本书进度，并返回服务端权威版本。
   */
  async save(progress: ReadingProgress): Promise<ProgressSyncResult> {
    const request: SaveProgressRequest = {
      bookId: await this.syncKey(progress.bookId),
      chapterTitle: progress.chapterTitle,
      locator: Math.max(0, Math.min(1000000000, Math.round(progress.locator))),
      percentage: Math.max(0, Math.min(100, Math.round(progress.percentage))),
      updatedAt: progress.updatedAt,
      deleted: progress.deleted === true,
      revision: progress.revision ?? 0
    };
    const envelope = await this.client.putJson('/api/app/v1/reader/progress', request);
    return this.normalizer.progressSave(envelope.data, progress.bookId);
  }

  private async syncKey(bookId: string): Promise<string> {
    return `sha256:${await this.digestTool.digestText(bookId, 'SHA256')}`;
  }

  private throwIfCancelled(cancellation?: DownloadCancellationToken): void {
    if (cancellation?.isCancelled() === true) throw new Error('阅读进度拉取已取消');
  }
}
