import assert from 'node:assert/strict';
import test from 'node:test';
import { entriesToPairs, mergeSubset, pairsToEntries, type DraftEntry } from '../src/lib/admin-textbook-draft.ts';

/** 构造包含归属、分类及核对信息的既有条目。 */
function entry(rowId: string, overrides: Partial<DraftEntry> = {}): DraftEntry {
  return {
    rowId, entryId: `entry-${rowId}`, unitId: 'unit-1', clientUnitId: 'cu-1',
    lessonId: 'lesson-1', clientLessonId: 'cl-1', text: '山', pinyin: 'shān', meaning: null,
    listTypes: ['literacy'], confirmed: true, confirmedDigest: 'verified-digest',
    automaticInitialized: true, preserved: { sourceRefs: [{ sourceId: 'source-1', page: 3 }] },
    ...overrides
  };
}

test('跨类型隐藏行不会与当前类型批量新增行共用身份', () => {
  const hidden = [entry('row-1'), entry('row-2'), entry('row-3')];
  const added = pairsToEntries([
    { text: '山水', pinyin: 'shān shuǐ' },
    { text: '白云', pinyin: 'bái yún' },
    { text: '天空', pinyin: 'tiān kōng' }
  ], hidden, 'chinese', { clientUnitId: 'cu-2', clientLessonId: 'cl-2' });
  const merged = mergeSubset(hidden, [], added);

  assert.equal(new Set(merged.map(item => item.rowId)).size, 6);
  assert.deepEqual(merged.slice(0, 3), hidden);
  assert.deepEqual(added.map(item => item.text), ['山水', '白云', '天空']);
  assert.ok(added.every(item => item.clientUnitId === 'cu-2' && item.clientLessonId === 'cl-2'));
});

test('稀疏的全册行标识与批次内新增标识均被避开', () => {
  const existing = [entry('row-4'), entry('row-6'), entry('legacy-entry')];
  const added = pairsToEntries([{ text: '甲' }, { text: '乙' }, { text: '丙' }], existing, 'chinese');

  assert.deepEqual(added.map(item => item.rowId), ['row-5', 'row-7', 'row-8']);
  assert.equal(new Set([...existing, ...added].map(item => item.rowId)).size, 6);
});

test('本批稍后出现的显式身份不能被前面的新增行占用', () => {
  const mapped = pairsToEntries([
    { text: '甲' }, { tokenId: 'row-1', text: '乙' }, { text: '丙' }
  ], [], 'chinese');

  assert.deepEqual(mapped.map(item => item.rowId), ['row-2', 'row-1', 'row-3']);
  assert.deepEqual(mapped.map(item => item.text), ['甲', '乙', '丙']);
});

test('保留旧行身份、分类、来源、核对信息及原有顺序', () => {
  const existing = [entry('row-8'), entry('row-2', { text: '水', pinyin: 'shuǐ', listTypes: ['writing'] })];
  const mapped = pairsToEntries(entriesToPairs(existing, 'chinese'), existing, 'chinese');

  assert.deepEqual(mapped, existing);
});

test('夹入空白行不会错配两栏或影响既有身份和半填行', () => {
  const existing = [entry('row-1')];
  const mapped = pairsToEntries([
    { text: '', pinyin: '' },
    { tokenId: 'row-1', text: '山', pinyin: 'shān' },
    { text: ' ', pinyin: '' },
    { text: '水', pinyin: '' },
    { text: '', pinyin: 'huǒ' }
  ], existing, 'chinese');

  assert.deepEqual(mapped.map(item => [item.text, item.pinyin]), [['山', 'shān'], ['水', null], ['', 'huǒ']]);
  assert.equal(mapped[0].rowId, 'row-1');
  assert.equal(new Set(mapped.map(item => item.rowId)).size, 3);
  assert.equal(mapped[0].confirmed, true);
});

test('英语新增半填行与批量配对行各自保留独立身份', () => {
  const existing = [entry('row-1', { text: 'apple', meaning: '苹果', listTypes: ['vocabulary'] })];
  const mapped = pairsToEntries([
    { meaning: '明天见', english: 'See you tomorrow!' },
    { meaning: '放学后', english: '' },
    { meaning: '', english: 'after school' }
  ], existing, 'english');

  assert.equal(new Set([...existing, ...mapped].map(item => item.rowId)).size, 4);
  assert.deepEqual(mapped.map(item => [item.meaning, item.text]), [
    ['明天见', 'See you tomorrow!'], ['放学后', ''], [null, 'after school']
  ]);
});
