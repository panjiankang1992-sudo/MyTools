/** 为远程音视频换票重试选择安全且可解释的恢复位置。 */
export class MediaPlaybackRecoveryPolicy {
  /** 实时位置优先，非法或过近结束位置回退到可用持久化断点或开头。 */
  position(liveMs: number, storedMs: number, durationMs: number): number {
    const duration = this.safe(durationMs, 0, 31_536_000_000);
    const live = this.safe(liveMs, 0, 31_536_000_000);
    const stored = this.safe(storedMs, 0, 31_536_000_000);
    let candidate = live >= 1000 ? live : stored;
    if (duration > 0) {
      candidate = Math.min(candidate, duration);
      if (candidate >= duration - 10000) return 0;
    }
    return candidate;
  }

  private safe(value: number, minimum: number, maximum: number): number {
    return Number.isSafeInteger(value) && value >= minimum && value <= maximum ? value : 0;
  }
}
