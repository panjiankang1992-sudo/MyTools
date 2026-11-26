import { computed, getCurrentInstance, onBeforeUnmount, onDeactivated, ref, type Ref } from 'vue';
import type { WorksheetArtifact, WorksheetMode, WordEntry } from '../types';
import type { CurriculumEntry } from '../domain/curriculum';
import { generateWorksheet } from '../lib/worksheet';
import { releasePdf, renderPdfPage } from '../lib/pdf-runtime';
import {
  backToEdit, clearSession, createSession, enterOutput, enterProcess, generate, importTextbook,
  setLayout, setProcessing, processSession, sessionCanOutput, setMode, setParsePolicy, setRawText,
  textbookImportRequest, fillSuggestedPinyin, confirmUnambiguousTokens, updateTokenMeaning,
  updateTokenPinyin, applyEnglishSuggestions, updateTokenEnglish, type WorksheetSession
} from '../domain/worksheet-session';
import { availableWorksheetModes, checkGeneratable, chineseTextForToken, englishTextForToken, requirementFor, type WorksheetProcessingOptions } from '../domain/worksheet-processing';
import { cleanupBoundaryPunctuation, tokenize, type ParsePolicy } from '../domain/worksheet-input';
import { candidateReadings } from '../lib/pinyin-adapter';
import { completePinyinFromCurriculum } from '../lib/curriculum-pinyin';
import { draftKeyV1, loadDraftV2, migrateFromV1, saveDraftV2, type DraftStorage, type StoredDraftV2 } from '../domain/worksheet-draft-v2';
import { createStoredDraft, restoreDraftSession } from '../domain/worksheet-draft-session';
import { openPrintPreview, type PrintWindowLike } from '../lib/worksheet-print';
import { parseEntryRows } from '../lib/csv';
import { newOperationId, recordPrintRequest, recordWorksheetSuccess } from '../lib/analytics';
import { translateEntries } from '../services/public-api';
import {
  editablePairsForSession, importEditablePairsIntoSession, removeEditablePair, updateEditablePair,
  type EditablePairSource, type EditableWorksheetPair
} from '../domain/worksheet-editable-pairs';

/** 学科内可选的全局题型；来源与字表类别不决定题型。 */
const MODES: Array<{ value: WorksheetMode; label: string; hint: string; subject: 'chinese' | 'english' }> = [
  { value: 'chinese-pinyin', label: '拼音字帖', hint: '看汉字，填写拼音', subject: 'chinese' },
  { value: 'chinese-dictation', label: '汉字字帖', hint: '看拼音，填写汉字', subject: 'chinese' },
  { value: 'english-dictation', label: '英语默写', hint: '看中文，填写英文', subject: 'english' },
  { value: 'english-meaning', label: '中文释义练习', hint: '看英文，填写中文', subject: 'english' }
];

/** 浏览器边界可以注入，回归测试执行真实会话动作而不依赖网络与浏览器全局。 */
export interface WorksheetSessionDependencies {
  render?: typeof generateWorksheet;
  storage?: DraftStorage;
  release?: (artifact: WorksheetArtifact) => void;
  recordSuccess?: typeof recordWorksheetSuccess;
  recordPrint?: typeof recordPrintRequest;
  translate?: typeof translateEntries;
}

/** 释放本页拥有的 PDF 解析资源和对象地址。 */
function releaseArtifact(artifact: WorksheetArtifact): void {
  releasePdf(artifact.bytes);
  URL.revokeObjectURL(artifact.url);
}

/** 连接统一原文、处理和学生/答案产物；异步任务不得覆写后续编辑。 */
export function useWorksheetV2Session(subject: 'chinese' | 'english', dependencies: WorksheetSessionDependencies = {}) {
  const render = dependencies.render ?? generateWorksheet;
  const release = dependencies.release ?? releaseArtifact;
  const reportSuccess = dependencies.recordSuccess ?? recordWorksheetSuccess;
  const reportPrint = dependencies.recordPrint ?? recordPrintRequest;
  const translate = dependencies.translate ?? translateEntries;
  const storage = dependencies.storage ?? {
    getItem: (key: string) => window.localStorage.getItem(key),
    setItem: (key: string, value: string) => window.localStorage.setItem(key, value),
    removeItem: (key: string) => window.localStorage.removeItem(key)
  };
  const session = ref<WorksheetSession>(createSession(subject)) as Ref<WorksheetSession>;
  const blockers = ref<Array<{ code: string; detail?: string }>>([]);
  const pinyinEditErrors = ref<Array<{ tokenId: string; code: string; detail?: string }>>([]);
  const notice = ref('');
  const artifact = ref<WorksheetArtifact | null>(null);
  const answerArtifact = ref<WorksheetArtifact | null>(null);
  const answerFingerprint = ref('');
  const page = ref(1);
  const zoom = ref<'fit' | 'actual'>('fit');
  const generating = ref(false);
  const answerGenerating = ref(false);
  const automaticPreparing = ref(false);
  const automaticError = ref<string | null>(null);
  let automaticController: AbortController | null = null;
  let automaticTask: Promise<boolean> | null = null;
  let automaticRequestId = 0;
  const sourceLabel = ref('');
  const savedSelection = ref<StoredDraftV2['selection']>({
    grade: subject === 'chinese' ? 1 : 3, semester: 'first', editionId: null,
    selectedIds: [], strategy: 'all', ordering: 'input', requestedCount: null, seed: null
  });
  const pendingLegacySelection = ref(false);
  const migratedFromV1 = ref(false);
  let requestId = 0;
  let answerRequestId = 0;
  let disposed = false;
  const worksheetIdentity = newOperationId();
  const reportedOperations = new Set<string>();

  /** 仅用于判定本次答案是否仍对应当前草稿，不将内容发送到统计接口。 */
  function fingerprint(): string {
    const current = session.value;
    // 原文仅改空白时，已确认题序与校对没有改变，答案也可以继续沿用。
    const content = current.document.tokens.map(token => ({ text: token.text, chineseText: token.chineseText, pinyin: token.pinyinSegments,
      pinyinState: token.pinyinState, meaning: token.meaning, meaningState: token.meaningState,
      englishText: token.englishText, englishState: token.englishState,
      senseId: token.senseId, acceptableAnswers: token.acceptableAnswers }));
    return JSON.stringify([current.document.documentId, content, current.processing, current.layout]);
  }

  const availableModes = computed(() => availableWorksheetModes(session.value.document.tokens, subject));
  const modeOptions = computed(() => MODES.filter(option => option.subject === subject).map(option => ({ ...option,
    disabled: !availableModes.value.includes(option.value),
    unavailableHint: ({ 'chinese-pinyin': '每行需要汉字', 'chinese-dictation': '每行需要有效拼音',
      'english-dictation': '每行需要中文', 'english-meaning': '每行需要英文' })[option.value]
  })));
  const selectedMode = computed(() => session.value.processing.mode);
  const canGenerateAnswer = computed(() => selectedMode.value !== null
    && checkGeneratable(session.value.document.tokens, selectedMode.value, 'answer').ok
    && !pinyinEditErrors.value.length);
  const canOutput = computed(() => !generating.value && sessionCanOutput(session.value) && artifact.value !== null
    && !(requirementFor(selectedMode.value ?? '', 'student').needsConfirmedPinyin && pinyinEditErrors.value.length));
  const canOutputAnswer = computed(() => canGenerateAnswer.value && !answerGenerating.value && answerArtifact.value !== null
    && answerFingerprint.value === fingerprint() && processSession(session.value).blockers.length === 0
    && !(requirementFor(selectedMode.value ?? '', 'answer').needsConfirmedPinyin && pinyinEditErrors.value.length));
  const artifactStale = computed(() => artifact.value !== null && !canOutput.value);
  const errorMessage = computed(() => {
    const code = session.value.workflow.lastError ?? blockers.value[0]?.code;
    if (!code) return '';
    const messages: Record<string, string> = {
      INCOMPLETE_ENTRY: '当前题型需要的读音或释义不完整，请逐项补齐。',
      TITLE_TOO_LONG: '标题过长，请缩短到 40 个字符以内。',
      ITEM_TOO_LARGE: '有题目过长，一页放不下，请拆分内容或调小格距。',
      WORKSHEET_MISSING_PINYIN: '部分字未能自动标注拼音，请补齐读音。',
      WORKSHEET_PINYIN_UNCONFIRMED: '部分读音无效，请检查后修改。',
      WORKSHEET_MISSING_ENGLISH: '部分内容未能自动翻译，请重试或填写英文。',
      WORKSHEET_INVALID_ENGLISH: '英文内容包含不支持的字符，请检查后修改。',
      WORKSHEET_INVALID_PINYIN: '拼音音节无效，请填写正确的拼音。',
      WORKSHEET_PINYIN_COUNT_MISMATCH: '音节数量与汉字数量不一致，请逐字核对。',
      WORKSHEET_PAIR_MULTILINE: '每个单元格填写一项，请把多项内容分到不同的行。',
      WORKSHEET_PAIR_INVALID_BASE: '已有内容的分项格式有误，请先修正再追加。',
      WORKSHEET_MISSING_MEANING: '请补齐当前题型需要的中文释义。',
      WORKSHEET_MISSING_TEXT: '存在空条目，请补上内容。',
      WORKSHEET_MODE_REQUIRED: '请先选择本次练习的题型。',
      WORKSHEET_EMPTY_INPUT: '请先输入或选择练习内容。',
      WORKSHEET_PARSE_QUOTE: '原文中的双引号没有闭合，请修正后重新生成。',
      WORKSHEET_LIMIT_SELECTED: '选入超过 200 项，请减少数量或使用抽取。',
      WORKSHEET_LIMIT_QUESTIONS: '重复展开后超过 500 题，请减少题目或重复次数。',
      TOO_MANY_ITEMS: '题目数量超过上限，请减少选入项或重复次数。',
      FONT_UNAVAILABLE: '字体加载失败，请重试。',
      GLYPH_MISSING: '有字不在内置字体中，请检查并替换该字。'
    };
    return messages[code] ?? `当前内容无法生成，请检查输入（${code}）。`;
  });
  const editablePairs = computed(() => editablePairsForSession(session.value));
  const processSummary = computed(() => {
    const processed = processSession(session.value);
    return `${session.value.document.tokens.length} 项内容 · 最终 ${processed.items.length} 题${session.value.processing.repetitions > 1 ? ` · 重复 ${session.value.processing.repetitions} 次` : ''}`;
  });

  /** 把已完成选题、排序和重复展开的题序传给渲染器。 */
  function toWordEntries(items: ReturnType<typeof processSession>['items']): WordEntry[] {
    return items.map((token, index) => {
      // 原文身份可以是拼音或中文释义，渲染正文只取对应语义字段，不能把它冒充缺失的答案。
      const text = subject === 'chinese' ? chineseTextForToken(token) : englishTextForToken(token);
      return { id: token.tokenId, text,
        pinyin: token.pinyinSegments.map(segment => segment.syllable).join(' '), meaning: token.meaning ?? '',
        unit: index + 1, kind: text.length > 1 ? 'word' : 'character' };
    });
  }

  /** 作废未完成的任务，旧 Promise 结束时只释放资源，不回写编辑状态。 */
  function cancelPending(): void {
    requestId += 1;
    answerRequestId += 1;
    generating.value = false;
    answerGenerating.value = false;
  }

  /** 用户编辑或返回时中止自动处理，过期回包不能覆盖当前输入。 */
  function cancelAutomatic(): void {
    automaticRequestId += 1;
    automaticController?.abort();
    automaticController = null;
    automaticTask = null;
    automaticPreparing.value = false;
    automaticError.value = null;
  }

  /** 提交同步动作；对既有预览只标记过期，直到新预览成功再释放。 */
  function apply(result: { session: WorksheetSession; notices: Array<{ code: string; detail?: string }>; blockers: Array<{ code: string; detail?: string }> }, preserveAutomatic = false): void {
    if (!preserveAutomatic) cancelAutomatic();
    cancelPending();
    session.value = result.session;
    pinyinEditErrors.value = pinyinEditErrors.value.filter(error => result.session.document.tokens.some(token => token.tokenId === error.tokenId));
    blockers.value = result.blockers;
    notice.value = result.notices.some(item => item.code === 'WORKSHEET_DEDUPE_MERGED') ? '已合并相同读音与义项的重复项。' : '';
  }

  /** 单侧输入只提供已有提示对应的题型；所有已用行必须支持同一个题型。 */
  function reconcileMode(): void {
    const current = session.value;
    const compatible = availableWorksheetModes(current.document.tokens, subject);
    if (current.processing.mode && compatible.includes(current.processing.mode)) return;
    const preferred = subject === 'chinese' ? 'chinese-pinyin' : 'english-meaning';
    const next = compatible.includes(preferred) ? preferred : compatible[0] ?? null;
    if (next === current.processing.mode) return;
    // 只更新题型与产物版本，不取消正在按行校验快照的首次补全。
    cancelPending();
    session.value = setMode(current, next).session;
  }

  /** 更新原文并保留用户输入格式。 */
  function applyRawText(value: string): void { apply(setRawText(session.value, value)); }
  /** 切换分项方式。 */
  function applyParsePolicy(policy: ParsePolicy): void { apply(setParsePolicy(session.value, policy)); }
  /** 选择全局题型。 */
  function applyMode(mode: WorksheetMode): void { apply(setMode(session.value, mode)); }
  /** 更新排版参数，使旧产物失效。 */
  function applyLayout(patch: Partial<WorksheetSession['layout']>): void { apply(setLayout(session.value, patch)); }
  /** 更新处理配置，题型变更仍须遵守学科边界。 */
  function applyProcessing(patch: Partial<WorksheetProcessingOptions>): void {
    if (patch.mode !== undefined && patch.mode !== null && patch.mode.startsWith('chinese') !== (subject === 'chinese')) return;
    apply(setProcessing(session.value, patch));
  }
  /** 设置抽取方式及数量。 */
  function applySelection(selection: 'all' | 'random-count', requestedCount: number | null): void {
    applyProcessing({ selection, requestedCount });
  }

  /** 按用户选择的册次与范围追加教材；字表类别不映射题型。 */
  function importCurriculumEntries(entries: CurriculumEntry[], editionId: string, contentVersion: string, label: string, metadata: { grade?: number; semester?: string } = {}, mode: 'append' | 'replace' = 'append'): void {
    if (!entries.length || !editionId || !contentVersion) {
      notice.value = '请选择有内容的教材版本和范围。';
      return;
    }
    apply(importTextbook(session.value, textbookImportRequest(entries, editionId, contentVersion, label, mode)));
    if (blockers.value.length) return;
    savedSelection.value = { ...savedSelection.value, ...metadata, editionId, selectedIds: entries.map(entry => entry.entryId) };
    pendingLegacySelection.value = false;
    sourceLabel.value = label;
    notice.value = `已${mode === 'replace' ? '替换为' : '追加'} ${entries.length} 项，当前共 ${session.value.document.tokens.length} 项。`;
  }

  /** 兼容两列词条表；任何非法行均不部分导入。 */
  function importPairedRows(value: string): void {
    const parsed = parseEntryRows(value);
    if (parsed.unterminatedQuote || parsed.invalidLine !== null || !parsed.pairs.length) {
      notice.value = '未导入内容：请检查每行的文字和读音/释义两列，以及双引号是否闭合。';
      return;
    }
    importEditablePairs(parsed.pairs.map(([text, second]) => subject === 'chinese'
      ? { text, pinyin: second } : { english: text, meaning: second }));
  }

  /** 原子提交配对编辑，并保存无效读音对应的行，后续生成不能忽略错误。 */
  function applyPairResult(result: ReturnType<typeof importEditablePairsIntoSession>): boolean {
    // 未改字段的失焦不取消正在补全的任务，也不让当前预览无故过期。
    if (result.session === session.value && !result.blockers.length) return true;
    apply(result);
    pinyinEditErrors.value = result.blockers.filter(error => error.code.startsWith('WORKSHEET_PINYIN_'))
      .map(error => ({ ...error, tokenId: error.detail?.startsWith('tokenId=') ? error.detail.slice('tokenId='.length) : '' }));
    if (session.value.processing.mode !== null || session.value.workflow.step !== 'input') reconcileMode();
    return result.blockers.length === 0;
  }
  /** 教材、图片与自定义输入统一使用成对字段；机器识别保留待校对状态。 */
  function importEditablePairs(rows: readonly EditableWorksheetPair[], mode: 'append' | 'replace' = 'replace', source: EditablePairSource = 'custom'): boolean {
    const accepted = applyPairResult(importEditablePairsIntoSession(session.value, rows, mode, source));
    notice.value = accepted ? `已保存 ${session.value.document.tokens.length} 项配对内容。` : '配对内容仍有需要修正的字段，请检查后继续。';
    return accepted;
  }
  /** 逐行修改时按稳定身份定位，未编辑的识别字段不转为人工确认。 */
  function updatePair(tokenId: string, patch: Partial<EditableWorksheetPair>): boolean {
    return applyPairResult(updateEditablePair(session.value, tokenId, patch));
  }
  /** 删除整行配对数据，避免只有一列上移导致答案错配。 */
  function removePair(tokenId: string): void { applyPairResult(removeEditablePair(session.value, tokenId)); }

  /** 清空内容与产物，同时使所有在途生成失效。 */
  function clearAll(): void {
    apply(clearSession(session.value));
    if (artifact.value) release(artifact.value);
    if (answerArtifact.value) release(answerArtifact.value);
    artifact.value = null;
    answerArtifact.value = null;
    answerFingerprint.value = '';
    sourceLabel.value = '';
    pinyinEditErrors.value = [];
    page.value = 1;
  }

  /** 生成独立学生/答案产物，只接纳仍属于最新编辑状态的结果。 */
  async function generateKind(answer: boolean): Promise<void> {
    if (requirementFor(selectedMode.value ?? '', answer ? 'answer' : 'student').needsConfirmedPinyin && pinyinEditErrors.value.length) {
      blockers.value = [...pinyinEditErrors.value];
      return;
    }
    const ticket = answer ? ++answerRequestId : ++requestId;
    const baseline = session.value;
    const capturedFingerprint = fingerprint();
    const grade = savedSelection.value.grade ?? (subject === 'chinese' ? 1 : 3);
    if (answer) answerGenerating.value = true; else generating.value = true;
    blockers.value = [];
    let produced: WorksheetArtifact | null = null;
    const result = await generate(baseline, async input => {
      produced = await render(toWordEntries(input.entries), {
        mode: input.mode, title: input.layout.title, grade, gridMm: input.layout.gridMm,
        // 重复只由处理层展开一次；渲染器按最终题序逐题投影。
        repetitions: 1, expandedQuestions: true, answer
      });
      return { generationId: 0, kind: answer ? 'answer' as const : 'student' as const,
        snapshotHash: capturedFingerprint, pageCount: produced.pageCount, sha256: '' };
    }, answer ? 'answer' : 'student');
    const currentTicket = answer ? answerRequestId : requestId;
    if (disposed || ticket !== currentTicket || fingerprint() !== capturedFingerprint) {
      if (produced) release(produced);
      if (ticket === currentTicket) { if (answer) answerGenerating.value = false; else generating.value = false; }
      return;
    }
    blockers.value = result.blockers;
    if (answer) {
      answerGenerating.value = false;
      if (answerArtifact.value) release(answerArtifact.value);
      answerArtifact.value = result.artifactGenerated ? produced : null;
      answerFingerprint.value = result.artifactGenerated ? capturedFingerprint : '';
      notice.value = result.artifactGenerated ? '答案已单独生成，可下载答案 PDF。' : '答案所需字段尚未齐全，请完成校对后重试。';
    } else {
      generating.value = false;
      // 只接纳结果的工作流元数据，原文、处理和版式始终以当前会话为准。
      session.value = { ...session.value, workflow: result.session.workflow };
      if (result.artifactGenerated && produced) {
        if (artifact.value) release(artifact.value);
        artifact.value = produced;
        page.value = 1;
        const chosen = baseline.processing.mode;
        const operation = `${baseline.document.documentId}#${result.session.workflow.operationId}`;
        if (chosen && !reportedOperations.has(operation)) {
          reportedOperations.add(operation);
          reportSuccess(subject === 'chinese' ? 'copybook' : 'words', `worksheet.${chosen}`, `${worksheetIdentity}#${operation}`);
        }
      }
    }
  }
  /** 生成学生卷预览。 */
  async function generateNow(): Promise<void> {
    const preparation = prepareForOutput('student');
    if (typeof preparation === 'boolean' ? !preparation : !await preparation) return;
    await generateKind(false);
  }
  /** 生成独立答案文件，不把答案写入学生卷。 */
  async function generateAnswerNow(): Promise<void> {
    const preparation = prepareForOutput('answer');
    if (typeof preparation === 'boolean' ? !preparation : !await preparation) return;
    // 单侧提示可以生成学生练习，但不能据此伪造另一侧的参考答案。
    if (!canGenerateAnswer.value) {
      const mode = selectedMode.value;
      blockers.value = mode ? checkGeneratable(session.value.document.tokens, mode, 'answer').blockers.map(code => ({ code }))
        : [{ code: 'WORKSHEET_MODE_REQUIRED' }];
      notice.value = '填写完整的两列内容后才能生成参考答案。';
      return;
    }
    await generateKind(true);
  }

  const needsCountChoice = computed(() => session.value.document.tokens.length > 200);
  const missingReadings = computed(() => subject === 'chinese' ? session.value.document.tokens
    .filter(token => !token.pinyinSegments.length || token.pinyinSegments.some(segment => !segment.syllable.trim())).length : 0);
  /** 用户显式补音只填空项，已有读音保持不变。 */
  function fillReadings(): void {
    if (subject === 'chinese') void prepareAutomaticFields(true);
  }
  /** 当前学生题型确需读音时补缺失项，已有校对保持不变。 */
  function prepareStudentReadings(): void {
    const current = session.value;
    if (!requirementFor(current.processing.mode ?? '', 'student').needsConfirmedPinyin) return;
    // 解析失败时保留的旧词条不能被当作本次原文补音。
    if (tokenize(current.document.rawText, current.document.parsePolicy).blocked || missingReadings.value === 0) return;
    void prepareAutomaticFields();
  }
  /** 查询多音字候选。 */
  function isAmbiguous(grapheme: string): boolean { return candidateReadings(grapheme).length > 1; }
  /** 保存一项手工读音。 */
  function editPinyin(tokenId: string, syllables: string): void {
    const result = updateTokenPinyin(session.value, tokenId, syllables);
    apply(result);
    pinyinEditErrors.value = pinyinEditErrors.value.filter(error => error.tokenId !== tokenId);
    if (result.blockers.length) pinyinEditErrors.value.push(...result.blockers.map(error => ({ ...error, tokenId })));
    if (session.value.workflow.step !== 'input') reconcileMode();
  }
  /** 保存一项手工释义。 */
  function editMeaning(tokenId: string, meaning: string): void {
    apply(updateTokenMeaning(session.value, tokenId, meaning));
    if (session.value.workflow.step !== 'input') reconcileMode();
  }
  /** 保存规范英文，原文输入保持不变。 */
  function editEnglish(tokenId: string, value: string): void {
    apply(updateTokenEnglish(session.value, tokenId, value));
    if (session.value.workflow.step !== 'input') reconcileMode();
  }

  /** 一次只请求未补齐的字段，原中文直接保留为释义。 */
  function needsTranslation(token: WorksheetSession['document']['tokens'][number]): boolean {
    return !englishTextForToken(token).trim() || !token.meaning?.trim();
  }

  /** 每条内容只首次自动补空；显式重试才允许再次补齐，失败和取消也不重置初始化标记。 */
  function prepareAutomaticFields(force = false): Promise<boolean> {
    if (disposed) return Promise.resolve(false);
    if (automaticTask) return automaticTask;
    const current = session.value;
    if (tokenize(current.document.rawText, current.document.parsePolicy).blocked || !current.document.tokens.length) return Promise.resolve(false);
    const eligibleIds = new Set(current.document.tokens.filter(token => force || !token.automaticInitialized).map(token => token.tokenId));
    if (!eligibleIds.size) return Promise.resolve(true);
    // 请求前就初始化全部新行，完整配对、网络失败及取消都不能在下一次失焦或返回时再补。
    session.value = { ...current, document: { ...current.document,
      tokens: current.document.tokens.map(token => token.automaticInitialized ? token : { ...token, automaticInitialized: true }) } };
    automaticError.value = null;
    if (subject === 'chinese') {
      const fields = (token: WorksheetSession['document']['tokens'][number]): string => JSON.stringify([
        token.text, chineseTextForToken(token), token.pinyinSegments, token.pinyinState
      ]);
      const pending = session.value.document.tokens.filter(token => eligibleIds.has(token.tokenId)
        && chineseTextForToken(token).trim()
        && (!token.pinyinSegments.length || token.pinyinSegments.some(segment => !segment.syllable.trim())))
        .map(token => ({ id: token.tokenId, text: chineseTextForToken(token), fields: fields(token) }));
      if (!pending.length) return Promise.resolve(true);
      const ticket = ++automaticRequestId;
      const documentId = session.value.document.documentId;
      const controller = new AbortController();
      automaticController = controller;
      automaticPreparing.value = true;
      const active = (): boolean => !disposed && !controller.signal.aborted && ticket === automaticRequestId
        && session.value.document.documentId === documentId;
      automaticTask = completePinyinFromCurriculum(pending, controller.signal, translate).then(candidates => {
        if (!active()) return false;
        // 直接改动缓冲也须校验快照；同形新行、删除后重建或人工音节都不能接收旧读音。
        const accepted = new Set(pending.filter(item => {
          const token = session.value.document.tokens.find(token => token.tokenId === item.id);
          return token && fields(token) === item.fields;
        }).map(item => item.id));
        const result = fillSuggestedPinyin(session.value, text =>
          candidates.find(candidate => candidate.sourceText === text)?.suggestion ?? { segments: [] }, accepted);
        if (result.filled) { apply(result, true); if (session.value.workflow.step !== 'input') reconcileMode(); }
        notice.value = result.filled ? `已自动标注 ${result.filled} 项拼音，可直接使用或修改。` : '';
        const remaining = session.value.document.tokens.filter(token => eligibleIds.has(token.tokenId)
          && chineseTextForToken(token).trim()
          && (!token.pinyinSegments.length || token.pinyinSegments.some(segment => !segment.syllable.trim()))).length;
        automaticError.value = remaining ? `还有 ${remaining} 项未能自动补全，可重试或手动填写。` : null;
        return true;
      }).finally(() => {
        if (ticket !== automaticRequestId) return;
        automaticPreparing.value = false;
        automaticController = null;
        automaticTask = null;
      });
      return automaticTask;
    }
    // 初次中文单侧内容可直接作释义；已经有英文时不能用旧原文覆盖用户主动清空的中文。
    const originals = applyEnglishSuggestions(session.value, session.value.document.tokens
      .filter(token => eligibleIds.has(token.tokenId) && !englishTextForToken(token)
        && /\p{Script=Han}/u.test(token.text) && !/\p{Script=Latin}/u.test(token.text))
      .map(token => ({ tokenId: token.tokenId, sourceText: token.text, meaning: token.text })));
    if (originals.filled) apply(originals, true);
    const fieldSnapshot = (token: WorksheetSession['document']['tokens'][number]): string => JSON.stringify([
      token.text, englishTextForToken(token), token.meaning, token.englishState, token.meaningState
    ]);
    const pending = session.value.document.tokens.filter(token => eligibleIds.has(token.tokenId) && needsTranslation(token))
      .map(token => ({ id: token.tokenId, text: englishTextForToken(token) || token.meaning || token.text,
        originalText: token.text, fields: fieldSnapshot(token) }));
    if (!pending.length) return Promise.resolve(true);
    const ticket = ++automaticRequestId;
    const documentId = session.value.document.documentId;
    const controller = new AbortController();
    automaticController = controller;
    automaticPreparing.value = true;
    const active = (): boolean => !disposed && !controller.signal.aborted && ticket === automaticRequestId
      && session.value.document.documentId === documentId;
    const run = async (): Promise<boolean> => {
      // 每批只尝试一次，失败留给显式重试；一次初始化可分批完成不同的新行。
      for (let start = 0; start < pending.length; start += 20) {
        if (!active()) return false;
        const batch = pending.slice(start, start + 20);
        try {
          const results = await translate(batch.map(({ id, text }) => ({ id, text })), controller.signal);
          if (!active()) return false;
          const accepted = results.filter(result => result.status === 'translated' && batch.some(item => {
            const token = session.value.document.tokens.find(token => token.tokenId === item.id);
            return item.id === result.id && item.text === result.sourceText && token && fieldSnapshot(token) === item.fields;
          }));
          const enriched = applyEnglishSuggestions(session.value, accepted.map(result => ({
            tokenId: result.id, sourceText: batch.find(item => item.id === result.id)!.originalText,
            englishText: result.englishText, meaning: result.meaning
          })));
          if (enriched.filled) { apply(enriched, true); if (session.value.workflow.step !== 'input') reconcileMode(); }
        } catch {
          if (!active()) return false;
          // 服务不可用时不继续请求后续批次；所有未完成行仍可由用户显式重试。
          break;
        }
      }
      if (active()) {
        const remaining = session.value.document.tokens.filter(token => eligibleIds.has(token.tokenId) && needsTranslation(token)).length;
        automaticError.value = remaining ? `还有 ${remaining} 项未能自动翻译，可点补全或手动填写。` : null;
      }
      return active();
    };
    automaticTask = run().finally(() => {
      if (ticket === automaticRequestId) {
        automaticPreparing.value = false;
        automaticController = null;
        automaticTask = null;
      }
    });
    return automaticTask;
  }

  /** 生成前补当前题型需要的字段，取消后不自动开始新一轮输出。 */
  function prepareForOutput(kind: 'student' | 'answer'): boolean | Promise<boolean> {
    if (!selectedMode.value) return !disposed;
    const requirement = requirementFor(selectedMode.value, kind);
    if (subject === 'chinese') {
      // 教材读音查询是异步的，生成与打印必须等待当前首次补全结束。
      if (automaticTask) return automaticTask;
      if (requirement.needsConfirmedPinyin && !pinyinEditErrors.value.length && missingReadings.value
        && !tokenize(session.value.document.rawText, session.value.document.parsePolicy).blocked) return prepareAutomaticFields();
      return !disposed;
    }
    if (automaticTask) return automaticTask;
    const needed = session.value.document.tokens.some(token =>
      !englishTextForToken(token).trim() || (requirement.needsMeaning && !token.meaning?.trim()));
    return needed ? prepareAutomaticFields() : !disposed;
  }

  /** 单项确认只影响该项，并重新验证音节数量。 */
  function confirmToken(tokenId: string): void {
    const token = session.value.document.tokens.find(item => item.tokenId === tokenId);
    if (!token || subject !== 'chinese') return;
    if (pinyinEditErrors.value.some(error => error.tokenId === tokenId)) {
      blockers.value = pinyinEditErrors.value.filter(error => error.tokenId === tokenId);
      return;
    }
    editPinyin(tokenId, token.pinyinSegments.map(segment => segment.syllable).join(' '));
  }
  /** 批量确认只处理无歧义且读音完整的候选。 */
  function confirmAll(): void {
    if (subject !== 'chinese') return;
    const result = confirmUnambiguousTokens(session.value, isAmbiguous);
    apply(result);
    notice.value = `已确认 ${result.confirmed} 项，含多音字的条目仍需逐项确认。`;
  }

  const hasStoredDraft = ref(false);
  /** 读取草稿存在状态，不自动恢复或写入。 */
  function refreshDraftPresence(): void {
    hasStoredDraft.value = loadDraftV2(storage, subject).status !== 'empty' || migrateFromV1(storage, subject).status !== 'none';
  }
  refreshDraftPresence();
  /** 主动保存完整草稿，失败保留当前编辑内容。 */
  function saveDraft(): boolean {
    try {
      if (pinyinEditErrors.value.length) {
        notice.value = '有读音修改尚未通过校验，请修正后保存，当前编辑仍保留。';
        return false;
      }
      const payload = createStoredDraft(session.value, { selection: savedSelection.value, migratedFromV1: migratedFromV1.value });
      const outcome = saveDraftV2(storage, subject, payload);
      notice.value = outcome.ok ? '草稿已保存到本机。' : '草稿保存失败；当前编辑仍在，请勿关闭页面。';
      refreshDraftPresence();
      return outcome.ok;
    } catch {
      notice.value = '草稿保存失败，请检查输入与配置；当前编辑仍在。';
      return false;
    }
  }
  /** 从完整负载恢复，失败不替换现有会话；旧版来源选择保留待恢复信息。 */
  function loadDraft(): void {
    const stored = loadDraftV2(storage, subject);
    const migration = stored.status === 'empty' ? migrateFromV1(storage, subject) : null;
    const payload = stored.status === 'ok' ? stored.payload : migration?.status === 'ok' ? migration.payload : null;
    if (!payload) {
      notice.value = stored.status === 'invalid' || migration?.status === 'invalid' ? '草稿格式无效，当前编辑未改变。' : '本机没有可恢复的草稿。';
      return;
    }
    try {
      const restored = restoreDraftSession(subject, payload);
      clearAll();
      apply({ session: restored, notices: [], blockers: [] });
      if (payload.selection) savedSelection.value = { ...payload.selection };
      sourceLabel.value = payload.sources?.map(source => source.label).filter(Boolean).join('、') ?? '';
      migratedFromV1.value = Boolean(payload.migratedFromV1);
      pendingLegacySelection.value = Boolean(migration?.requiresOnlineSelection);
      notice.value = migration
        ? `已恢复旧版草稿的题型与配对内容；旧格式未保存原始空白。${pendingLegacySelection.value ? '旧教材选择需要在教材区按原版本重新选择后追加。' : ''}`
        : '已恢复原文、分项方式、校对结果和版式。';
      if (restored.document.tokens.some(token => token.issues.some(issue => issue.code === 'WORKSHEET_DRAFT_AMBIGUOUS_CORRECTION'))) {
        notice.value += '旧草稿中部分同形词无法确定校对对应项，请逐项复核。';
      }
    } catch {
      notice.value = '草稿格式无效，当前编辑未改变。';
    }
    refreshDraftPresence();
  }
  /** 清除本学科草稿，不影响当前编辑。 */
  function dropDraft(): void {
    try {
      storage.removeItem(`yuyutian-toolbox:draft:v2:${subject}`);
      storage.removeItem(draftKeyV1(subject));
      notice.value = '已清除本机草稿。';
    } catch { notice.value = '无法清除本机草稿，当前编辑仍在。'; }
    refreshDraftPresence();
  }
  /** 显式保存迁移草稿，保留旧版键以便恢复。 */
  function commitDraft(): void { saveDraft(); }
  /** 进入处理步骤。 */
  function goProcess(): void { apply(enterProcess(session.value)); }
  /** 有效内容首次进入确认时提供仅需正文的默认题型，保留用户已经做出的题型选择。 */
  function prepareConfirmation(): void {
    const current = session.value;
    const parsed = tokenize(current.document.rawText, current.document.parsePolicy);
    if (parsed.blocked || parsed.spans.length === 0) {
      // 输入错误留在原步骤并显示定位；空引号和纯空白均不自动选择题型。
      apply({ session: current, notices: [], blockers: parsed.blocked
        ? parsed.issues.map(issue => ({ code: issue.code, detail: `line=${issue.line} column=${issue.column}` }))
        : [{ code: 'WORKSHEET_EMPTY_INPUT' }] });
      return;
    }
    reconcileMode();
    goProcess();
    void prepareAutomaticFields();
  }
  /** 返回编辑但不更改正文。 */
  function goBackToEdit(): void { apply(backToEdit(session.value)); }
  /** 进入输出。 */
  function goOutput(): void { apply(enterOutput(session.value)); }

  /** 下载指定当前产物，不重新生成。 */
  function download(current: WorksheetArtifact | null, valid: boolean): void {
    if (!current || !valid) { notice.value = '预览已过期，请先重新生成。'; return; }
    const link = document.createElement('a');
    link.href = current.url;
    link.download = current.filename;
    link.click();
  }
  /** 下载当前学生卷。 */
  function downloadPdf(): void { download(artifact.value, canOutput.value); }
  /** 下载当前独立答案卷。 */
  function downloadAnswerPdf(): void { download(answerArtifact.value, canOutputAnswer.value); }
  /** 打开独立打印窗口，其 URL 由该窗口拥有，关闭窗口不损坏主预览。 */
  async function printPreview(): Promise<void> {
    if (!artifact.value || !canOutput.value) { notice.value = '预览已过期，请先重新生成。'; return; }
    const current = artifact.value;
    // 打印持有独立快照，主页面后续编辑或释放预览不会破坏正在准备的纸张。
    const printBytes = new Uint8Array(current.bytes);
    const printUrl = URL.createObjectURL(new Blob([printBytes], { type: 'application/pdf' }));
    const chosen = session.value.processing.mode;
    const operation = `${session.value.document.documentId}#${session.value.workflow.operationId}`;
    const handle = await openPrintPreview(() => window.open('', '_blank') as unknown as PrintWindowLike | null, {
      pdfUrl: printUrl, filename: current.filename, pageCount: current.pageCount, title: '练习打印',
      renderPageImage: async (pageNumber, signal) => {
        if (signal.aborted) throw new Error('PRINT_CANCELLED');
        const canvas = document.createElement('canvas');
        await renderPdfPage(printBytes, canvas, pageNumber, 2, 'print');
        if (signal.aborted) throw new Error('PRINT_CANCELLED');
        const image = canvas.toDataURL('image/png');
        canvas.width = 0; canvas.height = 0;
        return image;
      },
      onRenderDisposed: () => releasePdf(printBytes),
      onPrintRequested: () => { if (chosen) reportPrint(subject === 'chinese' ? 'copybook' : 'words', `worksheet.${chosen}`, `${worksheetIdentity}#${operation}`); }
    });
    if (!handle.opened) URL.revokeObjectURL(printUrl);
    notice.value = !handle.opened ? '无法打开打印窗口，请改用导出 PDF。'
      : handle.ready ? '打印页已准备完成，可打印或导出 PDF。' : '打印页未准备完成，请在窗口中导出 PDF 后打印。';
  }
  /** 页面离开时取消在途任务；销毁时释放已经生成的资源。 */
  function dispose(): void {
    disposed = true;
    cancelAutomatic();
    cancelPending();
    if (artifact.value) release(artifact.value);
    if (answerArtifact.value) release(answerArtifact.value);
    artifact.value = null;
    answerArtifact.value = null;
  }
  if (getCurrentInstance()) {
    onDeactivated(() => { cancelPending(); cancelAutomatic(); });
    onBeforeUnmount(dispose);
  }

  return { session, blockers, notice, artifact, answerArtifact, page, zoom, generating, answerGenerating,
    automaticPreparing, automaticError, prepareAutomaticFields, cancelAutomaticFields: cancelAutomatic,
    editEnglish, editablePairs, importEditablePairs, updatePair, removePair,
    sourceLabel, modeOptions, selectedMode, availableModes, canGenerateAnswer, canOutput, canOutputAnswer, artifactStale, errorMessage, processSummary,
    applyRawText, applyParsePolicy, applyMode, applyLayout, applyProcessing, applySelection, importCurriculumEntries,
    needsCountChoice, missingReadings, fillReadings, prepareStudentReadings, isAmbiguous, editPinyin, editMeaning, confirmToken, confirmAll,
    clearAll, generateNow, generateAnswerNow, saveDraft, loadDraft, dropDraft, commitDraft, hasStoredDraft,
    refreshDraftPresence, pendingLegacySelection, savedSelection, goProcess, prepareConfirmation, goBackToEdit, goOutput,
    downloadPdf, downloadAnswerPdf, printPreview, cleanupBoundaryPunctuation, importPairedRows, dispose };
}
