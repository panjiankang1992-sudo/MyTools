import assert from 'node:assert/strict';
import test from 'node:test';
import type { CurriculumEntry } from '../src/domain/curriculum';
import { createInputDocument, syncInputDocument, type ParsePolicy } from '../src/domain/worksheet-input';
import {
  alignImportSources, buildTextbookImport, describeImportFailure, entriesToTokens,
  matchesVersion, serializeEntries, validateEntryTexts
} from '../src/domain/worksheet-import';

/** 构造教材条目。 */
function entry(overrides: Partial<CurriculumEntry> & { text: string }): CurriculumEntry {
  return {
    entryId: `e-${overrides.text}`,
    entryVersion: '1.0.2',
    editionId: 'cn-ty-1-1-pep-2024',
    unitId: 'cn-ty-1-1-pep-2024-u01',
    lessonId: 'l01',
    order: 1,
    form: 'word',
    listTypes: ['recognition'],
    pinyinDisplay: null,
    pinyinSegments: [],
    meaning: null,
    partOfSpeech: null,
    senseId: 's1',
    context: null,
    promptContext: null,
    acceptableAnswers: [],
    sourceRefs: [{ sourceId: 'smartedu', locator: 'p110', page: 110 }],
    reviewStatus: 'published',
    blockingIssues: [],
    ...overrides
  };
}

/** 构造导入请求。 */
function request(entries: CurriculumEntry[], overrides: Partial<Parameters<typeof buildTextbookImport>[0]> = {}) {
  return {
    entries,
    editionId: 'cn-ty-1-1-pep-2024',
    contentVersion: '1.0.2',
    label: '统编语文一年级上册 · 识字表',
    subject: 'chinese' as const,
    mode: 'append' as const,
    currentRawText: '',
    parsePolicy: 'whitespace' as ParsePolicy,
    revision: 0,
    ...overrides
  };
}

test('中文词间一个空格，词内不插空格', () => {
  const result = buildTextbookImport(request([entry({ text: '春天' }), entry({ text: '花朵' })]));
  assert.equal(result.rawText, '春天 花朵');
  assert.equal(result.blocked, false);
  assert.deepEqual(result.tokenTexts, ['春天', '花朵']);
});

test('英语含空格短语按引号规则序列化，并能原样解析回来', () => {
  const result = buildTextbookImport(request(
    [entry({ text: 'ice cream' }), entry({ text: 'apple' })],
    { subject: 'english', label: '译林英语三上 · Word list' }
  ));
  assert.equal(result.rawText, '"ice cream" apple');
  // 往返：解析结果必须与导入的条目文本一致。
  const spans = syncInputDocument(createInputDocument('english'), result.rawText, 'whitespace');
  assert.deepEqual(spans.document.tokens.map(token => token.text), ['ice cream', 'apple']);
});

test('多类字表同选时保留重复出现，不在导入阶段去重', () => {
  const entries = [
    entry({ text: '山', entryId: 'a', listTypes: ['recognition'] }),
    entry({ text: '山', entryId: 'b', listTypes: ['writing'] })
  ];
  const result = buildTextbookImport(request(entries));
  assert.equal(result.tokenTexts.length, 2, '导入阶段不得丢掉任何一次出现');
  assert.equal(result.sources.length, 2);
  assert.deepEqual(result.sources.map(refs => refs[0]!.entryId), ['a', 'b']);
});

test('不同读音与义项在导入阶段都被保留', () => {
  const entries = [
    entry({ text: '长', entryId: 'chang', pinyinSegments: [{ grapheme: '长', syllable: 'cháng' }], senseId: 'a' }),
    entry({ text: '长', entryId: 'zhang', pinyinSegments: [{ grapheme: '长', syllable: 'zhǎng' }], senseId: 'b' })
  ];
  const tokens = entriesToTokens(entries, 'cn-ty-1-1-pep-2024', '1.0.2');
  assert.equal(tokens.length, 2);
  assert.deepEqual(tokens.map(token => token.pinyinSegments[0]!.syllable), ['cháng', 'zhǎng']);
  assert.notEqual(tokens[0]!.senseId, tokens[1]!.senseId);
});

test('追加不覆盖已输入的原文，替换才改写', () => {
  const entries = [entry({ text: '山水' })];
  const appended = buildTextbookImport(request(entries, { currentRawText: '我自己写的 内容' }));
  assert.equal(appended.rawText, '我自己写的 内容 山水');
  assert.equal(appended.range.start, '我自己写的 内容 '.length);
  assert.equal(appended.rawText.slice(appended.range.start, appended.range.end), '山水');

  const replaced = buildTextbookImport(request(entries, { currentRawText: '我自己写的', mode: 'replace' }));
  assert.equal(replaced.rawText, '山水');
  assert.deepEqual(replaced.range, { start: 0, end: 2 });
});

test('来源引用带上版本与单元，且区间外的条目不冒充本次导入', () => {
  let document = createInputDocument('chinese');
  // 先有一段自定义原文。
  document = syncInputDocument(document, '自定义', 'whitespace', [[]]).document;
  const entries = [entry({ text: '山水' }), entry({ text: '日月' })];
  const imported = buildTextbookImport(request(entries, { currentRawText: '自定义', revision: document.revision }));
  const aligned = alignImportSources(imported.rawText, 'whitespace', imported.range, imported.sources, document);

  assert.equal(aligned.sources[0]!.length, 0, '区间外的自定义条目不得被标成教材来源');
  assert.equal(aligned.sources[1]![0]!.kind, 'textbook');
  assert.equal(aligned.sources[1]![0]!.contentVersion, '1.0.2');
  assert.equal(aligned.sources[1]![0]!.unitId, 'cn-ty-1-1-pep-2024-u01');
  assert.deepEqual(aligned.issues, []);
});

test('来源数量与解析结果不一致时报错，不强行指派', () => {
  const entries = [entry({ text: '山水' })];
  const imported = buildTextbookImport(request(entries));
  const document = createInputDocument('chinese');
  // 故意只给一个来源，却解析出两个条目。
  const aligned = alignImportSources('山水 日月', 'whitespace', { start: 0, end: 5 }, imported.sources, document);
  assert.ok(aligned.issues.some(issue => issue.code === 'WORKSHEET_IMPORT_SOURCE_SHORTAGE'));
});

test('中文条目内部含空白时定位报错，不默默裁掉', () => {
  const issues = validateEntryTexts([entry({ text: '春 天', entryId: 'bad' })], 'chinese');
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMPORT_INNER_WHITESPACE' && issue.index === 0));
  const result = buildTextbookImport(request([entry({ text: '春 天' })]));
  assert.equal(result.blocked, true);
  assert.equal(result.rawText, '', '阻断时不得写入原文');
});

test('空条目与首尾空白都会被指出', () => {
  const issues = validateEntryTexts([entry({ text: '   ' }), entry({ text: ' 春天 ' })], 'chinese');
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMPORT_EMPTY_TEXT' && issue.index === 0));
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMPORT_PADDED_TEXT' && issue.index === 1));
});

test('英语条目自带双引号会被指出，避免破坏引号包裹', () => {
  const issues = validateEntryTexts([entry({ text: 'say "hi"' })], 'english');
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMPORT_UNEXPECTED_QUOTE'));
});

test('核验分页结果没有混入其它版本', () => {
  const good = [entry({ text: '春天' })];
  assert.deepEqual(matchesVersion(good, 'cn-ty-1-1-pep-2024', '1.0.2'), []);
  const mixed = [entry({ text: '春天', editionId: 'other' }), entry({ text: '花朵', entryVersion: '1.0.1' })];
  const issues = matchesVersion(mixed, 'cn-ty-1-1-pep-2024', '1.0.2');
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMPORT_EDITION_MISMATCH'));
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_IMPORT_VERSION_MISMATCH'));
});

test('加载失败时保留已有原文，不回退示例', () => {
  let document = createInputDocument('chinese');
  document = syncInputDocument(document, '用户已输入的内容', 'whitespace', [[]]).document;
  const failure = describeImportFailure(document, 'WORKSHEET_IMPORT_LOAD_FAILED', 'HTTP 503');
  assert.equal(failure.preservedRawText, '用户已输入的内容');
  assert.equal(failure.code, 'WORKSHEET_IMPORT_LOAD_FAILED');
  assert.equal(document.rawText, '用户已输入的内容');
});

test('导入记录写入版本与标签，便于回编时追溯来源', () => {
  const result = buildTextbookImport(request([entry({ text: '春天' })], { revision: 4, label: '一上 · 识字表' }));
  assert.equal(result.record.sourceVersion, '1.0.2');
  assert.equal(result.record.label, '一上 · 识字表');
  assert.equal(result.record.appliedAtRevision, 5);
  assert.equal(result.record.kind, 'textbook');
});

test('serializeEntries 只取题面文本，不做任何改写', () => {
  const entries = [entry({ text: '春天' }), entry({ text: 'ice cream' })];
  assert.deepEqual(serializeEntries(entries), ['春天', 'ice cream']);
});

test('逐行策略下按行序列化，追加走换行分隔', () => {
  const result = buildTextbookImport(request(
    [entry({ text: '春天' }), entry({ text: '花朵' })],
    { parsePolicy: 'one-per-line', currentRawText: '已有' }
  ));
  assert.equal(result.rawText, '已有\n春天\n花朵');
  assert.equal(result.rawText.slice(result.range.start, result.range.end), '春天\n花朵');
});
