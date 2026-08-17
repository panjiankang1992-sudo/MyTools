const assert = require('node:assert/strict');

module.exports = function run(OpdsLinkPolicy) {
  const policy = new OpdsLinkPolicy();
  const base = 'https://Books.Example.com/catalog/featured.json?lang=zh';
  assert.equal(policy.resolve(base, '../files/book.epub?token=1'),
    'https://Books.Example.com/files/book.epub?token=1');
  assert.equal(policy.resolve(base, '/all.json'), 'https://Books.Example.com/all.json');
  assert.equal(policy.resolve(base, 'HTTPS://cdn.example.com/book.epub'), 'https://cdn.example.com/book.epub');
  assert.equal(policy.resolve(base, './next.json'), 'https://Books.Example.com/catalog/next.json');
  for (const value of ['//evil.example/book.epub', 'http://evil.example/book.epub', 'file:///tmp/book.epub',
    'https://user@evil.example/book.epub', '../../../../escape.epub', '../book.epub#fragment',
    '../book%2Fname.epub', '..\\book.epub', '../book name.epub', '']) {
    assert.equal(policy.resolve(base, value), undefined, `unsafe link accepted: ${value}`);
  }
  assert.equal(policy.resolve(base, { href: '/book.epub' }), undefined);
};
