import type { EditableWorksheetPair } from '../domain/worksheet-editable-pairs';

/**
 * 管理草稿与配对编辑器之间的映射。
 *
 * 公开 schema 与管理草稿都不能直接喂给编辑器，编辑器也不应把 UI 状态写回草稿，
 * 因此这里做一次显式转换，并保证 **rowId 在草稿生命周期内稳定**（设计文档 §5.1）。
 */

/** 草稿中的一条内容。 */
export interface DraftEntry {
  rowId: string;
  entryId: string | null;
  unitId: string | null;
  clientUnitId: string | null;
  lessonId: string | null;
  clientLessonId: string | null;
  text: string;
  pinyin: string | null;
  meaning: string | null;
  listTypes: string[];
  confirmed: boolean;
  /**
   * 核对时内容的摘要（服务端要求的可信凭据）。
   * 由**用户确认的那一刻**计算并保存；内容一旦变化就必须一并清空，
   * 否则会拿旧摘要为已改动的内容背书。
   */
  confirmedDigest?: string | null;
  /**
   * 该条是否**已经过自动补全**。
   *
   * 必须随草稿持久化：用户要求「一条内容只自动补全一次」，
   * 若这个标记不落库，重新打开草稿后再点自动补全就会**第二次**补全同一行，
   * 可能覆盖用户保存前的手工修改（他清空过的那一栏会被重新填上）。
   */
  automaticInitialized?: boolean;
  /**
   * 服务端草稿里**界面上没有编辑入口、但必须原样往返**的字段。
   *
   * 服务端 `AdminTextbookDraft.DraftEntry` 用不透明映射 `preserved` 承载
   * `sourceRefs`、`pinyinSegments`、`partOfSpeech`、`senseId`、`context`、
   * `promptContext`、`acceptableAnswers`、`form`、`order` 等字段。
   *
   * **丢了它就是数据丢失**：编辑器每次重建条目都会走 `pairsToEntries`，
   * 若那里不带这个键，管理员改一个字就会把整册的义项、上下文和来源清空。
   */
  preserved?: Record<string, unknown>;
}

/**
 * 计算内容摘要，算法与服务端 `DraftToBookMapper.contentDigest` 一致：
 * `SHA-256(text \0 pinyin \0 meaning)` 的十六进制。
 *
 * 只用 `crypto.subtle`（异步）——浏览器里没有同步可用的 SHA-256。
 * 因此摘要在**确认动作**里算一次并保存，而不是每次映射时重算。
 *
 * @param entry 待确认内容
 * @returns 十六进制摘要
 */
export async function computeContentDigest(entry: {
  text: string;
  pinyin?: string | null;
  meaning?: string | null;
}): Promise<string> {
  const payload = `${entry.text ?? ''}\u0000${entry.pinyin ?? ''}\u0000${entry.meaning ?? ''}`;
  const bytes = new TextEncoder().encode(payload);
  const hash = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(hash)].map(value => value.toString(16).padStart(2, '0')).join('');
}

/** 生成一个稳定的行标识。 */
export function newRowId(index: number): string {
  return `row-${index + 1}`;
}

/**
 * 为新增行生成一个在本草稿内**唯一**的行标识。
 *
 * 只按"当前可见子集里的下标"生成是不够的：另一个节点里可能已经有同号的行，
 * 合并时新行会被当成已存在而被丢弃，用户会看到"刚输入的内容不见了"。
 * 因此这里显式避开**全部**既有行号。
 *
 * @param existing 全部既有条目
 * @param offset   起始序号，通常为既有条数
 * @returns 未被占用的行标识
 */
export function uniqueRowId(existing: readonly DraftEntry[], offset: number): string {
  const taken = new Set(existing.map(entry => entry.rowId));
  let index = Math.max(offset, existing.length);
  let candidate = newRowId(index);
  let guard = 0;
  while (taken.has(candidate) && guard < 10_000) {
    index += 1;
    guard += 1;
    candidate = newRowId(index);
  }
  return candidate;
}

/**
 * 草稿条目 → 编辑器行。
 *
 * @param entries 草稿条目
 * @param subject 学科
 * @returns 编辑器行
 */
export function entriesToPairs(entries: readonly DraftEntry[], subject: string): EditableWorksheetPair[] {
  return entries.map(entry => (subject === 'english'
    // 用**已补全标记**，不是"是否已核对"：两者含义不同，
    // 用 confirmed 代替会让"补全过但未核对"的行在重新打开后被再补一次。
    ? { tokenId: entry.rowId, english: entry.text, meaning: entry.meaning ?? '',
        automaticInitialized: entry.automaticInitialized === true }
    : { tokenId: entry.rowId, text: entry.text, pinyin: entry.pinyin ?? '',
        automaticInitialized: entry.automaticInitialized === true }));
}

/**
 * 判断编辑器行是否**任一侧**有内容。
 *
 * 双列的语义：语文为「汉字｜拼音」，英语为「中文｜英文」。
 * 半填是正常状态，不能因为另一侧为空就把整行丢掉。
 *
 * @param pair    编辑器行
 * @param subject 学科
 * @returns 任一侧有非空白内容时为 true
 */
export function pairHasAnyContent(pair: EditableWorksheetPair, subject: string): boolean {
  const first = subject === 'english' ? pair.english : pair.text;
  const second = subject === 'english' ? pair.meaning : pair.pinyin;
  return (first ?? '').trim().length > 0 || (second ?? '').trim().length > 0;
}

/**
 * 编辑器行 → 草稿条目。
 * 保留既有条目的 rowId 与归属，只更新内容字段；**不因为编辑器重排而换身份**。
 *
 * @param pairs     编辑器行
 * @param existing  既有草稿条目，用于按 rowId 找回归属
 * @param subject   学科
 * @returns 更新后的草稿条目
 */
export function pairsToEntries(
  pairs: readonly EditableWorksheetPair[],
  existing: readonly DraftEntry[],
  subject: string,
  /**
   * 新增行的归属节点。
   *
   * 为什么必须显式传入：新行在 `existing` 里**匹配不到**（还没有 rowId），
   * 因此既拿不到稳定身份、也拿不到归属。此前不传，于是"在空单元里逐字输入"
   * 会生成多条**无归属**条目——它们不属于任何单元，在目录里看不见，
   * 却真实存在于草稿里。
   */
  defaultOwner: { clientUnitId?: string | null; clientLessonId?: string | null } = {}
): DraftEntry[] {
  const byRowId = new Map(existing.map(entry => [entry.rowId, entry]));
  // 先保留全册身份和本批已有身份，避免新行占用稍后才映射的旧行标识。
  const takenRowIds = new Set(existing.map(entry => entry.rowId));
  for (const pair of pairs) {
    if (pair.tokenId != null) takenRowIds.add(pair.tokenId);
  }
  let nextRowIndex = existing.length;
  return pairs
    // 只有**两侧都空**的行才算空白行，不写进草稿。
    //
    // 此前这里只按一侧判断（英语看 `english`、语文看 `text`），
    // 于是"只填了中文、还没填英文"这种**正常的半填状态**会把整行丢掉——
    // 用户已经输入的内容无声消失，而且不会报错。
    // 双列录入本来就允许一侧先填，另一侧留空等待补全或人工填写。
    .filter(pair => pairHasAnyContent(pair, subject))
    .map(pair => {
      let rowId = pair.tokenId;
      if (rowId == null) {
        // 本次已分配的身份也立即占用，批量新增不能反复拿到同一个候选值。
        while (takenRowIds.has(newRowId(nextRowIndex))) nextRowIndex += 1;
        rowId = newRowId(nextRowIndex);
        takenRowIds.add(rowId);
        nextRowIndex += 1;
      }
      const previous = byRowId.get(rowId);
      const text = subject === 'english' ? (pair.english ?? '').trim() : (pair.text ?? '').trim();
      const second = subject === 'english' ? (pair.meaning ?? '').trim() : (pair.pinyin ?? '').trim();
      const stillConfirmed = previous
        ? previous.confirmed && previous.text === text && sameSecond(previous, second, subject)
        : false;
      return {
        rowId,
        entryId: previous?.entryId ?? null,
        unitId: previous?.unitId ?? null,
        clientUnitId: previous?.clientUnitId ?? defaultOwner.clientUnitId ?? null,
        lessonId: previous?.lessonId ?? null,
        clientLessonId: previous?.clientLessonId ?? defaultOwner.clientLessonId ?? null,
        text,
        // **另一学科的字段一律保留，不清空**：
        // 学科是整册教材的属性，切换学科只是换一种解释方式；
        // 若在这里把拼音（或释义）置空，用户切到英语再切回来就会发现**拼音被抹掉了**。
        // 保留旧值让切换可逆，代价只是草稿里可能带着当前学科的无关字段（不影响发布内容）。
        pinyin: subject === 'english' ? (previous?.pinyin ?? null) : (second || null),
        meaning: subject === 'english' ? (second || null) : (previous?.meaning ?? null),
        listTypes: previous?.listTypes ?? [],
        // 保留映射必须原样带回（见 `preserved` 字段说明）：
        // 它是"界面上没有编辑入口的字段"的唯一载体，重建条目时丢掉等于静默数据丢失。
        ...(previous?.preserved ? { preserved: { ...previous.preserved } } : {}),
        // 已补全标记随行保留：它是"补过了"的事实，不因内容改动而失效
        // （内容改动会撤销的是**核对**，不是"补全过"这个历史）。
        automaticInitialized: pair.automaticInitialized === true || previous?.automaticInitialized === true,
        // 内容变化即撤销"已核对"：不能用旧的核对状态掩盖新内容。
        // 摘要必须与 confirmed 同进同退——留下旧摘要等于为已改动的内容背书。
        confirmed: stillConfirmed,
        confirmedDigest: stillConfirmed ? (previous?.confirmedDigest ?? null) : null
      };
    });
}

/** 判断第二栏（拼音或释义）是否未变。 */
function sameSecond(previous: DraftEntry, second: string, subject: string): boolean {
  const previousSecond = subject === 'english' ? (previous.meaning ?? '') : (previous.pinyin ?? '');
  return previousSecond === second;
}

/**
 * 把"可见子集"的编辑结果合并回完整条目列表。
 *
 * 目录树按单元／课文过滤后，编辑器只应编辑可见子集；若直接把子集结果当作全部条目，
 * 就会**静默删除其他单元的内容**。因此必须按 rowId 合并，并保持原有顺序。
 *
 * @param all            完整条目
 * @param visibleRowIds  进入编辑器的行标识
 * @param updatedSubset  编辑器返回的子集
 * @returns 合并后的完整条目
 */
export function mergeSubset(
  all: readonly DraftEntry[],
  visibleRowIds: readonly string[],
  updatedSubset: readonly DraftEntry[]
): DraftEntry[] {
  const visible = new Set(visibleRowIds);
  const updatedById = new Map(updatedSubset.map(entry => [entry.rowId, entry]));
  const result: DraftEntry[] = [];
  for (const entry of all) {
    if (!visible.has(entry.rowId)) {
      result.push(entry);
      continue;
    }
    const updated = updatedById.get(entry.rowId);
    // 子集中被删除的行不写回（用户确实删掉了它）。
    if (updated) result.push(updated);
  }
  // 子集中新增的行追加到末尾。
  // 判据是"该行是否在**可见子集**中"，不是"是否在全部条目中出现过"：
  // 另一个节点里的同名行不属于本次编辑范围，不能因为行号相同就把用户新输入的行丢掉。
  const known = new Set(visible);
  for (const entry of updatedSubset) {
    if (known.has(entry.rowId)) continue;
    // 行号可能与**不可见节点**里的既有行相同（新行的行号是按可见子集下标生成的）。
    // 直接追加会造成两行共用标识，后续合并互相覆盖；因此这里重新分配一个唯一行号。
    const collides = result.some(item => item.rowId === entry.rowId);
    if (collides) {
      result.push({ ...entry, rowId: uniqueRowId(result, result.length) });
      continue;
    }
    result.push(entry);
  }
  return result;
}

/**
 * 自动补全的"只补一次"规则（用户明确要求保留）。
 *
 * 语义与既有字帖流程 `useWorksheetV2Session.prepareAutomaticFields` 保持一致：
 * **在发起补全之前就把行标记为已初始化**，因此网络失败、取消、返回都不会导致下次再补。
 * 本模块只实现规则；真正的拼音候选由 `lib/pinyin-adapter` 的 `suggestPinyin` 提供，
 * 不在这里另写一套补全实现。
 */

/**
 * 判断某行是否需要自动补全。
 * 判定依据是 `automaticInitialized` 标记，**不是"字段当前是否为空"**——
 * 否则用户手动清空后会被再次补全，等于程序覆盖了用户的显式操作。
 *
 * @param pair 编辑器行
 * @returns 需要补全时为 true
 */
export function needsAutomaticCompletion(pair: EditableWorksheetPair): boolean {
  return pair.automaticInitialized !== true;
}

/**
 * 选出本次允许补全的行，并**立即**把它们标记为已初始化。
 *
 * 先标记再补是关键：若等补全成功才标记，那么一次网络失败就会让所有行在下次输入时重试，
 * 既浪费请求，也可能在用户已经开始手工修改后又被写入。
 *
 * @param pairs 编辑器行
 * @param force 为真时忽略标记，重新补全全部行（显式重试）
 * @returns 标记后的行与本次允许补全的行标识
 */
export function beginAutomaticCompletion(
  pairs: readonly EditableWorksheetPair[],
  force = false
): { pairs: EditableWorksheetPair[]; eligibleIds: Set<string> } {
  const eligibleIds = new Set<string>();
  const next = pairs.map((pair, index) => {
    const id = pair.tokenId ?? `row-${index + 1}`;
    if (!force && pair.automaticInitialized === true) {
      return pair;
    }
    eligibleIds.add(id);
    return { ...pair, automaticInitialized: true };
  });
  return { pairs: next, eligibleIds };
}

/** 补全目标：需要请求补全的一行。 */
export interface CompletionTarget {
  /** 行标识。 */
  rowId: string;
  /** 送入补全的文本。 */
  text: string;
  /** 缺的是哪一侧。 */
  missing: 'pinyin' | 'meaning' | 'english';
}

/**
 * 选出本次需要补全的行。
 *
 * 规则：
 * - 已标记初始化的行**不参与**（一条内容只自动补全一次）；
 * - 语文：缺拼音的行；
 * - 英语：**只缺一侧**的行——两侧都有说明内容已完整，不请求；
 *   两侧都空说明用户还没填，也不请求（补全无从下手）。
 *
 * @param pairs   编辑器行
 * @param subject 学科
 * @param eligibleIds 本次允许补全的行标识
 * @returns 补全目标
 */
export function selectCompletionTargets(
  pairs: readonly EditableWorksheetPair[],
  subject: string,
  eligibleIds: ReadonlySet<string>
): CompletionTarget[] {
  const targets: CompletionTarget[] = [];
  pairs.forEach((pair, index) => {
    const rowId = pair.tokenId ?? `row-${index + 1}`;
    if (!eligibleIds.has(rowId)) return;
    if (subject === 'english') {
      const english = (pair.english ?? '').trim();
      const meaning = (pair.meaning ?? '').trim();
      if (english && !meaning) targets.push({ rowId, text: english, missing: 'meaning' });
      else if (!english && meaning) targets.push({ rowId, text: meaning, missing: 'english' });
      return;
    }
    const text = (pair.text ?? '').trim();
    const pinyin = (pair.pinyin ?? '').trim();
    // F06：**只在拼音为空时才补**。
    //
    // 此前只看"有没有汉字"，于是手工填好的 `长 / zhǎng` 会被补全覆盖成 `cháng`——
    // 用户看不到任何提示，只会在字帖里发现读音错了。
    // 显式补全的契约是"**只填空白**"，与英语分支（`if (english && !meaning)`）保持一致。
    if (text && !pinyin) targets.push({ rowId, text, missing: 'pinyin' });
  });
  return targets;
}

/** 一行补全请求的结果。 */
export interface CompletionResult {
  /** 行标识。 */
  rowId: string;
  /**
   * **这份结果是对哪段原文算出来的**（F06）。
   *
   * 必须有：合并只按 rowId 匹配是不够的——行身份是**稳定**的（F04 特意保证的），
   * 用户在等待期间改了原文时 rowId 不变，于是"cat 的释义"会安到"dog 的行"上，
   * 得到 `dog` / `猫` 这种自相矛盾的内容。
   *
   * 缺失时合并会**保守丢弃**：填错会污染用户内容，少补只是没帮上忙。
   */
  sourceText?: string;
  /** 补出的释义。 */
  meaning?: string | null;
  /** 补出的英文。 */
  english?: string | null;
}

/**
 * 把补全回包合并进当前行。
 *
 * **必须在回包到达后重新读取当前状态**，而不是沿用发起请求时的快照：
 * 用户在等待期间可能已经手工填写了那一侧。若按旧快照写入，
 * 就会**用自动结果覆盖用户刚输入的内容**——这是补全功能最容易造成的破坏。
 *
 * @param pairs        当前行（可能是请求期间已被编辑过的）
 * @param eligibleIds  本次允许补全的行
 * @param results      回包结果
 * @returns 合并后的行与实际补全条数
 */
export function applyCompletionResults(
  pairs: readonly EditableWorksheetPair[],
  eligibleIds: ReadonlySet<string>,
  results: readonly CompletionResult[]
): { pairs: EditableWorksheetPair[]; filledCount: number } {
  const byRowId = new Map(results.map(result => [result.rowId, result]));
  let filledCount = 0;
  const next = pairs.map((pair, index) => {
    const rowId = pair.tokenId ?? `row-${index + 1}`;
    if (!eligibleIds.has(rowId)) return pair;
    const result = byRowId.get(rowId);
    if (!result) return pair;
    const english = (pair.english ?? '').trim();
    const meaning = (pair.meaning ?? '').trim();
    // F06：**先核对原文**。
    //
    // 回包是对"发起请求那一刻的文本"算出来的；行身份在用户改动后**不会变**，
    // 所以只按 rowId 匹配会把旧结果配到新原文上。
    // 拿不到 sourceText 时保守丢弃：宁可少补，不可补错。
    if (!result.sourceText) {
      return pair;
    }
    if (result.sourceText.trim() !== (english || meaning)) {
      return pair;
    }
    // 再次确认那一侧**此刻仍为空**；用户在等待期间的输入优先。
    if (english && !meaning) {
      const translated = (result.meaning ?? '').trim();
      if (!translated) return pair;
      filledCount += 1;
      return { ...pair, meaning: translated };
    }
    if (!english && meaning) {
      const translated = (result.english ?? '').trim();
      if (!translated) return pair;
      filledCount += 1;
      return { ...pair, english: translated };
    }
    return pair;
  });
  return { pairs: next, filledCount };
}

/**
 * 给指定条目写入"已核对"及其摘要。
 *
 * 摘要由调用方在**确认的那一刻**算好后传入（`crypto.subtle` 是异步的）。
 * 本函数只做纯赋值，因此可以被测试穷举各种组合：
 * 只有拿到摘要的条目才会被标记为已核对——**没有摘要就不确认**，
 * 避免出现"确认了但服务端无法验证"的中间态。
 *
 * @param entries 全部条目
 * @param digests 行标识到摘要的映射
 * @returns 更新后的条目
 */
export function markConfirmed(
  entries: readonly DraftEntry[],
  digests: ReadonlyMap<string, string>
): DraftEntry[] {
  return entries.map(entry => {
    const digest = digests.get(entry.rowId);
    if (!digest) {
      return entry;
    }
    return { ...entry, confirmed: true, confirmedDigest: digest };
  });
}
