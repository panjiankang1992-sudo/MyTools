import assert from 'node:assert/strict';
import { test } from 'node:test';
import { SaveState, newSaveKey } from '../src/lib/admin-save-queue.ts';

/**
 * F05 的四条核心性质。
 *
 * 审查指出的问题：**只有手动保存；没有 dirty／编辑代次／队列／冲突恢复；
 * 保存期间继续输入，旧响应仍显示"已保存"；失败操作没有冻结键与 payload。**
 *
 * 这里逐条锁定。
 */

/** 确定性的键工厂，便于断言"重试复用了同一个键"。 */
function keys(): () => string {
  let n = 0;
  return () => `key-${++n}`;
}

test('没有改动时不发起保存', () => {
  const state = new SaveState<{ text: string }>(3, keys());
  assert.equal(state.begin({ text: '山' }), null, '干净状态不应发起保存');
  assert.equal(state.dirty, false);
});

test('改动后 begin 冻结 payload／revision／键／编辑代次', () => {
  const state = new SaveState<{ text: string }>(3, keys());
  state.markDirty();
  const op = state.begin({ text: '山' });
  assert.ok(op, '有改动时必须能发起');
  assert.deepEqual(op.payload, { text: '山' });
  assert.equal(op.revision, 3);
  assert.equal(op.idempotencyKey, 'key-1');
  assert.equal(op.editSequence, 1);
});

test('串行：在途时再次 begin 返回 null', () => {
  const state = new SaveState<{ text: string }>(1, keys());
  state.markDirty();
  assert.ok(state.begin({ text: '山' }));
  assert.equal(state.begin({ text: '水' }), null, '同一时刻只能有一次保存在途');
  assert.equal(state.saving, true);
});

test('保存期间继续输入：回包成功也只能算 stale，不能标成已保存', () => {
  // 这是审查直接点出的那条："保存期间继续输入，旧响应仍显示已保存"。
  const state = new SaveState<{ text: string }>(1, keys());
  state.markDirty();
  const op = state.begin({ text: '山' })!;

  // 用户在等待期间又改了内容。
  state.markDirty();

  const outcome = state.succeed(op, 2);
  assert.equal(outcome, 'stale', '保存期间有新编辑时不能宣称已保存');
  assert.equal(state.dirty, true, '新编辑仍未保存');
  assert.equal(state.currentRevision, 2, '服务端修订号仍应前进');
});

test('没有新编辑时回包成功清算 dirty', () => {
  const state = new SaveState<{ text: string }>(1, keys());
  state.markDirty();
  const op = state.begin({ text: '山' })!;
  assert.equal(state.succeed(op, 2), 'saved');
  assert.equal(state.dirty, false);
  assert.equal(state.currentRevision, 2);
});

test('网络失败后用同一个键与同一份 payload 重试', () => {
  const state = new SaveState<{ text: string }>(1, keys());
  state.markDirty();
  const first = state.begin({ text: '山' })!;
  state.fail(first);

  assert.equal(state.hasPendingRetry, true);
  const retry = state.begin({ text: '水' })!;
  // 关键：重试用的是**原来那一份**，不是此刻的新内容——
  // 换了 payload 就成了另一个意图，服务端不会返回原结果。
  assert.equal(retry.idempotencyKey, first.idempotencyKey, '重试必须复用同一个幂等键');
  assert.deepEqual(retry.payload, { text: '山' }, '重试必须原样重发冻结的 payload');
  assert.equal(retry.editSequence, first.editSequence);
});

test('冲突不自动重试，也不把本地内容标成已保存', () => {
  const state = new SaveState<{ text: string }>(1, keys());
  state.markDirty();
  const op = state.begin({ text: '山' })!;
  assert.equal(state.conflict(op), 'conflict');
  assert.equal(state.hasPendingRetry, false, '冲突不能自动重试（那会覆盖别人的改动）');
  assert.equal(state.dirty, true, '本地内容仍未保存，等用户决定');
});

test('幂等键生成器在受限环境下也不重复', () => {
  // 仓库约定：不能依赖 crypto.randomUUID（受限环境可能没有）。
  const seen = new Set<string>();
  for (let i = 0; i < 200; i += 1) {
    seen.add(newSaveKey(i));
  }
  assert.equal(seen.size, 200, '生成的键不能重复');
});
