/**
 * 管理端批量粘贴的解析（设计文档 §3.4「批量粘贴」）。
 *
 * 规则：**每行一条**；去掉行首尾空白；剥离句末标点；去重保留首次出现的位置；
 * 空白行跳过。不按空格拆分——词语和短语内部允许有空格。
 */

/** 句末与列举用标点，粘贴自教材或文档时常见。 */
const TRAILING_PUNCTUATION = /[、。，,．.!！?？;；:：]+$/u;

/** 解析结果。 */
export interface BulkParseResult {
  /** 去重后的条目文本，按首次出现顺序。 */
  items: string[];
  /** 因重复被丢弃的条数。 */
  duplicateCount: number;
  /** 空白行数量。 */
  blankCount: number;
}

/**
 * 剥离行尾标点。
 *
 * @param line 原始行
 * @returns 剥离后的文本
 */
export function stripTrailingPunctuation(line: string): string {
  return line.trim().replace(TRAILING_PUNCTUATION, '').trim();
}

/**
 * 解析批量粘贴的文本。
 *
 * @param raw     粘贴内容
 * @param options 选项：`keepDuplicates` 为真时不去重
 * @returns 解析结果
 */
/** 批量粘贴的**一行**：左格与右格。 */
export interface BulkRow {
  /** 左格（语文为汉字，英语为中文）。 */
  left: string;
  /** 右格（语文为拼音，英语为英文）；单列粘贴时为空。 */
  right: string;
}

/**
 * 解析批量粘贴为**双列行**（F12）。
 *
 * **与 `parseBulkText` 的区别**：那个返回**扁平文本数组**，
 * 结构上无法表达"一行两格"——于是 `明天见\tSee you tomorrow!`
 * 会被当成**一条正文**，导入后左列里混着英文。
 *
 * 规则（与用户的契约一致）：
 *
 * - 按换行对齐，**一行一行保留**；
 * - 有制表符就按**第一个**制表符拆成左右两格；
 * - **空行保留**为"两格都空"的一行——否则后面的配对会整体上移；
 * - **不剥标点**、**不去重**：那是原文，不是待清洗的数据。
 *
 * @param raw 粘贴内容
 * @returns 双列行，顺序与输入一致
 */
export function parseBulkRows(raw: string): BulkRow[] {
  return raw.split(/\r?\n/u).map(line => {
    const tab = line.indexOf('\t');
    if (tab < 0) {
      return { left: line.trim(), right: '' };
    }
    return { left: line.slice(0, tab).trim(), right: line.slice(tab + 1).trim() };
  });
}

export function parseBulkText(raw: string, options: { dedupe?: boolean } = {}): BulkParseResult {
  const items: string[] = [];
  const seen = new Set<string>();
  let duplicateCount = 0;
  let blankCount = 0;
  for (const line of raw.split(/\r?\n/u)) {
    // F12：**不剥标点**。
    //
    // 此前每行都过 `stripTrailingPunctuation`，于是 `See you tomorrow!` 变成
    // `See you tomorrow`、`明天见。` 变成 `明天见`——**用户的原文被改了**，
    // 而且不会告诉他。用户契约写得很明确：「保留短语空格、标点和不同义项」。
    const text = line.trim();
    if (!text) {
      // F12：**空行保留位置**。
      //
      // 此前空行直接 `continue`，只在计数里留个痕迹。但空行是用户排版的一部分：
      // 双列粘贴里，跳过一行会让**后面所有行的配对整体上移一格**。
      // 现在把空行作为空字符串留在原位上。
      blankCount += 1;
      items.push('');
      continue;
    }
    // F12：**默认不去重**。
    //
    // 同一行文字在不同单元可能意思不同；静默去掉一条就是丢内容，而且用户看不到。
    // 需要去重时必须**显式**要求。
    if (options.dedupe === true) {
      if (seen.has(text)) {
        duplicateCount += 1;
        continue;
      }
      seen.add(text);
    }
    items.push(text);
  }
  return { items, duplicateCount, blankCount };
}

/** 粘贴目标节点。 */
export interface PasteTarget {
  /** 目标单元临时标识；未分单元为 `''`。 */
  clientUnitId: string;
  /** 目标课文临时标识；粘贴到单元层级时为 null。 */
  clientLessonId: string | null;
}

/** 用于判重的最小条目形状。 */
export interface ExistingEntryRef {
  text: string;
  clientUnitId: string | null;
  clientLessonId: string | null;
}

/**
 * 从粘贴结果中挑出真正需要新增的条目。
 *
 * **判重范围限定在目标节点内，绝不做全局去重。**
 * 同一个词完全可能出现在不同课文（例如"春天"在识字表和课文里各出现一次），
 * 全局去重会**静默丢掉第二条**，让教材内容缺失——而用户从界面上看不出少了什么。
 *
 * @param items    粘贴解析出的条目
 * @param existing 已有条目
 * @param target   粘贴目标节点
 * @returns 需要新增的条目与被跳过的条数
 */
export function selectNewItems(
  items: readonly string[],
  existing: readonly ExistingEntryRef[],
  target: PasteTarget
): { items: string[]; skippedCount: number } {
  const sameNode = existing.filter(entry => entry.clientUnitId === target.clientUnitId
    && (entry.clientLessonId ?? null) === target.clientLessonId);
  const taken = new Set(sameNode.map(entry => entry.text));
  const kept: string[] = [];
  let skippedCount = 0;
  for (const item of items) {
    if (taken.has(item)) {
      skippedCount += 1;
      continue;
    }
    taken.add(item);
    kept.push(item);
  }
  return { items: kept, skippedCount };
}
