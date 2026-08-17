const assert = require('node:assert/strict');

module.exports = function run(OpdsRequestInputPolicy) {
  const policy = new OpdsRequestInputPolicy();
  assert.equal(policy.url('  HTTPS://Books.Example.com/opds?q=1  '), 'https://Books.Example.com/opds?q=1');
  assert.equal(policy.url('https://books.example.com/'), 'https://books.example.com/');
  for (const value of ['http://books.example.com', 'https://user@books.example.com',
    'https://books.example.com\\path', 'https://books.example.com/a b', 'https://books.example.com\nnext']) {
    assert.throws(() => policy.url(value), /HTTPS/);
  }
  assert.equal(policy.authorization(undefined), undefined);
  assert.equal(policy.authorization(''), undefined);
  assert.equal(policy.authorization('Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=='),
    'Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==');
  for (const value of ['Bearer token', 'Basic abc', 'Basic ab=c', 'Basic abc===', 'Basic abc\ndef',
    `Basic ${'A'.repeat(2044)}`]) {
    assert.throws(() => policy.authorization(value), /认证头/);
  }
};
