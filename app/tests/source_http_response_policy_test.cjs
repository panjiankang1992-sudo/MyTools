const assert = require('node:assert/strict');

module.exports = function run(SourceHttpResponsePolicy) {
  const policy = new SourceHttpResponsePolicy();
  assert.equal(policy.headerError({ 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': '128' }), '');
  assert.equal(policy.headerError({ 'content-type': 'text/html' }), '');
  assert.equal(policy.headerError({ 'content-type': 'application/problem+json' }), '');
  assert.equal(policy.headerError({ 'content-type': 'application/xhtml+xml' }), '');
  assert.equal(policy.headerError({ 'content-type': 'application/octet-stream' }), '');
  assert.match(policy.headerError({ Location: 'https://other.example' }), /重定向/);
  assert.match(policy.headerError({ 'Content-Length': '-1' }), /长度/);
  assert.match(policy.headerError({ 'Content-Length': '5242881' }), /5 MB/);
  assert.match(policy.headerError({ 'Content-Length': '1.5' }), /长度/);
  assert.match(policy.headerError({ 'Content-Encoding': 'gzip' }), /压缩/);
  assert.match(policy.headerError({ 'Content-Type': 'image/png' }), /类型/);
  assert.match(policy.headerError({ 'Content-Type': 'application/pdf' }), /类型/);
  assert.match(policy.bodyError(''), /为空/);
  assert.equal(policy.bodyError('a'.repeat(5 * 1024 * 1024)), '');
  assert.match(policy.bodyError('a'.repeat(5 * 1024 * 1024 + 1)), /5 MB/);
  assert.equal(policy.bodyError('中'.repeat(Math.floor(5 * 1024 * 1024 / 3))), '');
  assert.match(policy.bodyError('中'.repeat(Math.floor(5 * 1024 * 1024 / 3) + 1)), /5 MB/);
  assert.equal(policy.bodyError('😀'.repeat(Math.floor(5 * 1024 * 1024 / 4))), '');
  assert.match(policy.bodyError('😀'.repeat(Math.floor(5 * 1024 * 1024 / 4) + 1)), /5 MB/);
}
