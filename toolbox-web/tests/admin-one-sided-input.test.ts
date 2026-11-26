import assert from 'node:assert/strict';
import { test } from 'node:test';
import { pairsToEntries } from '../src/lib/admin-textbook-draft.ts';
import type { EditableWorksheetPair } from '../src/domain/worksheet-editable-pairs.ts';

/**
 * 审查反例 `F01-one-sided-input-loss`（对应 F04，P1）的回归测试。
 *
 * **问题**：`pairsToEntries` 对英语只按**右列**（`english`）判断行是否为空。
 * 而英语的双列是**左中文（`meaning`）、右英语（`english`）**——
 * 用户只填了中文、还没来得及填英文时，整行会被当成空白行丢掉。
 * 语文同理：只看 `text`，不看 `pinyin`。
 *
 * **正确行为**：**任一侧有内容就保留该行**，缺的那侧留空等待补全或人工填写。
 * 丢掉用户已经输入的内容是不可接受的——它还不会报错。
 */

test('英语：只填左列中文时，整行必须保留', () => {
  // 英语条目的语义是 text=英文、meaning=中文（左中文右英语）。
  // 半填状态下 text 本来就该为空——**这里只要求"行还在、中文没丢"**，
  // 不要求把中文塞进 text（那会写反语义）。
  const pairs: EditableWorksheetPair[] = [{ meaning: '星期一', english: '' }];
  const entries = pairsToEntries(pairs, [], 'english');
  assert.equal(entries.length, 1, '只填中文的行被丢掉了——用户输入无声消失');
  assert.equal(entries[0].meaning, '星期一', '已输入的中文必须保留');
});

test('英语：只填右列英文时，整行必须保留', () => {
  const pairs: EditableWorksheetPair[] = [{ meaning: '', english: 'Monday' }];
  const entries = pairsToEntries(pairs, [], 'english');
  assert.equal(entries.length, 1, '只填英文的行被丢掉了');
  assert.equal(entries[0].text, 'Monday', '已输入的英文必须保留');
  // 空字段在草稿里存的是 null（不是空串）——按"空"断言，不假定具体表示。
  assert.ok(!entries[0].meaning, '未填的中文应为空');
});

test('语文：只填左列汉字时，整行必须保留', () => {
  const pairs: EditableWorksheetPair[] = [{ text: '山', pinyin: '' }];
  const entries = pairsToEntries(pairs, [], 'chinese');
  assert.equal(entries.length, 1, '只填汉字的行被丢掉了');
  assert.equal(entries[0].text, '山');
});

test('语文：只填右列拼音时，整行必须保留', () => {
  const pairs: EditableWorksheetPair[] = [{ text: '', pinyin: 'shān' }];
  const entries = pairsToEntries(pairs, [], 'chinese');
  assert.equal(entries.length, 1, '只填拼音的行被丢掉了');
  assert.equal(entries[0].pinyin, 'shān');
});

test('两侧都空白的行仍然不写进草稿', () => {
  // 反向断言：放宽过滤**不能**把真正空白的行也带进来，
  // 否则每次保存都会往草稿里塞一堆空条目。
  const pairs: EditableWorksheetPair[] = [{ text: '', pinyin: '' }, { meaning: '', english: '' }];
  assert.equal(pairsToEntries(pairs, [], 'chinese').length, 0);
  assert.equal(pairsToEntries(pairs, [], 'english').length, 0);
});

/**
 * 审查反例 `F03-current-unit-input-duplicates-unassigned`（对应 F04，P1）。
 *
 * **问题**：在**选中的单元**里逐字输入同一行，会生成多条**无归属**条目——
 * 它们不属于任何单元，在目录树里看不见，却真实存在于草稿里。
 *
 * **根因**：新行在 `existing` 里匹配不到（还没有 rowId），
 * 于是 `pairsToEntries` 既给不出稳定身份、也给不出归属。
 */

test('在选中单元里新增的行必须归属该单元', () => {
  const pairs: EditableWorksheetPair[] = [{ text: '山' }];
  const entries = pairsToEntries(pairs, [], 'chinese', { clientUnitId: 'u1', clientLessonId: null });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].clientUnitId, 'u1', '新增行必须归属当前选中的单元');
});

test('在选中课文里新增的行归属该课文', () => {
  const pairs: EditableWorksheetPair[] = [{ text: '山' }];
  const entries = pairsToEntries(pairs, [], 'chinese', { clientUnitId: null, clientLessonId: 'l1' });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].clientLessonId, 'l1');
});

test('带着 tokenId 回传时，同一行连续输入三次身份不变', () => {
  // **注意这条测的是纯函数的契约**：身份稳定靠调用方把 `tokenId` 带回来。
  // 编辑器层的回填由 `tests/admin-editor-row-identity.test.ts` 跑**真实处理器**验证——
  // 不能只靠这一条就宣称"新行身份稳定"。
  let entries = pairsToEntries([{ text: '山' }], [], 'chinese', { clientUnitId: 'u1' });
  const firstId = entries[0].rowId;
  const carry = (text: string): EditableWorksheetPair[] => [{ text, tokenId: firstId }];
  entries = pairsToEntries(carry('山峰'), entries, 'chinese', { clientUnitId: 'u1' });
  entries = pairsToEntries(carry('山峰高'), entries, 'chinese', { clientUnitId: 'u1' });
  assert.equal(entries.length, 1, '同一行不应变成多条');
  assert.equal(entries[0].rowId, firstId, '带 tokenId 回传时身份必须稳定');
  assert.equal(entries[0].text, '山峰高');
  assert.equal(entries[0].clientUnitId, 'u1', '归属必须保持');
});

test('已有归属的行不会被新行的默认归属覆盖', () => {
  const existing = pairsToEntries([{ text: '山' }], [], 'chinese', { clientUnitId: 'u1' });
  // 该行已经属于 u1；即使当前选中的是 u2，它也必须留在 u1。
  const after = pairsToEntries([{ text: '山', tokenId: existing[0].rowId }], existing, 'chinese',
    { clientUnitId: 'u2' });
  assert.equal(after[0].clientUnitId, 'u1', '默认归属只能用于**新行**，不能改写既有归属');
});
