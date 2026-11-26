import assert from 'node:assert/strict';
import test from 'node:test';
import { isAllowedRedirect } from '../src/lib/admin-redirect';

/**
 * 登录后跳转的白名单校验。
 * 这是安全相关逻辑：一旦允许外部地址，守卫重定向就变成开放重定向漏洞。
 */

test('允许已注册的站内路径', () => {
  assert.equal(isAllowedRedirect('/admin/textbooks'), true);
  assert.equal(isAllowedRedirect('/admin/textbooks/new'), true);
  assert.equal(isAllowedRedirect('/admin/textbooks/cn-ty-1-1/edit'), true);
  assert.equal(isAllowedRedirect('/copybook'), true);
  assert.equal(isAllowedRedirect('/words'), true);
  assert.equal(isAllowedRedirect('/pdf/merge'), true);
  assert.equal(isAllowedRedirect('/'), true);
});

test('拒绝外部地址与协议相对地址（开放重定向）', () => {
  assert.equal(isAllowedRedirect('https://evil.example/steal'), false);
  assert.equal(isAllowedRedirect('//evil.example/steal'), false);
  // 反斜杠变体：部分浏览器会把它当作协议相对地址。
  assert.equal(isAllowedRedirect('/\\evil.example'), false);
  assert.equal(isAllowedRedirect('http://127.0.0.1:4173/'), false);
});

test('拒绝非字符串与空值', () => {
  assert.equal(isAllowedRedirect(undefined), false);
  assert.equal(isAllowedRedirect(null), false);
  assert.equal(isAllowedRedirect(42), false);
  assert.equal(isAllowedRedirect(['/copybook']), false);
  assert.equal(isAllowedRedirect(''), false);
});

test('拒绝前缀相近但不同的路径', () => {
  assert.equal(isAllowedRedirect('/admin'), false);
  assert.equal(isAllowedRedirect('/adminx/textbooks'), false);
  assert.equal(isAllowedRedirect('/copypdf'), false);
  assert.equal(isAllowedRedirect('admin/textbooks'), false);
});

test('带查询与哈希的站内路径仍然允许', () => {
  assert.equal(isAllowedRedirect('/admin/textbooks?status=draft'), true);
  assert.equal(isAllowedRedirect('/words#section'), true);
});
