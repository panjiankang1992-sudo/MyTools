/**
 * 字帖原文模型与纯函数解析。
 *
 * 契约见 docs/design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md §2。
 * 原文（rawText）是唯一可返编真值；token 只是派生数据，规范化结果不回写原文。
 */

/** 内容来源类别。与题型（mode）无关。 */
export type SourceKind = 'textbook' | 'custom' | 'image' | 'legacy';

/** 分项策略。 */
export type ParsePolicy = 'whitespace' | 'one-per-line';

/** 字段校对状态。 */
export type CheckState = 'missing' | 'suggested' | 'confirmed';

/** 自动补全的实际来源；与 OCR 的 suggested 状态区分，便于原文变化时仅作废旧翻译。 */
export interface AutomaticFieldProvenance {
  source: 'text' | 'meaning' | 'english';
  sourceText: string;
  value: string;
}

/** 按字段保存自动结果的来源，不含网络请求或图片数据。 */
export type AutomaticFields = Partial<Record<'pinyin' | 'meaning' | 'english', AutomaticFieldProvenance>>;

/** 复制自动来源，避免会话、编辑缓冲和草稿共享可变元数据。 */
export function copyAutomaticFields(fields: AutomaticFields | undefined): AutomaticFields | undefined {
  return fields ? Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, { ...value }])) : undefined;
}

/** 学科。 */
export type InputSubject = 'chinese' | 'english';

/** 条目来源引用。 */
export interface InputSourceRef {
  sourceId: string;
  kind: SourceKind;
  editionId: string | null;
  contentVersion: string | null;
  entryId: string | null;
  /** 教材单元标识；仅教材来源有值，用于「按单元均衡」选题。 */
  unitId: string | null;
  imageId: string | null;
  /** 用户改过正文后置位；此时旧读音/义项不再冒充教材匹配结果。 */
  localOverride: boolean;
}

/** 条目问题，error 阻断输出，review 只提示校对。 */
export interface InputIssue {
  code: string;
  field: 'text' | 'pinyin' | 'meaning' | 'document';
  severity: 'error' | 'review';
}

/** 派生条目。 */
export interface InputToken {
  tokenId: string;
  text: string;
  /** 配对输入的汉字正文；显式空串表示仅提供拼音，原始 text 仍保留输入与位置。 */
  chineseText?: string;
  /** UTF-16 下标 [rawStart, rawEnd)，与 textarea.selectionStart 一致。 */
  rawStart: number;
  rawEnd: number;
  sourceRefs: InputSourceRef[];
  pinyinSegments: Array<{ grapheme: string; syllable: string }>;
  pinyinState: CheckState;
  meaning: string | null;
  meaningState: CheckState;
  /** 中文或混合来源对应的规范英文；原始 text 与 rawText 保持不变。 */
  englishText?: string | null;
  /** 自动翻译保留 suggested；人工改写标为 confirmed。 */
  englishState?: CheckState;
  automaticFields?: AutomaticFields;
  /** 每条内容只自动初始化一次；失败、取消和手工清空后也保留此标记。 */
  automaticInitialized?: boolean;
  senseId: string | null;
  acceptableAnswers: string[];
  issues: InputIssue[];
}

/** 已发生的导入记录，不是自动恢复来源的指令。 */
export interface InputImportRecord {
  sourceId: string;
  kind: SourceKind;
  appliedAtRevision: number;
  label: string;
  sourceVersion: string | null;
}

/** 原文文档。 */
export interface WorksheetInputDocument {
  schemaVersion: 2;
  documentId: string;
  subject: InputSubject;
  revision: number;
  rawText: string;
  parsePolicy: ParsePolicy;
  /** 当前原文的解析问题；旧 tokens 只作返编恢复，不能替代解析成功。 */
  parseIssues: ParseIssue[];
  tokens: InputToken[];
  imports: InputImportRecord[];
}

/** 原文与解析限额（契约 §2.3）。 */
export const INPUT_LIMITS = Object.freeze({
  /** 原文 UTF-8 字节上限。 */
  rawTextBytes: 64 * 1024,
  /** 一次解析最多 token 数。 */
  maxTokens: 2000,
  /** 一次选入题单最多条目数。 */
  maxSelected: 200,
  /** 重复展开后最多题目数。 */
  maxQuestions: 500
});

/** 解析问题。 */
export interface ParseIssue {
  code: string;
  /** 出现问题的 UTF-16 下标；文档级问题为 null。 */
  offset: number | null;
  line: number;
  column: number;
  detail?: string;
}

/** tokenizer 产出的原始项。 */
export interface RawSpan {
  /** 派生文本：NFC、去首尾空白、引用短语内部空白折叠为一个空格、去掉包裹引号。 */
  text: string;
  /** 原文下标区间（不含包裹引号之外的意义：区间覆盖含引号的完整片段）。 */
  rawStart: number;
  rawEnd: number;
  /** 是否来自双引号包裹。 */
  quoted: boolean;
}

/** 解析结果。 */
export interface TokenizeResult {
  spans: RawSpan[];
  issues: ParseIssue[];
  /** 存在阻断问题时不得进入下一步。 */
  blocked: boolean;
}

/** 支持的空白分隔符：普通空格、Tab、换行、回车、全角空格。 */
const WHITESPACE = new Set([' ', '\t', '\n', '\r', '\u3000', '\u00a0']);

/** 不允许出现的不可见控制字符（换行与 Tab 除外）。 */
function isForbiddenControl(code: number): boolean {
  if (code === 0x09 || code === 0x0a || code === 0x0d) return false;
  return code < 0x20 || (code >= 0x7f && code <= 0x9f);
}

/** 计算 offset 对应的行列，行列均从 1 开始。 */
export function locate(rawText: string, offset: number): { line: number; column: number } {
  let line = 1;
  let lineStart = 0;
  for (let index = 0; index < offset && index < rawText.length; index += 1) {
    if (rawText[index] === '\n') {
      line += 1;
      lineStart = index + 1;
    }
  }
  return { line, column: offset - lineStart + 1 };
}

/** 生成问题对象。 */
function issue(rawText: string, code: string, offset: number | null, detail?: string): ParseIssue {
  const position = offset === null ? { line: 1, column: 1 } : locate(rawText, offset);
  return { code, offset, line: position.line, column: position.column, ...(detail ? { detail } : {}) };
}

/** 派生文本规范化：NFC、去首尾空白、引用短语内部空白折叠为一个普通空格。 */
export function normalizeTokenText(value: string): string {
  return value.normalize('NFC').trim().replace(/\s+/gu, ' ');
}

/** 原文 UTF-8 字节数。 */
export function rawTextBytes(rawText: string): number {
  return new TextEncoder().encode(rawText).length;
}

/**
 * 把原文切成原始项。
 *
 * 状态机覆盖四种状态：空白、未引用文本、双引号文本、转义。
 * 中文与英文使用同一实现；差异只体现在调用方对结果的校验上。
 *
 * @param rawText 用户原文，保持不改写
 * @param policy 分项策略；`one-per-line` 只按换行分项，行内空格保留
 * @returns 原始项与问题；存在阻断问题时 `blocked` 为 true
 */
export function tokenize(rawText: string, policy: ParsePolicy): TokenizeResult {
  const issues: ParseIssue[] = [];
  const spans: RawSpan[] = [];

  const byteLength = rawTextBytes(rawText);
  if (byteLength > INPUT_LIMITS.rawTextBytes) {
    issues.push(issue(rawText, 'WORKSHEET_INPUT_LIMIT', null,
      `rawTextBytes=${byteLength} limit=${INPUT_LIMITS.rawTextBytes}`));
    return { spans, issues, blocked: true };
  }

  // 不可见控制字符先定位报错，不静默剔除。
  for (let index = 0; index < rawText.length; index += 1) {
    const code = rawText.charCodeAt(index);
    if (isForbiddenControl(code)) {
      issues.push(issue(rawText, 'WORKSHEET_INPUT_CONTROL_CHARACTER', index, `U+${code.toString(16).toUpperCase().padStart(4, '0')}`));
    }
  }
  if (issues.length) return { spans, issues, blocked: true };

  let index = 0;
  let unclosedQuoteAt = -1;

  /** 记录一个片段。 */
  const push = (start: number, end: number, body: string, quoted: boolean): void => {
    const text = normalizeTokenText(body);
    // 空片段不成题（连续空白或空引号）。
    if (!text) return;
    spans.push({ text, rawStart: start, rawEnd: end, quoted });
  };

  while (index < rawText.length) {
    const character = rawText[index]!;
    const newlineOnly = policy === 'one-per-line';

    if (character === '"') {
      // 引用短语：允许内部空白，支持 \" 与 \\。
      const start = index;
      index += 1;
      let body = '';
      let closed = false;
      while (index < rawText.length) {
        const current = rawText[index]!;
        if (current === '\\' && index + 1 < rawText.length) {
          const next = rawText[index + 1]!;
          if (next === '"' || next === '\\') {
            body += next;
            index += 2;
            continue;
          }
        }
        if (current === '"') {
          closed = true;
          index += 1;
          break;
        }
        // 逐行模式下引用内部换行视为普通字符；空白模式同样允许，最后折叠为单空格。
        body += current;
        index += 1;
      }
      if (!closed) unclosedQuoteAt = start;
      else push(start, index, body, true);
      if (!closed) break;
      continue;
    }

    const isSeparator = newlineOnly ? (character === '\n' || character === '\r') : WHITESPACE.has(character);
    if (isSeparator) {
      index += 1;
      continue;
    }

    // 未引用文本：读到下一个分隔符或引号为止。
    const start = index;
    let body = '';
    while (index < rawText.length) {
      const current = rawText[index]!;
      if (current === '"') break;
      const separator = newlineOnly ? (current === '\n' || current === '\r') : WHITESPACE.has(current);
      if (separator) break;
      body += current;
      index += 1;
    }
    push(start, index, body, false);
  }

  if (unclosedQuoteAt >= 0) {
    issues.push(issue(rawText, 'WORKSHEET_PARSE_QUOTE', unclosedQuoteAt));
    return { spans, issues, blocked: true };
  }
  if (spans.length > INPUT_LIMITS.maxTokens) {
    issues.push(issue(rawText, 'WORKSHEET_INPUT_LIMIT', null,
      `tokens=${spans.length} limit=${INPUT_LIMITS.maxTokens}`));
    return { spans, issues, blocked: true };
  }
  return { spans, issues, blocked: false };
}

/** 需要显式清理的标点：默认不作为词边界。 */
const BOUNDARY_PUNCTUATION = new Set([',', ';', '，', '；', '、', '。', '：', ':']);

/** 找出可作为词边界的标点位置，供「转为空格分隔」这一显式操作使用。 */
export function findBoundaryPunctuation(rawText: string): number[] {
  const positions: number[] = [];
  let insideQuote = false;
  for (let index = 0; index < rawText.length; index += 1) {
    const character = rawText[index]!;
    if (character === '"') {
      insideQuote = !insideQuote;
      continue;
    }
    if (insideQuote) continue;
    if (BOUNDARY_PUNCTUATION.has(character)) positions.push(index);
  }
  return positions;
}

/** 标点清理结果，可原样撤销。 */
export interface PunctuationCleanup {
  text: string;
  /** 被替换的标点数量。 */
  replaced: number;
}

/**
 * 把词边界标点替换为指定分隔符。必须由用户显式触发，并保留原文以便撤销。
 *
 * @param rawText 当前原文
 * @param separator 当前分项方式对应的空格或换行。
 * @returns 清理后的文本与被替换数量；未发现标点时原样返回
 */
export function cleanupBoundaryPunctuation(rawText: string, separator: ' ' | '\n' = ' '): PunctuationCleanup {
  const positions = findBoundaryPunctuation(rawText);
  if (!positions.length) return { text: rawText, replaced: 0 };
  const characters = [...rawText];
  for (const position of positions) characters[position] = separator;
  return { text: characters.join(''), replaced: positions.length };
}

/** 由 token 序列序列化为原文。教材导入与「追加」使用。 */
export function serializeTokens(texts: readonly string[], policy: ParsePolicy = 'whitespace'): string {
  if (policy === 'one-per-line') return texts.join('\n');
  return texts
    .map(text => (/\s/u.test(text) ? `"${text.replace(/\\/gu, '\\\\').replace(/"/gu, '\\"')}"` : text))
    .join(' ');
}

/** 追加导入内容到已有原文，返回新原文。 */
export function appendToRawText(rawText: string, addition: string, policy: ParsePolicy = 'whitespace'): string {
  const trimmed = addition.trim();
  if (!trimmed) return rawText;
  if (!rawText.trim()) return trimmed;
  const separator = policy === 'one-per-line' ? '\n' : ' ';
  return `${rawText.replace(/\s+$/u, '')}${separator}${trimmed}`;
}

/** 生成新的本地标识。 */
function newId(prefix: string): string {
  const random = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}-${random}`;
}

/** 条目的来源判定键：来源变化时不得沿用旧修正。 */
function sourceKey(refs: readonly InputSourceRef[]): string {
  return refs.map(ref => `${ref.kind}:${ref.editionId ?? ''}:${ref.entryId ?? ''}:${ref.imageId ?? ''}`).sort().join('|');
}

/** 由原始项创建新 token。 */
function createToken(span: RawSpan): InputToken {
  return {
    tokenId: newId('tok'),
    text: span.text,
    rawStart: span.rawStart,
    rawEnd: span.rawEnd,
    sourceRefs: [],
    pinyinSegments: [],
    pinyinState: 'missing',
    meaning: null,
    meaningState: 'missing',
    senseId: null,
    acceptableAnswers: [],
    issues: []
  };
}

/** 创建空文档。新建练习默认为空，不自动塞入示例。 */
export function createInputDocument(subject: InputSubject, policy: ParsePolicy = 'whitespace'): WorksheetInputDocument {
  return {
    schemaVersion: 2,
    documentId: newId('doc'),
    subject,
    revision: 0,
    rawText: '',
    parsePolicy: policy,
    parseIssues: [],
    tokens: [],
    imports: []
  };
}

/**
 * 按新原文重新解析，并对旧 token 做稳定的一对一匹配。
 *
 * 只有「文本相同且来源键相同」的旧条目才延续 tokenId 与已确认的读音/义项；
 * 文本被改动的条目视为新条目，旧修正自动失效，避免把旧读音错配到新字上。
 * 不使用 `map[text]` 合并同字，避免多音字互相覆盖。
 *
 * @param document 当前文档
 * @param rawText 新原文
 * @param policy 新的分项策略
 * @param sources 由调用方提供的新来源引用，按原始项顺序对齐
 * @returns 更新后的文档与解析问题
 */
export function syncInputDocument(
  document: WorksheetInputDocument,
  rawText: string,
  policy: ParsePolicy,
  sources: ReadonlyArray<readonly InputSourceRef[]> = []
): { document: WorksheetInputDocument; issues: ParseIssue[]; blocked: boolean } {
  const result = tokenize(rawText, policy);
  if (result.blocked) {
    // 解析失败时保留旧 token，允许用户继续修正原文。
    return {
      document: { ...document, rawText, parsePolicy: policy, parseIssues: result.issues, revision: document.revision + 1 },
      issues: result.issues,
      blocked: true
    };
  }

  const unused = new Set(document.tokens.map(token => token.tokenId));
  const byId = new Map(document.tokens.map(token => [token.tokenId, token]));
  const tokens: InputToken[] = [];
  const anchored = new Map<number, InputToken>();
  const reserved = new Set<string>();
  // 先定位编辑区间之外的原文，删除前一个同形词不能把它的义项转移给后一个。
  if (!document.parseIssues.length) {
    let prefix = 0;
    let suffix = 0;
    const oldRaw = document.rawText;
    while (prefix < oldRaw.length && prefix < rawText.length && oldRaw[prefix] === rawText[prefix]) prefix += 1;
    while (suffix < oldRaw.length - prefix && suffix < rawText.length - prefix
      && oldRaw[oldRaw.length - 1 - suffix] === rawText[rawText.length - 1 - suffix]) suffix += 1;
    const shift = rawText.length - oldRaw.length;
    result.spans.forEach((span, index) => {
      const delta = span.rawEnd <= prefix ? 0 : span.rawStart >= rawText.length - suffix ? shift : null;
      if (delta === null) return;
      const refs = sources[index] ?? [];
      const match = document.tokens.find(token => token.text === span.text
        && token.rawStart === span.rawStart - delta && token.rawEnd === span.rawEnd - delta
        && (!refs.length || sourceKey(token.sourceRefs) === sourceKey(refs)));
      if (match) { anchored.set(index, match); reserved.add(match.tokenId); }
    });
  }

  result.spans.forEach((span, index) => {
    const refs = [...(sources[index] ?? [])];
    const key = sourceKey(refs);
    // 调用方没有提供来源（普通文本编辑）时只按文本匹配，并保留旧来源与已确认读音；
    // 否则「导入教材后再多打一个字」就会把之前所有条目的读音清空。
    const hasProvidedSources = refs.length > 0;
    // 一对一匹配：优先取尚未使用、文本相同（且在提供来源时来源键也相同）的旧条目。
    let matched: InputToken | undefined = anchored.get(index);
    const candidates = [...unused].map(id => byId.get(id)!).filter(candidate => !reserved.has(candidate.tokenId)
      && candidate.text === span.text && (!hasProvidedSources || sourceKey(candidate.sourceRefs) === key));
    const remainingCount = result.spans.slice(index).filter((remaining, offset) => !anchored.has(index + offset)
      && remaining.text === span.text && (!hasProvidedSources || sourceKey(sources[index + offset] ?? []) === key)).length;
    // 未编辑区间已优先保留；剩余重复组数量不一致时不猜测原有校对属于哪一项。
    if (!matched && (candidates.length === 1 || candidates.length === remainingCount)) matched = candidates[0];
    if (matched) {
      unused.delete(matched.tokenId);
      tokens.push({
        ...matched,
        rawStart: span.rawStart,
        rawEnd: span.rawEnd,
        sourceRefs: refs.length ? refs : matched.sourceRefs
      });
      return;
    }
    tokens.push({ ...createToken(span), sourceRefs: refs });
  });

  return {
    document: { ...document, rawText, parsePolicy: policy, parseIssues: result.issues, revision: document.revision + 1, tokens },
    issues: result.issues,
    blocked: false
  };
}

/** 标记某条目的读音为已确认或待校对；用户自定义读音只保存在本机。 */
export function setTokenPinyin(
  document: WorksheetInputDocument,
  tokenId: string,
  segments: Array<{ grapheme: string; syllable: string }>,
  state: CheckState
): WorksheetInputDocument {
  return {
    ...document,
    revision: document.revision + 1,
    tokens: document.tokens.map(token => {
      if (token.tokenId !== tokenId) return token;
      const automaticFields = copyAutomaticFields(token.automaticFields);
      if (automaticFields) delete automaticFields.pinyin;
      return { ...token, pinyinSegments: segments, pinyinState: state, automaticFields, automaticInitialized: true,
        sourceRefs: token.sourceRefs.map(ref => ({ ...ref, localOverride: true })) };
    })
  };
}

/** 设置条目的中文释义。 */
export function setTokenMeaning(
  document: WorksheetInputDocument,
  tokenId: string,
  meaning: string | null,
  state: CheckState
): WorksheetInputDocument {
  return {
    ...document,
    revision: document.revision + 1,
    tokens: document.tokens.map(token => {
      if (token.tokenId !== tokenId) return token;
      const automaticFields = copyAutomaticFields(token.automaticFields);
      if (automaticFields) delete automaticFields.meaning;
      // 另一栏属于用户当前内容，改写或清空中文时不联动删除英语。
      return { ...token, meaning, meaningState: state, automaticFields, automaticInitialized: true };
    })
  };
}
