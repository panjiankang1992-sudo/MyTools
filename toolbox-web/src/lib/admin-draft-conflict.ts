import type { DraftEntry } from './admin-textbook-draft';

/**
 * 保存冲突的**差异计算**（设计文档 §3.7 / F05 / F12）。
 *
 * 设计原文：「过期修订返回冲突，不"后保存者覆盖前保存者"」；
 * 「冲突面板显示"服务器已有更新"，提供"查看差异""读取最新"」。
 *
 * 抽成纯函数的原因：差异是**两个草稿文档的集合比较**，正确性不依赖网络、
 * 也不依赖组件生命周期；写在页面里就只能靠"点一遍看看"，改一行都可能悄悄错。
 */

/** 参与差异比较的内容投影（只比对影响教材含义的字段）。 */
export interface ConflictContent {
  /** 语文为汉字、英语为英文。 */
  text: string;
  /** 逐字拼音；英语为 null，比较时统一成空串。 */
  pinyin: string;
  /** 中文释义；语文为 null，比较时统一成空串。 */
  meaning: string;
  /** 教材表类别；顺序不影响含义，比较前排序。 */
  listTypes: string[];
}

/** 差异分类。 */
export type ConflictDiffKind = 'local-only' | 'remote-only' | 'changed' | 'identical';

/** 差异中的一行。 */
export interface ConflictDiffRow {
  /** 行标识；两侧都有时是同一行的身份。 */
  rowId: string;
  /** 差异分类。 */
  kind: ConflictDiffKind;
  /** 本地侧内容；仅服务端有时为 null。 */
  local: ConflictContent | null;
  /** 服务端侧内容；仅本地有时为 null。 */
  remote: ConflictContent | null;
}

/** 差异汇总。 */
export interface ConflictDiff {
  /** 逐行差异，顺序为「本地顺序 + 仅服务端多出的行」。 */
  rows: ConflictDiffRow[];
  /** 仅本地有的行数。 */
  localOnlyCount: number;
  /** 仅服务端有的行数。 */
  remoteOnlyCount: number;
  /** 两侧都有但内容不同的行数。 */
  changedCount: number;
  /** 两侧都有的行数（含相同与不同）。 */
  sharedCount: number;
  /** 两侧是否**完全一致**（忽略空值与长度变化后的内容）。 */
  identical: boolean;
}

/** 把条目投影成可比较的内容；缺失值统一成空串，避免 `null` 与 `''` 被当成不同。 */
export function conflictContentOf(entry: DraftEntry): ConflictContent {
  return {
    text: entry.text ?? '',
    pinyin: entry.pinyin ?? '',
    meaning: entry.meaning ?? '',
    listTypes: [...(entry.listTypes ?? [])].sort()
  };
}

/** 判断两侧内容是否相同。 */
function sameContent(left: ConflictContent, right: ConflictContent): boolean {
  return left.text === right.text
    && left.pinyin === right.pinyin
    && left.meaning === right.meaning
    && left.listTypes.length === right.listTypes.length
    && left.listTypes.every((value, index) => value === right.listTypes[index]);
}

/**
 * 计算本地草稿与服务端草稿的条目差异。
 *
 * `rowId` 在草稿生命周期内稳定（设计 §5.1），因此它是**唯一正确的对齐键**：
 * 按下标比较会在"对方在中间插入一行"时把其后所有行都判成改动过。
 *
 * @param local  本地条目（内存中的编辑缓冲）
 * @param remote 服务端条目（最近一次读取的草稿）
 * @returns 差异与计数
 */
export function diffDraftEntries(
  local: readonly DraftEntry[],
  remote: readonly DraftEntry[]
): ConflictDiff {
  const remoteById = new Map(remote.map(entry => [entry.rowId, entry]));
  const localIds = new Set(local.map(entry => entry.rowId));
  const rows: ConflictDiffRow[] = [];
  let localOnlyCount = 0;
  let changedCount = 0;
  let sharedCount = 0;

  for (const entry of local) {
    const remoteEntry = remoteById.get(entry.rowId);
    const localContent = conflictContentOf(entry);
    if (!remoteEntry) {
      localOnlyCount += 1;
      rows.push({ rowId: entry.rowId, kind: 'local-only', local: localContent, remote: null });
      continue;
    }
    sharedCount += 1;
    const remoteContent = conflictContentOf(remoteEntry);
    if (sameContent(localContent, remoteContent)) {
      rows.push({ rowId: entry.rowId, kind: 'identical', local: localContent, remote: remoteContent });
    } else {
      changedCount += 1;
      rows.push({ rowId: entry.rowId, kind: 'changed', local: localContent, remote: remoteContent });
    }
  }

  let remoteOnlyCount = 0;
  for (const entry of remote) {
    if (localIds.has(entry.rowId)) continue;
    remoteOnlyCount += 1;
    rows.push({ rowId: entry.rowId, kind: 'remote-only', local: null, remote: conflictContentOf(entry) });
  }

  return {
    rows,
    localOnlyCount,
    remoteOnlyCount,
    changedCount,
    sharedCount,
    // 只有**没有任何差异**时才算一致；仅靠计数相等会漏掉"内容不同但条数相同"。
    identical: localOnlyCount === 0 && remoteOnlyCount === 0 && changedCount === 0
  };
}

/** 差异分类的中文说明。 */
export const CONFLICT_DIFF_LABELS: Readonly<Record<ConflictDiffKind, string>> = {
  'local-only': '本地新增',
  'remote-only': '服务器新增',
  changed: '双方都改过',
  identical: '两侧相同'
};

/**
 * 生成一句可展示的差异摘要。
 *
 * 明确区分三个方向，用户才知道**能不能直接覆盖**：
 * 只有本地新增/修改时覆盖风险低；服务器有新增或修改时必须先读差异。
 *
 * @param diff 差异结果
 * @returns 中文摘要
 */
export function summarizeConflictDiff(diff: ConflictDiff): string {
  if (diff.identical) {
    return '本地与服务端的条目内容一致，冲突可能来自其他字段（如目录或教材信息）。';
  }
  const parts: string[] = [];
  if (diff.localOnlyCount) parts.push(`本地有 ${diff.localOnlyCount} 条服务器没有`);
  if (diff.remoteOnlyCount) parts.push(`服务器有 ${diff.remoteOnlyCount} 条本地没有`);
  if (diff.changedCount) parts.push(`${diff.changedCount} 条两侧都改过`);
  return parts.join('；') + '。';
}


/**
 * 冲突面板要展示的状态。
 *
 * 单独抽出来是因为它需要**在调用 `SaveState.conflict()` 之前**取值：
 * `conflict()` 会清空 `pendingRetry`（冲突绝不能用旧 payload 覆盖服务器更新），
 * 清空之后就再也问不出"刚才是否放弃了一份排队中的重试"。
 *
 * @param cause        409 的服务端错误（含 `details`）
 * @param pendingRetry 调用 `conflict()` **之前**取到的待重试操作
 * @returns 面板状态
 */
export interface ConflictPanelState {
  /** 服务端给出的期望修订号；保存冲突只有 `expectedRevision`，没有 `actual`。 */
  expectedRevision: number | null;
  /** 是否因为这次冲突而放弃了一份排队中的重试（避免旧内容覆盖服务器更新）。 */
  abandonedRetry: boolean;
}

/**
 * 解析 409 冲突面板状态。
 *
 * @param details 服务端错误细节（`AdminApiError.details`）；缺失时全部按"未知"处理
 * @param hasPendingRetry 传入 `peekPendingRetry() !== null` 的结果
 * @returns 面板状态
 */
export function resolveConflictPanel(
  details: Record<string, unknown> | undefined,
  hasPendingRetry: boolean
): ConflictPanelState {
  const expected = details?.expectedRevision;
  return {
    // 只接受数字：服务端发布冲突的 details 形状是 `{reason, expected, actual}`，
    // 与保存冲突不同；这里不做跨形状猜测，读不到就显示"未知"。
    expectedRevision: typeof expected === 'number' && Number.isFinite(expected) ? expected : null,
    abandonedRetry: hasPendingRetry
  };
}
