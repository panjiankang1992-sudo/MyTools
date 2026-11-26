import assert from 'node:assert/strict';
import test from 'node:test';
import { createPinia, setActivePinia } from 'pinia';
import { useAdminAuthStore } from '../src/stores/admin-auth';

/**
 * 管理员会话状态机。
 * 重点：**依赖不可用不得被显示成"未登录"**，否则服务端故障会被误报为用户需要重新登录。
 */

/** 用给定响应替换全局 fetch。 */
function stubFetch(handler: (url: string, init?: RequestInit) => Response): () => void {
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) =>
    handler(String(input), init)) as typeof fetch;
  return () => {
    globalThis.fetch = original;
  };
}

/** 构造成功信封。 */
function ok(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ code: 'OK', data, requestId: 'r1' }), {
    status,
    headers: { 'content-type': 'application/json' }
  });
}

/** 构造错误信封。 */
function fail(code: string, messageKey: string, status: number): Response {
  return new Response(JSON.stringify({ code, messageKey, details: {}, requestId: 'r1' }), {
    status,
    headers: { 'content-type': 'application/json' }
  });
}

test('未登录时进入 anonymous，且不带任何账号信息', async () => {
  setActivePinia(createPinia());
  const restore = stubFetch(() => ok({ authenticated: false, adminEnabled: true }));
  try {
    const store = useAdminAuthStore();
    await store.initialize();
    assert.equal(store.status, 'anonymous');
    assert.equal(store.username, '');
    assert.equal(store.canWrite, false);
  } finally {
    restore();
  }
});

test('已登录时进入 admin 并记录身份，允许写入', async () => {
  setActivePinia(createPinia());
  const restore = stubFetch(() => ok({ authenticated: true, adminEnabled: true, username: 'someone', userId: '1001' }));
  try {
    const store = useAdminAuthStore();
    await store.initialize();
    assert.equal(store.status, 'admin');
    assert.equal(store.username, 'someone');
    assert.equal(store.canWrite, true);
  } finally {
    restore();
  }
});

test('依赖不可用时进入 unavailable，而不是伪装成未登录', async () => {
  setActivePinia(createPinia());
  const restore = stubFetch(() => fail('TOOLBOX_ADMIN_009', 'admin.unavailable', 503));
  try {
    const store = useAdminAuthStore();
    await store.initialize();
    assert.equal(store.status, 'unavailable');
    assert.equal(store.canWrite, false);
    assert.equal(store.lastErrorKey, 'admin.unavailable');
  } finally {
    restore();
  }
});

test('网络异常同样归为 unavailable，不误报为过期', async () => {
  setActivePinia(createPinia());
  const original = globalThis.fetch;
  globalThis.fetch = (async () => {
    throw new TypeError('network down');
  }) as typeof fetch;
  try {
    const store = useAdminAuthStore();
    await store.initialize();
    assert.equal(store.status, 'unavailable');
  } finally {
    globalThis.fetch = original;
  }
});

test('401 视为会话过期（expired），与未登录和不可用都不同', async () => {
  setActivePinia(createPinia());
  const restore = stubFetch(() => fail('TOOLBOX_ADMIN_001', 'admin.session.required', 401));
  try {
    const store = useAdminAuthStore();
    await store.initialize();
    assert.equal(store.status, 'expired');
  } finally {
    restore();
  }
});

test('登录成功后置为 admin；无管理员权限时不留本地会话', async () => {
  setActivePinia(createPinia());
  let call = 0;
  const restore = stubFetch(url => {
    call += 1;
    if (url.endsWith('/auth/csrf')) return ok({ csrfToken: 'nonce-1' });
    if (url.endsWith('/auth/login')) return ok({ authenticated: true, username: 'someone', csrfToken: 'csrf-1' });
    return ok({});
  });
  try {
    const store = useAdminAuthStore();
    await store.login('someone', 'secret');
    assert.equal(store.status, 'admin');
    assert.equal(call, 2);
  } finally {
    restore();
  }
});

test('登录被拒绝为无权限时回到 anonymous，且状态不含账号', async () => {
  setActivePinia(createPinia());
  const restore = stubFetch(url => {
    if (url.endsWith('/auth/csrf')) return ok({ csrfToken: 'nonce-1' });
    return fail('TOOLBOX_ADMIN_002', 'admin.forbidden', 403);
  });
  try {
    const store = useAdminAuthStore();
    await assert.rejects(() => store.login('someone', 'secret'));
    assert.equal(store.status, 'anonymous');
    assert.equal(store.username, '');
  } finally {
    restore();
  }
});

test('管理会话代码不读写任何浏览器存储（A06：无存储可伪造）', async () => {
  // 本环境（Node）没有 localStorage，因此"伪造本机存储"无法在运行时验证。
  // 但**更强**的保证可以验证：管理会话与请求代码根本不读写浏览器存储，
  // 因此不存在"写入的令牌被冒用"或"本机伪造状态被采信"的路径。
  const { readFile } = await import('node:fs/promises');
  for (const file of ['../src/stores/admin-auth.ts', '../src/services/admin-api.ts']) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8');
    // 去掉注释后再检查，避免文档里提到这些名字就误报。
    const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    assert.equal(/localStorage/.test(code), false, `${file} 不应读写 localStorage`);
    assert.equal(/sessionStorage/.test(code), false, `${file} 不应读写 sessionStorage`);
  }
});

test('跨标签页通道不会阻止宿主进程退出（unref）', async () => {
  const { readFile } = await import('node:fs/promises');
  const source = await readFile(new URL('../src/stores/admin-auth.ts', import.meta.url), 'utf8');
  // 未 unref 的 BroadcastChannel 会让 Node 测试跑完却不结束；这条断言防止回归。
  // 用字面量而不是正则：正则里的转义容易被工具链再转义一次，反而测不准。
  assert.equal(source.includes('.unref?.()'), true, '创建 BroadcastChannel 后必须 unref');
});

test('退出管理会话不触碰公共字帖的草稿存储（A09：公共输入保留）', async () => {
  // 管理会话清理只应影响管理状态。若它顺手清了公共草稿键，
  // 用户就会在"退出管理员模式"时丢掉正在编辑的字帖内容。
  const { readFile } = await import('node:fs/promises');
  const source = await readFile(new URL('../src/stores/admin-auth.ts', import.meta.url), 'utf8');
  const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  // 不得出现公共草稿的键前缀或模块引用。
  // 前缀取自 `worksheet-draft-v2.ts` 的实际常量，而不是我凭印象写的键名。
  assert.equal(code.includes('yuyutian-toolbox:draft'), false, '不得引用公共草稿键前缀');
  assert.equal(code.includes('worksheet-draft'), false, '不得引用公共草稿模块');
  assert.equal(code.includes('localStorage'), false, '不得直接操作浏览器存储');
  // 自检：确认被检查的源码确实被读到了，避免"文件为空导致全通过"。
  assert.equal(code.length > 500, true, `管理会话源码读取异常，长度=${code.length}`);
});
