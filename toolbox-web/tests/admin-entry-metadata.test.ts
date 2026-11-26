import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  ADMIN_LIST_TYPE_ORDER, applySourceRefToRows, EMPTY_SOURCE_REF, isSourceRefEmpty, listTypeLabel,
  matchesListTypeFilter, normalizeListTypes, primarySourceRefOf, setListTypesForRows, setPrimarySourceRef,
  sourceRefLabel, sourceRefsOf, toggleListType, toggleListTypeForRow, withSourceRefs
} from '../src/lib/admin-entry-metadata.ts';
import type { DraftEntry } from '../src/lib/admin-textbook-draft.ts';

/**
 * 设计 115 / 297 行：多表类别与条目级来源。
 *
 * 这两块都有"看起来对了、其实在丢数据"的失败模式：
 * - 类别：把"同一条属于多个表"实现成单选，或在重建条目时清空 listTypes；
 * - 来源：整包替换 `preserved`，把界面上没有入口的其它字段一起抹掉。
 * 因此下面的断言刻意针对"保留"与"不越界"。
 */

/** 构造一条草稿条目。 */
function entry(rowId: string, overrides: Partial<DraftEntry> = {}): DraftEntry {
  return {
    rowId,
    entryId: null,
    unitId: null,
    clientUnitId: 'cu1',
    lessonId: null,
    clientLessonId: null,
    text: '山',
    pinyin: 'shān',
    meaning: null,
    listTypes: [],
    confirmed: false,
    ...overrides
  };
}

test('类别可以多选：切换只影响目标类别，且归一化成固定顺序', () => {
  let types = toggleListType([], 'writing');
  assert.deepEqual(types, ['writing']);
  types = toggleListType(types, 'recognition');
  // 同一条属于多个表类别（设计 115 行），且顺序固定，便于比较与展示。
  assert.deepEqual(types, ['recognition', 'writing']);
  types = toggleListType(types, 'writing');
  assert.deepEqual(types, ['recognition']);
});

test('归一化去重并剔除非法值——非法类别不得被发到服务端', () => {
  assert.deepEqual(normalizeListTypes(['writing', 'writing', 'nope', 'recognition']), ['recognition', 'writing']);
  assert.deepEqual(normalizeListTypes([]), []);
});

test('类别中文名与公开端一致（英语的 vocabulary 叫"单词表"）', () => {
  assert.equal(listTypeLabel('recognition', 'chinese'), '识字表');
  assert.equal(listTypeLabel('writing', 'chinese'), '写字表');
  assert.equal(listTypeLabel('vocabulary', 'chinese'), '词语表');
  assert.equal(listTypeLabel('vocabulary', 'english'), '单词表');
  assert.equal(listTypeLabel('expression', 'chinese'), '表达与短语');
  assert.equal(ADMIN_LIST_TYPE_ORDER.length, 4);
});

test('类别筛选：空筛选显示全部；命中"任意一个"所选类别即显示', () => {
  const both = entry('r1', { listTypes: ['recognition', 'writing'] });
  const onlyVocabulary = entry('r2', { listTypes: ['vocabulary'] });
  assert.equal(matchesListTypeFilter(both, []), true, '未选择类别时不过滤');
  assert.equal(matchesListTypeFilter(both, ['writing']), true);
  assert.equal(matchesListTypeFilter(onlyVocabulary, ['recognition', 'writing']), false);
  assert.equal(matchesListTypeFilter(onlyVocabulary, ['vocabulary', 'recognition']), true);
});

test('给范围设置默认类别只改范围内的行，不越界改动其他行', () => {
  const entries = [entry('r1'), entry('r2'), entry('r3')];
  const next = setListTypesForRows(entries, ['r1', 'r2'], ['writing']);
  assert.deepEqual(next[0].listTypes, ['writing']);
  assert.deepEqual(next[1].listTypes, ['writing']);
  assert.deepEqual(next[2].listTypes, [], '范围外的行必须原样保留');
  // 原地不可变：原数组不受影响（否则撤销快照会被污染）。
  assert.deepEqual(entries[0].listTypes, []);
});

test('切换单行类别不触碰其他行', () => {
  const entries = [entry('r1'), entry('r2', { listTypes: ['writing'] })];
  const next = toggleListTypeForRow(entries, 'r1', 'recognition');
  assert.deepEqual(next[0].listTypes, ['recognition']);
  assert.deepEqual(next[1].listTypes, ['writing']);
});

test('来源写入 preserved.sourceRefs，且保留 preserved 里的其它字段', () => {
  // 关键鉴别力：界面上没有 `partOfSpeech` 的入口，但服务端把它放在 preserved 里往返。
  // 若实现整包替换 preserved，这一条会失败——而那正是"改一个字丢光义项"的 bug。
  const original = entry('r1', { preserved: { partOfSpeech: 'noun', sourceRefs: [{ sourceId: '附录A', locator: null, page: 78 }] } });
  const next = setPrimarySourceRef(original, { sourceId: '词语表', locator: '第二列', page: 82 });

  assert.equal(next.preserved?.partOfSpeech, 'noun', '其它保留字段必须原样带回');
  assert.deepEqual(sourceRefsOf(next), [{ sourceId: '词语表', locator: '第二列', page: 82 }]);
  // 服务端 DraftToBookMapper 按 `sourceId/locator/page` 读取，形状必须完全一致。
  assert.deepEqual(next.preserved?.sourceRefs, [{ sourceId: '词语表', locator: '第二列', page: 82 }]);
});

test('多来源时"主来源"只改第一条，其余记录不丢（来自已发布教材的导入）', () => {
  const original = entry('r1', {
    preserved: { sourceRefs: [{ sourceId: '附录A', locator: null, page: 78 }, { sourceId: '附录B', locator: null, page: 90 }] }
  });
  const next = setPrimarySourceRef(original, { sourceId: '校本', locator: '', page: 3 });
  assert.deepEqual(sourceRefsOf(next), [
    { sourceId: '校本', locator: '', page: 3 },
    { sourceId: '附录B', locator: '', page: 90 }
  ]);
});

test('清空主来源删除第一条，其余来源前移；没有来源时不残留空数组', () => {
  const two = entry('r1', {
    preserved: { sourceRefs: [{ sourceId: '附录A', locator: null, page: 78 }, { sourceId: '附录B', locator: null, page: 90 }] }
  });
  assert.deepEqual(sourceRefsOf(setPrimarySourceRef(two, { ...EMPTY_SOURCE_REF })), [{ sourceId: '附录B', locator: '', page: 90 }]);

  const one = entry('r2', { preserved: { sourceRefs: [{ sourceId: '附录A', locator: null, page: 78 }] } });
  const cleared = setPrimarySourceRef(one, { ...EMPTY_SOURCE_REF });
  assert.equal('sourceRefs' in (cleared.preserved ?? {}), false, '清空后不应发送空来源数组');
  assert.equal(cleared.preserved, undefined, 'preserved 变空时应整体删除，不发无意义的空对象');
});

test('给定范围设置默认来源：只覆盖范围内的主来源', () => {
  const entries = [entry('r1'), entry('r2', { preserved: { sourceRefs: [{ sourceId: '原来源', locator: null, page: 1 }] } }), entry('r3')];
  const next = applySourceRefToRows(entries, ['r1', 'r2'], { sourceId: '附录A', locator: '', page: 78 });
  assert.deepEqual(primarySourceRefOf(next[0]), { sourceId: '附录A', locator: '', page: 78 });
  assert.deepEqual(primarySourceRefOf(next[1]), { sourceId: '附录A', locator: '', page: 78 });
  assert.deepEqual(primarySourceRefOf(next[2]), { sourceId: '', locator: '', page: null }, '范围外的行不动');
});

test('结构不符的 preserved.sourceRefs 被忽略而不是猜测（不把字符串硬塞进对象）', () => {
  const weird = entry('r1', { preserved: { sourceRefs: ['附录A', 42, { sourceId: 'ok' }] } });
  assert.deepEqual(sourceRefsOf(weird), [{ sourceId: 'ok', locator: '', page: null }]);
  assert.deepEqual(sourceRefsOf(entry('r2')), [], '没有 preserved 时返回空数组');
  // 非数组（服务端可能存了单对象）一律视为无来源，不擅自转换。
  assert.deepEqual(sourceRefsOf(entry('r3', { preserved: { sourceRefs: { sourceId: 'x' } } })), []);
});

test('withSourceRefs 空数组时删除键，非空时写入；isSourceRefEmpty 判三项皆空', () => {
  const withOne = withSourceRefs(entry('r1'), [{ sourceId: 'A', locator: '', page: 1 }]);
  assert.equal(Array.isArray(withOne.preserved?.sourceRefs), true);
  assert.equal(withSourceRefs(withOne, []).preserved, undefined);
  assert.equal(isSourceRefEmpty({ sourceId: ' ', locator: '', page: null }), true);
  assert.equal(isSourceRefEmpty({ sourceId: '', locator: '', page: 0 }), false, '页码 0 是有效值，不能当成空');
});

test('来源说明可读；空来源有明确文案', () => {
  assert.equal(sourceRefLabel({ sourceId: '附录词语表', locator: '第二列', page: 78 }), '附录词语表 · 第 78 页 · 第二列');
  assert.equal(sourceRefLabel({ ...EMPTY_SOURCE_REF }), '未填写来源');
});
