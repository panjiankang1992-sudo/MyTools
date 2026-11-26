import assert from 'node:assert/strict';
import test from 'node:test';
import { adminRequest, newIdempotencyKey } from '../src/services/admin-api';

/**
 * 写请求必须携带 `Idempotency-Key`。
 *
 * 这是一个**曾经真实缺失**的契约：前端一处都没发它，而后端对缺少它的写请求
 * 返回 428 `TOOLBOX_ADMIN_010`。两端各自的测试都通过
 * （后端测试自己造键、前端测试不发请求），**组合起来管理端保存与发布全部失败**。
 *
 * 因此这里直接验证"发出去的请求里有没有这个头"，而不是读源码找字符串。
 */

/** 用桩 fetch 捕获一次请求。 */
async function capture(init: Parameters<typeof adminRequest>[1]): Promise<Headers> {
  const original = globalThis.fetch;
  let captured: Headers | undefined;
  globalThis.fetch = (async (_url: string, options: RequestInit) => {
    captured = new Headers(options.headers);
    return new Response(JSON.stringify({ code: 'OK', data: {} }), {
      status: 200, headers: { 'Content-Type': 'application/json' }
    });
  }) as typeof fetch;
  try {
    await adminRequest('/textbooks', init);
  } finally {
    globalThis.fetch = original;
  }
  assert.ok(captured, '未捕获到请求');
  return captured;
}

test('PUT 写请求自动携带幂等键（缺少它服务端会返回 428）', async () => {
  const headers = await capture({ method: 'PUT', body: '{}' });
  assert.equal(headers.has('Idempotency-Key'), true, '写请求必须带幂等键');
  assert.equal((headers.get('Idempotency-Key') ?? '').length > 0, true);
});

test('POST 写请求同样携带幂等键', async () => {
  const headers = await capture({ method: 'POST', body: '{}' });
  assert.equal(headers.has('Idempotency-Key'), true);
});

test('GET 读请求不需要幂等键', async () => {
  const headers = await capture({ method: 'GET' });
  assert.equal(headers.has('Idempotency-Key'), false, '读请求带它没有意义');
});

test('调用方传入的键被原样使用（重试同一逻辑操作时必须复用）', async () => {
  const headers = await capture({ method: 'POST', body: '{}', idempotencyKey: 'stable-key-1' });
  assert.equal(headers.get('Idempotency-Key'), 'stable-key-1',
    '网络中断后重试必须复用同一个键，服务端才会返回原结果而不是再写一次');
});

test('两次不同的写请求得到不同的键（否则两次操作会互相顶掉）', async () => {
  const first = await capture({ method: 'POST', body: '{}' });
  const second = await capture({ method: 'POST', body: '{}' });
  assert.notEqual(first.get('Idempotency-Key'), second.get('Idempotency-Key'));
});

test('键生成器在缺少 crypto.randomUUID 时仍产出不重复的值', () => {
  const original = globalThis.crypto;
  // 模拟不支持 randomUUID 的环境。
  Object.defineProperty(globalThis, 'crypto', { value: {}, configurable: true });
  try {
    const keys = new Set(Array.from({ length: 200 }, () => newIdempotencyKey()));
    assert.equal(keys.size, 200, '退回实现也必须不重复');
    for (const key of keys) assert.equal(key.length > 0, true);
  } finally {
    Object.defineProperty(globalThis, 'crypto', { value: original, configurable: true });
  }
});
