/** 为单个流式下载提供只增不减的取消信号。 */
export class DownloadCancellationToken {
  private cancelled: boolean = false;
  private readonly handlers: Set<() => void> = new Set<() => void>();

  /** 请求取消当前下载。 */
  cancel(): void {
    if (this.cancelled) return;
    this.cancelled = true;
    Array.from(this.handlers.values()).forEach((handler: () => void) => handler());
    this.handlers.clear();
  }

  /** 返回下载是否已被用户取消。 */
  isCancelled(): boolean {
    return this.cancelled;
  }

  /** 绑定当前网络请求的立即中止动作。 */
  bind(handler: () => void): void {
    if (this.cancelled) {
      handler();
      return;
    }
    this.handlers.add(handler);
  }

  /** 解除已经结束的网络请求，避免取消影响后续对象。 */
  unbind(handler?: () => void): void {
    if (handler === undefined) this.handlers.clear();
    else this.handlers.delete(handler);
  }
}
