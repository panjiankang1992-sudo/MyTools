import type { CurriculumEntry, ReviewStatus, Subject } from './curriculum';
import { stableHash } from './hash';
import type { EntryOrdering, SelectionResult, SelectionStrategy } from './selection';
import type { WorksheetMode } from '../types';

/**
 * 题单快照：内容身份与排版版本分离。
 * 纯版式修改只增加 revision 并改变 renderHash，不产生新的成功使用计数。
 */

/** 快照中的条目来源。 */
export type WorksheetEntryOrigin = 'textbook' | 'custom' | 'override';

/** 快照条目：保留来源引用，自定义条目不伪造教材审核状态。 */
export type WorksheetEntry = Omit<CurriculumEntry, 'editionId' | 'unitId' | 'reviewStatus'> & {
  origin: WorksheetEntryOrigin;
  editionId: string | null;
  unitId: string | null;
  reviewStatus: ReviewStatus | 'local-validated';
  sourceEntryRef: {
    editionId: string;
    contentVersion: string;
    entryId: string;
    entryVersion: string;
  } | null;
};

/** 纸面参数。 */
export interface WorksheetLayout {
  paper: 'A4';
  orientation: 'portrait';
  marginMm: 15;
  gridMm: 10 | 12 | 14;
  /** 用户是否手动指定格距；已指定后不再随年级变化覆盖。 */
  gridMmUserSet: boolean;
  chineseGrid: 'tian' | 'square';
  pinyinArea: 'four-lines' | 'underline';
  englishLineGapMm: 3;
  showNameBlank: boolean;
  showDateBlank: boolean;
  showClassBlank: boolean;
}

/** 题单快照。 */
export interface WorksheetSnapshot {
  /** 快照结构版本；v2 起记录原文身份与处理策略。 */
  schemaVersion: 2;
  worksheetId: string;
  operationId: string;
  revision: number;
  /** 原文文档标识与修订号；原文正文本身不进入学生 PDF。 */
  inputDocumentId: string;
  inputRevision: number;
  parsePolicy: 'whitespace' | 'one-per-line';
  /** 逐字或按词；影响题目集合，因此进入内容指纹。 */
  chineseUnit: 'word' | 'character';
  duplicatePolicy: 'merge-equivalent' | 'keep-occurrences';
  subject: Subject;
  mode: WorksheetMode;
  contentRefs: ReadonlyArray<{ editionId: string; contentVersion: string; sha256: string }>;
  orderedEntries: ReadonlyArray<WorksheetEntry>;
  seed: string | null;
  selectionAlgorithm: string;
  selectionStrategy: SelectionStrategy;
  ordering: EntryOrdering;
  repetitions: number;
  title: string;
  layout: WorksheetLayout;
  fontVersion: string;
  rendererVersion: string;
  contentHash: string;
  renderHash: string;
}

/** 由同一个快照生成的 PDF 产物。 */
export interface PdfArtifactMeta {
  kind: 'student' | 'answer';
  worksheetId: string;
  revision: number;
  /** 生成代次，用于丢弃迟到结果。 */
  generationId: number;
  inputRevision: number;
  renderRevision: number;
  /** 冻结快照的内容指纹，用于判断产物是否仍对应当前草稿。 */
  snapshotHash: string;
  pageCount: number;
  layoutHash: string;
  filename: string;
  sha256: string;
}

/** 渲染器版本，排版算法变化时必须递增。 */
export const RENDERER_VERSION = 'worksheet-renderer-v2';

/** 字体版本，随内置字体子集策略变化递增。 */
export const FONT_VERSION = 'noto-sans-cjk-sc-regular-1';

/** 标题允许的最大 Unicode 码点数，超出时在输入侧提示而非静默截断。 */
export const TITLE_MAX_CODE_POINTS = 40;

/** 单次选择条目上限。 */
export const MAX_SELECTED_ENTRIES = 200;

/** 展开后题目上限。 */
export const MAX_EXPANDED_QUESTIONS = 500;

/** 创建空纸面参数，格距按年级给出默认值。 */
export function createLayout(grade: number, gridMmUserSet = false): WorksheetLayout {
  return {
    paper: 'A4',
    orientation: 'portrait',
    marginMm: 15,
    gridMm: defaultGridMm(grade),
    gridMmUserSet,
    chineseGrid: 'tian',
    pinyinArea: 'four-lines',
    englishLineGapMm: 3,
    showNameBlank: true,
    showDateBlank: true,
    showClassBlank: false
  };
}

/** 首次或未指定格距时，1—2 年级 14 mm，其余 12 mm。 */
export function defaultGridMm(grade: number): 10 | 12 | 14 {
  return grade <= 2 ? 14 : 12;
}

/** 将选题结果转换为快照条目，正式条目保留来源引用，自定义条目标记本地校验。 */
export function toWorksheetEntries(entries: readonly CurriculumEntry[], edition: { editionId: string; contentVersion: string; contentSha256: string } | null): WorksheetEntry[] {
  return entries.map(entry => {
    const { editionId, unitId, reviewStatus, ...rest } = entry;
    return {
      ...rest,
      origin: 'textbook' as const,
      editionId,
      unitId,
      reviewStatus,
      sourceEntryRef: edition
        ? { editionId: edition.editionId, contentVersion: edition.contentVersion, entryId: entry.entryId, entryVersion: entry.entryVersion }
        : null
    };
  });
}

/** 构造自定义条目，不写入教材来源。 */
export function toCustomWorksheetEntry(entry: {
  entryId: string;
  text: string;
  pinyinDisplay: string | null;
  meaning: string | null;
  form: WorksheetEntry['form'];
  listTypes: WorksheetEntry['listTypes'];
  order: number;
}): WorksheetEntry {
  return {
    entryId: entry.entryId,
    entryVersion: 'custom-1',
    lessonId: null,
    order: entry.order,
    form: entry.form,
    listTypes: entry.listTypes,
    text: entry.text,
    pinyinDisplay: entry.pinyinDisplay,
    pinyinSegments: [],
    meaning: entry.meaning,
    partOfSpeech: null,
    senseId: `${entry.entryId}:custom`,
    context: null,
    promptContext: null,
    acceptableAnswers: [],
    sourceRefs: [],
    blockingIssues: [],
    origin: 'custom',
    editionId: null,
    unitId: null,
    reviewStatus: 'local-validated',
    sourceEntryRef: null
  };
}

/** 计算内容指纹：包含题目身份、顺序、模式与重复，不含标题和字号。 */
export function computeContentHash(snapshot: Pick<WorksheetSnapshot, 'mode' | 'repetitions' | 'ordering' | 'selectionAlgorithm' | 'seed' | 'orderedEntries' | 'parsePolicy' | 'chineseUnit' | 'duplicatePolicy'>): string {
  const identity = snapshot.orderedEntries.map(entry => [
    entry.entryId,
    entry.text,
    entry.pinyinDisplay ?? '',
    entry.meaning ?? '',
    entry.senseId,
    entry.sourceEntryRef?.entryVersion ?? 'custom'
  ]);
  return stableHash(JSON.stringify({
    mode: snapshot.mode,
    repetitions: snapshot.repetitions,
    ordering: snapshot.ordering,
    selectionAlgorithm: snapshot.selectionAlgorithm,
    seed: snapshot.seed,
    parsePolicy: snapshot.parsePolicy,
    chineseUnit: snapshot.chineseUnit,
    duplicatePolicy: snapshot.duplicatePolicy,
    identity
  }));
}

/** 计算排版指纹：包含纸面参数与字体、渲染器版本。 */
export function computeRenderHash(
  snapshot: Pick<WorksheetSnapshot, 'title' | 'layout' | 'fontVersion' | 'rendererVersion' | 'orderedEntries'> & { contentHash: string }
): string {
  return stableHash(JSON.stringify({
    // 必须包含内容指纹：只 hash 条数会让同数量不同内容得到同一排版指纹。
    contentHash: snapshot.contentHash,
    title: snapshot.title,
    layout: snapshot.layout,
    fontVersion: snapshot.fontVersion,
    rendererVersion: snapshot.rendererVersion,
    count: snapshot.orderedEntries.length
  }));
}

/** 校验标题长度，返回错误码而不是静默截断。 */
export function validateTitle(title: string): 'ok' | 'titleTooLong' {
  return Array.from(title.normalize('NFC')).length > TITLE_MAX_CODE_POINTS ? 'titleTooLong' : 'ok';
}

/** 校验一次生成的规模上限。 */
export function validateScale(entryCount: number, repetitions: number): 'ok' | 'tooManyEntries' | 'tooManyQuestions' | 'invalidRepetitions' {
  if (!Number.isInteger(repetitions) || repetitions < 1 || repetitions > 5) return 'invalidRepetitions';
  if (entryCount > MAX_SELECTED_ENTRIES) return 'tooManyEntries';
  if (entryCount * repetitions > MAX_EXPANDED_QUESTIONS) return 'tooManyQuestions';
  return 'ok';
}

/** 组装不可变快照，revision 由调用方按内容/版式变化递增。 */
export function createSnapshot(input: {
  worksheetId: string;
  operationId: string;
  revision: number;
  subject: Subject;
  mode: WorksheetMode;
  entries: readonly WorksheetEntry[];
  selection: SelectionResult;
  selectionStrategy: SelectionStrategy;
  ordering: EntryOrdering;
  repetitions: number;
  title: string;
  layout: WorksheetLayout;
  contentRefs: Array<{ editionId: string; contentVersion: string; sha256: string }>;
  inputDocumentId: string;
  inputRevision: number;
  parsePolicy: 'whitespace' | 'one-per-line';
  chineseUnit: 'word' | 'character';
  duplicatePolicy: 'merge-equivalent' | 'keep-occurrences';
}): WorksheetSnapshot {
  const base = {
    schemaVersion: 2 as const,
    worksheetId: input.worksheetId,
    operationId: input.operationId,
    revision: input.revision,
    inputDocumentId: input.inputDocumentId,
    inputRevision: input.inputRevision,
    parsePolicy: input.parsePolicy,
    chineseUnit: input.chineseUnit,
    duplicatePolicy: input.duplicatePolicy,
    subject: input.subject,
    mode: input.mode,
    contentRefs: Object.freeze(input.contentRefs.map(ref => Object.freeze({ ...ref }))),
    // 深拷贝后只读冻结：渲染只接受快照投影，不读页面上的可变对象。
    orderedEntries: Object.freeze(input.entries.map(entry => deepFreezeEntry(entry))),
    seed: input.selection.seed,
    selectionAlgorithm: input.selection.algorithm,
    selectionStrategy: input.selectionStrategy,
    ordering: input.ordering,
    repetitions: input.repetitions,
    title: input.title,
    layout: Object.freeze({ ...input.layout }),
    fontVersion: FONT_VERSION,
    rendererVersion: RENDERER_VERSION
  };
  const contentHash = computeContentHash(base);
  return Object.freeze({
    ...base,
    contentHash,
    renderHash: computeRenderHash({ ...base, contentHash })
  });
}

/**
 * 深拷贝并冻结一个条目，包括嵌套的拼音片段、来源引用与可接受答案。
 * 只做浅冻结会让页面上的响应式对象仍能改到快照内部。
 */
function deepFreezeEntry(entry: WorksheetEntry): WorksheetEntry {
  return Object.freeze({
    ...entry,
    pinyinSegments: Object.freeze((entry.pinyinSegments ?? []).map(segment => Object.freeze({ ...segment }))),
    acceptableAnswers: Object.freeze([...(entry.acceptableAnswers ?? [])]),
    sourceRefs: Object.freeze((entry.sourceRefs ?? []).map(ref => Object.freeze({ ...ref }))),
    listTypes: Object.freeze([...(entry.listTypes ?? [])])
  }) as WorksheetEntry;
}
