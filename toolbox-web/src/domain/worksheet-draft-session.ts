import { createSession, setParsePolicy, setRawText, type WorksheetSession } from './worksheet-session';
import { copyAutomaticFields, tokenize, type InputSubject, type InputToken } from './worksheet-input';
import {
  validateStoredDraft, WorksheetDraftError,
  type StoredDraftV2, type StoredSourceSummary, type StoredTokenCorrection
} from './worksheet-draft-v2';

/** 页面中独立于会话保存的教材选择与迁移标记。 */
export interface DraftSessionMetadata {
  selection?: StoredDraftV2['selection'];
  sources?: StoredSourceSummary[];
  migratedFromV1?: boolean;
}

/** 从已发生的来源记录与逐项引用构造可恢复摘要，不保存图片本体。 */
function summarizeSources(session: WorksheetSession): StoredSourceSummary[] {
  const references = session.document.tokens.flatMap(token => token.sourceRefs);
  const summaries = session.document.imports.map(imported => {
    const reference = references.find(source => source.sourceId === imported.sourceId);
    return {
      sourceId: imported.sourceId, kind: imported.kind, editionId: reference?.editionId ?? null,
      contentVersion: imported.sourceVersion ?? reference?.contentVersion ?? null, label: imported.label
    };
  });
  for (const reference of references) {
    if (summaries.some(summary => summary.sourceId === reference.sourceId)) continue;
    summaries.push({ sourceId: reference.sourceId, kind: reference.kind, editionId: reference.editionId, contentVersion: reference.contentVersion, label: '' });
  }
  return summaries;
}

/** 将整个会话保存为可序列化草稿；逐项保存位置、来源、读音、义项与版式。 */
export function createStoredDraft(session: WorksheetSession, metadata: DraftSessionMetadata = {}): StoredDraftV2 {
  const parsed = tokenize(session.document.rawText, session.document.parsePolicy);
  const occurrences = new Map<string, number>();
  // 解析错误时原文仍可主动保存，但旧 tokens 不能冒充当前原文的校对结果。
  const matches = !parsed.blocked && parsed.spans.length === session.document.tokens.length && parsed.spans.every((span, index) => span.text === session.document.tokens[index]?.text);
  if (!parsed.blocked && !matches) throw new WorksheetDraftError();
  const corrections = matches ? session.document.tokens.map((token, tokenIndex): StoredTokenCorrection => {
    const occurrence = occurrences.get(token.text) ?? 0;
    occurrences.set(token.text, occurrence + 1);
    return {
      textFingerprint: token.text, tokenIndex, occurrence,
      pinyin: token.pinyinSegments.map(segment => ({ ...segment })), pinyinState: token.pinyinState,
      meaning: token.meaning, meaningState: token.meaningState,
      ...(token.chineseText === undefined ? {} : { chineseText: token.chineseText }),
      ...(token.englishText === undefined ? {} : { englishText: token.englishText }),
      ...(token.englishState === undefined ? {} : { englishState: token.englishState }),
      ...(token.automaticFields === undefined ? {} : { automaticFields: copyAutomaticFields(token.automaticFields) }),
      ...(token.automaticInitialized === undefined ? {} : { automaticInitialized: token.automaticInitialized }),
      sourceRefs: token.sourceRefs.map(source => ({ ...source })), senseId: token.senseId,
      acceptableAnswers: [...token.acceptableAnswers], issues: token.issues.map(issue => ({ ...issue }))
    };
  }) : [];
  return validateStoredDraft(session.document.subject, {
    schemaVersion: 2, subject: session.document.subject, rawText: session.document.rawText,
    parsePolicy: session.document.parsePolicy, corrections,
    sources: metadata.sources ?? summarizeSources(session),
    processing: { ...session.processing }, title: session.layout.title, layout: { ...session.layout },
    selection: metadata.selection ?? {
      grade: null, semester: 'first', editionId: null, selectedIds: [], strategy: session.processing.selection,
      ordering: session.processing.ordering, requestedCount: session.processing.requestedCount, seed: session.processing.seed
    },
    migratedFromV1: metadata.migratedFromV1 ?? false, savedAt: new Date().toISOString()
  });
}

/** 将已验证的校对副本应用到确定的单个 token。 */
function applyCorrection(token: InputToken, correction: StoredTokenCorrection): InputToken {
  return {
    ...token, pinyinSegments: correction.pinyin.map(segment => ({ ...segment })), pinyinState: correction.pinyinState,
    meaning: correction.meaning, meaningState: correction.meaningState,
    ...(correction.chineseText === undefined ? {} : { chineseText: correction.chineseText }),
    ...(correction.englishText === undefined ? {} : { englishText: correction.englishText }),
    ...(correction.englishState === undefined ? {} : { englishState: correction.englishState }),
    ...(correction.automaticFields === undefined ? {} : { automaticFields: copyAutomaticFields(correction.automaticFields) }),
    // 旧草稿没有首次处理标记，恢复时视为已初始化，避免自动改写用户保存的内容。
    automaticInitialized: correction.automaticInitialized ?? true,
    sourceRefs: (correction.sourceRefs ?? []).map(source => ({ ...source })), senseId: correction.senseId ?? null,
    acceptableAnswers: [...(correction.acceptableAnswers ?? [])], issues: (correction.issues ?? []).map(issue => ({ ...issue }))
  };
}

/** 绑定已存校对；无法确定位置的旧版同形项保留待校对状态，绝不跨项套用。 */
function restoreCorrections(tokens: InputToken[], corrections: StoredTokenCorrection[]): InputToken[] {
  const restored: InputToken[] = tokens.map(token => ({ ...token, automaticInitialized: true }));
  const occupied = new Set<number>();
  const byText = new Map<string, number[]>();
  tokens.forEach((token, index) => byText.set(token.text, [...(byText.get(token.text) ?? []), index]));
  const legacy = new Map<string, StoredTokenCorrection[]>();
  for (const correction of corrections) {
    const candidates = byText.get(correction.textFingerprint) ?? [];
    const index = correction.tokenIndex ?? (correction.occurrence === undefined ? undefined : candidates[correction.occurrence]);
    if (index === undefined) {
      legacy.set(correction.textFingerprint, [...(legacy.get(correction.textFingerprint) ?? []), correction]);
      continue;
    }
    if (occupied.has(index) || restored[index]?.text !== correction.textFingerprint) throw new WorksheetDraftError();
    occupied.add(index);
    restored[index] = applyCorrection(restored[index]!, correction);
  }
  for (const [text, records] of legacy) {
    const available = (byText.get(text) ?? []).filter(index => !occupied.has(index));
    if (records.length > available.length) throw new WorksheetDraftError();
    if (records.length === available.length) {
      // 旧写入器按 token 顺序保存；完整同形组可以按出现顺序一对一恢复。
      records.forEach((correction, occurrence) => {
        const index = available[occurrence]!;
        restored[index] = applyCorrection(restored[index]!, correction);
      });
    } else {
      // 旧记录只保存已确认项；少于同形项总数时无法知道用户确认的是哪一项。
      for (const index of available) restored[index] = {
        ...restored[index]!, issues: [...restored[index]!.issues, { code: 'WORKSHEET_DRAFT_AMBIGUOUS_CORRECTION', field: 'document', severity: 'review' }]
      };
    }
  }
  return restored;
}

/** 原子恢复为全新会话；调用方只有成功返回后才替换当前会话，旧产物永不恢复。 */
export function restoreDraftSession(subject: InputSubject, payload: unknown): WorksheetSession {
  const draft = validateStoredDraft(subject, payload);
  const empty = createSession(subject, { ...draft.layout, title: draft.title });
  const configured = setParsePolicy(empty, draft.parsePolicy).session;
  const synced = setRawText(configured, draft.rawText);
  const tokens = synced.blockers.length ? [] : restoreCorrections(synced.session.document.tokens, draft.corrections);
  return {
    ...synced.session,
    document: {
      ...synced.session.document, tokens,
      imports: draft.sources.map(source => ({ sourceId: source.sourceId, kind: source.kind, label: source.label, sourceVersion: source.contentVersion, appliedAtRevision: synced.session.document.revision }))
    },
    processing: { ...draft.processing }, layout: { ...draft.layout, title: draft.title }
  };
}
