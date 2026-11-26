import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  diffDraftEntries, resolveConflictPanel, summarizeConflictDiff, type ConflictDiff
} from '../src/lib/admin-draft-conflict.ts';
import type { DraftEntry } from '../src/lib/admin-textbook-draft.ts';

/**
 * F05/F12：保存冲突时**必须真的比对两侧内容**，而不是只显示"冲突了"。
 *
 * 设计 §3.7 要求冲突面板提供"查看差异"。若差异函数把"两侧都改过"和
 * "仅一侧有"混为一谈，用户就无法判断覆盖的后果——这正是本文件的鉴别力所在。
 */

/** 构造一条草稿条目；只填与差异有关的字段。 */
function entry(rowId: string, text: string, second?: string): DraftEntry {
  return {
    rowId,
    entryId: null,
    unitId: null,
    clientUnitId: 'cu1',
    lessonId: null,
    clientLessonId: null,
    text,
    pinyin: second ?? null,
    meaning: null,
    listTypes: [],
    confirmed: false
  };
}

test('仅本地有的行判为"本地新增"，仅服务端有的行判为"服务器新增"', () => {
  const local = [entry('r1', '山'), entry('r2', '水')];
  const remote = [entry('r1', '山'), entry('r9', '火')];
  const diff = diffDraftEntries(local, remote);

  const kinds = new Map(diff.rows.map(row => [row.rowId, row.kind]));
  assert.equal(kinds.get('r2'), 'local-only');
  assert.equal(kinds.get('r9'), 'remote-only');
  assert.equal(diff.localOnlyCount, 1);
  assert.equal(diff.remoteOnlyCount, 1);
  assert.equal(diff.changedCount, 0);
  assert.equal(diff.identical, false);
});

test('两侧都有的同一行按内容比对，改过才算 changed', () => {
  const local = [entry('r1', '山', 'shān'), entry('r2', '水', 'shuǐ')];
  const remote = [entry('r1', '山', 'shān'), entry('r2', '水', 'shuì')];
  const diff = diffDraftEntries(local, remote);

  assert.equal(diff.changedCount, 1, '只有 r2 的第二栏变了');
  assert.equal(diff.sharedCount, 2);
  const changed = diff.rows.find(row => row.kind === 'changed');
  assert.equal(changed?.rowId, 'r2');
  // 差异必须带上**两侧的值**，否则界面无法展示"改前/改后"。
  assert.equal(changed?.local?.pinyin, 'shuǐ');
  assert.equal(changed?.remote?.pinyin, 'shuì');
});

test('内容完全相同时判为一致（含 null 与空串的等价）', () => {
  const local = [entry('r1', '山', 'shān')];
  // 服务端把可空字段序列化成 `null`，本地空串——语义相同，不能算冲突差异。
  const remote: DraftEntry[] = [{ ...entry('r1', '山'), pinyin: 'shān', meaning: null }];
  const diff = diffDraftEntries(local, remote);

  assert.equal(diff.identical, true);
  assert.equal(diff.changedCount, 0);
  assert.equal(diff.rows.every(row => row.kind === 'identical'), true);
  assert.equal(summarizeConflictDiff(diff).includes('一致'), true);
});

test('类别集合按"无序集合"比较：顺序不同不算改动', () => {
  const local: DraftEntry[] = [{ ...entry('r1', '山'), listTypes: ['writing', 'recognition'] }];
  const remote: DraftEntry[] = [{ ...entry('r1', '山'), listTypes: ['recognition', 'writing'] }];
  assert.equal(diffDraftEntries(local, remote).identical, true,
    '类别是集合语义，顺序不同不应报成"双方都改过"');

  // 反例：真的少了一类时必须报出来，否则"排序后比较"会被写成"永远相等"。
  const missing: DraftEntry[] = [{ ...entry('r1', '山'), listTypes: ['recognition'] }];
  assert.equal(diffDraftEntries(local, missing).changedCount, 1);
});

test('行的对齐键是 rowId，不是下标（对方在中间插行不得把后续全判成改动）', () => {
  // **这条测试的鉴别力**：按下标比较时，remote 在前面插入 r0 之后，
  // r1/r2 都会与错位的行比较而全部变成 changed。
  const local = [entry('r1', '山'), entry('r2', '水')];
  const remote = [entry('r0', '金'), entry('r1', '山'), entry('r2', '水')];
  const diff = diffDraftEntries(local, remote);

  assert.equal(diff.changedCount, 0, '内容没变，只是对方多了一行——不能报成双方都改过');
  assert.equal(diff.remoteOnlyCount, 1);
  assert.equal(diff.localOnlyCount, 0);
});

test('差异摘要分别说明三个方向，缺一则用户无法判断覆盖风险', () => {
  const diff: ConflictDiff = { rows: [], localOnlyCount: 2, remoteOnlyCount: 3, changedCount: 1, sharedCount: 1, identical: false };
  const summary = summarizeConflictDiff(diff);
  assert.equal(summary.includes('本地有 2 条'), true);
  assert.equal(summary.includes('服务器有 3 条'), true);
  assert.equal(summary.includes('1 条两侧都改过'), true);
});

test('409 面板状态：从 details 读 expectedRevision，并从"调用 conflict() 之前"取待重试信息', () => {
  // **鉴别力**：`conflict()` 会清空 pendingRetry，所以必须在调用它**之前**取值；
  // 若实现顺序反了，abandonedRetry 永远是 false——用户不会知道排队中的重试被放弃了。
  assert.deepEqual(resolveConflictPanel({ expectedRevision: 5 }, false), { expectedRevision: 5, abandonedRetry: false });
  assert.deepEqual(resolveConflictPanel({ expectedRevision: 5 }, true), { expectedRevision: 5, abandonedRetry: true });
});

test('缺失或非数字的 expectedRevision 一律按"未知"处理，不猜测', () => {
  // 发布冲突的 details 是 `{reason, expected, actual}`；保存冲突只有 expectedRevision。
  // 读不到数字时必须显示未知，而不是把别的字段硬当修订号。
  assert.equal(resolveConflictPanel(undefined, false).expectedRevision, null);
  assert.equal(resolveConflictPanel({ expected: 3 }, false).expectedRevision, null);
  assert.equal(resolveConflictPanel({ expectedRevision: '3' }, false).expectedRevision, null);
  assert.equal(resolveConflictPanel({ expectedRevision: Number.NaN }, false).expectedRevision, null);
});
