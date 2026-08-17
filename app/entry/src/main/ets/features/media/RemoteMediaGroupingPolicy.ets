import { RemoteMediaItem } from './RemoteMediaModels';

export class RemoteMediaDirectoryGroup {
  readonly directoryName: string;
  readonly items: RemoteMediaItem[];

  constructor(directoryName: string, items: RemoteMediaItem[]) {
    this.directoryName = directoryName;
    this.items = items;
  }
}

/** 按Web前端一致的目录规则组织远程媒体卡片。 */
export class RemoteMediaGroupingPolicy {
  /** 按目录聚合媒体，日期目录优先并按新到旧排列。 */
  groups(items: RemoteMediaItem[]): RemoteMediaDirectoryGroup[] {
    const grouped = new Map<string, RemoteMediaItem[]>();
    items.forEach((item: RemoteMediaItem) => {
      if (item.kind === 'directory' || item.kind === 'other') return;
      // 媒体墙按日期聚合；目录只作为筛选维度，避免把远程原始路径直接暴露成大标题。
      const name = this.mediaGroupName(item);
      const values = grouped.get(name) ?? [];
      values.push(item);
      grouped.set(name, values);
    });
    return Array.from(grouped.entries()).map((entry: [string, RemoteMediaItem[]]) =>
      new RemoteMediaDirectoryGroup(entry[0], entry[1])).sort(
      (left: RemoteMediaDirectoryGroup, right: RemoteMediaDirectoryGroup) =>
      this.compareDirectoryNames(left.directoryName, right.directoryName));
  }

  /** 返回已排序、无重复的目录筛选项。 */
  directoryNames(items: RemoteMediaItem[]): string[] {
    const names = new Set<string>();
    items.forEach((item: RemoteMediaItem) => {
      if (item.kind === 'directory' || item.kind === 'other') return;
      names.add(this.normalizedDirectoryName(item.directoryName));
    });
    return Array.from(names).sort((left: string, right: string) => this.compareDirectoryNames(left, right));
  }

  /** 将日期目录转换为相册式分组标题，目录筛选仍保留原始值。 */
  displayName(directoryName: string, now: number = Date.now()): string {
    const segments = directoryName.split('/').filter((segment: string) => segment.length > 0);
    if (segments.length === 0) return '根目录';
    const value = segments[segments.length - 1];
    const prefix = segments.slice(0, -1).join('/');
    if (/^\d{8}$/.test(value)) {
      const year = Number(value.substring(0, 4));
      const month = Number(value.substring(4, 6));
      const day = Number(value.substring(6, 8));
      const date = new Date(year, month - 1, day);
      if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
        return directoryName;
      }
      const today = new Date(now);
      const todayStart = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
      const targetStart = new Date(year, month - 1, day).getTime();
      const dateLabel = `${month}月${day}日`;
      const relative = targetStart === todayStart ? `今天 · ${dateLabel}` :
        (targetStart === todayStart - 24 * 60 * 60 * 1000 ? `昨天 · ${dateLabel}` :
          `${year}年${dateLabel}`);
      return prefix.length > 0 ? `${prefix} · ${relative}` : relative;
    }
    if (/^\d{6}$/.test(value)) {
      const year = Number(value.substring(0, 4));
      const month = Number(value.substring(4, 6));
      if (month < 1 || month > 12) return directoryName;
      const monthLabel = `${year}年${month}月`;
      return prefix.length > 0 ? `${prefix} · ${monthLabel}` : monthLabel;
    }
    return directoryName;
  }

  private normalizedDirectoryName(value: string | undefined): string {
    if (value === undefined || value.length === 0 || value.length > 4096 ||
      /[\u0000-\u001F\u007F\\]/.test(value)) return '根目录';
    return value;
  }

  private mediaGroupName(item: RemoteMediaItem): string {
    const modified = item.lastModified.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (modified !== null) {
      const value = `${modified[1]}${modified[2]}${modified[3]}`;
      if (this.validDateDirectory(value)) return value;
    }
    const directoryName = this.normalizedDirectoryName(item.directoryName);
    const dateSegments = directoryName.split('/').filter((segment: string) => /^\d{8}$/.test(segment));
    if (dateSegments.length > 0) {
      const value = dateSegments[dateSegments.length - 1];
      if (this.validDateDirectory(value)) return value;
    }
    return directoryName;
  }

  private validDateDirectory(value: string): boolean {
    if (!/^\d{8}$/.test(value)) return false;
    const year = Number(value.substring(0, 4));
    const month = Number(value.substring(4, 6));
    const day = Number(value.substring(6, 8));
    const date = new Date(year, month - 1, day);
    return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day;
  }

  private compareDirectoryNames(left: string, right: string): number {
    const leftDate = this.dateDirectoryKey(left);
    const rightDate = this.dateDirectoryKey(right);
    if (leftDate.length > 0 && rightDate.length > 0) return rightDate.localeCompare(leftDate);
    if (leftDate.length > 0) return -1;
    if (rightDate.length > 0) return 1;
    return left.localeCompare(right);
  }

  private dateDirectoryKey(directoryName: string): string {
    return directoryName.split('/').filter((segment: string) => /^\d{6,8}$/.test(segment)).join('');
  }
}
