const assert = require('node:assert/strict');
const { RssResponseNormalizer } = require(process.argv[2] + '/RssResponseNormalizer.js');
const { RssHtmlPolicy } = require(process.argv[2] + '/RssHtmlPolicy.js');
const n = new RssResponseNormalizer();
const entry = { id: '9223372036854775807', subscriptionId: '11111111-1111-1111-1111-111111111111',
  sourceTitle: 'Source', title: 'Article', read: false, starred: false, revision: 1, discoveredAt: 123 };
assert.equal(n.entry(entry).id, '9223372036854775807');
assert.equal(n.entry(entry).publishedAt, 0);
assert.equal(n.entry(entry).contentHtml, '');
for (const patch of [{ id: 9007199254740992 }, { id: '-1' }, { read: 'false' }, { revision: 0.5 },
  { revision: 9007199254740992 }, { subscriptionId: '../other-owner' }, { contentHtml: 'x'.repeat(400001) }]) {
  assert.throws(() => n.entry({ ...entry, ...patch }), /RSS_INVALID_RESPONSE/);
}
assert.throws(() => n.page({ items: Array(101).fill(entry) }), /RSS_INVALID_RESPONSE/);
assert.throws(() => n.discovery({ candidates: Array(11).fill({ url: 'https://example.com' }) }), /RSS_INVALID_RESPONSE/);
assert.throws(() => n.groups([{ id: entry.subscriptionId, name: 'x', sortOrder: -1, revision: 0 }]), /RSS_INVALID_RESPONSE/);
assert.equal(n.discovery({ candidates: [], preview: null }).preview, undefined);
assert.equal(n.imports([{ url: 'https://example.com/feed', status: 'NEW' }])[0].status, 'NEW');
for (const url of ['javascript:alert(1)', 'file:///etc/passwd', 'https://a@private.example/', 'https://example.com/\nattack', 'data:text/html,x']) {
  assert.equal(RssHtmlPolicy.external(url), false, url);
}
assert.equal(RssHtmlPolicy.external('https://example.com/article?a=1#here'), true);
assert.equal(RssHtmlPolicy.ownedDataUrl('data:text/html;charset=UTF-8;base64,a%2Bb%3D', 'a+b='), true);
assert.equal(RssHtmlPolicy.ownedDataUrl('data:text/html;charset=UTF-8;base64,foreign', 'own'), false);
assert.equal(RssHtmlPolicy.ownedDataUrl('data:text/html;base64,%broken', 'own'), false);
assert.equal(RssHtmlPolicy.ownedDataUrl('data:text/html;base64,', ''), false);
const fallback = RssHtmlPolicy.document('', '<script>alert(1)</script>', 100);
assert.ok(fallback.includes("default-src 'none'"));
assert.ok(fallback.includes('font-size:24px'));
assert.ok(!fallback.includes('<script>'));
const article = RssHtmlPolicy.document('<p>Hello</p><img src="https://example.com/a.png">', '', 10);
assert.ok(article.includes('img-src https:;'));
assert.ok(article.includes('img{display:block;'));
assert.ok(article.includes('referrerpolicy="no-referrer"'));
assert.ok(article.includes("connect-src 'none'"));
assert.ok(article.includes('font-size:16px'));
console.log('RSS response boundaries and reader policy: passed');
