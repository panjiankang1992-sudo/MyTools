import { DownloadMessage } from './DownloadToolsApi';

/** 带有稳定渲染键的一条会话快照。 */
export class DownloadHistoryEntry {
  readonly row: DownloadMessage;
  readonly key: string;
  readonly snapshot: string;

  constructor(row: DownloadMessage, version: number, snapshot: string) {
    this.row = row;
    this.key = row.id + ':' + version;
    this.snapshot = snapshot;
  }
}

/** 列表仅为可见会话建 UI，并增量通知插入以保留滚动位置。 */
export class DownloadHistoryDataSource implements IDataSource {
  private entries: DownloadHistoryEntry[] = [];
  private listeners: DataChangeListener[] = [];
  private version: number = 0;

  /** 返回当前记录数。 */
  totalCount(): number {
    return this.entries.length;
  }

  /** 返回指定位置的记录。 */
  getData(index: number): DownloadHistoryEntry {
    return this.entries[index];
  }

  /** 定位记录在当前历史列表中的位置。 */
  indexOf(id: string): number {
    return this.entries.findIndex((entry: DownloadHistoryEntry) => entry.row.id === id);
  }

  /** 注册列表的数据监听。 */
  registerDataChangeListener(listener: DataChangeListener): void {
    if (!this.listeners.includes(listener)) this.listeners.push(listener);
  }

  /** 解除列表的数据监听。 */
  unregisterDataChangeListener(listener: DataChangeListener): void {
    this.listeners = this.listeners.filter((item: DataChangeListener) => item !== listener);
  }

  /** 按时间顺序应用历史集合，保留快照未变化的记录。 */
  sync(rows: DownloadMessage[]): void {
    const ids = new Set<string>(rows.map((row: DownloadMessage) => row.id));
    for (let index = this.entries.length - 1; index >= 0; index--) {
      if (!ids.has(this.entries[index].row.id)) {
        this.entries.splice(index, 1);
        this.listeners.forEach((listener: DataChangeListener) => listener.onDataDelete(index));
      }
    }
    for (let index = 0; index < rows.length; index++) {
      const row = rows[index];
      if (this.entries[index]?.row === row) continue;
      const snapshot = JSON.stringify(row);
      if (this.entries[index]?.row.id !== row.id) {
        // 取消未完成的本地上传会改变其排序位置，移动时仍保持记录唯一。
        const previous = this.entries.findIndex((entry: DownloadHistoryEntry, position: number) =>
          position > index && entry.row.id === row.id);
        let inserted = new DownloadHistoryEntry(row, ++this.version, snapshot);
        if (previous >= 0) {
          const moved = this.entries.splice(previous, 1)[0];
          this.listeners.forEach((listener: DataChangeListener) => listener.onDataDelete(previous));
          if (moved.snapshot === snapshot) inserted = moved;
        }
        this.entries.splice(index, 0, inserted);
        this.listeners.forEach((listener: DataChangeListener) => listener.onDataAdd(index));
      } else if (this.entries[index].snapshot !== snapshot) {
        this.entries[index] = new DownloadHistoryEntry(row, ++this.version, snapshot);
        this.listeners.forEach((listener: DataChangeListener) => listener.onDataChange(index));
      }
    }
  }
}
