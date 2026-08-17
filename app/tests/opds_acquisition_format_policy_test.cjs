const assert = require('node:assert/strict');

module.exports = function run(OpdsAcquisitionFormatPolicy) {
  const policy = new OpdsAcquisitionFormatPolicy();
  assert.equal(policy.resolve('https://books.example/book.epub?token=1', ''), 'epub');
  assert.equal(policy.resolve('https://books.example/download', 'application/epub+zip; charset=binary'), 'epub');
  assert.equal(policy.resolve('https://books.example/download', 'application/pdf'), 'pdf');
  assert.equal(policy.resolve('https://books.example/download', 'application/x-cbz'), 'cbz');
  assert.equal(policy.resolve('https://books.example/download', 'text/plain'), 'txt');
  assert.equal(policy.resolve('https://books.example/download', 'application/x-mobipocket-ebook'), 'mobi');
  assert.equal(policy.resolve('https://books.example/download', 'application/vnd.amazon.ebook'), 'mobi');
  for (const value of ['application/pdf-evil', 'application/not-epub', 'text/plain-malware',
    'application/vnd.comicbook+zip-evil', 'application/x-mobipocket-ebook-extra', '']) {
    assert.equal(policy.resolve('https://books.example/download', value), 'unknown');
  }
};
