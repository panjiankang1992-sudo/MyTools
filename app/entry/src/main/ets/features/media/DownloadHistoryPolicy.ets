export interface DownloadHistoryItem {
  owner: string;
  id: string;
  name: string;
  status: string;
  receivedBytes: number;
  totalBytes: number;
  updatedAt: number;
}

/** 投影账户隔离的下载摘要，禁止损坏的持久化值直接进入界面。 */
export class DownloadHistoryPolicy {
  /** 规范下载摘要并限制为10个账户、每个账户50项。 */
  normalize(value: Object | undefined, restore: boolean = false): DownloadHistoryItem[] {
    if (!Array.isArray(value) || value.length > 10000) return [];
    const candidates: DownloadHistoryItem[] = [];
    value.forEach((candidate: Object) => {
      const item = this.project(candidate, restore);
      if (item !== undefined) candidates.push(item);
    });
    candidates.sort((left: DownloadHistoryItem, right: DownloadHistoryItem) => right.updatedAt - left.updatedAt);
    const owners: string[] = [];
    const counts: Record<string, number> = {};
    const identities: string[] = [];
    const result: DownloadHistoryItem[] = [];
    candidates.forEach((item: DownloadHistoryItem) => {
      const identity = `${item.owner.length}:${item.owner}${item.id}`;
      if (identities.indexOf(identity) >= 0) return;
      const count = counts[item.owner] ?? 0;
      if (count >= 50 || (count === 0 && owners.length >= 10)) return;
      if (count === 0) owners.push(item.owner);
      counts[item.owner] = count + 1;
      identities.push(identity);
      result.push(item);
    });
    return result.slice(0, 500);
  }

  private project(candidate: Object, restore: boolean): DownloadHistoryItem | undefined {
    if (candidate === null || typeof candidate !== 'object' || Array.isArray(candidate)) return undefined;
    const item = candidate as Record<string, Object>;
    const owner = item['owner'];
    const id = item['id'];
    const name = item['name'];
    const status = item['status'];
    if (typeof owner !== 'string' || !/^account:v2:[a-f0-9]{64}$/.test(owner) ||
      typeof id !== 'string' || id.length < 1 || id.length > 128 || /[\u0000-\u001F\u007F]/.test(id) ||
      typeof name !== 'string' || name.length < 1 || name.length > 1024 || /[\u0000-\u001F\u007F]/.test(name) ||
      (status !== 'running' && status !== 'completed' && status !== 'interrupted' && status !== 'cancelled' &&
        status !== 'failed')) {
      return undefined;
    }
    const receivedBytes = this.safeInteger(item['receivedBytes'], 0, Number.MAX_SAFE_INTEGER);
    const totalBytes = this.safeInteger(item['totalBytes'], 0, Number.MAX_SAFE_INTEGER);
    const updatedAt = this.safeInteger(item['updatedAt'], 1, Number.MAX_SAFE_INTEGER);
    if (receivedBytes === undefined || totalBytes === undefined || updatedAt === undefined ||
      (totalBytes > 0 && receivedBytes > totalBytes)) return undefined;
    const safeStatus: string = status === 'running' ? 'running' : (status === 'completed' ? 'completed' :
      (status === 'interrupted' ? 'interrupted' : (status === 'cancelled' ? 'cancelled' : 'failed')));
    return { owner, id, name, status: restore && safeStatus === 'running' ? 'interrupted' : safeStatus,
      receivedBytes, totalBytes, updatedAt };
  }

  private safeInteger(value: Object | undefined, minimum: number, maximum: number): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum ?
      value : undefined;
  }
}
