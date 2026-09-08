/** 不含播放票据和存储地址的有声书续播断点。 */
export interface AudiobookPlaybackProgress {
  owner: string;
  mediaItemId: string;
  mediaVersion: number;
  title: string;
  generationId: string;
  generationVersion: number;
  chapterIndex: number;
  positionMs: number;
  updatedAt: number;
}

/** 校验本地有声书续播断点，避免跨账户、跨文件版本或伪造票据恢复播放。 */
export class AudiobookPlaybackProgressPolicy {
  /** 将不可信持久化值投影为一条受限续播断点。 */
  normalize(value: Object | undefined): AudiobookPlaybackProgress | undefined {
    if (value === undefined || value === null || typeof value !== 'object' || Array.isArray(value)) return undefined;
    const item = value as Record<string, Object>;
    const owner = item['owner'];
    const mediaItemId = item['mediaItemId'];
    const title = item['title'];
    const generationId = item['generationId'];
    if (typeof owner !== 'string' || !/^account:v2:[a-f0-9]{64}$/.test(owner) ||
      typeof mediaItemId !== 'string' || !this.uuid(mediaItemId) || typeof generationId !== 'string' ||
      !this.uuid(generationId) || typeof title !== 'string' || title.length < 1 ||
      title.length > 300 || /[\u0000-\u001F\u007F]/.test(title)) return undefined;
    const mediaVersion = this.integer(item['mediaVersion'], 1, 1000000);
    const generationVersion = this.integer(item['generationVersion'], 1, 1000000);
    const chapterIndex = this.integer(item['chapterIndex'], 0, 20000);
    const positionMs = this.integer(item['positionMs'], 0, 31536000000);
    const updatedAt = this.integer(item['updatedAt'], 1, Number.MAX_SAFE_INTEGER);
    if (mediaVersion === undefined || generationVersion === undefined || chapterIndex === undefined ||
      positionMs === undefined || updatedAt === undefined) return undefined;
    return { owner: owner, mediaItemId: mediaItemId.toLowerCase(), mediaVersion: mediaVersion, title: title,
      generationId: generationId.toLowerCase(), generationVersion: generationVersion, chapterIndex: chapterIndex,
      positionMs: positionMs, updatedAt: updatedAt };
  }

  /** 判断断点是否属于当前登录账户和当前受管文本版本。 */
  matches(value: AudiobookPlaybackProgress | undefined, owner: string, mediaItemId: string,
    mediaVersion: number): boolean {
    return value !== undefined && value.owner === owner && value.mediaItemId === mediaItemId.toLowerCase() &&
      value.mediaVersion === mediaVersion;
  }

  /** 选择不靠近章节末尾的安全恢复时间；无效位置从本章开头开始。 */
  position(value: AudiobookPlaybackProgress | undefined, durationMs: number | undefined): number {
    if (value === undefined || value.positionMs < 1000) return 0;
    const duration = durationMs === undefined || !Number.isSafeInteger(durationMs) || durationMs <= 0 ? 0 : durationMs;
    return duration > 0 && value.positionMs >= duration - 10000 ? 0 : Math.min(value.positionMs, duration || value.positionMs);
  }

  private uuid(value: Object | undefined): boolean {
    return typeof value === 'string' && /^[0-9a-f-]{36}$/i.test(value);
  }

  private integer(value: Object | undefined, minimum: number, maximum: number): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum ?
      value : undefined;
  }
}
