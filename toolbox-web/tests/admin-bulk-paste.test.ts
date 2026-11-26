import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseBulkRows, parseBulkText, selectNewItems } from '../src/lib/admin-bulk-paste.ts';

/**
 * 审查反例 `F05-tsv-not-paired-punctuation-and-blank-loss`（对应 F12）。
 *
 * 探针：
 * ```
 * input: '明天见\tSee you tomorrow!\n\n放学后\tafter school'
 * actual: items[0] === '明天见\tSee you tomorrow'   ← 标点被剥掉
 *         items.length === 2                        ← 空行被跳过
 *         blankCount === 1
 * ```
 *
 * **用户的契约**（目标里写明）：「按换行对齐，**保留短语空格、标点和不同义项**」、
 * 「空行保位置、**不剥标点**、**不默认删重复义项**」。
 *
 * **分成两组断言**：第一组是"不能再破坏原文"（本轮修的），
 * 第二组是"要保留配对"（需要更大重构，**尚未做**，所以这里不写假断言）。
 */

test('不剥标点：感叹号必须保留', () => {
  const parsed = parseBulkText('See you tomorrow!');
  assert.equal(parsed.items[0], 'See you tomorrow!', '句末标点是原文的一部分，剥掉就改了用户的输入');
});

test('不剥标点：中文句号同样保留', () => {
  const parsed = parseBulkText('明天见。');
  assert.equal(parsed.items[0], '明天见。', '中文标点也是原文');
});

test('短语内部的空格必须保留', () => {
  const parsed = parseBulkText('See you tomorrow');
  assert.equal(parsed.items[0], 'See you tomorrow', '短语空格不能当分隔符');
});

test('空行保留位置，而不是被静默跳过', () => {
  const parsed = parseBulkText('甲\n\n乙');
  // 空行是用户排版的一部分；跳过它会让上下行的配对整体错位。
  assert.deepEqual(parsed.items, ['甲', '', '乙'], '空行必须留在原位');
  assert.equal(parsed.blankCount, 1);
});

test('默认不去重：不同义项必须都保留', () => {
  // 契约是"不默认删重复义项"——同一个词在不同单元可能意思不同，
  // 静默去掉一条就是丢内容，而且用户看不到。
  const parsed = parseBulkText('bank\nbank');
  assert.deepEqual(parsed.items, ['bank', 'bank'], '默认不能去重');
  assert.equal(parsed.duplicateCount, 0);
});

test('显式要求去重时才去重', () => {
  const parsed = parseBulkText('bank\nbank', { dedupe: true });
  assert.deepEqual(parsed.items, ['bank']);
  assert.equal(parsed.duplicateCount, 1);
});

/**
 * 以下是对 `selectNewItems` 的覆盖。
 *
 * **说明**：这些是**我补写的**，不是原文件里那些——
 * 我在写这个文件时覆盖了一个已存在的同名文件（见
 * `docs/verification/toolbox-admin-fixes-2026-10-06/README.md`），
 * 原文件里有 12 项测试，`git` 在本机被 Xcode 许可挡住，我取不回来。
 * 这一节只覆盖我能从函数契约推断出的行为，**不能替代原件**。
 */

test('判重只在目标节点内：同一词出现在不同课文时必须都保留', () => {
  const existing = [
    { text: 'bank', clientUnitId: 'u1', clientLessonId: 'l1' },
    { text: 'river', clientUnitId: 'u1', clientLessonId: 'l2' }
  ];
  const picked = selectNewItems(['bank'], existing, { clientUnitId: 'u1', clientLessonId: 'l2' });
  // `bank` 在 l1 里存在，但目标节点是 l2——那不是一个"重复"。
  assert.deepEqual(picked.items, ['bank'], '不同课文里的同名词必须保留');
  assert.equal(picked.skippedCount, 0);
});

test('同一目标节点内的重复才被跳过', () => {
  const existing = [{ text: 'bank', clientUnitId: 'u1', clientLessonId: 'l1' }];
  const picked = selectNewItems(['bank', 'river'], existing, { clientUnitId: 'u1', clientLessonId: 'l1' });
  assert.deepEqual(picked.items, ['river']);
  assert.equal(picked.skippedCount, 1);
});

test('粘贴到单元层级时，课文归属也参与判重范围', () => {
  // 单元层级的目标是 clientLessonId: null；它不该把课文里的内容当成同节点重复。
  const existing = [{ text: 'bank', clientUnitId: 'u1', clientLessonId: 'l1' }];
  const picked = selectNewItems(['bank'], existing, { clientUnitId: 'u1', clientLessonId: null });
  assert.deepEqual(picked.items, ['bank'], '单元层级与课文层级不是同一个节点');
});

/**
 * 审查反例 `F05-tsv-not-paired-punctuation-and-blank-loss` 的核心：
 * **TSV 必须拆成双列**。
 *
 * 探针输入：`'明天见\tSee you tomorrow!\n\n放学后\tafter school'`
 *
 * 期望：三行——第一行两格、第二行**空行但保留**、第三行两格。
 * 实际（修复前）：`items[0] === '明天见\tSee you tomorrow'`——
 * **制表符连同两列内容挤成了一条正文**，导入后左列里会出现英文。
 */

test('TSV 按制表符拆成左右两列', () => {
  const rows = parseBulkRows('明天见\tSee you tomorrow!');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].left, '明天见');
  assert.equal(rows[0].right, 'See you tomorrow!', '右列不能与左列挤在一起');
});

test('空行保留成"两列都空"的一行，而不是被跳过', () => {
  const rows = parseBulkRows('明天见\tSee you tomorrow!\n\n放学后\tafter school');
  assert.equal(rows.length, 3, '空行必须占一行，否则后面的配对整体上移');
  assert.deepEqual(rows[1], { left: '', right: '' });
  assert.equal(rows[0].left, '明天见');
  assert.equal(rows[2].right, 'after school');
});

test('没有制表符时按单列处理，右列留空', () => {
  const rows = parseBulkRows('山\n水');
  assert.deepEqual(rows, [{ left: '山', right: '' }, { left: '水', right: '' }]);
});

test('单列的行不会把内容塞进右列', () => {
  const rows = parseBulkRows('山');
  assert.equal(rows[0].right, '', '没有制表符就不该有右列内容');
});
