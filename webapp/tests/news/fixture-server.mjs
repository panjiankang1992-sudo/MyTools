import { createServer } from 'node:http';
import { createDeliveryFixture } from './delivery-fixture.mjs';

// 仅监听回环地址；所有新闻和身份均为验收夹具，不能用于生产来源。
const cutoff = '2026-10-18T00:00:00Z';
const id = '00000000-0000-4000-8000-000000000001';
const runId = '00000000-0000-4000-8000-000000000010';
const region = { status: 'VERIFIED', regionCode: 'GEONAME-1', nodeId: 'fixture-executor', resolvedAt: cutoff, details: { cityName: 'Fixture City', sourcePackLabel: 'Fixture City sources', precision: 'CITY', observedAt: cutoff, expiresAt: cutoff, cached: false, sourcePackVersion: 1 } };
const snapshotRegion = { status: 'UNRESOLVED', regionCode: null, nodeId: 'fixture-executor', resolvedAt: null, details: null };
const makeItem = rank => ({ rank, eventId: `event-${rank}`, title: rank === 2 ? '<img src=x onerror=alert(1)> Untrusted source title' : `Open source weekly update ${String(rank).padStart(2, '0')}`,
  url: rank === 2 ? 'javascript:alert(1)' : `https://example.test/article/${rank}`, publishedAt: cutoff, sourceIds: ['fixture-source'], detailStatus: rank <= 8 ? 'AVAILABLE' : rank <= 10 ? 'EVIDENCE_TOO_SHORT' : 'HEADLINE_ONLY',
  detail: rank <= 8 ? { text: 'This is an offline acceptance fixture. The article excerpt is displayed as plain text and keeps its original language. It includes the source evidence that was available before the cutoff. <script>alert(1)</script> must remain text.', method: 'SOURCE_EXCERPT_V1', citations: [{ articleId: `article-${rank}`, sourceId: 'fixture-source', url: `https://example.test/article/${rank}`, contentHash: 'fixture-hash', fetchedAt: cutoff }] } : null,
  technical: [1, 3].includes(rank) ? { discussionUrl: 'https://example.test/discussion', github: { status: rank === 1 ? 'WARMING_UP' : 'READY', netStars: rank === 1 ? null : -2, stars: 1234, coverageSeconds: 0, sampleCount: 1, observedAt: cutoff } } : null });
const sections = Object.fromEntries(['TECH','WORLD','DOMESTIC','LOCAL'].map(category => {
  const count = category === 'TECH' ? 20 : category === 'LOCAL' ? 0 : 3;
  return [category, { category, windowStart: '2026-10-11T00:00:00Z', windowEnd: cutoff, candidateCount: count, headlineCount: count, detailCount: Math.min(8, count), quality: 'DEGRADED', gaps: category === 'LOCAL' ? ['HEADLINE_SHORTFALL','DETAIL_SHORTFALL','REGION_UNRESOLVED'] : ['DETAIL_SHORTFALL'], items: Array.from({ length: count }, (_, i) => makeItem(i + 1)) }];
}));
const edition = { id, cutoff, createdAt: cutoff, zone: 'Asia/Shanghai', rankingVersion: 'technical-evidence-v2', region: snapshotRegion, quality: 'DEGRADED', sections };
let subscription = { id: '00000000-0000-4000-8000-000000000020', revision: 1, enabled: false, zoneId: 'Asia/Shanghai', cutoffTime: '07:30', nextDate: '2026-10-19', nextDueAt: '2026-10-18T23:30:00Z', updatedAt: cutoff };
let runs = [{ id: runId, businessDate: '2026-10-18', zoneId: 'Asia/Shanghai', cutoff, status: 'QUEUED', taskStatus: 'PENDING', editionId: null, errorCode: null, skippedDays: 2 }];
let failPreview = true;
let forceConflict = false;
let failHistory = false;
let failEvidence = false;
const requests = [];
function reply(res, data, status = 200, code = '0000') { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify({ code, message: code === '0000' ? 'OK' : code, data })); }
const delivery = createDeliveryFixture(reply);
const server = createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  let raw = ''; for await (const chunk of req) raw += chunk;
  const body = raw ? JSON.parse(raw) : {};
  if (path === '/__news_test' && req.method === 'POST') { delivery.control(body); failEvidence = body.failEvidence ?? failEvidence; forceConflict = body.conflict ?? forceConflict; failHistory = body.failHistory ?? failHistory; reply(res, { ready: true }); return; }
  if (path === '/__news_test' && req.method === 'GET') { reply(res, { requests, subscription, runs, delivery: delivery.snapshot() }); return; }
  if (req.headers.authorization !== 'Bearer news-ui-fixture') { reply(res, null, 401, 'GATEWAY_001'); return; }
  if (path === '/api/user/info') { reply(res, { id: 7, userId: 7, username: 'fixture', nickname: 'Fixture reader', role: 'USER', status: 'ACTIVE' }); return; }
  if (!path.startsWith('/api/v1/news/')) { reply(res, null, 404, 'NOT_FOUND'); return; }
  requests.push({ path, method: req.method, body });
  if (delivery.handle(path, req, res, body)) return;
  if (path.endsWith('/capabilities')) reply(res, { latestCutoff: cutoff, rankingVersion: 'technical-evidence-v2', headlineLimit: 20, detailLimit: 10, detailMethod: 'SOURCE_EXCERPT_V1', regionStatus: 'UNRESOLVED', deliveryEnabled: false });
  else if (path.endsWith('/local-region')) reply(res, region);
  else if (path.endsWith('/subscription') && req.method === 'GET') reply(res, { subscription, schedulingEnabled: false, deliveryEnabled: false });
  else if (path.endsWith('/subscription') && req.method === 'PUT') {
    if (forceConflict || body.expectedRevision !== subscription.revision) { forceConflict = false; subscription.revision++; reply(res, null, 409, 'NEWS_014'); }
    else { subscription = { ...subscription, ...body, revision: subscription.revision + 1 }; reply(res, subscription); }
  } else if (path.endsWith('/editions')) {
    if (failHistory) reply(res, null, 503, 'GATEWAY_003'); else reply(res, [{ id, cutoff, createdAt: cutoff, quality: 'DEGRADED' }]);
  } else if (path.includes('/editions/') && path.endsWith('/evidence')) {
    if (failEvidence) reply(res, null, 503, 'GATEWAY_003');
    else reply(res, { editionId: id, checkedAt: cutoff, items: Array.from({ length: 8 }, (_, i) => ({ articleId: `article-${i + 1}`,
      status: i === 1 ? 'RETENTION_EXPIRED' : i === 2 ? 'UNAVAILABLE' : 'AVAILABLE', expiresAt: i === 2 ? null : '2026-10-25T00:00:00Z' })) });
  } else if (path.includes('/editions/')) reply(res, edition);
  else if (path.endsWith('/previews')) {
    if (failPreview) { failPreview = false; req.socket.destroy(); } else reply(res, edition);
  } else if (path.endsWith('/cancel')) { runs = [{ ...runs[0], status: 'CANCELLED', taskStatus: 'CANCELLED' }]; reply(res, runs[0]); }
  else if (path.endsWith('/runs')) reply(res, runs);
  else reply(res, null, 404, 'NOT_FOUND');
});
server.listen(Number(process.env.NEWS_FIXTURE_PORT || 24142), '127.0.0.1', () => console.log('Offline news fixture server ready'));
