/** 文本阅读位置的版本化编码结果。 */
export interface DecodedTextLocator {
  versioned: boolean;
  fraction: number;
  legacyPixels: number;
}

/** 在同步协议上区分新版章节比例与旧版滚动像素。 */
export class ReaderTextLocatorPolicy {
  static readonly VERSION_BASE: number = 500000000;
  static readonly MAX_LOCATOR: number = 1000000000;

  /** 把章节内比例编码到带版本标记的高半区。 */
  encode(fraction: number): number {
    const normalized = Number.isFinite(fraction) ? Math.max(0, Math.min(1, fraction)) : 0;
    return ReaderTextLocatorPolicy.VERSION_BASE +
      Math.floor(normalized * (ReaderTextLocatorPolicy.MAX_LOCATOR - ReaderTextLocatorPolicy.VERSION_BASE));
  }

  /** 解码新版比例；低半区按旧版滚动像素保留。 */
  decode(locator: number, estimatedHeight: number): DecodedTextLocator {
    const normalized = Number.isSafeInteger(locator) ?
      Math.max(0, Math.min(ReaderTextLocatorPolicy.MAX_LOCATOR, locator)) : 0;
    if (normalized >= ReaderTextLocatorPolicy.VERSION_BASE) {
      return {
        versioned: true,
        fraction: Math.max(0, Math.min(1, (normalized - ReaderTextLocatorPolicy.VERSION_BASE) /
          (ReaderTextLocatorPolicy.MAX_LOCATOR - ReaderTextLocatorPolicy.VERSION_BASE))),
        legacyPixels: 0
      };
    }
    return {
      versioned: false,
      fraction: Math.max(0, Math.min(1, normalized / Math.max(1, estimatedHeight))),
      legacyPixels: normalized
    };
  }
}
