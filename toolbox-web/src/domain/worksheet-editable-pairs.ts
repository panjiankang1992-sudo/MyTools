import {
  appendToRawText, copyAutomaticFields, serializeTokens, syncInputDocument, tokenize,
  type AutomaticFields, type CheckState, type InputToken, type SourceKind
} from './worksheet-input';
import { chineseTextForToken, englishTextForToken, isValidPinyinSyllable, splitGraphemes } from './worksheet-processing';
import { onInputChanged } from './worksheet-workflow';
import type { SessionResult, WorksheetSession } from './worksheet-session';

/** 编辑器的一行配对数据；字段按语义命名，不依赖左右列的显示顺序。 */
export interface EditableWorksheetPair {
  tokenId?: string;
  text?: string;
  pinyin?: string;
  meaning?: string;
  english?: string;
  pinyinState?: CheckState;
  meaningState?: CheckState;
  englishState?: CheckState;
  automaticFields?: AutomaticFields;
  automaticInitialized?: boolean;
}

/** 来源决定初始可信状态；识别结果不能冒充用户已经逐项确认。 */
export type EditablePairSource = Extract<SourceKind, 'custom' | 'image'>;

/** 导出每行的稳定身份及字段状态，返回编辑不会丢失配对关系。 */
export function editablePairsForSession(session: WorksheetSession): EditableWorksheetPair[] {
  return session.document.tokens.map(token => ({ ...(session.document.subject === 'chinese'
    ? { tokenId: token.tokenId, text: chineseTextForToken(token), pinyin: token.pinyinSegments.map(segment => segment.syllable).join(' '), pinyinState: token.pinyinState }
    : { tokenId: token.tokenId, meaning: token.meaning ?? '', english: englishTextForToken(token),
      meaningState: token.meaningState, englishState: token.englishState ?? (englishTextForToken(token) ? 'confirmed' : 'missing') }),
    ...(token.automaticFields ? { automaticFields: copyAutomaticFields(token.automaticFields) } : {}),
    ...(token.automaticInitialized === undefined ? {} : { automaticInitialized: token.automaticInitialized }) }));
}

/** 清理单元格首尾空白，内部换行不允许悄悄生成多条错位数据。 */
function cell(value: string | undefined): string { return (value ?? '').normalize('NFC').trim(); }

/** 兼容旧调用方：原文改变后只解除过期来源记录，已经显示的配对内容始终保留。 */
export function clearStalePairCompletions(row: EditableWorksheetPair): EditableWorksheetPair {
  const fields = copyAutomaticFields(row.automaticFields);
  if (!fields) return row;
  const next: EditableWorksheetPair = { ...row, automaticFields: fields };
  for (const target of ['pinyin', 'meaning', 'english'] as const) {
    const generated = fields[target];
    if (!generated) continue;
    // 人工确认或编辑后解除自动来源，后续改写另一栏不能清掉用户的内容。
    if (next[`${target}State`] !== 'suggested' || cell(next[target]) !== generated.value) {
      delete fields[target];
      continue;
    }
    if (cell(next[generated.source]) !== generated.sourceText) {
      delete fields[target];
    }
  }
  if (!Object.keys(fields).length) delete next.automaticFields;
  return next;
}

/** 非空字段只有实际编辑时才升为人工确认；未改字段保留原有机器状态。 */
function fieldState(value: string, explicit: CheckState | undefined, previous: string, previousState: CheckState | undefined, source: EditablePairSource): CheckState {
  if (!value) return 'missing';
  if (explicit) return explicit;
  if (value === previous && previousState) return previousState;
  return source === 'image' ? 'suggested' : 'confirmed';
}

/** 把配对表原子导入原文；同形词只按稳定标识绑定，新增行绝不借用别行校对。 */
export function importEditablePairsIntoSession(
  session: WorksheetSession,
  rows: readonly EditableWorksheetPair[],
  mode: 'append' | 'replace' = 'replace',
  source: EditablePairSource = 'custom'
): SessionResult {
  const chinese = session.document.subject === 'chinese';
  const activeRows = rows.map(clearStalePairCompletions).filter(row => chinese ? cell(row.text) || cell(row.pinyin) : cell(row.meaning) || cell(row.english));
  const structuralErrors: SessionResult['blockers'] = [];
  const previousById = new Map(session.document.tokens.map(token => [token.tokenId, token]));
  const usedIds = new Set<string>();
  // 先验证整张表，坏行不得使后续中文或读音上移到前一项。
  activeRows.forEach((row, index) => {
    const fields = chinese ? [row.text, row.pinyin] : [row.meaning, row.english];
    if (fields.some(value => /[\r\n]/u.test(cell(value)))) structuralErrors.push({ code: 'WORKSHEET_PAIR_MULTILINE', detail: `row=${index + 1}` });
    if (mode === 'replace' && row.tokenId) {
      if (!previousById.has(row.tokenId) || usedIds.has(row.tokenId)) structuralErrors.push({ code: 'WORKSHEET_TOKEN_NOT_FOUND', detail: `row=${index + 1}` });
      usedIds.add(row.tokenId);
    }
  });
  if (mode === 'append' && tokenize(session.document.rawText, session.document.parsePolicy).blocked) {
    structuralErrors.push({ code: 'WORKSHEET_PAIR_INVALID_BASE' });
  }
  if (structuralErrors.length) return { session, notices: [], blockers: structuralErrors };
  if (!activeRows.length && mode === 'append') return { session, notices: [], blockers: [] };

  const policy = session.document.parsePolicy;
  const texts = activeRows.map(row => chinese ? cell(row.text) || cell(row.pinyin) : cell(row.english) || cell(row.meaning));
  const addition = serializeTokens(texts, policy);
  const rawText = mode === 'append' ? appendToRawText(session.document.rawText, addition, policy) : addition;
  // 用空身份表解析位置，随后显式绑定旧标识，避免按文字自动匹配造成重复词串行。
  const parsed = syncInputDocument({ ...session.document, tokens: [] }, rawText, policy);
  if (parsed.blocked) return { session, notices: [], blockers: parsed.issues.map(issue => ({ code: issue.code })) };
  const prefixCount = mode === 'append' ? session.document.tokens.length : 0;
  const sourceId = `pair-${globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`}`;
  const blockers: SessionResult['blockers'] = [];
  const tokens = parsed.document.tokens.map((fresh, index): InputToken => {
    if (index < prefixCount) return { ...session.document.tokens[index]!, rawStart: fresh.rawStart, rawEnd: fresh.rawEnd };
    const row = activeRows[index - prefixCount]!;
    const old = mode === 'replace' && row.tokenId ? previousById.get(row.tokenId) : undefined;
    const changed = old && (chinese
      ? chineseTextForToken(old) !== cell(row.text) || cell(row.pinyin) !== old.pinyinSegments.map(segment => segment.syllable).join(' ')
      : cell(row.meaning) !== (old.meaning ?? '') || cell(row.english) !== englishTextForToken(old));
    const token: InputToken = { ...fresh, ...(old ?? {}), text: fresh.text, rawStart: fresh.rawStart, rawEnd: fresh.rawEnd,
      automaticInitialized: Boolean(old?.automaticInitialized || row.automaticInitialized),
      sourceRefs: old?.sourceRefs.length ? old.sourceRefs.map(ref => ({ ...ref, localOverride: ref.localOverride || Boolean(changed && source === 'custom') })) : [{
        sourceId, kind: source, editionId: null, contentVersion: null, entryId: null,
        unitId: null, imageId: source === 'image' ? sourceId : null, localOverride: source === 'custom'
      }] };
    // 自动来源必须跟随稳定行身份往返；旧行对象展开不能把被清除的来源重新带回。
    if (row.automaticFields) token.automaticFields = copyAutomaticFields(row.automaticFields);
    else delete token.automaticFields;
    if (chinese) {
      token.chineseText = cell(row.text);
      const pinyin = cell(row.pinyin);
      const parts = pinyin ? pinyin.split(/\s+/u) : [];
      const graphemes = splitGraphemes(token.chineseText);
      // 不截断无效输入；额外音节仍留在编辑表中，让用户能直接修正。
      token.pinyinSegments = parts.map((syllable, position) => ({ grapheme: graphemes[position] ?? '', syllable }));
      token.pinyinState = fieldState(pinyin, row.pinyinState, old?.pinyinSegments.map(segment => segment.syllable).join(' ') ?? '', old?.pinyinState, source);
      if (graphemes.length && parts.length && parts.length !== graphemes.length) blockers.push({ code: 'WORKSHEET_PINYIN_COUNT_MISMATCH', detail: `tokenId=${token.tokenId}` });
      if (parts.some(part => !isValidPinyinSyllable(part))) blockers.push({ code: 'WORKSHEET_PINYIN_INVALID', detail: `tokenId=${token.tokenId}` });
    } else {
      const meaning = cell(row.meaning);
      const english = cell(row.english);
      token.meaning = meaning || null;
      token.meaningState = fieldState(meaning, row.meaningState, old?.meaning ?? '', old?.meaningState, source);
      token.englishText = english;
      token.englishState = fieldState(english, row.englishState, old ? englishTextForToken(old) : '', old?.englishState, source);
      // 正文换了仍保留同一行中文，不借用下一行；旧义项答案只在原词未变时沿用。
      if (old && englishTextForToken(old) !== english) { token.senseId = null; token.acceptableAnswers = []; }
    }
    return token;
  });
  const document = { ...parsed.document, tokens,
    imports: activeRows.some(row => !row.tokenId) ? [...session.document.imports, {
      sourceId, kind: source, label: '', sourceVersion: null, appliedAtRevision: parsed.document.revision
    }] : session.document.imports };
  const comparable = (items: readonly InputToken[]): string => JSON.stringify(items.map(({ rawStart: _start, rawEnd: _end, sourceRefs: _sources, ...token }) => token));
  const equivalent = !session.document.parseIssues.length && comparable(session.document.tokens) === comparable(tokens);
  if (equivalent && rawText === session.document.rawText && !blockers.length) return { session, notices: [], blockers };
  return { session: { ...session, document, workflow: onInputChanged(session.workflow, equivalent) }, notices: [], blockers };
}

/** 单行编辑只修改指定身份，重复项、其它行与未编辑字段均保留。 */
export function updateEditablePair(session: WorksheetSession, tokenId: string, patch: Partial<EditableWorksheetPair>): SessionResult {
  const rows = editablePairsForSession(session);
  const row = rows.find(item => item.tokenId === tokenId);
  if (!row) return { session, notices: [], blockers: [{ code: 'WORKSHEET_TOKEN_NOT_FOUND' }] };
  const next = { ...row, ...patch, tokenId };
  // 修改属于现有内容的人工操作，不改动另一栏，也不重新启动自动补全。
  next.automaticInitialized = true;
  if (patch.pinyin !== undefined) next.pinyinState = cell(patch.pinyin) ? 'confirmed' : 'missing';
  if (patch.meaning !== undefined) next.meaningState = cell(patch.meaning) ? 'confirmed' : 'missing';
  if (patch.english !== undefined) next.englishState = cell(patch.english) ? 'confirmed' : 'missing';
  return importEditablePairsIntoSession(session, rows.map(item => item.tokenId === tokenId ? next : item));
}

/** 删除指定行不会把它的配对字段移给后一个同形词。 */
export function removeEditablePair(session: WorksheetSession, tokenId: string): SessionResult {
  return importEditablePairsIntoSession(session, editablePairsForSession(session).filter(row => row.tokenId !== tokenId));
}
