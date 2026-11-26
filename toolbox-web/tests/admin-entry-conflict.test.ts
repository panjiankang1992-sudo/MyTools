import assert from 'node:assert/strict';
import test from 'node:test';
import { entryConflictRecovery } from '../src/lib/admin-entry-conflict';
import { AdminApiError } from '../src/services/admin-api';

test('冲突按实际原因区分，不推断是否有其他人操作', () => {
  for (const reason of ['baselineMismatch', 'revisionConflict', 'revisionMismatch', 'idempotencyKeyReused', 'editionExists', 'futureConflict']) {
    const result = entryConflictRecovery(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason }, 409));
    assert.match(result.message, /当前输入已保留/);
    assert.doesNotMatch(result.message, /其他人|其他管理员/);
    assert.equal(result.requiresReload, !['idempotencyKeyReused', 'editionExists'].includes(reason));
    assert.equal(result.renewRetryKey === true, reason === 'idempotencyKeyReused');
  }
});

test('发布禁用说明配置问题，不要求读取教材或更换请求标识', () => {
  const result = entryConflictRecovery(new AdminApiError('TOOLBOX_ADMIN_011', 'admin.publish.disabled', { reason: 'repositoryIsFiles' }, 409));
  assert.match(result.message, /尚未启用教材发布/);
  assert.equal(result.requiresReload, false);
  assert.equal(result.renewRetryKey, undefined);
});

test('缺少原因的旧服务端 409 仍阻止自动覆盖', () => {
  const result = entryConflictRecovery(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', undefined, 409));
  assert.equal(result.requiresReload, true);
  assert.doesNotMatch(result.message, /其他人/);
});

test('安全合并冲突指出具体条目与字段，并保留版本门禁', () => {
  const result = entryConflictRecovery(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', {
    reason: 'rebaseConflict', conflicts: [{ path: 'entries.entry-1.pinyin', entityId: 'entry-1', field: 'pinyin', reason: 'bothChanged' }]
  }, 409), { 'entry-1': '长' });
  assert.match(result.message, /“长”的拼音/);
  assert.match(result.message, /无法自动合并/);
  assert.equal(result.requiresReload, true);
});

test('原始版本暂不可用保留重试，不误导用户重新录入', () => {
  const result = entryConflictRecovery(new AdminApiError('TOOLBOX_ADMIN_004', 'admin.conflict', { reason: 'baselineUnavailable' }, 409));
  assert.equal(result.requiresReload, false);
  assert.match(result.message, /原始版本/);
});
