import assert from 'node:assert/strict';
import test from 'node:test';
import { loginErrorMessage } from '../src/lib/admin-login-messages';

/** 登录提示。重点：三类失败必须彼此可区分，否则用户会做无效的补救。 */

test('未登录过时不显示任何提示', () => {
  assert.equal(loginErrorMessage(''), '');
  assert.equal(loginErrorMessage(null), '');
});

test('凭据被拒时说"账号或密码不正确"', () => {
  assert.equal(loginErrorMessage('admin.session.required'), '账号或密码不正确。');
});

test('凭据有效但不是管理员时，必须与"密码错了"区分（A03）', () => {
  const forbidden = loginErrorMessage('admin.forbidden');
  assert.notEqual(forbidden, loginErrorMessage('admin.session.required'),
    '两种情况给同一句话，用户会一直重输密码');
  assert.equal(forbidden.includes('管理员权限'), true);
});

test('管理功能未启用时明说，避免用户反复重试', () => {
  const disabled = loginErrorMessage('admin.disabled');
  assert.equal(disabled.includes('未启用'), true);
  assert.notEqual(disabled, '登录失败，请重试。');
});

test('未知 key 给出可读兜底，而不是机器标识符', () => {
  assert.equal(loginErrorMessage('admin.someFutureCase'), '登录失败，请重试。');
  assert.equal(/^[a-z]+\./.test(loginErrorMessage('admin.someFutureCase')), false);
});
