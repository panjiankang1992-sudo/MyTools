import assert from 'node:assert/strict';
import test from 'node:test';
import { ADMIN_ERROR_MESSAGES, adminErrorMessage } from '../src/lib/admin-error-messages';

/** 管理端错误 key 的中文提示。重点与校验问题码一致：未收录的 key 必须原样显示。 */

test('已知错误 key 给出中文提示，而不是机器标识符', () => {
  assert.equal(/^[a-z]+\./.test(adminErrorMessage('admin.disabled')), false, '不能把 key 本身当提示');
  assert.equal(adminErrorMessage('admin.forbidden').includes('管理员权限'), true);
  // 这条要说明"没有被当作退出"——设计区分"功能不可用"与"已退出"。
  assert.equal(adminErrorMessage('admin.identityUnavailable').includes('不会被当作退出'), true);
});

test('未收录的 key 原样显示（新加的错误不能被静默吞掉）', () => {
  assert.equal(adminErrorMessage('admin.someFutureRule'), 'admin.someFutureRule');
});

test('key 缺失时给出可读兜底', () => {
  assert.equal(adminErrorMessage(null), '操作失败，请重试。');
  assert.equal(adminErrorMessage(undefined), '操作失败，请重试。');
});

test('映射表非空，且没有一条是裸 key（防误清空后测试假通过）', () => {
  const entries = Object.entries(ADMIN_ERROR_MESSAGES);
  assert.equal(entries.length > 10, true, `映射表过小：${entries.length}`);
  for (const [key, text] of entries) {
    assert.equal(text.length > 0, true, `${key} 的提示不能为空`);
    assert.equal(/^[a-z][a-zA-Z.]*$/.test(text), false, `${key} 的提示不能是裸 key`);
  }
});

test('恢复类提示都说明"内容仍保留"，避免用户以为白改了', () => {
  // 设计第 118 行要求失败时"保留并就地提示"；提示里说清楚这一点，用户才不会被吓到重来一遍。
  assert.equal(adminErrorMessage('admin.conflict').includes('覆盖'), true);
  assert.equal(adminErrorMessage('admin.request.timeout').includes('仍保留'), true);
});
