import type { WorksheetMode } from '../types';
import {
  appendToRawText, copyAutomaticFields, createInputDocument, setTokenMeaning, setTokenPinyin, syncInputDocument, tokenize,
  type InputSourceRef, type InputSubject, type InputToken, type ParsePolicy, type WorksheetInputDocument
} from './worksheet-input';
import {
  PROCESSING_DEFAULTS, checkGeneratable, chineseTextForToken, englishTextForToken, isValidChineseMeaning,
  isValidEnglishText, isValidPinyinForToken, isValidPinyinSyllable, processWorksheetInput, requirementFor,
  type ProcessingNotice, type WorksheetOutputKind, type WorksheetProcessingOptions
} from './worksheet-processing';
import {
  alignImportSources, buildTextbookImport, type ImportMode, type TextbookImportRequest
} from './worksheet-import';
import {
  beginGeneration, canOutput, clearWorkflow, completeGeneration, createWorkflow,
  failGeneration, goToOutput, goToProcess, onInputChanged, onLayoutChanged,
  onProcessingChanged, returnToEdit, type WorkflowArtifact, type WorkflowState
} from './worksheet-workflow';
import type { CurriculumEntry } from './curriculum';

/**
 * 字帖会话：把原文、处理与工作流串成一条可测的链路。
 *
 * 设计要点（按用户最后修正）：**先输入内容，再由用户统一选择全局题型**。
 * 识字表／写字表／词语表只决定取词范围，**不决定题型**，也不存在按表分配题型或混合卷。
 * 本模块不依赖 Vue 与 DOM，便于在 Node 中完整验证。
 */

/** 本次会话的全部状态。 */
export interface WorksheetSession {
  document: WorksheetInputDocument;
  processing: WorksheetProcessingOptions;
  workflow: WorkflowState;
  /** 布局参数，仅影响排版指纹。 */
  layout: { gridMm: number; title: string };
}

/** 生成产物所需的内容快照输入。 */
export interface SessionSnapshotInput {
  subject: InputSubject;
  mode: WorksheetMode;
  entries: ReturnType<typeof processWorksheetInput>['items'];
  processing: WorksheetProcessingOptions;
  layout: { gridMm: number; title: string };
  inputDocumentId: string;
  inputRevision: number;
}

/** 渲染器：把快照输入变成产物。测试可注入桩。 */
export type SessionRenderer = (input: SessionSnapshotInput) => Promise<Omit<WorkflowArtifact, 'status'>>;

/** 一次动作的结果。 */
export interface SessionResult {
  session: WorksheetSession;
  notices: ProcessingNotice[];
  /** 阻断原因；非空时不得生成。 */
  blockers: ProcessingNotice[];
}

/** 翻译结果以原文与稳定条目标识共同绑定，迟到结果不能应用到别的词。 */
export interface EnglishSuggestion {
  tokenId: string;
  sourceText: string;
  englishText?: string | null;
  meaning?: string | null;
}

/** 只有不含英文答案的中文原文可以直接用作释义。 */
function isChineseSourceMeaning(value: string): boolean {
  return isValidChineseMeaning(value) && !/\p{Script=Latin}/u.test(value);
}

/** 英语来源中的中文本身就是用户提供的释义；混合行需拆分，避免把答案印入题面。 */
function preserveChineseMeaning(document: WorksheetInputDocument): WorksheetInputDocument {
  if (document.subject !== 'english' || document.parseIssues.length) return document;
  return { ...document, tokens: document.tokens.map(token => !token.meaning?.trim()
    && token.meaningState !== 'confirmed' && isChineseSourceMeaning(token.text)
    ? { ...token, meaning: token.text, meaningState: 'confirmed' } : token) };
}

/** 只在派生题单中使用规范英文，原始条目及返编原文保持不变。 */
function sessionInputTokens(session: WorksheetSession): InputToken[] {
  if (session.document.subject !== 'english') return session.document.tokens.map(token => ({ ...token, text: chineseTextForToken(token) }));
  return session.document.tokens.map(token => ({
    ...token,
    text: englishTextForToken(token),
    meaning: token.meaning?.trim() ? token.meaning : isChineseSourceMeaning(token.text) ? token.text : null,
    meaningState: token.meaning?.trim() ? token.meaningState : isChineseSourceMeaning(token.text) ? 'confirmed' : 'missing'
  }));
}

/** 创建会话；新建练习默认空原文、未选题型。 */
export function createSession(subject: InputSubject, layout: { gridMm: number; title: string } = { gridMm: 12, title: '' }): WorksheetSession {
  return {
    document: createInputDocument(subject),
    processing: { ...PROCESSING_DEFAULTS },
    workflow: createWorkflow(),
    layout: { ...layout }
  };
}

/** 按实际输出的题目顺序与字段计算指纹，不把原文排版空白、条目身份或光标区间当作内容。 */
function sessionFingerprint(session: WorksheetSession, kind: WorksheetOutputKind): string {
  const mode = session.processing.mode;
  const requirement = requirementFor(mode ?? '', kind);
  const processed = processWorksheetInput(sessionInputTokens(session), session.processing);
  return JSON.stringify({
    subject: session.document.subject,
    mode,
    kind,
    layout: session.layout,
    blockers: processed.blockers,
    items: processed.items.map(token => ({
      text: token.text,
      englishState: mode?.startsWith('english') ? token.englishState : undefined,
      pinyin: requirement.needsConfirmedPinyin ? token.pinyinSegments : undefined,
      pinyinState: requirement.needsConfirmedPinyin ? token.pinyinState : undefined,
      meaning: requirement.needsMeaning ? token.meaning : undefined,
      meaningState: requirement.needsMeaning ? token.meaningState : undefined,
      acceptableAnswers: kind === 'answer' ? token.acceptableAnswers : undefined
    }))
  });
}

/** 两份有效输入产生完全相同的输出时才允许沿用已有产物。 */
function equivalentInput(session: WorksheetSession, document: WorksheetInputDocument): boolean {
  if (tokenize(session.document.rawText, session.document.parsePolicy).blocked
    || tokenize(document.rawText, document.parsePolicy).blocked) return false;
  const kind = session.workflow.artifact?.kind ?? 'student';
  return sessionFingerprint(session, kind) === sessionFingerprint({ ...session, document }, kind);
}

/** 复制纯数据快照并冻结每一层，防止异步渲染期间外部修改嵌套词条与版式。 */
function freezeSnapshot(input: SessionSnapshotInput): SessionSnapshotInput {
  const copied = JSON.parse(JSON.stringify(input)) as SessionSnapshotInput;
  const freeze = (value: unknown): void => {
    if (value === null || typeof value !== 'object') return;
    // 先冻结子对象，确保数组内部的读音、来源与答案同样不可写。
    for (const nested of Object.values(value)) freeze(nested);
    Object.freeze(value);
  };
  freeze(copied);
  return copied;
}

/**
 * 更新原文；保留用户原始空格、换行与引号。
 *
 * @param session 当前会话
 * @param rawText 新原文
 * @param sources 可选的来源引用，按原始项顺序对齐
 * @returns 新会话与解析问题
 */
export function setRawText(
  session: WorksheetSession,
  rawText: string,
  sources: ReadonlyArray<readonly InputSourceRef[]> = []
): SessionResult {
  const synced = syncInputDocument(session.document, rawText, session.document.parsePolicy, sources);
  const document = preserveChineseMeaning(synced.document);
  // 解析失败时保留旧条目，但绝不静默丢弃用户刚输入的原文。
  const equivalent = !synced.blocked && equivalentInput(session, document);
  return {
    session: {
      ...session,
      document,
      workflow: onInputChanged(session.workflow, equivalent)
    },
    notices: [],
    blockers: synced.issues.map(issue => ({ code: issue.code, detail: `line=${issue.line} column=${issue.column}` }))
  };
}

/** 切换分项策略；原文不变，只是重新解析。 */
export function setParsePolicy(session: WorksheetSession, policy: ParsePolicy): SessionResult {
  const synced = syncInputDocument(session.document, session.document.rawText, policy);
  const document = preserveChineseMeaning(synced.document);
  return {
    session: {
      ...session,
      document,
      workflow: onInputChanged(session.workflow, !synced.blocked && equivalentInput(session, document))
    },
    notices: [],
    blockers: synced.issues.map(issue => ({ code: issue.code }))
  };
}

/**
 * 导入教材内容到统一原文。
 *
 * 契约 §2.2：默认追加，替换必须由用户显式选择；加载失败不得回退示例、不得清空已有原文。
 *
 * @param session 当前会话
 * @param request 导入请求（不含原文与修订号，由会话提供）
 * @returns 新会话与导入问题
 */
export function importTextbook(
  session: WorksheetSession,
  request: Omit<TextbookImportRequest, 'currentRawText' | 'revision' | 'subject' | 'parsePolicy'> & { mode: ImportMode }
): SessionResult {
  const built = buildTextbookImport({
    ...request,
    currentRawText: session.document.rawText,
    revision: session.document.revision,
    subject: session.document.subject,
    parsePolicy: session.document.parsePolicy
  });
  if (built.blocked) {
    // 阻断时原文一字不改。
    return {
      session,
      notices: [],
      blockers: built.issues.map(issue => ({ code: issue.code, detail: issue.index === null ? undefined : `index=${issue.index}` }))
    };
  }
  const aligned = alignImportSources(built.rawText, session.document.parsePolicy, built.range, built.sources, session.document);
  const synced = syncInputDocument(session.document, built.rawText, session.document.parsePolicy, aligned.sources);
  const record = built.record;

  // 契约 §2.2：拼音与义项必须随条目一起保留，否则汉字字帖会因为「没有读音」而无法生成。
  // 区间内的条目按出现顺序与导入条目一一对应。
  let position = -1;
  const enrichedTokens = synced.document.tokens.map((token) => {
    const inRange = token.rawStart >= built.range.start && token.rawEnd <= built.range.end;
    if (!inRange) return token;
    position += 1;
    const source = request.entries[position];
    if (!source) return token;
    return {
      ...token,
      pinyinSegments: source.pinyinSegments.map(segment => ({ grapheme: segment.grapheme, syllable: segment.syllable })),
      pinyinState: source.pinyinSegments.length ? 'confirmed' as const : 'missing' as const,
      meaning: source.meaning,
      meaningState: source.meaning ? 'confirmed' as const : 'missing' as const,
      senseId: source.senseId || null,
      acceptableAnswers: [...source.acceptableAnswers]
    };
  });

  return {
    session: {
      ...session,
      document: { ...synced.document, tokens: enrichedTokens, imports: [...session.document.imports, record] },
      workflow: onInputChanged(session.workflow, false)
    },
    notices: [],
    blockers: [...built.issues, ...aligned.issues]
      .filter(issue => issue.code.endsWith('MISMATCH') || issue.code.endsWith('SHORTAGE') || issue.code.endsWith('LEFTOVER'))
      .map(issue => ({ code: issue.code }))
  };
}

/**
 * 选择全局题型。题型覆盖本次全部有效条目，不因字表类别而改变。
 *
 * 契约 §1.1：切学科后只能选择该学科的合法模式。
 *
 * @param session 当前会话
 * @param mode 目标题型；`null` 表示尚未选择
 * @returns 新会话
 */
export function setMode(session: WorksheetSession, mode: WorksheetMode | null): SessionResult {
  const chinese = mode === null || mode.startsWith('chinese');
  // 学科与题型不匹配时拒绝改动，避免出现英语页面选中汉语题型。
  if (mode !== null && chinese !== (session.document.subject === 'chinese')) {
    return { session, notices: [], blockers: [{ code: 'WORKSHEET_MODE_SUBJECT_MISMATCH' }] };
  }
  return {
    session: {
      ...session,
      processing: { ...session.processing, mode },
      workflow: onProcessingChanged(session.workflow)
    },
    notices: [],
    blockers: []
  };
}

/** 更新处理参数（重复、选题、排序、逐字等）。 */
export function setProcessing(
  session: WorksheetSession,
  patch: Partial<WorksheetProcessingOptions>
): SessionResult {
  return {
    session: {
      ...session,
      processing: { ...session.processing, ...patch },
      workflow: onProcessingChanged(session.workflow)
    },
    notices: [],
    blockers: []
  };
}

/** 更新版式参数：只影响排版指纹，不改变内容身份。 */
export function setLayout(session: WorksheetSession, patch: Partial<{ gridMm: number; title: string }>): SessionResult {
  return {
    session: { ...session, layout: { ...session.layout, ...patch }, workflow: onLayoutChanged(session.workflow) },
    notices: [],
    blockers: []
  };
}

/** 处理当前原文，得到题目集合与提示。 */
export function processSession(session: WorksheetSession): SessionResult & { items: SessionSnapshotInput['entries'] } {
  const parsed = tokenize(session.document.rawText, session.document.parsePolicy);
  if (parsed.blocked) {
    // 原文是唯一真值；解析失败保留的旧 tokens 不得再参与本次处理或生成。
    return {
      session,
      notices: [],
      blockers: parsed.issues.map(issue => ({ code: issue.code, detail: `line=${issue.line} column=${issue.column}` })),
      items: []
    };
  }
  const result = processWorksheetInput(sessionInputTokens(session), session.processing);
  return { session, notices: result.notices, blockers: result.blockers, items: result.items };
}

/**
 * 生成产物。
 *
 * 未选择题型、没有内容或存在阻断时不得生成，并如实返回原因。
 *
 * @param session 当前会话
 * @param renderer 渲染器
 * @param outputKind 输出对象，默认生成学生卷
 * @returns 新会话与生成结果
 */
export async function generate(
  session: WorksheetSession,
  renderer: SessionRenderer,
  outputKind: WorksheetOutputKind = 'student'
): Promise<SessionResult & { artifactGenerated: boolean }> {
  const processed = processSession(session);
  const mode = session.processing.mode;
  if (mode === null) {
    return { ...processed, artifactGenerated: false, blockers: [...processed.blockers, { code: 'WORKSHEET_MODE_REQUIRED' }] };
  }
  if (processed.blockers.length) return { ...processed, artifactGenerated: false };

  // 模板必须兼容每一行；不能靠随机抽题或逐字展开静默排除缺少题面的内容。
  const allRows = checkGeneratable(sessionInputTokens(session), mode, outputKind);
  if (!allRows.ok) return { ...processed, artifactGenerated: false,
    blockers: allRows.blockers.map(code => ({ code })) };

  // 契约 §3：学生卷与答案卷只校验自身真正需要的字段。
  const feasibility = checkGeneratable(processed.items, mode, outputKind);
  if (!feasibility.ok) {
    return {
      ...processed,
      artifactGenerated: false,
      blockers: [...processed.blockers, ...feasibility.blockers.map(code => ({ code }))]
    };
  }

  const started = beginGeneration(processed.session.workflow);
  const withBusy: WorksheetSession = { ...processed.session, workflow: started.state };
  const fingerprint = sessionFingerprint(withBusy, outputKind);
  const snapshot = freezeSnapshot({
    subject: withBusy.document.subject,
    mode,
    entries: processed.items,
    processing: withBusy.processing,
    layout: withBusy.layout,
    inputDocumentId: withBusy.document.documentId,
    inputRevision: withBusy.document.revision
  });
  try {
    const artifact = await renderer(snapshot);
    return {
      session: {
        ...withBusy,
        workflow: completeGeneration(withBusy.workflow, started.generationId, { ...artifact, kind: outputKind, sessionFingerprint: fingerprint })
      },
      notices: processed.notices,
      blockers: [],
      artifactGenerated: true
    };
  } catch (error) {
    const code = error instanceof Error ? error.message : 'WORKSHEET_GENERATE_FAILED';
    return {
      session: { ...withBusy, workflow: failGeneration(withBusy.workflow, started.generationId, code) },
      notices: processed.notices,
      blockers: [{ code }],
      artifactGenerated: false
    };
  }
}

/**
 * 为本机候选补全缺失读音。
 *
 * 本机自动结果保留 `suggested` 来源标记，有效读音可以直接使用；
 * 已有非空读音保持不变；对齐完整的条目只补空白音节，不推测残缺数组的位置。
 *
 * @param session 当前会话
 * @param suggest 逐字读音候选实现（便于测试注入）
 * @param eligibleIds 本次允许补齐的行标识；不传表示显式补全部空项。
 * @returns 新会话与补全数量
 */
export function fillSuggestedPinyin(
  session: WorksheetSession,
  suggest: (text: string) => { segments: Array<{ grapheme: string; syllable: string }> },
  eligibleIds?: ReadonlySet<string>
): SessionResult & { filled: number } {
  let filled = 0;
  const tokens = session.document.tokens.map((token) => {
    if (eligibleIds && !eligibleIds.has(token.tokenId)) return token;
    // 非空读音无论是否有效都交由用户校对，自动补全只处理真正缺失的音节。
    if (token.pinyinSegments.length && token.pinyinSegments.every(segment => segment.syllable.trim())) return token;
    const text = chineseTextForToken(token);
    if (!text) return token;
    const graphemes = [...new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(text)].map(part => part.segment);
    if (token.pinyinSegments.length && (token.pinyinSegments.length !== graphemes.length
      || token.pinyinSegments.some((segment, index) => segment.grapheme !== graphemes[index]))) return token;
    const suggestion = suggest(text);
    // 新候选也必须逐字对齐，禁止把后续字的拼音前移到缺字位置。
    if (suggestion.segments.length !== graphemes.length
      || suggestion.segments.some((segment, index) => segment.grapheme !== graphemes[index])) return token;
    // 缺音占位必须保留，过滤空项会把后续字的音节移到错误位置。
    const usable = suggestion.segments.map((segment, index) => {
      const existing = token.pinyinSegments[index];
      return existing?.syllable.trim() ? existing : { ...segment,
        syllable: isValidPinyinSyllable(segment.syllable) ? segment.syllable : '' };
    });
    if (!usable.some((segment, index) => segment.syllable && !token.pinyinSegments[index]?.syllable.trim())) return token;
    filled += 1;
    return { ...token, pinyinSegments: usable, pinyinState: 'suggested' as const,
      automaticFields: { ...copyAutomaticFields(token.automaticFields), pinyin: {
        source: 'text' as const, sourceText: text, value: usable.map(segment => segment.syllable).join(' ')
      } } };
  });
  if (!filled) return { session, notices: [], blockers: [], filled: 0 };
  return {
    session: {
      ...session,
      document: { ...session.document, revision: session.document.revision + 1, tokens },
      workflow: onProcessingChanged(session.workflow)
    },
    notices: [{ code: 'WORKSHEET_PINYIN_SUGGESTED', detail: `filled=${filled}` }],
    blockers: [],
    filled
  };
}

/**
 * 逐字修改某项读音。用户手工输入即为最高优先级，直接标为已确认。
 *
 * @param session 当前会话
 * @param tokenId 条目标识
 * @param syllables 空格分隔的带调音节，数量必须与字素一致
 * @returns 新会话与问题
 */
export function updateTokenPinyin(session: WorksheetSession, tokenId: string, syllables: string): SessionResult {
  const token = session.document.tokens.find(item => item.tokenId === tokenId);
  if (!token) return { session, notices: [], blockers: [{ code: 'WORKSHEET_TOKEN_NOT_FOUND' }] };
  const parts = syllables.trim() ? syllables.trim().split(/\s+/u) : [];
  const graphemes = [...new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(chineseTextForToken(token))].map(part => part.segment);
  if (graphemes.length && parts.length && parts.length !== graphemes.length) {
    // 数量不匹配不得硬对齐，否则会把音节错配到别的字上。
    return { session, notices: [], blockers: [{ code: 'WORKSHEET_PINYIN_COUNT_MISMATCH', detail: `expected=${graphemes.length} got=${parts.length}` }] };
  }
  if (parts.some(part => !isValidPinyinSyllable(part))) {
    return { session, notices: [], blockers: [{ code: 'WORKSHEET_PINYIN_INVALID' }] };
  }
  const segments = parts.map((syllable, index) => ({ grapheme: graphemes[index] ?? '', syllable }));
  return {
    session: {
      ...session,
      document: setTokenPinyin(session.document, tokenId, segments, parts.length ? 'confirmed' : 'missing'),
      workflow: onProcessingChanged(session.workflow)
    },
    notices: [],
    blockers: []
  };
}

/**
 * 逐字修改某项释义。用户手工输入即为最高优先级。
 *
 * @param session 当前会话
 * @param tokenId 条目标识
 * @param meaning 释义文本
 * @returns 新会话
 */
export function updateTokenMeaning(session: WorksheetSession, tokenId: string, meaning: string): SessionResult {
  return {
    session: {
      ...session,
      document: setTokenMeaning(session.document, tokenId, meaning.trim() || null, meaning.trim() ? 'confirmed' : 'missing'),
      workflow: onProcessingChanged(session.workflow)
    },
    notices: [],
    blockers: []
  };
}

/** 自动补全英语配对字段；只填空字段，不覆盖教材、人工或已经采用的机器结果。 */
export function applyEnglishSuggestions(session: WorksheetSession, suggestions: readonly EnglishSuggestion[]): SessionResult & { filled: number } {
  if (session.document.subject !== 'english' || tokenize(session.document.rawText, session.document.parsePolicy).blocked) {
    return { session, notices: [], blockers: [], filled: 0 };
  }
  let filled = 0;
  const byId = new Map(suggestions.map(suggestion => [suggestion.tokenId, suggestion]));
  const tokens = session.document.tokens.map(token => {
    const suggestion = byId.get(token.tokenId);
    // 同一标识也要复核原词；调用方的异步任务可能已被用户编辑作废。
    if (!suggestion || suggestion.sourceText !== token.text) return token;
    let next = token;
    if (!token.meaning?.trim() && !englishTextForToken(token) && token.meaningState !== 'confirmed' && isChineseSourceMeaning(token.text)) {
      next = { ...next, meaning: token.text, meaningState: 'confirmed' };
    }
    const english = suggestion.englishText?.trim();
    if (!englishTextForToken(next) && next.englishState !== 'confirmed' && english && isValidEnglishText(english)) {
      next = { ...next, englishText: english, englishState: 'suggested',
        automaticFields: { ...copyAutomaticFields(next.automaticFields), english: {
          source: 'meaning', sourceText: next.meaning?.trim() || suggestion.meaning?.trim() || token.text, value: english
        } } };
    }
    const meaning = suggestion.meaning?.trim();
    if (!next.meaning?.trim() && next.meaningState !== 'confirmed' && meaning && isValidChineseMeaning(meaning)) {
      next = { ...next, meaning, meaningState: 'suggested',
        automaticFields: { ...copyAutomaticFields(next.automaticFields), meaning: {
          source: 'english', sourceText: englishTextForToken(next), value: meaning
        } } };
    }
    if (next !== token) filled += 1;
    return next;
  });
  if (!filled) return { session, notices: [], blockers: [], filled: 0 };
  return {
    session: { ...session, document: { ...session.document, tokens, revision: session.document.revision + 1 }, workflow: onProcessingChanged(session.workflow) },
    notices: [{ code: 'WORKSHEET_ENGLISH_SUGGESTED', detail: `filled=${filled}` }], blockers: [], filled
  };
}

/** 修改一项规范英文；暂时非法的输入也保留显示，但不能继续输出旧产物。 */
export function updateTokenEnglish(session: WorksheetSession, tokenId: string, value: string): SessionResult {
  const token = session.document.tokens.find(item => item.tokenId === tokenId);
  if (!token || session.document.subject !== 'english') return { session, notices: [], blockers: [{ code: 'WORKSHEET_TOKEN_NOT_FOUND' }] };
  const englishText = value.trim();
  const englishState = isValidEnglishText(englishText) ? 'confirmed' as const : 'missing' as const;
  // 英文与中文独立编辑；修改一栏不删除另一栏，也不再次自动翻译。
  const automaticFields = copyAutomaticFields(token.automaticFields);
  if (automaticFields) delete automaticFields.english;
  return {
    session: { ...session,
      document: { ...session.document, revision: session.document.revision + 1,
        tokens: session.document.tokens.map(item => item.tokenId === tokenId
          ? { ...item, englishText, englishState, automaticFields, automaticInitialized: true } : item) },
      workflow: onProcessingChanged(session.workflow) },
    notices: [], blockers: englishText && englishState === 'missing' ? [{ code: 'WORKSHEET_INVALID_ENGLISH' }] : []
  };
}

/**
 * 批量确认明确无歧义的条目。
 *
 * 契约 §3.1：含多音字、缺读音或音节数不匹配的条目**不得**被批量确认跳过。
 *
 * @param session 当前会话
 * @param isAmbiguous 判断字素是否多音；由调用方注入，避免领域层依赖具体拼音库
 * @returns 新会话与确认数量
 */
export function confirmUnambiguousTokens(
  session: WorksheetSession,
  isAmbiguous: (grapheme: string) => boolean
): SessionResult & { confirmed: number } {
  let confirmed = 0;
  const tokens = session.document.tokens.map((token) => {
    if (token.pinyinState === 'confirmed') return token;
    const graphemes = [...new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(chineseTextForToken(token))].map(part => part.segment);
    const hasReading = isValidPinyinForToken(token);
    const ambiguous = graphemes.some(grapheme => isAmbiguous(grapheme));
    if (!hasReading || ambiguous) return token;
    confirmed += 1;
    return { ...token, pinyinState: 'confirmed' as const };
  });
  if (!confirmed) return { session, notices: [], blockers: [], confirmed: 0 };
  return {
    session: {
      ...session,
      document: { ...session.document, revision: session.document.revision + 1, tokens },
      workflow: onProcessingChanged(session.workflow)
    },
    notices: [{ code: 'WORKSHEET_PINYIN_CONFIRMED', detail: `confirmed=${confirmed}` }],
    blockers: [],
    confirmed
  };
}

/** 进入处理步骤。 */
export function enterProcess(session: WorksheetSession): SessionResult {
  return { session: { ...session, workflow: goToProcess(session.workflow) }, notices: [], blockers: [] };
}

/** 从预览回到编辑；未改值时不使产物过期。 */
export function backToEdit(session: WorksheetSession): SessionResult {
  return { session: { ...session, workflow: returnToEdit(session.workflow) }, notices: [], blockers: [] };
}

/** 进入输出步骤；产物过期时不允许。 */
export function enterOutput(session: WorksheetSession): SessionResult {
  if (!sessionCanOutput(session)) return { session, notices: [], blockers: [] };
  return { session: { ...session, workflow: goToOutput(session.workflow) }, notices: [], blockers: [] };
}

/** 清空会话内容与产物，但保留学科。 */
export function clearSession(session: WorksheetSession): SessionResult {
  return {
    session: {
      ...session,
      document: createInputDocument(session.document.subject, session.document.parsePolicy),
      processing: { ...session.processing },
      workflow: clearWorkflow(session.workflow)
    },
    notices: [],
    blockers: []
  };
}

/** 是否可以直接输出。 */
export function sessionCanOutput(session: WorksheetSession): boolean {
  if (!canOutput(session.workflow)) return false;
  if (tokenize(session.document.rawText, session.document.parsePolicy).blocked) return false;
  const artifact = session.workflow.artifact!;
  return artifact.sessionFingerprint === sessionFingerprint(session, artifact.kind);
}

/**
 * 把纯文本追加到原文，保持已有内容不变。
 *
 * @param session 当前会话
 * @param addition 追加内容
 * @returns 新会话
 */
export function appendText(session: WorksheetSession, addition: string): SessionResult {
  return setRawText(session, appendToRawText(session.document.rawText, addition, session.document.parsePolicy));
}

/** 由教材条目构造导入请求；listTypes 只作为取词范围，不影响题型。 */
export function textbookImportRequest(
  entries: readonly CurriculumEntry[],
  editionId: string,
  contentVersion: string,
  label: string,
  mode: ImportMode
): Omit<TextbookImportRequest, 'currentRawText' | 'revision' | 'subject' | 'parsePolicy'> {
  return { entries, editionId, contentVersion, label, mode };
}
