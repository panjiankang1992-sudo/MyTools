const assert = require('node:assert/strict');

module.exports = function run(OpdsNetworkResponsePolicy) {
  const policy = new OpdsNetworkResponsePolicy();
  for (const type of ['application/opds+json', 'application/json; charset=utf-8', 'application/problem+json',
    'application/atom+xml', 'application/xml', 'text/xml']) {
    assert.equal(policy.headerError({ 'Content-Type': type, 'Content-Length': '128' }), '');
  }
  assert.match(policy.headerError({ Location: 'https://other.example' }), /重定向/);
  assert.match(policy.headerError({ 'Content-Length': '-1' }), /长度/);
  assert.match(policy.headerError({ 'Content-Length': '5242881' }), /5 MB/);
  assert.match(policy.headerError({ 'Content-Encoding': 'gzip' }), /压缩/);
  assert.match(policy.headerError({ 'Content-Type': 'application/json-evil' }), /类型/);
  assert.match(policy.headerError({ 'Content-Type': 'text/html' }), /类型/);
  assert.equal(policy.bodyError(''), 'OPDS目录为空');
  assert.equal(policy.bodyError('a'.repeat(5 * 1024 * 1024)), '');
  assert.match(policy.bodyError('a'.repeat(5 * 1024 * 1024 + 1)), /5 MB/);
  assert.equal(policy.bodyError('中'.repeat(Math.floor(5 * 1024 * 1024 / 3))), '');
  assert.match(policy.bodyError('中'.repeat(Math.floor(5 * 1024 * 1024 / 3) + 1)), /5 MB/);
};
