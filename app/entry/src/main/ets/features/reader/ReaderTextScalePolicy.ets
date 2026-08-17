/** 规范系统字体倍率，避免异常配置导致正文不可读或分页规模失控。 */
export class ReaderTextScalePolicy {
  /** 将系统字体倍率限制在阅读器支持的0.8至3.2倍范围内。 */
  normalize(value: number | undefined): number {
    if (value === undefined || !Number.isFinite(value)) return 1;
    return Math.max(0.8, Math.min(3.2, value));
  }

  /** 判断倍率变化是否足以触发正文重排。 */
  changed(previous: number, next: number): boolean {
    return Math.abs(previous - next) >= 0.01;
  }
}
