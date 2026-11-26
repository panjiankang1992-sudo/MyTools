/** 图库排版所需的最小媒体信息。 */
export interface GalleryLayoutItem { itemId: string; directoryId: string; }

/** 保持列归属稳定的图库排版结果。 */
export interface GalleryPlacement { itemId: string; column: number; aspectRatio: number; }

/** 按原图比例和累计列高排版，追加或迟到的图片尺寸不打乱既有列归属。 */
export class MediaGalleryLayoutPolicy {
  private readonly ratios: Map<string, number> = new Map<string, number>();

  /** 记录缩略图真实宽高，极端长图保留完整内容并限制容器跨度。 */
  rememberSize(id: string, width: number, height: number): boolean {
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return false;
    const ratio = Math.max(0.25, Math.min(4, width / height));
    if (this.ratios.get(id) === ratio) return false;
    this.ratios.set(id, ratio);
    // 仅保存有界的尺寸元数据，文件缓存由原缩略图缓存管理。
    while (this.ratios.size > 2048) {
      const first = this.ratios.keys().next();
      if (first.done) break;
      this.ratios.delete(first.value);
    }
    return true;
  }

  /** 以视口为坐标系；完整可见时保持位置，其余卡片居中，超长图片靠顶部。 */
  returnScrollDelta(top: number, height: number, viewportHeight: number): number {
    const bottom = top + height;
    if (top >= -1 && bottom <= viewportHeight + 1) return 0;
    if (top <= 0 && bottom >= viewportHeight) return 0;
    return height >= viewportHeight ? top : top + (height - viewportHeight) / 2;
  }

  /** 每个日期组独立平衡列高，新项加入较短列，已有项不会跳到另一列。 */
  reconcile(items: GalleryLayoutItem[], previous: GalleryPlacement[]): GalleryPlacement[] {
    const retained = new Map<string, GalleryPlacement>();
    previous.forEach((value: GalleryPlacement) => retained.set(value.itemId, value));
    const heights = new Map<string, number[]>();
    return items.map((item: GalleryLayoutItem): GalleryPlacement => {
      const columns = heights.get(item.directoryId) ?? [0, 0];
      const old = retained.get(item.itemId);
      const column = old?.column ?? (columns[0] <= columns[1] ? 0 : 1);
      const ratio = this.ratios.get(item.itemId) ?? old?.aspectRatio ?? 1;
      columns[column] += 1 / ratio + 0.06;
      heights.set(item.directoryId, columns);
      return { itemId: item.itemId, column, aspectRatio: ratio };
    });
  }
}
