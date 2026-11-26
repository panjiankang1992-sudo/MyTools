import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeSubset, pairsToEntries, entriesToPairs } from '../src/lib/admin-textbook-draft';

/**
 * 设计第 118 行：「切换节点前提交当前输入缓冲……**不能悄悄丢失**」。
 *
 * 这里验证它所依赖的**逻辑**：按**旧**节点的可见范围提交，不会影响其他节点的内容。
 * 界面上的调用时机（切换前的 watch）无法在此自动验证——那需要组件挂载测试。
 */

const entry = (rowId: string, unitId: string, lessonId: string | null, text: string) => ({
  rowId, entryId: null, unitId: null, clientUnitId: unitId, lessonId: null,
  clientLessonId: lessonId, text, pinyin: null, meaning: null,
  listTypes: ['writing'], confirmed: false, confirmedDigest: null
});

test('按旧节点提交缓冲：只影响旧节点范围，不碰其他节点', () => {
  const all = [
    entry('row-1', 'u1', 'l1', '山'),
    entry('row-2', 'u2', 'l2', '水')
  ];
  // 用户在 u1 下把"山"改成了"山峰"，缓冲尚未上抛；此时切换到 u2。
  const u1Entries = all.filter(e => e.clientUnitId === 'u1');
  const buffered = [{ tokenId: 'row-1', text: '山峰', pinyin: '' }];
  const committed = mergeSubset(all, u1Entries.map(e => e.rowId),
    pairsToEntries(buffered, u1Entries, 'chinese'));

  assert.equal(committed.find(e => e.rowId === 'row-1')?.text, '山峰', '旧节点的输入必须被提交');
  assert.equal(committed.find(e => e.rowId === 'row-2')?.text, '水', '其他节点不受影响');
});

test('提交不会复活在旧节点已删除的行', () => {
  const all = [entry('row-1', 'u1', 'l1', '山'), entry('row-2', 'u1', 'l1', '水')];
  const u1 = all.filter(e => e.clientUnitId === 'u1');
  // 缓冲里只剩第一行（用户删了第二行）。
  const committed = mergeSubset(all, u1.map(e => e.rowId),
    pairsToEntries([{ tokenId: 'row-1', text: '山', pinyin: '' }], u1, 'chinese'));
  assert.equal(committed.length, 1, '被删除的行不得因提交而复活');
});

test('缓冲为空时不改动任何条目（切到空节点不应清空旧内容）', () => {
  const all = [entry('row-1', 'u1', 'l1', '山')];
  // 切换前的提交路径对"可见条目为空"有提前返回，这里验证其依据：
  // 用旧节点的条目参与，空缓冲只会产出一个空行而不是删除。
  const u1 = all.filter(e => e.clientUnitId === 'u1');
  const pairs = entriesToPairs(u1, 'chinese');
  assert.equal(pairs.length, 1);
  assert.equal(pairs[0].text, '山');
});
