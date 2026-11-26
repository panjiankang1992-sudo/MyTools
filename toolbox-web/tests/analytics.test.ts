import assert from 'node:assert/strict';
import test from 'node:test';

/**
 * 事件上报的去重与队列行为。
 * 本机存储与网络在测试中替换为受控实现，其他逻辑保持与浏览器一致。
 */

/** 极简的 localStorage 替身。 */
class MemoryStorage {
  private store = new Map<string, string>();
  getItem(key: string): string | null { return this.store.has(key) ? this.store.get(key)! : null; }
  setItem(key: string, value: string): void { this.store.set(key, value); }
  removeItem(key: string): void { this.store.delete(key); }
  clear(): void { this.store.clear(); }
}

interface SentBatch { events: Array<{ eventId: string; eventType: string; toolId: string | null; operationId: string | null }> }

const sent: SentBatch[] = [];
let respond: (batch: SentBatch) => Response;

/** 在导入被测模块之前准备浏览器替身，并返回模块实例。 */
async function bootstrap() {
  const storage = new MemoryStorage();
  (globalThis as { localStorage?: unknown }).localStorage = storage;
  sent.length = 0;
  respond = batch => new Response(JSON.stringify({
    code: 'OK',
    requestId: 'request-1',
    data: { results: batch.events.map(event => ({ eventId: event.eventId, status: 'accepted' })) }
  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  (globalThis as { fetch?: unknown }).fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    if (url.endsWith('/session')) {
      return new Response(JSON.stringify({ code: 'OK', requestId: 'r', data: { expiresAt: new Date(Date.now() + 3600_000).toISOString(), csrfToken: 'csrf' } }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    const batch = JSON.parse(String(init?.body ?? '{"events":[]}')) as SentBatch;
    sent.push(batch);
    return respond(batch);
  };
  // 每次重新导入以获得干净的模块状态。
  return await import(`../src/lib/analytics.ts?case=${Math.random()}`);
}

test('success is counted once per tool and operation, while a second print counts again', async () => {
  const analytics = await bootstrap();
  analytics.recordWorksheetSuccess('copybook', 'worksheet.chinese-dictation', 'op-1');
  analytics.recordWorksheetSuccess('copybook', 'worksheet.chinese-dictation', 'op-1');
  analytics.recordWorksheetSuccess('copybook', 'worksheet.chinese-dictation', 'op-2');
  analytics.recordPrintRequest('copybook', 'worksheet.chinese-dictation', 'op-1');
  analytics.recordPrintRequest('copybook', 'worksheet.chinese-dictation', 'op-1');
  await analytics.flushEvents();
  const events = sent.flatMap(batch => batch.events);
  assert.equal(events.filter(event => event.eventType === 'worksheet_success').length, 2);
  assert.equal(events.filter(event => event.eventType === 'print_request').length, 2, '每次真实打印发起都应新增一条');
  assert.equal(events.filter(event => event.eventType === 'entry_click').length, 0);
});

test('entry clicks carry a category and are queued once per call', async () => {
  const analytics = await bootstrap();
  analytics.recordEntryClick('pdf', 'pdf.merge');
  analytics.recordEntryClick('pdf', null);
  await analytics.flushEvents();
  const events = sent.flatMap(batch => batch.events);
  assert.equal(events.length, 2);
  assert.equal(new Set(events.map(event => event.eventId)).size, 2);
  assert.ok(events.some(event => event.toolId === 'pdf.merge'));
  assert.ok(events.some(event => event.toolId === null));
});

test('failed uploads keep the queue so a retry does not lose events', async () => {
  const analytics = await bootstrap();
  analytics.recordEntryClick('words', null);
  respond = () => new Response('{"code":"TOOLBOX_008","messageKey":"dependency.disabled"}', { status: 503, headers: { 'Content-Type': 'application/json' } });
  await analytics.flushEvents();
  assert.equal(sent.length > 0, true);
  const attemptsAfterFailure = sent.length;
  respond = batch => new Response(JSON.stringify({
    code: 'OK',
    requestId: 'request-2',
    data: { results: batch.events.map(event => ({ eventId: event.eventId, status: 'accepted' })) }
  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  await analytics.flushEvents();
  assert.equal(sent.length, attemptsAfterFailure + 1, '失败后应重试同一批事件');
  const retried = sent[sent.length - 1]!.events;
  assert.equal(retried.length, 1);
  await analytics.flushEvents();
  assert.equal(sent.length, attemptsAfterFailure + 1, '成功后队列应清空，不再重复上报');
});

test('local usage history stays minimal and can be cleared', async () => {
  const analytics = await bootstrap();
  analytics.recordEntryClick('copybook', null);
  analytics.recordWorksheetSuccess('copybook', 'worksheet.chinese-dictation', 'op-a');
  const stored = analytics.useUsageEvents().value;
  assert.equal(stored.length, 2);
  for (const item of stored) {
    assert.deepEqual(Object.keys(item).sort(), ['category', 'eventId', 'eventType', 'occurredAt', 'operationId', 'toolId'].sort());
  }
  analytics.clearLocalUsage();
  assert.equal(analytics.useUsageEvents().value.length, 0);
});

test('date key follows Asia/Shanghai natural days', async () => {
  const analytics = await bootstrap();
  assert.equal(analytics.dateKey(new Date('2026-10-02T15:59:00Z')), '2026-10-02');
  assert.equal(analytics.dateKey(new Date('2026-10-02T16:01:00Z')), '2026-10-03');
});
