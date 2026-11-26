import type { CurriculumEntry, ListType } from './curriculum';
import { compareEntries } from './curriculum';
import { balancedQuota, createSeededRandom, deriveSeed, normalizeText, shuffleWith } from './random';

/**
 * 确定性选题：过滤 → 可用性校验 → 规范化 → 去重 → 稳定排序 → 抽取 → 题目排序。
 * 同一 seed、同一候选集与同一策略必须得到完全一致的顺序。
 */

/** 选题策略。 */
export type SelectionStrategy = 'all' | 'random' | 'balanced';

/** 题目排序方式。 */
export type EntryOrdering = 'textbook' | 'random' | 'manual';

/** 选题请求。 */
export interface SelectionRequest {
  /** 候选条目，可包含未通过可用性校验的条目。 */
  candidates: readonly CurriculumEntry[];
  /** 选题策略。 */
  strategy: SelectionStrategy;
  /** 期望题目数量；策略为 all 时忽略。 */
  requestedCount: number;
  /** 随机种子；策略为 random 或 balanced 时必填。 */
  seed: string | null;
  /** 最终题目排序方式。 */
  ordering: EntryOrdering;
  /** 仅保留指定单元；为空表示不限制。 */
  unitIds?: readonly string[];
  /** 仅保留指定课文；为空表示不限制。 */
  lessonIds?: readonly string[];
  /** 仅保留指定清单类别；为空表示不限制。 */
  listTypes?: readonly ListType[];
}

/** 分组配额明细，用于向用户说明实际抽取分布。 */
export interface UnitQuota {
  unitId: string;
  quota: number;
  available: number;
}

/** 被阻断的条目及原因。 */
export interface BlockedEntry {
  entryId: string;
  text: string;
  issues: string[];
}

/** 选题结果。 */
export interface SelectionResult {
  /** 最终有序条目。 */
  entries: CurriculumEntry[];
  /** 用户期望数量。 */
  requested: number;
  /** 通过校验的候选数量。 */
  available: number;
  /** 候选不足时为 true，界面需显示实际可用数量。 */
  shortage: boolean;
  /** 实际使用的种子，策略为 all 时为 null。 */
  seed: string | null;
  /** 算法标识，写入快照用于复现。 */
  algorithm: string;
  /** 分组配额，仅均衡策略返回。 */
  quotaByUnit: UnitQuota[];
  /** 因校对或字段问题被排除的条目。 */
  blocked: BlockedEntry[];
}

/** 单个条目的规范化形态，用于去重与排序。 */
export interface NormalizedEntry {
  entry: CurriculumEntry;
  text: string;
  /** 读音或释义的多音字/义项区分键。 */
  senseKey: string;
}

/** 条目的去重键：规范化文本 + 义项 + 语境，不无条件小写，不丢来源。 */
export function entryDedupeKey(entry: CurriculumEntry): string {
  const text = normalizeText(entry.text);
  const sense = normalizeText(entry.senseId || `${entry.pinyinDisplay ?? ''}|${entry.meaning ?? ''}`);
  const context = normalizeText(entry.context ?? '');
  return JSON.stringify([text, sense, context, entry.form]);
}

/** 判断条目是否可用于生成练习。 */
export function entryUsable(entry: CurriculumEntry, subjectForm: { needsPinyin: boolean; needsMeaning: boolean }): string[] {
  const issues = [...entry.blockingIssues];
  if (!normalizeText(entry.text)) issues.push('emptyText');
  if (subjectForm.needsPinyin && !normalizeText(entry.pinyinDisplay ?? '')) issues.push('missingPinyin');
  if (subjectForm.needsMeaning && !normalizeText(entry.meaning ?? '')) issues.push('missingMeaning');
  if (entry.form !== 'character' && entry.form !== 'word' && entry.form !== 'phrase') issues.push('unknownForm');
  return issues;
}

/** 规范化并去重候选条目，保留首次出现的顺序与全部来源。 */
export function normalizeCandidates(candidates: readonly CurriculumEntry[]): {
  entries: NormalizedEntry[];
  blocked: BlockedEntry[];
} {
  const seen = new Set<string>();
  const entries: NormalizedEntry[] = [];
  for (const entry of candidates) {
    if (entry.blockingIssues.length > 0) continue;
    const key = entryDedupeKey(entry);
    if (seen.has(key)) continue;
    seen.add(key);
    entries.push({
      entry: { ...entry, text: normalizeText(entry.text) },
      text: normalizeText(entry.text),
      senseKey: normalizeText(entry.senseId || `${entry.pinyinDisplay ?? ''}|${entry.meaning ?? ''}`)
    });
  }
  return { entries, blocked: [] };
}

/** 按策略过滤候选条目。 */
export function filterCandidates(candidates: readonly CurriculumEntry[], request: SelectionRequest): CurriculumEntry[] {
  const units = request.unitIds?.length ? new Set(request.unitIds) : null;
  const lessons = request.lessonIds?.length ? new Set(request.lessonIds) : null;
  const lists = request.listTypes?.length ? new Set(request.listTypes) : null;
  return candidates.filter(entry => {
    if (units && !units.has(entry.unitId)) return false;
    if (lessons && !(entry.lessonId && lessons.has(entry.lessonId))) return false;
    if (lists && !entry.listTypes.some(type => lists.has(type))) return false;
    return true;
  });
}

/** 按教材顺序稳定排序条目。 */
export function sortByTextbook(entries: readonly CurriculumEntry[]): CurriculumEntry[] {
  return entries.slice().sort(compareEntries);
}

/** 按分组计算均衡配额。 */
export function planBalancedQuota(entries: readonly CurriculumEntry[], requested: number): UnitQuota[] {
  const byUnit = new Map<string, CurriculumEntry[]>();
  for (const entry of entries) {
    const group = byUnit.get(entry.unitId);
    if (group) group.push(entry);
    else byUnit.set(entry.unitId, [entry]);
  }
  const unitIds = [...byUnit.keys()].sort();
  const capacities = unitIds.map(unitId => byUnit.get(unitId)!.length);
  const quota = balancedQuota(capacities, requested);
  return unitIds.map((unitId, index) => ({ unitId, quota: quota[index]!, available: capacities[index]! }));
}

/** 按策略执行确定性抽取，候选不足时返回全部可用条目并标记 shortage。 */
export function selectForWorksheet(request: SelectionRequest, subjectForm: { needsPinyin: boolean; needsMeaning: boolean }): SelectionResult {
  const filtered = filterCandidates(request.candidates, request);
  const usable: CurriculumEntry[] = [];
  const blocked: BlockedEntry[] = [];
  for (const entry of filtered) {
    const issues = entryUsable(entry, subjectForm);
    if (issues.length) blocked.push({ entryId: entry.entryId, text: entry.text, issues });
    else usable.push(entry);
  }
  const { entries: normalized } = normalizeCandidates(usable);
  const pool = sortByTextbook(normalized.map(item => item.entry));
  const requested = Math.max(0, Math.floor(request.requestedCount));
  const available = pool.length;
  const strategy = request.strategy;
  let picked: CurriculumEntry[];
  let quotaByUnit: UnitQuota[] = [];
  let algorithm: string;
  if (strategy === 'all') {
    picked = pool;
    algorithm = 'all-v1';
  } else if (strategy === 'balanced') {
    const seed = request.seed ?? 'default';
    const plan = planBalancedQuota(pool, requested);
    quotaByUnit = plan;
    const byUnit = new Map<string, CurriculumEntry[]>();
    for (const entry of pool) {
      const group = byUnit.get(entry.unitId);
      if (group) group.push(entry);
      else byUnit.set(entry.unitId, [entry]);
    }
    picked = [];
    for (const item of plan) {
      if (item.quota <= 0) continue;
      const group = byUnit.get(item.unitId)!;
      // 每组使用派生 seed 独立抽样，保证组内顺序稳定且组间不相关。
      const random = createSeededRandom(deriveSeed(seed, item.unitId));
      picked.push(...shuffleWith(group, random).slice(0, item.quota));
    }
    algorithm = 'balanced-quota-v1';
  } else {
    const seed = request.seed ?? 'default';
    const random = createSeededRandom(seed);
    picked = shuffleWith(pool, random).slice(0, requested);
    algorithm = 'seeded-shuffle-v1';
  }
  const ordered = request.ordering === 'random' && (strategy !== 'all' || request.seed)
    ? shuffleWith(picked, createSeededRandom(deriveSeed(request.seed ?? 'default', 'final-order')))
    : picked;
  return {
    entries: ordered,
    requested,
    available,
    shortage: strategy !== 'all' && available < requested,
    seed: strategy === 'all' ? null : (request.seed ?? 'default'),
    algorithm,
    quotaByUnit,
    blocked
  };
}

/** 重复展开：按题目顺序连续编号，同一内容重复 N 次仍保持原顺序。 */
export function expandRepetitions<T>(entries: readonly T[], repetitions: number): T[] {
  const times = Math.max(1, Math.floor(repetitions));
  const result: T[] = [];
  for (let round = 0; round < times; round += 1) {
    for (const entry of entries) result.push(entry);
  }
  return result;
}
