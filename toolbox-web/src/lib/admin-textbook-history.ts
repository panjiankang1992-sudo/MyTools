/**
 * 管理编辑器的撤销历史（设计文档 B03：重命名／重排／删除**后撤销**）。
 *
 * 采用**快照式**而不是逆操作式：
 * - 逆操作要求每个改动都写一份正确的反向操作，漏一个就会出现"撤销后状态更乱"；
 * - 快照式只需保证"改动前先存一份"，**正确性不依赖每个操作各自实现得对**。
 *
 * 代价是内存，因此限制栈深度。草稿本身有大小上限（整册约 800 条），
 * 50 步在可接受范围内。
 */

/** 撤销栈的最大深度。超出后丢弃最早的一份。 */
export const MAX_HISTORY_DEPTH = 50;

/** 一次可撤销的状态快照。 */
export interface EditorSnapshot<T> {
  /** 快照内容。 */
  state: T;
  /** 产生该快照的操作名，用于界面提示（如"撤销删除单元"）。 */
  label: string;
}

/**
 * 撤销栈。
 *
 * 用法：**任何会改变文档结构的操作之前**调用 `record(state, label)`。
 */
export class UndoStack<T> {
  private readonly entries: EditorSnapshot<T>[] = [];

  /**
   * 记录一份改动前的状态。
   *
   * @param state 改动前的状态
   * @param label 即将执行的操作名
   */
  record(state: T, label: string): void {
    this.entries.push({ state, label });
    if (this.entries.length > MAX_HISTORY_DEPTH) {
      this.entries.shift();
    }
  }

  /**
   * 弹出最近一份快照。
   *
   * @returns 快照；栈空时为 null
   */
  undo(): EditorSnapshot<T> | null {
    return this.entries.pop() ?? null;
  }

  /** 当前可撤销的步数。 */
  get depth(): number {
    return this.entries.length;
  }

  /** 清空历史（例如载入另一册教材时）。 */
  clear(): void {
    this.entries.length = 0;
  }
}
