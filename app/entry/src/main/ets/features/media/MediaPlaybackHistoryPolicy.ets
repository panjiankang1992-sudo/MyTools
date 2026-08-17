export interface MediaPlaybackProgress {
  owner: string;
  key: string;
  accountId: string;
  name: string;
  path: string;
  kind: string;
  contentType: string;
  size: number;
  lastModified: string;
  positionMs: number;
  durationMs: number;
  updatedAt: number;
}

/** 校验并限制远程音视频播放历史，禁止持久化数据构造任意播放路径。 */
export class MediaPlaybackHistoryPolicy {
  /** 将不可信持久化值投影为账户隔离的有界播放历史。 */
  normalize(value: Object | undefined): MediaPlaybackProgress[] {
    if (!Array.isArray(value) || value.length > 10000) return [];
    const valid: MediaPlaybackProgress[] = [];
    value.forEach((candidate: Object) => {
      if (candidate === null || typeof candidate !== 'object' || Array.isArray(candidate)) return;
      const item = candidate as Record<string, Object>;
      const projected = this.project(item);
      if (projected !== undefined) valid.push(projected);
    });
    const owners: string[] = [];
    const counts: Record<string, number> = {};
    const keys: string[] = [];
    const result: MediaPlaybackProgress[] = [];
    valid.sort((left: MediaPlaybackProgress, right: MediaPlaybackProgress) => right.updatedAt - left.updatedAt)
      .forEach((item: MediaPlaybackProgress) => {
        const identity = `${item.owner}|${item.key}`;
        if (keys.indexOf(identity) >= 0) return;
        const count = counts[item.owner] ?? 0;
        if (count >= 200 || (count === 0 && owners.length >= 10)) return;
        if (count === 0) owners.push(item.owner);
        counts[item.owner] = count + 1;
        keys.push(identity);
        result.push(item);
      });
    return result.slice(0, 2000);
  }

  private project(item: Record<string, Object>): MediaPlaybackProgress | undefined {
    const owner = item['owner'];
    const key = item['key'];
    const accountId = item['accountId'];
    const name = item['name'];
    const path = item['path'];
    const kind = item['kind'];
    const contentType = item['contentType'];
    const lastModified = item['lastModified'];
    if (typeof owner !== 'string' || !/^account:v2:[a-f0-9]{64}$/.test(owner) ||
      typeof accountId !== 'string' || !/^[1-9][0-9]{0,19}$/.test(accountId) ||
      typeof name !== 'string' || name.length < 1 || name.length > 512 || /[\u0000-\u001F\u007F]/.test(name) ||
      typeof path !== 'string' || !this.validPath(path) || typeof key !== 'string' ||
      key !== `${accountId.length}:${accountId}${path}` || (kind !== 'audio' && kind !== 'video') ||
      typeof contentType !== 'string' || contentType.length > 256 || /[\u0000-\u001F\u007F]/.test(contentType) ||
      typeof lastModified !== 'string' || lastModified.length > 128 || /[\u0000-\u001F\u007F]/.test(lastModified)) return undefined;
    const size = this.safeInteger(item['size'], 0, Number.MAX_SAFE_INTEGER);
    const positionMs = this.safeInteger(item['positionMs'], 0, 31536000000);
    const durationMs = this.safeInteger(item['durationMs'], 0, 31536000000);
    const updatedAt = this.safeInteger(item['updatedAt'], 1, Number.MAX_SAFE_INTEGER);
    if (size === undefined || positionMs === undefined || durationMs === undefined || updatedAt === undefined) {
      return undefined;
    }
    const safeKind: string = kind === 'audio' ? 'audio' : 'video';
    return { owner, key, accountId, name, path, kind: safeKind, contentType, size, lastModified, positionMs, durationMs,
      updatedAt };
  }

  private validPath(path: string): boolean {
    if (path.length < 2 || path.length > 4096 || !path.startsWith('/') || path.endsWith('/') ||
      /[\u0000-\u001F\u007F\\]/.test(path) || path.indexOf('//') >= 0) return false;
    return path.split('/').every((segment: string, index: number) => index === 0 ||
      (segment.length > 0 && segment !== '.' && segment !== '..'));
  }

  private safeInteger(value: Object | undefined, minimum: number, maximum: number): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum ?
      value : undefined;
  }
}
