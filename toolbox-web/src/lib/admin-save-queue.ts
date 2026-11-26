/**
 * 管理草稿的**保存状态机**（F05）。
 *
 * 抽取成纯模块而不是写在页面里，是因为这里有几条**容易写错、且错了不会报错**的性质：
 *
 * 1. **冻结**：一次保存发起时，payload、revision、幂等键、编辑代次必须一起冻结。
 *    若保存过程中用户又改了内容，用的仍是**冻结的那一份**——
 *    否则"保存的是用户按下按钮那一刻的内容"这句话就不成立。
 * 2. **串行**：同一时刻只允许一次保存在途。并发保存会让先发的回包覆盖后发的。
 * 3. **旧响应不得覆盖新编辑**：回包成功时若编辑代次已经前进，
 *    只能更新"服务端已到哪个 revision"，**不能**把本地内容标成"已保存"。
 *    此前页面在保存期间继续输入，旧响应仍然显示"已保存"。
 * 4. **重试复用同一个键**：网络中断后用**同一个**幂等键与**同一份** payload 重发，
 *    服务端才会返回原结果而不是再写一次。
 */

/** 一次保存操作的冻结快照。 */
export interface FrozenSave<TPayload> {
  /** 冻结的内容。 */
  payload: TPayload;
  /** 这次保存基于的服务端修订号。 */
  revision: number;
  /** 幂等键；重试必须复用它。 */
  idempotencyKey: string;
  /** 发起时的编辑代次，用于判断回包是否已经过期。 */
  editSequence: number;
}

/** 保存结果。 */
export type SaveOutcome = 'saved' | 'stale' | 'conflict';

/** 生成幂等键；不依赖 `crypto.randomUUID`，便于在受限环境与测试里使用。 */
export function newSaveKey(seed: number): string {
  return `save-${seed}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 跟踪"哪些内容已经存到服务端"的纯状态机。
 *
 * 它**不负责发请求**——调用方把请求结果喂回来。这样并发与错序这些情形
 * 可以在没有网络的情况下被确定性地测出来。
 */
export class SaveState<TPayload> {
  private readonly keyFactory: () => string;

  /** 当前编辑代次；每次内容变化 +1。 */
  private editSequence = 0;
  /** 已经成功存到服务端的编辑代次。 */
  private savedSequence = 0;
  /** 服务端当前的修订号。 */
  private revision: number;
  /** 在途操作；同一时刻最多一个。 */
  private inFlight: FrozenSave<TPayload> | null = null;
  /** 失败后待重试的操作；重试必须原样重发。 */
  private pendingRetry: FrozenSave<TPayload> | null = null;

  /**
   * @param revision    初始服务端修订号
   * @param keyFactory  幂等键工厂；测试里可注入确定性实现
   */
  constructor(revision: number, keyFactory: () => string = () => newSaveKey(Date.now())) {
    this.revision = revision;
    this.keyFactory = keyFactory;
  }

  /** 标记内容发生变化。 */
  markDirty(): void {
    this.editSequence += 1;
  }

  /** 是否有未保存的改动。 */
  get dirty(): boolean {
    return this.savedSequence !== this.editSequence;
  }

  /** 是否已有保存在途。 */
  get saving(): boolean {
    return this.inFlight !== null;
  }

  /** 服务端修订号（成功保存后会前进）。 */
  get currentRevision(): number {
    return this.revision;
  }

  /** 发起时冻结的编辑代次；用于判断回包是否过期。 */
  get frozenEditSequence(): number {
    return this.inFlight?.editSequence ?? this.pendingRetry?.editSequence ?? this.editSequence;
  }

  /**
   * 开始一次保存。
   *
   * **有在途操作或待重试操作时返回 null** —— 调用方据此跳过（串行）。
   * 待重试的操作**优先**，且**复用原键与原 payload**。
   *
   * @param payload 要保存的内容
   * @returns 冻结的操作；无需发起时为 null
   */
  begin(payload: TPayload): FrozenSave<TPayload> | null {
    if (this.inFlight) {
      return null;
    }
    if (this.pendingRetry) {
      this.inFlight = this.pendingRetry;
      this.pendingRetry = null;
      return this.inFlight;
    }
    if (!this.dirty) {
      return null;
    }
    this.inFlight = {
      payload,
      revision: this.revision,
      idempotencyKey: this.keyFactory(),
      editSequence: this.editSequence
    };
    return this.inFlight;
  }

  /**
   * 报告成功。
   *
   * @param operation 当次冻结操作
   * @param newRevision 服务端返回的修订号
   * @returns `saved` 表示这次的内容确实存下了；`stale` 表示保存期间又有新编辑，
   *          只前进了修订号、**内容仍是未保存状态**
   */
  succeed(operation: FrozenSave<TPayload>, newRevision: number): SaveOutcome {
    this.inFlight = null;
    this.revision = newRevision;
    // 只有"回包对应的那次编辑"就是"当前最新编辑"时，才算真的存完了。
    // 否则用户在这期间又改了东西，标成已保存会让他以为改动已经落库。
    if (operation.editSequence === this.editSequence) {
      this.savedSequence = operation.editSequence;
      return 'saved';
    }
    return 'stale';
  }

  /**
   * 报告一次性失败（网络中断、超时）。
   *
   * 操作进入**待重试**，并把原键与原 payload 保留下来。
   *
   * @param operation 当次冻结操作
   */
  fail(operation: FrozenSave<TPayload>): void {
    this.inFlight = null;
    this.pendingRetry = operation;
  }

  /**
   * 报告修订冲突（409）：说明服务端有别人的更新。
   *
   * 冲突**不自动重试**——那会用本地内容覆盖别人的改动。
   * 调用方应展示差异并让用户决定。
   *
   * @param operation 当次冻结操作
   */
  conflict(operation: FrozenSave<TPayload>): SaveOutcome {
    this.inFlight = null;
    this.pendingRetry = null;
    void operation;
    return 'conflict';
  }

  /** 有需要重试的操作时为 true。 */
  get hasPendingRetry(): boolean {
    return this.pendingRetry !== null;
  }

  /** 取待重试操作（不改变状态），供调用方比较/展示。 */
  peekPendingRetry(): FrozenSave<TPayload> | null {
    return this.pendingRetry;
  }
}
