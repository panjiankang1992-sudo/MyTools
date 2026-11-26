/** 根据可见区域、向下滚动速度和近期请求耗时估算下一批的预取距离。 */
export class MediaCatalogPrefetchPolicy {
  private viewportHeight: number = 0;
  private contentHeight: number = 0;
  private measuredItems: number = 0;
  private requestDurationMs: number = 600;
  private lastOffset: number = 0;
  private lastScrollAt: number = 0;
  private forwardSpeed: number = 0;

  /** 更新滚动容器高度，单位为逻辑像素。 */
  updateViewport(height: number): void {
    this.viewportHeight = Number.isFinite(height) ? Math.max(0, height) : 0;
  }

  /** 更新已排版内容高度，避免用资源数量猜测不同卡片的实际高度。 */
  updateContent(height: number, itemCount: number = 0): void {
    this.contentHeight = Number.isFinite(height) ? Math.max(0, height) : 0;
    this.measuredItems = Number.isSafeInteger(itemCount) ? Math.max(0, itemCount) : 0;
  }

  /** 切换查询或目录时清空速度样本，保留容器测量值和近期网络耗时。 */
  resetScroll(): void {
    this.lastScrollAt = 0;
    this.lastOffset = 0;
    this.forwardSpeed = 0;
  }

  /** 对向下滚动速度做平滑处理，向上滚动时立即取消速度带来的额外预取。 */
  observeScroll(offset: number, now: number): void {
    if (!Number.isFinite(offset) || !Number.isFinite(now)) return;
    const current = Math.max(0, offset);
    const elapsed = now - this.lastScrollAt;
    const distance = current - this.lastOffset;
    if (this.lastScrollAt > 0 && elapsed > 0 && elapsed <= 500 && distance > 0) {
      const measured = Math.min(4, distance / Math.max(16, elapsed));
      this.forwardSpeed = this.forwardSpeed * 0.4 + measured * 0.6;
    } else if (distance < 0 || elapsed > 500) {
      this.forwardSpeed = 0;
    }
    this.lastOffset = current;
    this.lastScrollAt = now;
  }

  /** 平滑记录成功请求耗时，异常长耗时不无限扩大预取范围。 */
  recordRequest(durationMs: number): void {
    if (!Number.isFinite(durationMs) || durationMs < 0) return;
    const measured = Math.min(5000, Math.max(100, durationMs));
    this.requestDurationMs = this.requestDurationMs * 0.65 + measured * 0.35;
  }

  /** 首批展示后至少准备二十四项，再按速度和请求耗时保留前方三至六屏。 */
  shouldPrefetch(offset: number, now: number, loadedItems: number = 0): boolean {
    if (!Number.isFinite(offset) || !Number.isFinite(now) || !Number.isSafeInteger(loadedItems) ||
      loadedItems < 0) return false;
    // 初始预取不依赖滚动事件或尚未完成的尺寸测量，首批内容仍立即展示。
    if (loadedItems > 0 && loadedItems < 24) return true;
    if (this.viewportHeight <= 0 || this.contentHeight <= 0) return false;
    // 停止滚动后不继续沿用快速滑动的速度，防止空闲时扩大缓冲区。
    const speed = now - this.lastScrollAt <= 500 ? this.forwardSpeed : 0;
    const reserve = Math.min(this.viewportHeight * 6,
      this.viewportHeight * 3 + speed * (this.requestDurationMs + 300));
    // 连续分页可能先于下一次排版完成；按已测项数估算增长，避免沿用旧高度下载整个图库。
    const estimatedHeight = this.measuredItems > 0 && loadedItems > this.measuredItems ?
      this.contentHeight * loadedItems / this.measuredItems : this.contentHeight;
    const remaining = estimatedHeight - Math.max(0, offset) - this.viewportHeight;
    return remaining < reserve;
  }

  /** 全屏时按当前位置保留后续二十四项，不使用已经卸载的列表高度。 */
  shouldPrefetchViewer(length: number, currentIndex: number): boolean {
    return Number.isSafeInteger(length) && Number.isSafeInteger(currentIndex) && currentIndex >= 0 &&
      currentIndex < length && length - currentIndex - 1 < 24;
  }
}
