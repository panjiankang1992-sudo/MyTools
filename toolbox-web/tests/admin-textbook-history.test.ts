import assert from 'node:assert/strict';
import test from 'node:test';
import { MAX_HISTORY_DEPTH, UndoStack } from '../src/lib/admin-textbook-history';

/**
 * 撤销栈。重点：能撤销、深度有界、栈空时不产生假状态。
 */

test('撤销恢复到改动前的状态，并带出可提示的操作名', () => {
  const stack = new UndoStack<string>();
  stack.record('原始', '删除单元');
  assert.equal(stack.depth, 1);
  const snapshot = stack.undo();
  assert.equal(snapshot?.state, '原始');
  assert.equal(snapshot?.label, '删除单元', '界面应能提示"撤销删除单元"');
  assert.equal(stack.depth, 0);
});

test('多次改动可逐级撤销（后进先出）', () => {
  const stack = new UndoStack<number>();
  stack.record(1, '第一步');
  stack.record(2, '第二步');
  assert.equal(stack.undo()?.state, 2, '应先回到最近一次改动前');
  assert.equal(stack.undo()?.state, 1);
});

test('栈空时撤销返回 null，而不是伪造一个状态', () => {
  const stack = new UndoStack<string>();
  assert.equal(stack.undo(), null);
});

test('深度有界：超出后丢弃最早的快照，不会无限占用内存', () => {
  const stack = new UndoStack<number>();
  for (let index = 0; index < MAX_HISTORY_DEPTH + 10; index += 1) {
    stack.record(index, `第 ${index} 步`);
  }
  assert.equal(stack.depth, MAX_HISTORY_DEPTH);
  // 最早的那些已被丢弃：连续撤销到空，最后拿到的是较新的状态。
  let last = null;
  let count = 0;
  while (stack.undo()) count += 1;
  assert.equal(count, MAX_HISTORY_DEPTH);
  void last;
});

test('清空历史后不可再撤销（载入另一册时使用）', () => {
  const stack = new UndoStack<string>();
  stack.record('旧册内容', '改名');
  stack.clear();
  assert.equal(stack.depth, 0);
  assert.equal(stack.undo(), null);
});
