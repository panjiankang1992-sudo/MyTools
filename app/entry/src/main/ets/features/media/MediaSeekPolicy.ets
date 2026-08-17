/** 统一约束页面、播放器和系统媒体会话的远程音视频定位。 */
export class MediaSeekPolicy {
  private static readonly MAX_POSITION_MS: number = 365 * 24 * 60 * 60 * 1000;
  private static readonly MAX_OFFSET_MS: number = 60 * 60 * 1000;

  /** 将绝对毫秒位置取整并按已知时长或全局上限夹紧。 */
  absolute(value: number, durationMs: number): number | undefined {
    if (!Number.isFinite(value)) return undefined;
    const duration = this.duration(durationMs);
    const maximum = duration > 0 ? duration : MediaSeekPolicy.MAX_POSITION_MS;
    return Math.max(0, Math.min(maximum, Math.round(value)));
  }

  /** 以当前毫秒位置为基准应用最多一小时的单次偏移。 */
  offset(currentMs: number, deltaMs: number, durationMs: number): number | undefined {
    const current = this.absolute(currentMs, durationMs);
    if (current === undefined || !Number.isFinite(deltaMs)) return undefined;
    const boundedDelta = Math.max(-MediaSeekPolicy.MAX_OFFSET_MS,
      Math.min(MediaSeekPolicy.MAX_OFFSET_MS, Math.round(deltaMs)));
    return this.absolute(current + boundedDelta, durationMs);
  }

  private duration(value: number): number {
    return Number.isSafeInteger(value) && value > 0 && value <= MediaSeekPolicy.MAX_POSITION_MS ? value : 0;
  }
}
