import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

/** 为声明式书源提供保守且可取消的幂等请求重试决策。 */
export class SourceHttpRetryPolicy {
  private static readonly RETRY_DELAY_MS: number = 200;

  /** 仅允许GET在第一次瞬时失败后再尝试一次。 */
  shouldRetry(isGet: boolean, attempt: number, status?: number): boolean {
    if (!isGet || attempt !== 0) return false;
    if (status === undefined) return true;
    return status === 429 || status === 502 || status === 503 || status === 504;
  }

  /** 在重试退避期间响应页面取消，不留下后台计时器。 */
  wait(cancellation?: DownloadCancellationToken): Promise<void> {
    if (cancellation?.isCancelled()) return Promise.reject(new Error('书源请求重试已取消'));
    return new Promise<void>((resolve: () => void, reject: (reason: Error) => void) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        cancellation?.unbind(cancelHandler);
        resolve();
      }, SourceHttpRetryPolicy.RETRY_DELAY_MS);
      const cancelHandler = (): void => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        cancellation?.unbind(cancelHandler);
        reject(new Error('书源请求重试已取消'));
      };
      cancellation?.bind(cancelHandler);
    });
  }
}
