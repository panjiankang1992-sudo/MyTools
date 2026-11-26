import assert from 'node:assert/strict';
import test from 'node:test';
import {
  RENDERER_VERSION,
  computeContentHash,
  computeRenderHash,
  createLayout,
  createSnapshot,
  defaultGridMm,
  toCustomWorksheetEntry,
  toWorksheetEntries,
  validateScale,
  validateTitle
} from '../src/domain/worksheet-snapshot';
import type { SelectionResult } from '../src/domain/selection';
import type { CurriculumEntry } from '../src/domain/curriculum';

/** 构造用于快照的教材条目。 */
function entry(entryId: string, text: string): CurriculumEntry {
  return {
    entryId,
    entryVersion: 'v1',
    editionId: 'edition-1',
    unitId: 'unit-1',
    lessonId: null,
    order: 0,
    form: 'word',
    listTypes: ['vocabulary'],
    text,
    pinyinDisplay: 'pīn yīn',
    pinyinSegments: [],
    meaning: 'meaning',
    partOfSpeech: null,
    senseId: `${entryId}:sense`,
    context: null,
    promptContext: null,
    acceptableAnswers: [],
    sourceRefs: [],
    reviewStatus: 'published',
    blockingIssues: []
  };
}

const plan: SelectionResult = { entries: [], requested: 0, available: 0, shortage: false, seed: 'seed', algorithm: 'all-v1', quotaByUnit: [], blocked: [] };

/** 组装一个完整快照，便于比较指纹差异。 */
function snapshot(overrides: { title?: string; gridMm?: 10 | 12 | 14; texts?: string[] } = {}) {
  const entries = toWorksheetEntries((overrides.texts ?? ['山水', '风云']).map((text, index) => entry(`e${index}`, text)), { editionId: 'edition-1', contentVersion: 'v1', contentSha256: 'hash' });
  return createSnapshot({
    worksheetId: 'worksheet-1',
    operationId: 'operation-1',
    revision: 1,
    subject: 'chinese',
    mode: 'chinese-dictation',
    entries,
    selection: plan,
    selectionStrategy: 'all',
    ordering: 'textbook',
    repetitions: 1,
    title: overrides.title ?? '练习',
    layout: { ...createLayout(2), gridMm: overrides.gridMm ?? 14 },
    contentRefs: [{ editionId: 'edition-1', contentVersion: 'v1', sha256: 'hash' }],
      inputDocumentId: 'doc-1',
      inputRevision: 1,
      parsePolicy: 'whitespace',
      chineseUnit: 'word',
      duplicatePolicy: 'merge-equivalent'
  });
}

test('content hash ignores title and grid size while render hash tracks them', () => {
  const base = snapshot();
  const renamed = snapshot({ title: '另一个标题' });
  const resized = snapshot({ gridMm: 10 });
  assert.equal(base.contentHash, renamed.contentHash, '纯版式变化不改变内容身份');
  assert.notEqual(base.renderHash, renamed.renderHash, '标题变化应改变排版指纹');
  assert.equal(base.contentHash, resized.contentHash);
  assert.notEqual(base.renderHash, resized.renderHash);
});

test('content hash changes when the question set or mode changes', () => {
  const base = snapshot();
  const different = snapshot({ texts: ['山水'] });
  assert.notEqual(base.contentHash, different.contentHash);
  const compute = computeContentHash({ mode: 'chinese-pinyin', repetitions: 1, ordering: 'textbook', selectionAlgorithm: 'all-v1', seed: null, orderedEntries: base.orderedEntries, parsePolicy: 'whitespace', chineseUnit: 'word', duplicatePolicy: 'merge-equivalent' });
  assert.notEqual(base.contentHash, compute);
});

test('render hash includes font and renderer version', () => {
  const base = snapshot();
  const altered = { ...base, fontVersion: 'other-font' };
  assert.notEqual(computeRenderHash(base), computeRenderHash(altered));
});

test('snapshot entries freeze their source references and remain immutable', () => {
  const built = snapshot();
  assert.equal(built.orderedEntries[0]!.sourceEntryRef?.contentVersion, 'v1');
  assert.ok(Object.isFrozen(built));
  assert.throws(() => { (built as { revision: number }).revision = 99; }, TypeError);
});

test('custom entries never claim textbook review status', () => {
  const custom = toCustomWorksheetEntry({ entryId: 'custom:1', text: '苹果', pinyinDisplay: null, meaning: 'apple', form: 'word', listTypes: ['vocabulary'], order: 0 });
  assert.equal(custom.origin, 'custom');
  assert.equal(custom.editionId, null);
  assert.equal(custom.unitId, null);
  assert.equal(custom.reviewStatus, 'local-validated');
  assert.equal(custom.sourceEntryRef, null);
});

test('grid defaults follow grade and manual choice is preserved by the caller', () => {
  assert.equal(defaultGridMm(1), 14);
  assert.equal(defaultGridMm(2), 14);
  assert.equal(defaultGridMm(3), 12);
  assert.equal(defaultGridMm(6), 12);
  assert.equal(createLayout(1).gridMm, 14);
  assert.equal(createLayout(4).gridMm, 12);
  assert.equal(createLayout(1, true).gridMmUserSet, true);
});

test('title validation rejects over-long input instead of truncating', () => {
  assert.equal(validateTitle('正常标题'), 'ok');
  assert.equal(validateTitle('字'.repeat(40)), 'ok');
  assert.equal(validateTitle('字'.repeat(41)), 'titleTooLong');
  // 补充平面字符按码点计数，不能被拆成两个代理项。
  assert.equal(validateTitle('𠀀'.repeat(40)), 'ok');
  assert.equal(validateTitle('𠀀'.repeat(41)), 'titleTooLong');
});

test('scale validation enforces the documented first-release limits', () => {
  assert.equal(validateScale(200, 1), 'ok');
  assert.equal(validateScale(201, 1), 'tooManyEntries');
  assert.equal(validateScale(100, 5), 'ok');
  assert.equal(validateScale(101, 5), 'tooManyQuestions');
  assert.equal(validateScale(10, 0), 'invalidRepetitions');
  assert.equal(validateScale(10, 6), 'invalidRepetitions');
});

test('排版指纹必须包含内容指纹：同数量不同内容不得得到同一指纹', () => {
  // 契约 §2.1：renderHash 只含条数时，同数量不同内容会碰撞。
  const left = snapshot({ texts: ['山水', '风云'] });
  const right = snapshot({ texts: ['日月', '星辰'] });
  assert.equal(left.orderedEntries.length, right.orderedEntries.length, '两个夹具条数相同');
  assert.notEqual(left.contentHash, right.contentHash);
  assert.notEqual(left.renderHash, right.renderHash, '内容不同必须导致排版指纹不同');
});

test('内容指纹包含分项策略、逐字策略与去重策略', () => {
  const base = snapshot();
  assert.notEqual(base.contentHash, computeContentHash({ ...base, parsePolicy: 'one-per-line' }));
  assert.notEqual(base.contentHash, computeContentHash({ ...base, chineseUnit: 'character' }));
  assert.notEqual(base.contentHash, computeContentHash({ ...base, duplicatePolicy: 'keep-occurrences' }));
});

test('快照记录 v2 结构与原文身份', () => {
  const base = snapshot();
  assert.equal(base.schemaVersion, 2);
  assert.equal(base.inputDocumentId, 'doc-1');
  assert.equal(base.inputRevision, 1);
  assert.equal(base.parsePolicy, 'whitespace');
  assert.equal(RENDERER_VERSION, 'worksheet-renderer-v2', '排版算法已整体更换，渲染器版本必须递增');
});

test('快照深冻结：嵌套拼音、来源引用与可接受答案都不能被改动', () => {
  const base = snapshot();
  const entry = base.orderedEntries[0]!;
  assert.ok(Object.isFrozen(base));
  assert.ok(Object.isFrozen(entry), '条目本身必须冻结');
  assert.ok(Object.isFrozen(entry.pinyinSegments), '嵌套拼音片段必须冻结');
  assert.ok(Object.isFrozen(entry.acceptableAnswers), '可接受答案必须冻结');
  assert.ok(Object.isFrozen(entry.sourceRefs), '来源引用必须冻结');
  assert.ok(Object.isFrozen(entry.listTypes), '清单类别必须冻结');
  // 冻结后写入必须抛错（严格模式下），证明不是只做了浅拷贝。
  assert.throws(() => { (entry as { text: string }).text = 'changed'; }, TypeError);
});
