import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { invalidateSession, publicRequest, translateEntries } from '../src/services/public-api';
import { completePinyinFromCurriculum } from '../src/lib/curriculum-pinyin';

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; invalidateSession(); });

/** 构造公开 API 信封，测试不访问网络。 */
function envelope(data: unknown): Response {
  return new Response(JSON.stringify({ code: 'OK', requestId: 'test', data }), { headers: { 'content-type': 'application/json' } });
}

test('翻译使用匿名会话和CSRF，不带主站凭证', async () => {
  invalidateSession();
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    const path = String(input);
    calls.push(path);
    assert.equal(init?.credentials, 'same-origin');
    const headers = new Headers(init?.headers);
    assert.equal(headers.has('Authorization'), false);
    assert.equal(headers.has('X-Owner-Id'), false);
    if (path.endsWith('/session')) return envelope({ csrfToken: 'test-csrf', expiresAt: '2099-01-01T00:00:00Z' });
    assert.equal(path, '/public/v1/translations');
    assert.equal(headers.get('X-Toolbox-Csrf'), 'test-csrf');
    assert.deepEqual(JSON.parse(String(init?.body)), { items: [{ id: 'one', text: '星期一' }] });
    return envelope({ items: [{ id: 'one', sourceText: '星期一', englishText: 'Monday', meaning: '星期一', status: 'translated' }] });
  };
  const results = await translateEntries([{ id: 'one', text: '星期一' }], new AbortController().signal);
  assert.equal(results[0]!.englishText, 'Monday');
  assert.deepEqual(calls, ['/public/v1/session', '/public/v1/translations']);
});

test('调用方取消信号可中止实际请求，不被内部超时控制器覆盖', async () => {
  const controller = new AbortController();
  let transportSignal: AbortSignal | null | undefined;
  globalThis.fetch = (_input, init) => new Promise((_resolve, reject) => {
    transportSignal = init?.signal;
    transportSignal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  });
  const pending = publicRequest('/translations', { signal: controller.signal });
  controller.abort();
  await assert.rejects(pending, /TOOLBOX_TIMEOUT/);
  assert.equal(transportSignal?.aborted, true);
});

test('已取消的翻译不会创建会话或发出请求', async () => {
  let calls = 0;
  globalThis.fetch = async () => { calls += 1; return envelope({}); };
  const controller = new AbortController();
  controller.abort();
  assert.deepEqual(await translateEntries([{ id: 'one', text: '星期一' }], controller.signal), []);
  assert.equal(calls, 0);
});

test('拼音请求使用相同会话及明确学科，教材逐字结果原样返回', async () => {
  const segments = [{ grapheme: '行', syllable: 'háng' }];
  globalThis.fetch = async (input, init) => {
    if (String(input).endsWith('/session')) return envelope({ csrfToken: 'csrf', expiresAt: '2099-01-01T00:00:00Z' });
    assert.deepEqual(JSON.parse(String(init?.body)), { items: [{ id: 'one', text: '行' }], subject: 'chinese' });
    return envelope({ items: [{ id: 'one', sourceText: '行', englishText: null, meaning: null,
      status: 'translated', pinyinSegments: segments }] });
  };
  const result = await translateEntries([{ id: 'one', text: '行' }], new AbortController().signal, 'chinese');
  assert.deepEqual(result[0]!.pinyinSegments, segments);
});

test('真实拼音客户端不会将编辑状态序列化为后端不接受的额外字段', async () => {
  let requests = 0;
  globalThis.fetch = async (input, init) => {
    if (String(input).endsWith('/session')) return envelope({ csrfToken: 'csrf', expiresAt: '2099-01-01T00:00:00Z' });
    requests++;
    assert.deepEqual(JSON.parse(String(init?.body)), {
      items: [{ id: 'one', text: '行' }], subject: 'chinese'
    });
    return envelope({ items: [{ id: 'one', sourceText: '行', englishText: null, meaning: null,
      status: 'translated', pinyinSegments: [{ grapheme: '行', syllable: 'háng' }] }] });
  };
  const rows = [{ id: 'one', text: '行', source: 'text', target: 'pinyin', fields: 'private snapshot' }];
  const result = await completePinyinFromCurriculum(rows, new AbortController().signal, translateEntries,
    () => { throw new Error('unexpected fallback'); });
  assert.equal(requests, 1);
  assert.equal(result[0]!.suggestion.segments[0]!.syllable, 'háng');
});
