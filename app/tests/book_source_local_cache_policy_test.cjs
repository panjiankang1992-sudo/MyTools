const assert = require('node:assert/strict');

const modulePath = process.argv[2];
if (!modulePath) throw new Error('Compiled policy module path is required');
const { BookSourceLocalCachePolicy } = require(modulePath);

const policy = new BookSourceLocalCachePolicy();
const sourceUrl = 'https://source.example/rules.json';
const bookUrl = 'https://source.example/book/1';
const chapterUrl = 'https://source.example/book/1/2';
const now = 1_800_000_000_000;
const document = {
  kind: 'text',
  chapters: [{ title: '第2章', content: '不得写入目录缓存', resourceUri: chapterUrl }],
  imageUris: []
};

const catalogRecord = policy.catalogRecord(sourceUrl, bookUrl, document, now);
assert.equal(catalogRecord.chapters[0].content, '');
assert.equal(policy.catalog(catalogRecord, sourceUrl, bookUrl).chapters.length, 1);
assert.equal(policy.catalog(catalogRecord, `${sourceUrl}/other`, bookUrl), undefined);
assert.equal(policy.catalog({ ...catalogRecord, chapters: [{ title: '危险', resourceUri: 'file:///etc/passwd' }] },
  sourceUrl, bookUrl), undefined);

const content = { kind: 'text', text: '正文内容', imageUris: [], remoteImageUrls: [] };
const chapter = document.chapters[0];
const chapterRecord = policy.chapterRecord(sourceUrl, bookUrl, chapter, 1, content, now);
assert.equal(policy.chapter(chapterRecord, sourceUrl, bookUrl, chapter, 1).text, '正文内容');
assert.equal(policy.chapter(chapterRecord, sourceUrl, bookUrl, chapter, 2), undefined);
assert.equal(policy.chapter({ ...chapterRecord, content: { ...content, kind: 'comic' } },
  sourceUrl, bookUrl, chapter, 1), undefined);

assert.equal(policy.shouldRefreshCatalog(now, now + 6 * 60 * 60 * 1000 - 1), false);
assert.equal(policy.shouldRefreshCatalog(now, now + 6 * 60 * 60 * 1000), true);

console.log('Book source local cache policy tests passed');
