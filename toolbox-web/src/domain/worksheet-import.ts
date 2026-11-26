import type { CurriculumEntry } from './curriculum';
import {
  appendToRawText, serializeTokens, tokenize,
  type InputImportRecord, type InputSourceRef, type InputSubject, type InputToken,
  type ParsePolicy, type WorksheetInputDocument
} from './worksheet-input';

/**
 * 教材条目转可编辑原文。
 *
 * 契约见 docs/design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md §2.2。
 * 只有条目的 text 进入原文；读音、义项与来源引用作为附带映射保留。
 * 选项变化只改变候选集合，不自动改写原文；替换必须由用户显式选择。
 */

/** 导入写入方式。 */
export type ImportMode = 'append' | 'replace';

/** 一次教材导入的输入。 */
export interface TextbookImportRequest {
  entries: readonly CurriculumEntry[];
  editionId: string;
  contentVersion: string;
  /** 界面上显示这次导入的标签，例如「统编语文一年级上册 · 识字表」。 */
  label: string;
  subject: InputSubject;
  mode: ImportMode;
  /** 当前原文；追加时不会被清空。 */
  currentRawText: string;
  parsePolicy: ParsePolicy;
  /** 当前原文的修订号，用于导入记录。 */
  revision: number;
}

/** 导入过程中发现的问题。 */
export interface ImportIssue {
  code: string;
  /** 出问题的条目在主序列中的下标。 */
  index: number | null;
  detail?: string;
}

/** 一次导入的产物；调用方据此更新文档。 */
export interface TextbookImportResult {
  rawText: string;
  /** 本次导入文本在新原文中的下标区间 [start,end)。 */
  range: { start: number; end: number };
  /** 按序列化顺序排列的来源引用，与导入的条目一一对应。 */
  sources: InputSourceRef[][];
  tokenTexts: string[];
  record: InputImportRecord;
  issues: ImportIssue[];
  /** 有阻断问题时不得写入原文。 */
  blocked: boolean;
}

/** 取出条目的题面文本；实际拼接与转义由 serializeTokens 负责。 */
export function serializeEntries(entries: readonly CurriculumEntry[]): string[] {
  return entries.map(entry => entry.text);
}

/** 由教材条目构造来源引用。 */
export function entrySourceRef(
  entry: CurriculumEntry,
  editionId: string,
  contentVersion: string
): InputSourceRef {
  return {
    sourceId: entry.sourceRefs[0]?.sourceId ?? `${editionId}@${contentVersion}`,
    kind: 'textbook',
    editionId,
    contentVersion,
    entryId: entry.entryId,
    unitId: entry.unitId,
    imageId: null,
    localOverride: false
  };
}

/**
 * 校验教材来源文本。
 *
 * 中文条目内部出现空白说明这条数据本身有问题（中文词内不应有空格），
 * 需要定位报错而不是默默裁掉或替换。
 *
 * @param entries 教材条目
 * @param subject 学科
 * @returns 问题列表
 */
export function validateEntryTexts(entries: readonly CurriculumEntry[], subject: InputSubject): ImportIssue[] {
  const issues: ImportIssue[] = [];
  entries.forEach((entry, index) => {
    if (!entry.text.trim()) {
      issues.push({ code: 'WORKSHEET_IMPORT_EMPTY_TEXT', index, detail: entry.entryId });
      return;
    }
    if (entry.text !== entry.text.trim()) {
      issues.push({ code: 'WORKSHEET_IMPORT_PADDED_TEXT', index, detail: entry.entryId });
    }
    // 中文词内部的空白不可接受；英语短语允许含空格。
    if (subject === 'chinese' && /\s/u.test(entry.text.trim())) {
      issues.push({ code: 'WORKSHEET_IMPORT_INNER_WHITESPACE', index, detail: entry.entryId });
    }
    if (subject === 'english' && entry.text.includes('"')) {
      issues.push({ code: 'WORKSHEET_IMPORT_UNEXPECTED_QUOTE', index, detail: entry.entryId });
    }
  });
  return issues;
}

/**
 * 由教材条目构造一次导入。
 *
 * @param request 导入输入
 * @returns 新原文、导入区间、来源映射与问题；有阻断问题时 `blocked` 为 true
 */
export function buildTextbookImport(request: TextbookImportRequest): TextbookImportResult {
  const { entries, subject, mode, parsePolicy } = request;
  const issues = validateEntryTexts(entries, subject);
  const serialized = serializeTokens(serializeEntries(entries), parsePolicy);

  const base: Omit<TextbookImportResult, 'rawText' | 'range' | 'blocked'> = {
    sources: entries.map(entry => [entrySourceRef(entry, request.editionId, request.contentVersion)]),
    tokenTexts: entries.map(entry => entry.text),
    record: {
      sourceId: request.editionId,
      kind: 'textbook',
      appliedAtRevision: request.revision + 1,
      label: request.label,
      sourceVersion: request.contentVersion
    },
    issues
  };

  if (issues.some(issue => issue.code !== 'WORKSHEET_IMPORT_PADDED_TEXT')) {
    return { ...base, rawText: request.currentRawText, range: { start: 0, end: 0 }, blocked: true };
  }

  if (mode === 'replace') {
    return { ...base, rawText: serialized, range: { start: 0, end: serialized.length }, blocked: false };
  }

  // 追加：新内容接在原文之后，原有内容不变。
  const merged = appendToRawText(request.currentRawText, serialized, parsePolicy);
  const start = merged.length - serialized.length;
  return { ...base, rawText: merged, range: { start, end: merged.length }, blocked: false };
}

/**
 * 把导入区间内的来源引用对齐到解析出的条目上。
 *
 * 区间之外的条目保留文档原有来源；区间之内的条目按出现顺序取用本次导入的来源。
 * 若数量不一致（例如教材文本与解析结果对不上），多余的来源不强行指派，并报出问题。
 *
 * @param rawText 新原文
 * @param policy 分项策略
 * @param range 本次导入的下标区间
 * @param importSources 本次导入的来源，按顺序
 * @param document 当前文档，用于复用区间外的来源
 * @returns 与解析结果等长的来源数组与问题
 */
export function alignImportSources(
  rawText: string,
  policy: ParsePolicy,
  range: { start: number; end: number },
  importSources: ReadonlyArray<readonly InputSourceRef[]>,
  document: WorksheetInputDocument
): { sources: InputSourceRef[][]; issues: ImportIssue[] } {
  const { spans } = tokenize(rawText, policy);
  const issues: ImportIssue[] = [];
  const sources: InputSourceRef[][] = [];
  let importIndex = 0;

  spans.forEach((span, spanIndex) => {
    const inRange = span.rawStart >= range.start && span.rawEnd <= range.end;
    if (inRange) {
      const next = importSources[importIndex];
      if (next) {
        sources.push([...next]);
        importIndex += 1;
      } else {
        sources.push([]);
        issues.push({ code: 'WORKSHEET_IMPORT_SOURCE_SHORTAGE', index: spanIndex, detail: span.text });
      }
      return;
    }
    // 区间外：沿用文档中同位置的来源，避免把已有内容标成本次导入。
    sources.push(document.tokens[spanIndex]?.sourceRefs ?? []);
  });

  if (importIndex < importSources.length) {
    issues.push({ code: 'WORKSHEET_IMPORT_SOURCE_LEFTOVER', index: null, detail: `leftover=${importSources.length - importIndex}` });
  }
  return { sources, issues };
}

/** 教材加载失败的提示；用于「不得回退示例、不得清空已有原文」的界面判断。 */
export interface ImportFailure {
  code: string;
  detail?: string;
  /** 失败时原文保持不变的保证。 */
  preservedRawText: string;
}

/**
 * 教材加载或导入失败时的处理：保留原文，明确报错，不回退示例内容。
 *
 * @param document 当前文档
 * @param code 失败码
 * @param detail 附加信息
 * @returns 失败描述
 */
export function describeImportFailure(
  document: WorksheetInputDocument,
  code: string,
  detail?: string
): ImportFailure {
  return { code, detail, preservedRawText: document.rawText };
}

/** 条目是否与指定版本一致；用于核验分页结果没有混入其它版本。 */
export function matchesVersion(
  entries: readonly CurriculumEntry[],
  editionId: string,
  contentVersion: string
): ImportIssue[] {
  const issues: ImportIssue[] = [];
  entries.forEach((entry, index) => {
    if (entry.editionId !== editionId) {
      issues.push({ code: 'WORKSHEET_IMPORT_EDITION_MISMATCH', index, detail: `${entry.editionId} != ${editionId}` });
    }
    if (entry.entryVersion && entry.entryVersion !== contentVersion) {
      issues.push({ code: 'WORKSHEET_IMPORT_VERSION_MISMATCH', index, detail: `${entry.entryVersion} != ${contentVersion}` });
    }
  });
  return issues;
}

/** 由教材条目直接构造派生条目，供不经过文本编辑的调用方使用。 */
export function entriesToTokens(
  entries: readonly CurriculumEntry[],
  editionId: string,
  contentVersion: string
): InputToken[] {
  return entries.map((entry, index) => ({
    tokenId: `textbook-${entry.entryId || index}`,
    text: entry.text.normalize('NFC'),
    rawStart: 0,
    rawEnd: entry.text.length,
    sourceRefs: [entrySourceRef(entry, editionId, contentVersion)],
    pinyinSegments: entry.pinyinSegments.map(segment => ({ grapheme: segment.grapheme, syllable: segment.syllable })),
    pinyinState: entry.pinyinSegments.length ? 'confirmed' : 'missing',
    meaning: entry.meaning,
    meaningState: entry.meaning ? 'confirmed' : 'missing',
    senseId: entry.senseId || null,
    acceptableAnswers: [...entry.acceptableAnswers],
    issues: []
  }));
}
