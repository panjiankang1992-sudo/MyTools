import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DigestTool } from '../tools/DigestTool';
import { ReadingProgress } from './ReaderModels';
import { ProgressSyncResult } from './ReaderSyncModels';
import { ReaderSyncResponseNormalizer } from './ReaderSyncResponseNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

interface SaveProgressRequest {
  bookKey: string;
  chapterIndex: number;
  chapterUrl: string;
  position: ProgressPosition;
  deleted: boolean;
  expectedVersion?: number;
}

interface ProgressPosition {
  chapterTitle: string;
  locator: number;
  percentage: number;
  clientUpdatedAt: number;
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
    const envelope = await this.client.get('/api/app/v1/reader/progress?includeDeleted=true', cancellation);
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

  /** 按本地图书稳定标识读取服务端单条进度，支持远程目录首次打开恢复。 */
  async find(bookId: string, cancellation?: DownloadCancellationToken): Promise<ReadingProgress | undefined> {
    const key = await this.syncKey(bookId);
    this.throwIfCancelled(cancellation);
    const envelope = await this.client.get('/api/app/v1/reader/progress?includeDeleted=true', cancellation);
    this.throwIfCancelled(cancellation);
    return this.normalizer.progressByKey(envelope.data, key, bookId);
  }

  /**
   * 保存单本书进度，并返回服务端权威版本。
   */
  async save(progress: ReadingProgress): Promise<ProgressSyncResult> {
    const position: ProgressPosition = { chapterTitle: progress.chapterTitle,
      locator: Math.max(0, Math.min(1000000000, Math.round(progress.locator))),
      percentage: Math.max(0, Math.min(100, Math.round(progress.percentage))),
      clientUpdatedAt: progress.updatedAt };
    const request: SaveProgressRequest = {
      bookKey: await this.syncKey(progress.bookId),
      chapterIndex: 0,
      chapterUrl: '',
      position,
      deleted: progress.deleted === true
    };
    if ((progress.revision ?? 0) > 0) request.expectedVersion = progress.revision;
    const envelope = await this.client.postJson('/api/app/v1/reader/progress', request);
    return this.normalizer.progressSave(envelope.data, progress.bookId);
  }

  private async syncKey(bookId: string): Promise<string> {
    return `sha256:${await this.digestTool.digestText(bookId, 'SHA256')}`;
  }

  private throwIfCancelled(cancellation?: DownloadCancellationToken): void {
    if (cancellation?.isCancelled() === true) throw new Error('阅读进度拉取已取消');
  }
}
