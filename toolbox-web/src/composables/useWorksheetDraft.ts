import { computed, ref, watch, type Ref, type UnwrapNestedRefs } from 'vue';
import type { CurriculumEntry, ListType, Semester, Subject, TextbookCatalog, TextbookVersion } from '../domain/curriculum';
import { compareEntries, gradesForSubject, isUsableEdition } from '../domain/curriculum';
import { selectForWorksheet, type EntryOrdering, type SelectionResult, type SelectionStrategy } from '../domain/selection';
import {
  TITLE_MAX_CODE_POINTS, createLayout, createSnapshot, defaultGridMm,
  validateScale, type WorksheetEntry, type WorksheetLayout
} from '../domain/worksheet-snapshot';
import { sha256Hex } from '../domain/hash';
import { fetchTextbookCatalog, fetchTextbookVersion, loadAllEntries } from '../services/curriculum-api';
import { SAMPLE_CONTENT_VERSION, SAMPLE_EDITION_ID, sampleCurriculumEntries, sampleUnits } from '../data/sample-entries';
import { findWorksheetTool } from '../config/tools';
import { generateWorksheet } from '../lib/worksheet';
import { parseEntryRows } from '../lib/csv';
import { newOperationId, recordPrintRequest, recordWorksheetSuccess } from '../lib/analytics';
import type { ToolCategory, WordEntry, WorksheetArtifact, WorksheetMode, WorksheetOptions } from '../types';

/**
 * 字帖/单词工作台的状态与流程。
 * 负责教材选择、确定性选题、题单快照、排版输出与打印，界面层只负责展示。
 */

/** 带完整性信息的渲染产物。 */
export interface RenderedArtifact extends WorksheetArtifact {
  sha256: string;
  layoutHash: string;
  revision: number;
}

/** 内容来源。 */
export type ContentSource = 'textbook' | 'sample' | 'custom';

/** 本机草稿结构，只保存配置与本地自定义词条，不含学生信息。 */
interface StoredDraft {
  schemaVersion: 1;
  grade: number | null;
  semester: Semester;
  editionId: string | null;
  mode: WorksheetMode;
  title: string;
  repetitions: number;
  layout: WorksheetLayout;
  selectedIds: string[];
  customEntries: CurriculumEntry[];
  strategy: SelectionStrategy;
  ordering: EntryOrdering;
  requestedCount: number;
  seed: string | null;
}

const DRAFT_PREFIX = 'yuyutian-toolbox:draft:v1:';
const CUSTOM_UNIT_ID = 'custom-unit';
const CUSTOM_EDITION_ID = 'custom';

/** 生成一个随机种子。 */
function randomSeed(): string {
  const buffer = new Uint32Array(1);
  if (globalThis.crypto?.getRandomValues) globalThis.crypto.getRandomValues(buffer);
  else buffer[0] = Math.floor(Math.random() * 0xffffffff);
  return buffer[0]!.toString(36);
}

/** 把教材条目转换为排版引擎使用的旧结构，答案仍由引擎按模式隔离。 */
function toWordEntry(entry: WorksheetEntry): WordEntry {
  return {
    id: entry.entryId,
    text: entry.text,
    pinyin: entry.pinyinDisplay ?? '',
    meaning: entry.meaning ?? '',
    unit: 0,
    kind: entry.form
  };
}

/** 为自定义文本构造条目，拼音逐字对齐失败时保留整体拼音。 */
function buildCustomEntry(text: string, second: string, subject: Subject, index: number): CurriculumEntry {
  const graphemes = Array.from(text);
  const syllables = subject === 'chinese' ? second.split(/\s+/u).filter(Boolean) : [];
  return {
    entryId: `custom:${newOperationId()}`,
    entryVersion: 'custom-1',
    editionId: CUSTOM_EDITION_ID,
    unitId: CUSTOM_UNIT_ID,
    lessonId: null,
    order: index,
    form: graphemes.length === 1 ? 'character' : subject === 'english' && text.includes(' ') ? 'phrase' : 'word',
    listTypes: ['vocabulary'] as ListType[],
    text,
    pinyinDisplay: subject === 'chinese' ? second : null,
    pinyinSegments: syllables.length === graphemes.length ? graphemes.map((grapheme, position) => ({ grapheme, syllable: syllables[position]! })) : [],
    meaning: subject === 'english' ? second : null,
    partOfSpeech: null,
    senseId: `custom-${index}:local`,
    context: null,
    promptContext: null,
    acceptableAnswers: [],
    sourceRefs: [],
    reviewStatus: 'draft',
    blockingIssues: []
  };
}

/** 创建工作台状态。 */
export function useWorksheetDraft(subject: Ref<Subject>) {
  const catalog = ref<TextbookCatalog | null>(null);
  const catalogState = ref<'idle' | 'loading' | 'ready' | 'unavailable'>('idle');
  const catalogError = ref('');

  const grade = ref<number | null>(null);
  const semester = ref<Semester>('first');
  const editionId = ref<string | null>(null);
  const version = ref<TextbookVersion | null>(null);
  const versionState = ref<'idle' | 'loading' | 'ready' | 'unavailable'>('idle');
  const versionError = ref('');
  const unitIds = ref<string[]>([]);
  const lessonIds = ref<string[]>([]);
  const listTypes = ref<ListType[]>([]);

  const scopeEntries = ref<CurriculumEntry[]>([]);
  const scopeState = ref<'idle' | 'loading' | 'ready' | 'unavailable'>('idle');
  const scopeError = ref('');

  const customEntries = ref<CurriculumEntry[]>([]);
  const selectedIds = ref<string[]>([]);

  const strategy = ref<SelectionStrategy>('all');
  /** 是否已建立初始选题；用户主动清空后不再自动补选。 */
  const initialised = ref(false);
  /** 由设置区的「导入词条」按钮置位，词条面板据此直接打开输入弹窗。 */
  const importRequested = ref(false);
  const ordering = ref<EntryOrdering>('textbook');
  const requestedCount = ref(8);
  const seed = ref<string | null>(null);
  const lastPlan = ref<SelectionResult | null>(null);
  const previousSelection = ref<{ ids: string[]; seed: string | null; strategy: SelectionStrategy } | null>(null);

  const mode = ref<WorksheetMode>(subject.value === 'chinese' ? 'chinese-dictation' : 'english-dictation');
  const repetitions = ref(1);
  const title = ref('');
  const layout = ref<WorksheetLayout>(createLayout(2));
  const revision = ref(0);

  const studentArtifact = ref<RenderedArtifact | null>(null);
  const answerArtifact = ref<RenderedArtifact | null>(null);
  const busy = ref(false);
  const error = ref('');
  const draftNotice = ref('');

  const worksheetTool = computed(() => findWorksheetTool(subject.value === 'chinese' ? 'copybook' : 'words', mode.value));
  const category = computed<ToolCategory>(() => subject.value === 'chinese' ? 'copybook' : 'words');
  const toolId = computed(() => worksheetTool.value?.id ?? `worksheet.${mode.value}`);
  const contentSource = computed<ContentSource>(() => editionId.value && scopeEntries.value.length ? 'textbook' : customEntries.value.length && !scopeEntries.value.length ? 'custom' : 'sample');

  let worksheetIdentity: { worksheetId: string; operationId: string; contentHash: string } | null = null;
  let generationRevision = 0;
  let releaseTimer: ReturnType<typeof setTimeout> | undefined;

  const gradeOptions = computed(() => gradesForSubject(subject.value).map(value => ({ label: `${value}`, value })));

  /** 当前槽位已发布的版本。 */
  const editions = computed(() => {
    if (!catalog.value || grade.value === null) return [];
    return catalog.value.editions
      .filter(item => item.subject === subject.value && item.grade === grade.value && item.semester === semester.value)
      .slice()
      .sort((left, right) => left.editionId.localeCompare(right.editionId));
  });

  /** 当前槽位的覆盖状态，用于向用户显示真实可用范围。 */
  const coverage = computed(() => catalog.value?.coverage.find(slot => slot.subject === subject.value && slot.grade === grade.value && slot.semester === semester.value) ?? null);

  /** 教材候选条目与示例条目合并后的候选池。 */
  const candidates = computed<CurriculumEntry[]>(() => {
    const base = editionId.value && scopeEntries.value.length ? scopeEntries.value : sampleCurriculumEntries(subject.value);
    return [...base, ...customEntries.value];
  });

  const entryIndex = computed(() => new Map(candidates.value.map(entry => [entry.entryId, entry])));

  /** 当前实际选中的条目，按候选顺序稳定排序。 */
  const selectedEntries = computed<WorksheetEntry[]>(() => {
    const chosen = selectedIds.value.flatMap(id => {
      const entry = entryIndex.value.get(id);
      return entry ? [entry] : [];
    });
    return chosen.slice().sort(compareEntries).map(entry => {
      const isCustom = entry.editionId === CUSTOM_EDITION_ID;
      return {
        ...entry,
        editionId: isCustom ? null : entry.editionId,
        unitId: isCustom ? null : entry.unitId,
        origin: isCustom ? 'custom' as const : 'textbook' as const,
        reviewStatus: isCustom ? 'local-validated' as const : entry.reviewStatus,
        sourceEntryRef: isCustom || !version.value ? null : {
          editionId: version.value.edition.editionId,
          contentVersion: version.value.edition.contentVersion,
          entryId: entry.entryId,
          entryVersion: entry.entryVersion
        }
      };
    });
  });

  const questionCount = computed(() => selectedEntries.value.length * repetitions.value);
  const scaleState = computed(() => validateScale(selectedEntries.value.length, repetitions.value));
  const titleState = computed(() => (Array.from(title.value.trim().normalize('NFC')).length > TITLE_MAX_CODE_POINTS ? 'titleTooLong' as const : 'ok' as const));

  /** 可用的清单类别，只显示所选版本真实存在的类别。 */
  const availableListTypes = computed<ListType[]>(() => {
    if (version.value?.listTypes.length) return version.value.listTypes;
    return ['vocabulary'];
  });

  /** 范围树数据：教材单元/课文或示例单元。 */
  const unitTree = computed(() => {
    if (editionId.value && version.value) {
      const lessons = version.value.lessons.slice().sort((left, right) => left.order - right.order || left.lessonId.localeCompare(right.lessonId));
      return version.value.units.slice().sort((left, right) => left.order - right.order || left.unitId.localeCompare(right.unitId)).map(unit => ({
        key: unit.unitId,
        label: unit.title,
        children: lessons.filter(lesson => lesson.unitId === unit.unitId).map(lesson => ({ key: lesson.lessonId, label: lesson.title }))
      }));
    }
    return sampleUnits().map(unit => ({ key: unit.unitId, label: unit.title, children: [] as Array<{ key: string; label: string }> }));
  });

  /** 当前呈现的教材数据版本标识。 */
  const contentRef = computed(() => version.value
    ? { editionId: version.value.edition.editionId, contentVersion: version.value.edition.contentVersion, sha256: version.value.sha256 }
    : { editionId: SAMPLE_EDITION_ID, contentVersion: SAMPLE_CONTENT_VERSION, sha256: '' });

  /** 读取已发布教材目录；不可用时保留示例与自定义内容。 */
  async function loadCatalog(): Promise<void> {
    catalogState.value = 'loading';
    try {
      catalog.value = await fetchTextbookCatalog({ subject: subject.value });
      catalogState.value = 'ready';
    } catch (cause) {
      catalog.value = null;
      catalogError.value = cause instanceof Error ? cause.message : 'request.failed';
      catalogState.value = 'unavailable';
    }
  }

  /** 读取所选版本内容，同时加载全部分页条目。 */
  async function loadVersion(): Promise<void> {
    const currentEdition = editions.value.find(item => item.editionId === editionId.value);
    if (!currentEdition || !isUsableEdition(currentEdition)) {
      version.value = null;
      scopeEntries.value = [];
      versionState.value = 'idle';
      scopeState.value = 'idle';
      return;
    }
    versionState.value = 'loading';
    scopeState.value = 'loading';
    try {
      const info = await fetchTextbookVersion(currentEdition.editionId, currentEdition.contentVersion);
      if (version.value?.edition.editionId !== info.edition.editionId || version.value?.edition.contentVersion !== info.edition.contentVersion) {
        // 切换书册时重置教材选择，避免不同册内容悄悄混用。
        unitIds.value = [];
        lessonIds.value = [];
      }
      version.value = info;
      versionState.value = 'ready';
      const page = await loadAllEntries(info.edition.editionId, info.edition.contentVersion, {
        unitIds: unitIds.value,
        lessonIds: lessonIds.value,
        listTypes: listTypes.value
      });
      scopeEntries.value = page.entries;
      scopeState.value = 'ready';
      // 范围（单元/课文/类别）或书册变化后必须按当前策略重新选题，
      // 否则候选集已更新而选中集合仍是旧的，界面会出现「已选 0 项」。
      applyStrategy(strategy.value, seed.value);
    } catch (cause) {
      version.value = null;
      scopeEntries.value = [];
      versionError.value = cause instanceof Error ? cause.message : 'request.failed';
      versionState.value = 'unavailable';
      scopeState.value = 'unavailable';
    }
  }

  /** 应用选题策略：全部、随机 N 或按单元均衡 N。 */
  function applyStrategy(next: SelectionStrategy = strategy.value, nextSeed: string | null = null): void {
    const pool = candidates.value;
    const needsSeed = next !== 'all';
    const useSeed = needsSeed ? (nextSeed ?? seed.value ?? randomSeed()) : null;
    seed.value = useSeed;
    strategy.value = next;
    const plan = selectForWorksheet({
      candidates: pool,
      strategy: next,
      requestedCount: requestedCount.value,
      seed: useSeed,
      ordering: ordering.value,
      unitIds: editionId.value ? unitIds.value : undefined,
      lessonIds: editionId.value ? lessonIds.value : undefined,
      listTypes: listTypes.value.length ? listTypes.value : undefined
    }, { needsPinyin: subject.value === 'chinese', needsMeaning: subject.value === 'english' });
    lastPlan.value = plan;
    selectedIds.value = plan.entries.map(entry => entry.entryId);
  }

  /** 换一组：生成新的种子并重新抽取。 */
  function regroup(): void {
    previousSelection.value = { ids: [...selectedIds.value], seed: seed.value, strategy: strategy.value };
    applyStrategy(strategy.value === 'all' ? 'random' : strategy.value, randomSeed());
  }

  /** 恢复上一组：完整恢复词条顺序与种子。 */
  function restoreGroup(): void {
    const previous = previousSelection.value;
    if (!previous) return;
    selectedIds.value = [...previous.ids];
    seed.value = previous.seed;
    strategy.value = previous.strategy;
    previousSelection.value = null;
  }

  /** 按当前策略重新计算，用于范围或类别变化后同步选择。 */
  function refreshSelection(): void {
    applyStrategy(strategy.value, seed.value);
  }

  /** 导入自定义词条；解析失败时返回可定位的错误码。 */
  function importCustom(value: string): 'ok' | 'invalidImport' | 'tooManyEntries' {
    const parsed = parseEntryRows(value);
    if (parsed.unterminatedQuote || parsed.invalidLine !== null || !parsed.pairs.length) return 'invalidImport';
    if (customEntries.value.length + parsed.pairs.length > 200) return 'tooManyEntries';
    const base = customEntries.value.length;
    const added = parsed.pairs.map((pair, index) => buildCustomEntry(pair[0], pair[1], subject.value, base + index));
    customEntries.value = [...customEntries.value, ...added];
    // 导入只追加新选项，不改动筛选条件，也不复活已取消的条目。
    selectedIds.value = [...selectedIds.value, ...added.map(entry => entry.entryId)];
    return 'ok';
  }

  /** 更新自定义条目内容。 */
  function updateCustom(entryId: string, patch: { text?: string; second?: string }): 'ok' | 'missingFields' {
    const target = customEntries.value.find(entry => entry.entryId === entryId);
    if (!target) return 'missingFields';
    const text = (patch.text ?? target.text).normalize('NFC').trim();
    const second = (patch.second ?? (subject.value === 'chinese' ? target.pinyinDisplay : target.meaning) ?? '').normalize('NFC').trim();
    if (!text || !second) return 'missingFields';
    const rebuilt = buildCustomEntry(text, second, subject.value, target.order);
    customEntries.value = customEntries.value.map(entry => entry.entryId === entryId ? { ...rebuilt, entryId } : entry);
    return 'ok';
  }

  /** 删除自定义条目。 */
  function removeCustom(entryId: string): void {
    customEntries.value = customEntries.value.filter(entry => entry.entryId !== entryId);
    selectedIds.value = selectedIds.value.filter(id => id !== entryId);
  }

  /** 勾选或取消单个条目。 */
  function toggleEntry(entryId: string, checked: boolean): void {
    selectedIds.value = checked
      ? [...new Set([...selectedIds.value, entryId])]
      : selectedIds.value.filter(id => id !== entryId);
    lastPlan.value = null;
  }

  /** 全选当前候选池。 */
  function selectAll(): void {
    selectedIds.value = candidates.value.map(entry => entry.entryId);
    lastPlan.value = null;
  }

  /** 清空选择。 */
  function clearSelection(): void {
    selectedIds.value = [];
    lastPlan.value = null;
  }

  /** 释放旧的产物地址与 PDF 文档句柄。 */
  function releaseArtifact(artifact: RenderedArtifact | null): void {
    if (!artifact) return;
    URL.revokeObjectURL(artifact.url);
  }

  /** 引擎错误码映射为可展示键。 */
  function errorKey(cause: unknown): string {
    const code = cause instanceof Error ? cause.message : '';
    if (code.includes('FONT_UNAVAILABLE')) return 'fontError';
    if (code.includes('GLYPH_MISSING')) return 'glyphError';
    if (code.includes('ITEM_TOO_LARGE')) return 'sizeError';
    if (code.includes('TOO_MANY_ITEMS')) return 'tooMany';
    if (code.includes('INCOMPLETE_ENTRY')) return 'missingFields';
    if (code.includes('TITLE_TOO_LONG')) return 'tooLongTitle';
    if (code.includes('EMPTY_WORKSHEET')) return 'selectAtLeastOne';
    return 'generationError';
  }

  /** 生成学生卷与可选答案卷，仅最新一次结果可以替换界面。 */
  async function generate(includeAnswer = false): Promise<void> {
    const current = ++generationRevision;
    const entries = selectedEntries.value;
    error.value = '';
    if (!entries.length || scaleState.value !== 'ok' || titleState.value !== 'ok') {
      releaseArtifact(studentArtifact.value);
      releaseArtifact(answerArtifact.value);
      studentArtifact.value = null;
      answerArtifact.value = null;
      // 没有可排版的题目时必须结束忙碌状态，否则界面会一直停在加载提示。
      busy.value = false;
      return;
    }
    busy.value = true;
    const options: WorksheetOptions = {
      mode: mode.value,
      title: title.value.trim() || '',
      grade: grade.value ?? 2,
      gridMm: layout.value.gridMm,
      repetitions: repetitions.value,
      answer: false
    };
    let student: RenderedArtifact | null = null;
    let answer: RenderedArtifact | null = null;
    try {
      revision.value += 1;
      const snapshot = createSnapshot({
        worksheetId: worksheetIdentity?.worksheetId ?? newOperationId(),
        operationId: worksheetIdentity?.operationId ?? newOperationId(),
        revision: revision.value,
        subject: subject.value,
        mode: mode.value,
        entries,
        selection: lastPlan.value ?? emptyPlan(),
        selectionStrategy: strategy.value,
        ordering: ordering.value,
        repetitions: repetitions.value,
        title: options.title,
        layout: layout.value,
        contentRefs: [contentRef.value],
        // 旧的组合式还没有原文文档；先使用契约默认值，接入统一输入界面后再喂真实身份。
        inputDocumentId: `legacy-${subject.value}`,
        inputRevision: 0,
        parsePolicy: 'whitespace',
        chineseUnit: 'word',
        duplicatePolicy: 'merge-equivalent'
      });
      // 内容变化才形成新的操作身份；纯版式修改只增加 revision。
      if (!worksheetIdentity || worksheetIdentity.contentHash !== snapshot.contentHash) {
        worksheetIdentity = { worksheetId: snapshot.worksheetId, operationId: newOperationId(), contentHash: snapshot.contentHash };
      }
      const words = entries.map(toWordEntry);
      const studentPdf = await generateWorksheet(words, options);
      const studentHash = await sha256Hex(studentPdf.bytes);
      student = { ...studentPdf, sha256: studentHash, layoutHash: snapshot.renderHash, revision: snapshot.revision };
      if (includeAnswer) {
        const answerPdf = await generateWorksheet(words, { ...options, answer: true });
        const answerHash = await sha256Hex(answerPdf.bytes);
        answer = { ...answerPdf, sha256: answerHash, layoutHash: snapshot.renderHash, revision: snapshot.revision };
      }
      // 过期结果不得覆盖新结果，也不允许继续打印旧卷。
      if (current !== generationRevision) {
        releaseArtifact(student);
        releaseArtifact(answer);
        return;
      }
      releaseArtifact(studentArtifact.value);
      releaseArtifact(answerArtifact.value);
      studentArtifact.value = student;
      answerArtifact.value = answer;
      if (worksheetIdentity) recordWorksheetSuccess(category.value, toolId.value, worksheetIdentity.operationId);
    } catch (cause) {
      releaseArtifact(student);
      releaseArtifact(answer);
      if (current === generationRevision) error.value = errorKey(cause);
    } finally {
      if (current === generationRevision) busy.value = false;
    }
  }

  /** 空选题结果，用于尚未执行策略时构造快照。 */
  function emptyPlan(): SelectionResult {
    return { entries: [], requested: 0, available: 0, shortage: false, seed: null, algorithm: 'all-v1', quotaByUnit: [], blocked: [] };
  }

  /** 延迟触发重新生成，避免连续修改设置时反复排版。 */
  function scheduleGenerate(): void {
    busy.value = true;
    clearTimeout(releaseTimer);
    releaseTimer = setTimeout(() => { if (selectedIds.value.length) void generate(!!answerArtifact.value); }, 240);
  }

  /** 明确新建练习：分配新的操作身份。 */
  function startNewWorksheet(): void {
    worksheetIdentity = null;
    applyStrategy(strategy.value, strategy.value === 'all' ? null : randomSeed());
  }

  /** 记录一次真实的打印发起。 */
  function notePrintRequest(): void {
    if (worksheetIdentity) recordPrintRequest(category.value, toolId.value, worksheetIdentity.operationId);
  }

  /** 保存本机草稿；默认不保存学生信息。 */
  function saveDraft(): void {
    const draft: StoredDraft = {
      schemaVersion: 1,
      grade: grade.value,
      semester: semester.value,
      editionId: editionId.value,
      mode: mode.value,
      title: title.value,
      repetitions: repetitions.value,
      layout: { ...layout.value },
      selectedIds: [...selectedIds.value],
      customEntries: customEntries.value.map(entry => ({ ...entry })),
      strategy: strategy.value,
      ordering: ordering.value,
      requestedCount: requestedCount.value,
      seed: seed.value
    };
    try {
      localStorage.setItem(`${DRAFT_PREFIX}${subject.value}`, JSON.stringify(draft));
      draftNotice.value = 'draftSaved';
    } catch { draftNotice.value = 'draftSaveFailed'; }
  }

  /** 读取本机草稿。 */
  function loadDraft(): boolean {
    try {
      const raw = localStorage.getItem(`${DRAFT_PREFIX}${subject.value}`);
      if (!raw) return false;
      const draft = JSON.parse(raw) as StoredDraft;
      if (draft.schemaVersion !== 1) return false;
      grade.value = draft.grade;
      semester.value = draft.semester;
      editionId.value = draft.editionId;
      mode.value = draft.mode;
      title.value = draft.title;
      repetitions.value = draft.repetitions;
      layout.value = { ...draft.layout };
      customEntries.value = draft.customEntries ?? [];
      strategy.value = draft.strategy;
      ordering.value = draft.ordering;
      requestedCount.value = draft.requestedCount;
      seed.value = draft.seed;
      selectedIds.value = draft.selectedIds ?? [];
      draftNotice.value = 'draftRestored';
      return true;
    } catch { return false; }
  }

  /** 清除本机草稿。 */
  function clearDraft(): void {
    try { localStorage.removeItem(`${DRAFT_PREFIX}${subject.value}`); } catch { /* 存储不可用时无需处理。 */ }
    draftNotice.value = 'draftCleared';
  }

  /** 设置格距；用户手动指定后不再随年级覆盖。 */
  function setGridMm(value: 10 | 12 | 14): void {
    layout.value = { ...layout.value, gridMm: value, gridMmUserSet: true };
  }

  /** 年级变化时按默认规则调整格距，已手动指定则保持不变。 */
  watch(grade, value => {
    if (!layout.value.gridMmUserSet) layout.value = { ...layout.value, gridMm: defaultGridMm(value ?? 2) };
  });

  // 学科切换时重置教材状态，避免两个学科互相污染。
  watch(subject, () => {
    mode.value = subject.value === 'chinese' ? 'chinese-dictation' : 'english-dictation';
    grade.value = null;
    editionId.value = null;
    version.value = null;
    scopeEntries.value = [];
    customEntries.value = [];
    selectedIds.value = [];
    catalog.value = null;
    catalogState.value = 'idle';
    initialised.value = false;
    void loadCatalog();
  }, { immediate: true });

  // 首次进入或切换学科时按默认策略（选中范围全部内容）建立初始选题，
  // 用户手动清空后不再自动补选。
  watch([candidates, initialised], () => {
    if (initialised.value || !candidates.value.length) return;
    initialised.value = true;
    applyStrategy(strategy.value, seed.value);
  }, { immediate: true });

  watch([grade, semester], () => { editionId.value = null; version.value = null; scopeEntries.value = []; });
  watch(editionId, () => { void loadVersion(); });
  watch([unitIds, lessonIds, listTypes], () => { if (editionId.value) void loadVersion(); }, { deep: true });

  return {
    catalog, catalogState, catalogError, loadCatalog,
    grade, gradeOptions, semester, editionId, editions, coverage,
    version, versionState, versionError, unitIds, lessonIds, listTypes, availableListTypes, unitTree,
    scopeEntries, scopeState, scopeError,
    customEntries, importCustom, updateCustom, removeCustom,
    candidates, selectedIds, selectedEntries, toggleEntry, selectAll, clearSelection,
    contentSource, contentRef,
    strategy, ordering, requestedCount, seed, lastPlan, previousSelection,
    applyStrategy, regroup, restoreGroup, refreshSelection, startNewWorksheet,
    mode, repetitions, title, layout, setGridMm,
    studentArtifact, answerArtifact, busy, error, generate, scheduleGenerate, revision,
    questionCount, scaleState, titleState,
    category, toolId, notePrintRequest,
    saveDraft, loadDraft, clearDraft, draftNotice, importRequested
  };
}

/** 解包后的工作台状态类型，子组件以 props 形式接收。 */
export type WorksheetDraftState = UnwrapNestedRefs<ReturnType<typeof useWorksheetDraft>>;
