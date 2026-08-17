import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

/** 表示一个只能由持有者释放的书源网络请求租约。 */
export interface SourceHttpRequestLease {
  key: string;
  revision: number;
}

/**
 * 限制同一书源的网络并发，避免搜索、发现和阅读请求同时压垮来源站点。
 */
export class SourceHttpRequestCoordinator {
  private readonly active: Map<string, number> = new Map<string, number>();
  private revision: number = 0;

  /** 可取消地等待并取得指定书源的唯一请求租约。 */
  async acquire(sourceUrl: string, cancellation?: DownloadCancellationToken): Promise<SourceHttpRequestLease> {
    const key = sourceUrl.trim();
    if (key.length === 0) throw new Error('书源请求标识为空');
    while (this.active.has(key)) {
      await this.wait(cancellation);
    }
    if (cancellation?.isCancelled()) throw new Error('书源请求等待已取消');
    const revision = ++this.revision;
    this.active.set(key, revision);
    return { key: key, revision: revision };
  }

  /** 释放仍由调用者持有的租约；陈旧释放不能误删后续请求。 */
  release(lease: SourceHttpRequestLease): void {
    if (this.active.get(lease.key) === lease.revision) this.active.delete(lease.key);
  }

  private wait(cancellation?: DownloadCancellationToken): Promise<void> {
    if (cancellation?.isCancelled()) return Promise.reject(new Error('书源请求等待已取消'));
    return new Promise<void>((resolve: () => void, reject: (reason: Error) => void) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        cancellation?.unbind(cancelHandler);
        resolve();
      }, 25);
      const cancelHandler = (): void => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        cancellation?.unbind(cancelHandler);
        reject(new Error('书源请求等待已取消'));
      };
      cancellation?.bind(cancelHandler);
    });
  }
}

/** 搜索、发现和阅读引擎共用同一书源并发域。 */
export const sourceHttpRequestCoordinator: SourceHttpRequestCoordinator = new SourceHttpRequestCoordinator();
