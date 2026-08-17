/** 阅读器分页使用的有界视口。 */
export interface ReaderViewport {
  width: number;
  height: number;
}

/** 归一化ArkUI区域尺寸，隔离异常布局值并抑制无意义微小变化。 */
export class ReaderViewportPolicy {
  /** 只接受数值vp，并夹紧到手机、平板及分屏的合理范围。 */
  normalize(width: Object | undefined, height: Object | undefined): ReaderViewport | undefined {
    if (typeof width !== 'number' || typeof height !== 'number' ||
      !Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return undefined;
    return {
      width: Math.max(240, Math.min(2400, width)),
      height: Math.max(320, Math.min(2400, height))
    };
  }

  /** 宽高变化不足2vp时不触发昂贵分页。 */
  changed(current: ReaderViewport, next: ReaderViewport): boolean {
    return Math.abs(current.width - next.width) >= 2 || Math.abs(current.height - next.height) >= 2;
  }
}
