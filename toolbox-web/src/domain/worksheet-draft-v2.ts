import { INPUT_LIMITS, rawTextBytes, serializeTokens, tokenize, type AutomaticFields, type CheckState, type InputIssue, type InputSourceRef, type ParsePolicy } from './worksheet-input';
import type { WorksheetProcessingOptions } from './worksheet-processing';
import type { WorksheetLayout } from './worksheet-snapshot';

/**
 * 字帖草稿的保存与旧版迁移。
 *
 * 契约见 docs/design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md §5.3。
 * 沿用主动保存：不因 OCR 自动把原图或识别文本持久化；图片对象只存在本次内存里。
 */

/** v1 草稿键前缀（保持原样，迁移时只读）。 */
export const DRAFT_PREFIX_V1 = 'yuyutian-toolbox:draft:v1:';

/** v2 草稿键前缀。 */
export const DRAFT_PREFIX_V2 = 'yuyutian-toolbox:draft:v2:';

/** 可注入的存储接口，便于在 Node 中测试；浏览器传入 localStorage。 */
export interface DraftStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/** 草稿中保存的来源摘要，不含图片本体。 */
export interface StoredSourceSummary {
  sourceId: string;
  kind: InputSourceRef['kind'];
  editionId: string | null;
  contentVersion: string | null;
  label: string;
}

/** 校对结果按出现位置和文本共同绑定，旧草稿可缺少位置字段。 */
export interface StoredTokenCorrection {
  textFingerprint: string;
  tokenIndex?: number;
  occurrence?: number;
  pinyin: Array<{ grapheme: string; syllable: string }>;
  pinyinState: 'missing' | 'suggested' | 'confirmed';
  meaning: string | null;
  meaningState: 'missing' | 'suggested' | 'confirmed';
  /** 保留纯拼音行的空汉字字段，恢复时不能把拼音原文误放到汉字栏。 */
  chineseText?: string;
  /** 规范英文与原始文本分别保存，兼容没有这些字段的旧草稿。 */
  englishText?: string | null;
  englishState?: CheckState;
  automaticFields?: AutomaticFields;
  automaticInitialized?: boolean;
  sourceRefs?: InputSourceRef[];
  senseId?: string | null;
  acceptableAnswers?: string[];
  issues?: InputIssue[];
}

/** 保留旧版可恢复样式；新流程至少使用格距与标题。 */
export type StoredDraftLayout = { gridMm: number } & Partial<Omit<WorksheetLayout, 'gridMm'>>;

/** v2 草稿负载；只允许可序列化的原文、来源摘要、校对与配置。 */
export interface StoredDraftV2 {
  schemaVersion: 2;
  subject: 'chinese' | 'english';
  rawText: string;
  parsePolicy: ParsePolicy;
  sources: StoredSourceSummary[];
  corrections: StoredTokenCorrection[];
  processing: WorksheetProcessingOptions;
  title: string;
  layout: StoredDraftLayout;
  /** 教材选择用于联网重新取回，不保存条目正文。 */
  selection: {
    grade: number | null;
    semester: string;
    editionId: string | null;
    selectedIds: string[];
    strategy: string;
    ordering: string;
    requestedCount: number | null;
    seed: string | null;
  };
  /** 是否由 v1 迁移而来；界面据此提示原始输入格式未保存。 */
  migratedFromV1: boolean;
  savedAt: string;
}

/** 保存结果。 */
export interface SaveOutcome {
  ok: boolean;
  notice: 'draftSaved' | 'draftSaveFailed' | 'draftNotSerializable';
}

/** 读取结果。 */
export interface LoadOutcome {
  status: 'ok' | 'empty' | 'invalid';
  payload?: StoredDraftV2;
  notice?: string;
}

/** 生成 v2 草稿键。 */
export function draftKeyV2(subject: 'chinese' | 'english'): string {
  return `${DRAFT_PREFIX_V2}${subject}`;
}

/** 生成 v1 草稿键。 */
export function draftKeyV1(subject: 'chinese' | 'english'): string {
  return `${DRAFT_PREFIX_V1}${subject}`;
}

/**
 * 判断值里是否含有不允许持久化的内容。
 *
 * 契约 §5.3：v2 不存 Blob、base64、临时 URL 与 Worker。
 *
 * @param value 待检查的值
 * @returns 是否含有禁止内容
 */
export function containsForbiddenPayload(value: unknown): boolean {
  const active = new WeakSet<object>();
  const visit = (item: unknown, depth: number): boolean => {
    if (item === null || item === undefined) return false;
    if (depth > 20) return true;
    if (typeof item === 'string') return item.startsWith('data:') || item.startsWith('blob:');
    if (typeof item === 'number') return !Number.isFinite(item);
    if (typeof item === 'boolean') return false;
    if (typeof item !== 'object') return true;
    // 环状对象、浏览器对象和二进制数据不得进入主动保存的草稿。
    if (active.has(item) || (!Array.isArray(item) && Object.getPrototypeOf(item) !== Object.prototype && Object.getPrototypeOf(item) !== null)) return true;
    active.add(item);
    const forbidden = Object.values(item).some(child => visit(child, depth + 1));
    active.delete(item);
    return forbidden;
  };
  try { return visit(value, 0); } catch { return true; }
}

/** 草稿格式错误仅影响本次恢复，不允许调用方替换已有会话。 */
export class WorksheetDraftError extends Error {
  /** 创建稳定的草稿错误码。 */
  constructor() { super('WORKSHEET_DRAFT_INVALID'); this.name = 'WorksheetDraftError'; }
}

type RecordValue = Record<string, unknown>;
const MAX_DRAFT_LENGTH = 2 * 1024 * 1024;

/** 验证单层对象，拒绝数组和空值。 */
function record(value: unknown): RecordValue {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new WorksheetDraftError();
  return value as RecordValue;
}

/** 验证受限长度的字符串。 */
function string(value: unknown, limit = 65_536): string {
  if (typeof value !== 'string' || value.length > limit) throw new WorksheetDraftError();
  return value;
}

/** 验证可为空的字符串。 */
function nullableString(value: unknown, limit = 65_536): string | null {
  return value === null ? null : string(value, limit);
}

/** 验证有限枚举。 */
function choice<T extends string>(value: unknown, values: readonly T[]): T {
  if (!values.includes(value as T)) throw new WorksheetDraftError();
  return value as T;
}

/** 验证有限整数范围。 */
function integer(value: unknown, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) throw new WorksheetDraftError();
  return value;
}

/** 验证布尔值。 */
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new WorksheetDraftError();
  return value;
}

/** 验证受限数组。 */
function array(value: unknown, max: number = INPUT_LIMITS.maxTokens): unknown[] {
  if (!Array.isArray(value) || value.length > max) throw new WorksheetDraftError();
  return value;
}

/** 验证来源引用，不把旧来源摘要猜成逐项来源。 */
function sourceRef(value: unknown): InputSourceRef {
  const item = record(value);
  return { sourceId: string(item.sourceId, 512), kind: choice(item.kind, ['custom', 'textbook', 'image', 'legacy']), editionId: nullableString(item.editionId, 512), contentVersion: nullableString(item.contentVersion, 512), entryId: nullableString(item.entryId, 512), unitId: nullableString(item.unitId, 512), imageId: nullableString(item.imageId, 512), localOverride: boolean(item.localOverride) };
}

/** 校验及归一旧版处理别名，保留等价选题行为。 */
function processing(value: unknown, subject: StoredDraftV2['subject']): WorksheetProcessingOptions {
  const item = record(value);
  const modes = subject === 'chinese' ? ['chinese-dictation', 'chinese-pinyin'] as const : ['english-dictation', 'english-meaning'] as const;
  const ordering = item.ordering === 'textbook' || item.ordering === 'manual' ? 'input' : item.ordering;
  const selection = item.selection === 'random' ? 'random-count' : item.selection === 'balanced' ? 'balanced-unit' : item.selection;
  return {
    mode: item.mode === null ? null : choice(item.mode, modes),
    chineseUnit: choice(item.chineseUnit, ['word', 'character']),
    duplicatePolicy: choice(item.duplicatePolicy, ['merge-equivalent', 'keep-occurrences']),
    ordering: choice(ordering, ['input', 'random']), selection: choice(selection, ['all', 'random-count', 'balanced-unit']),
    requestedCount: item.requestedCount === null ? null : integer(item.requestedCount, 1, 200),
    seed: nullableString(item.seed, 512), repetitions: integer(item.repetitions, 1, 5) as WorksheetProcessingOptions['repetitions']
  };
}

/** 校验样式并保留可恢复的旧版纸面参数。 */
function layout(value: unknown): StoredDraftLayout {
  const item = record(value);
  if (typeof item.gridMm !== 'number' || !Number.isFinite(item.gridMm) || item.gridMm < 10 || item.gridMm > 18) throw new WorksheetDraftError();
  const result: StoredDraftLayout = { gridMm: item.gridMm };
  if (item.paper !== undefined) result.paper = choice(item.paper, ['A4'] as const);
  if (item.orientation !== undefined) result.orientation = choice(item.orientation, ['portrait'] as const);
  if (item.marginMm !== undefined) result.marginMm = integer(item.marginMm, 15, 15) as 15;
  if (item.gridMmUserSet !== undefined) result.gridMmUserSet = boolean(item.gridMmUserSet);
  if (item.chineseGrid !== undefined) result.chineseGrid = choice(item.chineseGrid, ['tian', 'square'] as const);
  if (item.pinyinArea !== undefined) result.pinyinArea = choice(item.pinyinArea, ['four-lines', 'underline'] as const);
  if (item.englishLineGapMm !== undefined) result.englishLineGapMm = integer(item.englishLineGapMm, 3, 3) as 3;
  for (const key of ['showNameBlank', 'showDateBlank', 'showClassBlank'] as const) if (item[key] !== undefined) result[key] = boolean(item[key]);
  return result;
}

/** 校验完整草稿并返回独立副本；兼容无位置字段的已存 v2 校对记录。 */
export function validateStoredDraft(subject: StoredDraftV2['subject'], value: unknown): StoredDraftV2 {
  if (containsForbiddenPayload(value)) throw new WorksheetDraftError();
  const draft = record(value);
  if (draft.schemaVersion !== 2 || draft.subject !== subject) throw new WorksheetDraftError();
  const rawText = string(draft.rawText);
  if (rawTextBytes(rawText) > INPUT_LIMITS.rawTextBytes) throw new WorksheetDraftError();
  const parsePolicy = choice(draft.parsePolicy, ['whitespace', 'one-per-line']);
  const sources = array(draft.sources).map(value => {
    const item = record(value);
    return { sourceId: string(item.sourceId, 512), kind: choice(item.kind, ['custom', 'textbook', 'image', 'legacy']), editionId: nullableString(item.editionId, 512), contentVersion: nullableString(item.contentVersion, 512), label: string(item.label, 2048) } satisfies StoredSourceSummary;
  });
  const corrections = array(draft.corrections).map(value => {
    const item = record(value);
    const result: StoredTokenCorrection = {
      textFingerprint: string(item.textFingerprint),
      pinyin: array(item.pinyin).map(value => { const segment = record(value); return { grapheme: string(segment.grapheme), syllable: string(segment.syllable) }; }),
      pinyinState: choice(item.pinyinState, ['missing', 'suggested', 'confirmed']),
      meaning: nullableString(item.meaning), meaningState: choice(item.meaningState, ['missing', 'suggested', 'confirmed'])
    };
    if (item.tokenIndex !== undefined) result.tokenIndex = integer(item.tokenIndex, 0, INPUT_LIMITS.maxTokens - 1);
    if (item.occurrence !== undefined) result.occurrence = integer(item.occurrence, 0, INPUT_LIMITS.maxTokens - 1);
    if (item.englishText !== undefined) result.englishText = nullableString(item.englishText);
    if (item.chineseText !== undefined) result.chineseText = string(item.chineseText);
    if (item.englishState !== undefined) result.englishState = choice(item.englishState, ['missing', 'suggested', 'confirmed'] as const);
    if (item.automaticInitialized !== undefined) result.automaticInitialized = boolean(item.automaticInitialized);
    if (item.automaticFields !== undefined) {
      const fields = record(item.automaticFields);
      result.automaticFields = {};
      // 自动来源是可选的新字段，旧草稿保持兼容；只接纳三种明确的补全目标。
      for (const target of ['pinyin', 'meaning', 'english'] as const) {
        if (fields[target] === undefined) continue;
        const generated = record(fields[target]);
        result.automaticFields[target] = { source: choice(generated.source, ['text', 'meaning', 'english']),
          sourceText: string(generated.sourceText), value: string(generated.value) };
      }
    }
    if (item.sourceRefs !== undefined) result.sourceRefs = array(item.sourceRefs, 100).map(sourceRef);
    if (item.senseId !== undefined) result.senseId = nullableString(item.senseId, 512);
    if (item.acceptableAnswers !== undefined) result.acceptableAnswers = array(item.acceptableAnswers, 200).map(value => string(value));
    if (item.issues !== undefined) result.issues = array(item.issues, 100).map(value => {
      const issue = record(value);
      return { code: string(issue.code, 256), field: choice(issue.field, ['text', 'pinyin', 'meaning', 'document']), severity: choice(issue.severity, ['error', 'review']) };
    });
    return result;
  });
  const selected = record(draft.selection);
  const selection: StoredDraftV2['selection'] = {
    grade: selected.grade === null ? null : integer(selected.grade, 1, 6), semester: choice(selected.semester, ['first', 'second']),
    editionId: nullableString(selected.editionId, 512), selectedIds: array(selected.selectedIds).map(value => string(value, 512)),
    strategy: choice(selected.strategy, ['all', 'random', 'balanced', 'random-count', 'balanced-unit']),
    ordering: choice(selected.ordering, ['textbook', 'manual', 'input', 'random']),
    requestedCount: selected.requestedCount === null ? null : integer(selected.requestedCount, 1, 200), seed: nullableString(selected.seed, 512)
  };
  const savedAt = string(draft.savedAt, 64);
  if (!Number.isFinite(Date.parse(savedAt))) throw new WorksheetDraftError();
  const result: StoredDraftV2 = { schemaVersion: 2, subject, rawText, parsePolicy, sources, corrections, processing: processing(draft.processing, subject), title: string(draft.title, 512), layout: layout(draft.layout), selection, migratedFromV1: boolean(draft.migratedFromV1), savedAt };
  // 位置与文本必须同时匹配；错误索引、重复绑定均拒绝整份恢复。
  const parsed = tokenize(rawText, parsePolicy);
  const occupied = new Set<number>();
  const occurrences = new Map<string, number[]>();
  parsed.spans.forEach((span, index) => occurrences.set(span.text, [...(occurrences.get(span.text) ?? []), index]));
  if (!parsed.blocked) for (const correction of corrections) {
    const candidates = occurrences.get(correction.textFingerprint) ?? [];
    if (!candidates.length) throw new WorksheetDraftError();
    const index = correction.tokenIndex ?? (correction.occurrence === undefined ? undefined : candidates[correction.occurrence]);
    if (index !== undefined) {
      if (!candidates.includes(index) || occupied.has(index) || (correction.occurrence !== undefined && candidates[correction.occurrence] !== index)) throw new WorksheetDraftError();
      occupied.add(index);
    } else if (correction.occurrence !== undefined) throw new WorksheetDraftError();
  }
  return result;
}

/**
 * 保存 v2 草稿。
 *
 * @param storage 存储实现
 * @param subject 学科
 * @param payload 草稿负载
 * @returns 保存结果；失败时调用方应提示「当前编辑仍在」而不是丢弃原文
 */
export function saveDraftV2(
  storage: DraftStorage,
  subject: 'chinese' | 'english',
  payload: Omit<StoredDraftV2, 'schemaVersion' | 'savedAt'>
): SaveOutcome {
  const draft: StoredDraftV2 = {
    ...payload,
    schemaVersion: 2,
    savedAt: new Date().toISOString()
  };
  if (containsForbiddenPayload(draft)) return { ok: false, notice: 'draftNotSerializable' };
  let serialized: string;
  try {
    serialized = JSON.stringify(validateStoredDraft(subject, draft));
    if (serialized.length > MAX_DRAFT_LENGTH) return { ok: false, notice: 'draftNotSerializable' };
  } catch { return { ok: false, notice: 'draftNotSerializable' }; }
  try {
    storage.setItem(draftKeyV2(subject), serialized);
    return { ok: true, notice: 'draftSaved' };
  } catch {
    // 容量不足或已禁用存储：保留当前编辑，不丢原文。
    return { ok: false, notice: 'draftSaveFailed' };
  }
}

/**
 * 读取 v2 草稿。只接受合法的 v2；不做静默迁移。
 *
 * @param storage 存储实现
 * @param subject 学科
 * @returns 读取结果
 */
export function loadDraftV2(storage: DraftStorage, subject: 'chinese' | 'english'): LoadOutcome {
  let raw: string | null = null;
  try {
    raw = storage.getItem(draftKeyV2(subject));
  } catch {
    return { status: 'empty' };
  }
  if (!raw) return { status: 'empty' };
  if (raw.length > MAX_DRAFT_LENGTH) return { status: 'invalid' };
  try {
    const payload = validateStoredDraft(subject, JSON.parse(raw));
    return { status: 'ok', payload };
  } catch {
    return { status: 'invalid' };
  }
}

/** v1 草稿中与本轮改造相关的字段。 */
export interface StoredDraftV1 {
  schemaVersion: 1;
  mode: string | null;
  title: string;
  repetitions: number;
  layout: StoredDraftLayout;
  selectedIds: string[];
  /** 同时兼容早期 WordEntry 与实际保存的 CurriculumEntry 字段。 */
  customEntries: Array<{
    text: string;
    id?: string;
    entryId?: string;
    pinyin?: string;
    pinyinDisplay?: string | null;
    pinyinSegments?: Array<{ grapheme: string; syllable: string }>;
    meaning: string | null;
    senseId?: string;
    acceptableAnswers?: string[];
    unit?: number;
    kind?: string;
  }>;
  strategy: string;
  ordering: string;
  requestedCount: number | null;
  seed: string | null;
  grade: number | null;
  semester: string;
  editionId: string | null;
}

/** v1 迁移结果。 */
export interface MigrationOutcome {
  status: 'ok' | 'none' | 'invalid';
  /** 由旧词条生成的等价规范化原文。 */
  rawText: string;
  /** 迁移提示；契约要求明确说明原始输入格式没有被保存。 */
  notice: string | null;
  /** 不能离线编造的教材部分：只有选择信息，没有条目正文。 */
  requiresOnlineSelection: boolean;
  payload: StoredDraftV2 | null;
}

/**
 * 从 v1 草稿迁移。
 *
 * v1 没有保存 rawText，因此只能从 customEntries 生成**等价规范化文本**，
 * 并明确告知用户原始空格与引号没有被保存；教材部分只保存了选择，
 * 必须联网按原版本重新取回，不得在本地编造条目正文。
 *
 * @param storage 存储实现
 * @param subject 学科
 * @returns 迁移结果
 */
export function migrateFromV1(storage: DraftStorage, subject: 'chinese' | 'english'): MigrationOutcome {
  let raw: string | null = null;
  try {
    raw = storage.getItem(draftKeyV1(subject));
  } catch {
    return { status: 'none', rawText: '', notice: null, requiresOnlineSelection: false, payload: null };
  }
  if (!raw) return { status: 'none', rawText: '', notice: null, requiresOnlineSelection: false, payload: null };

  let draft: RecordValue;
  try {
    if (raw.length > MAX_DRAFT_LENGTH) throw new WorksheetDraftError();
    draft = record(JSON.parse(raw));
  } catch {
    // 迁移失败只影响这一份草稿，绝不清理其它 localStorage 键。
    return { status: 'invalid', rawText: '', notice: 'draftMigrationFailed', requiresOnlineSelection: false, payload: null };
  }
  if (draft.schemaVersion !== 1) {
    return { status: 'invalid', rawText: '', notice: 'draftMigrationFailed', requiresOnlineSelection: false, payload: null };
  }

  try {
    const entries = array(draft.customEntries === undefined ? [] : draft.customEntries).map(record);
    const texts = entries.map(entry => string(entry.text).normalize('NFC'));
    const rawText = serializeTokens(texts, 'whitespace');
    const parsed = tokenize(rawText, 'whitespace');
    if (parsed.blocked || parsed.spans.length !== entries.length) throw new WorksheetDraftError();
    const occurrences = new Map<string, number>();
    const corrections = entries.map((entry, tokenIndex): StoredTokenCorrection => {
      const text = parsed.spans[tokenIndex]!.text;
      const graphemes = [...new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(text)].map(part => part.segment);
      const display = entry.pinyinDisplay ?? entry.pinyin ?? '';
      const syllables = string(display).trim().split(/\s+/u).filter(Boolean);
      const pinyin = entry.pinyinSegments === undefined ? syllables.map((syllable, index) => ({ grapheme: graphemes[index] ?? '', syllable })) : array(entry.pinyinSegments).map(value => { const segment = record(value); return { grapheme: string(segment.grapheme), syllable: string(segment.syllable) }; });
      const complete = pinyin.length === graphemes.length && pinyin.every((segment, index) => segment.grapheme === graphemes[index] && segment.syllable.trim());
      const meaning = entry.meaning === undefined || entry.meaning === null ? null : string(entry.meaning).trim() || null;
      const occurrence = occurrences.get(text) ?? 0;
      occurrences.set(text, occurrence + 1);
      return {
        tokenIndex, occurrence, textFingerprint: text,
        pinyin, pinyinState: pinyin.length ? complete ? 'confirmed' : 'suggested' : 'missing',
        meaning, meaningState: meaning ? 'confirmed' : 'missing',
        senseId: entry.senseId === undefined ? null : nullableString(entry.senseId, 512),
        acceptableAnswers: entry.acceptableAnswers === undefined ? [] : array(entry.acceptableAnswers, 200).map(value => string(value)),
        sourceRefs: [{ sourceId: 'legacy-v1', kind: 'legacy', editionId: null, contentVersion: null, entryId: entry.entryId === undefined && entry.id === undefined ? null : string(entry.entryId ?? entry.id, 512), unitId: null, imageId: null, localOverride: true }]
      };
    });
    const payload = validateStoredDraft(subject, {
      schemaVersion: 2, subject, savedAt: new Date().toISOString(), rawText, parsePolicy: 'whitespace',
      sources: entries.length ? [{ sourceId: 'legacy-v1', kind: 'legacy', editionId: null, contentVersion: null, label: 'v1' }] : [],
      corrections, title: draft.title === undefined ? '' : draft.title, layout: draft.layout === undefined ? { gridMm: 12 } : draft.layout, migratedFromV1: true,
      processing: {
        mode: draft.mode ?? null, chineseUnit: 'word', duplicatePolicy: 'merge-equivalent',
        ordering: draft.ordering === undefined ? 'textbook' : draft.ordering, selection: draft.strategy === undefined ? 'all' : draft.strategy,
        requestedCount: draft.requestedCount ?? null, seed: draft.seed ?? null, repetitions: draft.repetitions === undefined ? 1 : draft.repetitions
      },
      selection: {
        grade: draft.grade ?? null, semester: draft.semester === undefined ? 'first' : draft.semester, editionId: draft.editionId ?? null,
        selectedIds: draft.selectedIds === undefined ? [] : draft.selectedIds, strategy: draft.strategy === undefined ? 'all' : draft.strategy, ordering: draft.ordering === undefined ? 'textbook' : draft.ordering,
        requestedCount: draft.requestedCount ?? null, seed: draft.seed ?? null
      }
    });
    return { status: 'ok', rawText, notice: 'draftMigratedFromV1', requiresOnlineSelection: payload.selection.selectedIds.length > 0, payload };
  } catch {
    // 旧键只读；不完整或类型错误的迁移不会写入 v2 或清理其它键。
    return { status: 'invalid', rawText: '', notice: 'draftMigrationFailed', requiresOnlineSelection: false, payload: null };
  }
}

/**
 * 迁移后写回 v2。只有用户显式保存时才调用，v1 键保留不动。
 *
 * @param storage 存储实现
 * @param subject 学科
 * @param payload 迁移得到的内容
 * @returns 保存结果
 */
export function commitMigration(
  storage: DraftStorage,
  subject: 'chinese' | 'english',
  payload: Partial<StoredDraftV2>
): SaveOutcome {
  const complete = {
    subject,
    rawText: payload.rawText ?? '',
    parsePolicy: payload.parsePolicy ?? 'whitespace',
    sources: payload.sources ?? [],
    corrections: payload.corrections ?? [],
    processing: payload.processing ?? {
      mode: null, chineseUnit: 'word', duplicatePolicy: 'merge-equivalent',
      ordering: 'input', selection: 'all', requestedCount: null, seed: null, repetitions: 1
    },
    title: payload.title ?? '',
    layout: payload.layout ?? { gridMm: 12 },
    selection: payload.selection ?? {
      grade: null, semester: 'first', editionId: null, selectedIds: [],
      strategy: 'all', ordering: 'textbook', requestedCount: null, seed: null
    },
    migratedFromV1: payload.migratedFromV1 ?? true
  } satisfies Omit<StoredDraftV2, 'schemaVersion' | 'savedAt'>;
  return saveDraftV2(storage, subject, complete);
}
