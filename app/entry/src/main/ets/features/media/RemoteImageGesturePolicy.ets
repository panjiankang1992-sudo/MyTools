export interface ImageTranslation {
  x: number;
  y: number;
}

/**
 * 为远程图片查看器提供确定且有界的缩放、平移和滑动判定。
 */
export class RemoteImageGesturePolicy {
  /**
   * 把任意切图偏移归一化为单步方向，避免重复手势造成大跨度跳转。
   */
  normalizeNavigationDirection(value: number): number {
    if (!Number.isFinite(value) || value === 0) return 0;
    return value > 0 ? 1 : -1;
  }

  /**
   * 在有界图片序列中计算循环相邻位置，非法索引返回-1。
   */
  nextIndex(itemCount: number, currentIndex: number, direction: number): number {
    if (!Number.isInteger(itemCount) || itemCount < 2 || itemCount > 10000 ||
      !Number.isInteger(currentIndex) || currentIndex < 0 || currentIndex >= itemCount) return -1;
    const normalized = this.normalizeNavigationDirection(direction);
    if (normalized === 0) return -1;
    return (currentIndex + normalized + itemCount) % itemCount;
  }

  /**
   * 把缩放比例限制在1至4倍，并拒绝非有限输入。
   */
  clampScale(value: number): number {
    if (!Number.isFinite(value)) return 1;
    return Math.max(1, Math.min(4, value));
  }

  /**
   * 根据当前缩放比例限制平移，避免图片被无限拖离可视区域。
   */
  clampTranslation(x: number, y: number, scale: number): ImageTranslation {
    const safeScale = this.clampScale(scale);
    if (safeScale <= 1) return { x: 0, y: 0 };
    const horizontalLimit = Math.min(1200, (safeScale - 1) * 360);
    const verticalLimit = Math.min(1600, (safeScale - 1) * 520);
    return {
      x: this.clampFinite(x, -horizontalLimit, horizontalLimit),
      y: this.clampFinite(y, -verticalLimit, verticalLimit)
    };
  }

  /**
   * 仅在原始比例下把足够明显的水平手势转换为前后切图方向。
   */
  swipeDirection(offsetX: number, offsetY: number, scale: number): number {
    if (this.clampScale(scale) > 1 || !Number.isFinite(offsetX) || !Number.isFinite(offsetY)) return 0;
    if (Math.abs(offsetX) < 72 || Math.abs(offsetX) <= Math.abs(offsetY) * 1.25) return 0;
    return offsetX < 0 ? 1 : -1;
  }

  /**
   * 仅在原始比例下把足够明显的纵向手势转换为混合媒体切换方向。
   */
  verticalSwipeDirection(offsetX: number, offsetY: number, scale: number): number {
    if (this.clampScale(scale) > 1 || !Number.isFinite(offsetX) || !Number.isFinite(offsetY)) return 0;
    if (Math.abs(offsetY) < 80 || Math.abs(offsetY) <= Math.abs(offsetX) * 1.2) return 0;
    return offsetY < 0 ? 1 : -1;
  }

  /** 根据拖动比例或同方向甩动速度决定翻页，短距离抖动和斜向手势不翻页。 */
  pageSwipeDirection(offsetX: number, offsetY: number, velocityY: number, scale: number, height: number): number {
    if (this.clampScale(scale) > 1 || !Number.isFinite(offsetX) || !Number.isFinite(offsetY) ||
      !Number.isFinite(height) || height <= 0 || Math.abs(offsetY) <= Math.abs(offsetX) * 1.2) return 0;
    const threshold = Math.max(72, Math.min(160, height * 0.18));
    const flick = Number.isFinite(velocityY) && Math.abs(velocityY) >= 700 &&
      velocityY * offsetY > 0 && Math.abs(offsetY) >= 28;
    if (Math.abs(offsetY) < threshold && !flick) return 0;
    return offsetY < 0 ? 1 : -1;
  }

  /** 有相邻页时跟随手指一比一移动，序列边界提供有界阻尼。 */
  pageDragOffset(offset: number, height: number, hasNeighbor: boolean): number {
    if (!Number.isFinite(offset) || !Number.isFinite(height) || height <= 0) return 0;
    const bounded = Math.max(-height, Math.min(height, offset));
    return hasNeighbor ? bounded : bounded * 0.2;
  }

  private clampFinite(value: number, minimum: number, maximum: number): number {
    if (!Number.isFinite(value)) return 0;
    return Math.max(minimum, Math.min(maximum, value));
  }
}
