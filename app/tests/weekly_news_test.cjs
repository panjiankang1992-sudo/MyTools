const assert = require('node:assert/strict');
const { WeeklyNewsNormalizer } = require(process.argv[2] + '/WeeklyNewsNormalizer.js');
const n = new WeeklyNewsNormalizer();
const id = '11111111-1111-1111-1111-111111111111';
const end = '2026-11-22T06:30:00Z';
const job = { id, status: 'RUNNING', attempt: 1, revision: 1, windowStart: '2026-11-15T06:30:00Z',
  windowEnd: end, createdAt: end, updatedAt: end, progress: { stage: 'COLLECTING', completedCategories: 0 }, reportRevision: 0 };
assert.equal(n.active({ job: null }), undefined);
assert.equal(n.active({ job }).id, id);
assert.equal(n.job(job).progress.completedCategories, 0);
for (const patch of [{ id: '../foreign' }, { attempt: 11 }, { status: 'DONE' }, { revision: 0.5 },
  { windowEnd: 'invalid' }, { progress: { stage: 'MADE_UP', completedCategories: 0 } },
  { progress: { stage: 'ANALYZING', completedCategories: 11 } }]) {
  assert.throws(() => n.job({ ...job, ...patch }), /NEWS_INVALID_RESPONSE/);
}
const article = { rank: 1, eventId: 'event-1', category: 'TECH', title: 'Fixture', summary: 'Source-supported fixture summary.',
  publishedAt: end, sources: ['Source'], sourceLinks: ['https://example.org/news'] };
assert.deepEqual(n.article(article).points, []);
assert.equal(n.section({ category: 'TECH', count: 1, shortfall: 19, items: [article] }).items.length, 1);
for (const patch of [{ eventId: '../other-owner' }, { category: 'UNKNOWN' }, { rank: 21 }, { summary: 'x'.repeat(1801) },
  { image: { assetId: 'other', sourceUrl: 'https://example.org' } }, { points: Array(6).fill('point') }]) {
  assert.throws(() => n.article({ ...article, ...patch }), /NEWS_INVALID_RESPONSE/);
}
for (const url of ['javascript:alert(1)', 'file:///private/file', 'https://user@host/', 'https://example.org/\nsecret']) {
  assert.throws(() => n.article({ ...article, sourceLinks: [url] }), /NEWS_INVALID_RESPONSE/);
}
assert.throws(() => n.section({ category: 'TECH', count: 20, shortfall: 0, items: Array(21).fill(article) }), /NEWS_INVALID_RESPONSE/);
assert.throws(() => n.history({ items: [job], nextCursor: '../id' }), /NEWS_INVALID_RESPONSE/);
assert.equal(n.history({ items: [job], nextCursor: null }).nextCursor, '');
assert.equal(n.asset({ assetId: 'a'.repeat(64), mime: 'image/png', base64: 'AAAA' }).mime, 'image/png');
assert.throws(() => n.asset({ assetId: 'a'.repeat(64), mime: 'image/svg+xml', base64: 'AAAA' }), /NEWS_INVALID_RESPONSE/);
console.log('Weekly news response and image boundaries: passed');
