import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildPublishIntent, CHANGE_NOTE_MAX_LENGTH, normalizeChangeNote
} from '../src/lib/admin-publish-request.ts';
import { publishDraft, rebaseDraft } from '../src/services/admin-textbooks-api.ts';

/**
 * 设计 362 行：发布请求体为 `{draftRevision, baseContentVersion, changeNote}`。
 *
 * **鉴别力**：`changeNote` 在本轮之前前端零命中；字段名写错时服务端只会忽略它，
 * 版本记录里的"修改摘要"会永远是空的，而没有任何报错。
 */

test('发布请求体恰好包含设计契约的三个字段', () => {
  const body = buildPublishIntent({ revision: 7, baseContentVersion: 'demo-book@3', changeNote: ' 修订了第一单元 ' });
  assert.deepEqual(Object.keys(body).sort(), ['baseContentVersion', 'changeNote', 'draftRevision']);
  assert.equal(body.draftRevision, 7);
  assert.equal(body.baseContentVersion, 'demo-book@3');
  assert.equal(body.changeNote, '修订了第一单元');
});

test('从未发布的草稿基线为 null，且摘要留空（设计未规定必填）', () => {
  const body = buildPublishIntent({ revision: 1 });
  assert.equal(body.baseContentVersion, null);
  assert.equal(body.changeNote, '', '未填写时发空串，而不是漏发字段');
});

test('摘要去空白并截断到上限，避免超长文本进入版本记录', () => {
  assert.equal(normalizeChangeNote(null), '');
  assert.equal(normalizeChangeNote('   '), '');
  assert.equal(normalizeChangeNote('a'.repeat(CHANGE_NOTE_MAX_LENGTH + 50)).length, CHANGE_NOTE_MAX_LENGTH);
});

test('draftRevision 是数字且来自调用方（发布必须绑定确认过的那一版）', () => {
  const body = buildPublishIntent({ revision: 0, changeNote: '首次' });
  assert.equal(body.draftRevision, 0);
  assert.equal(Number.isInteger(body.draftRevision), true);
});

test('publishDraft 真的把三字段发进请求体（不是只在纯函数里构造）', async () => {
  // **鉴别力**：只测 `buildPublishIntent` 的话，调用方把返回值丢掉也能通过。
  // 这一条断言的是**线上真正发出去的 JSON**（含查询串里的 draftRevision）。
  const original = globalThis.fetch;
  let captured: { url: string; body: string } | undefined;
  globalThis.fetch = (async (url: string, options: RequestInit) => {
    captured = { url: String(url), body: String(options.body ?? '') };
    return new Response(JSON.stringify({
      code: 'OK',
      data: { editionId: 'demo-book', contentVersion: 'v2', contentSha256: 'h', snapshotReloaded: true },
      requestId: 'r'
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }) as typeof fetch;
  try {
    await publishDraft('demo-book', { draftRevision: 9, baseContentVersion: 'demo-book@3', changeNote: '补充附录来源' });
  } finally {
    globalThis.fetch = original;
  }
  assert.ok(captured, '未捕获到发布请求');
  assert.deepEqual(JSON.parse(captured.body), {
    draftRevision: 9,
    baseContentVersion: 'demo-book@3',
    changeNote: '补充附录来源'
  });
  // 当前服务端用 `@RequestParam` 读 draftRevision，因此查询串里也必须带上它。
  assert.equal(captured.url.includes('draftRevision=9'), true, '当前服务端从查询参数读修订号');
  assert.equal(captured.url.endsWith('/textbooks/demo-book/publish?draftRevision=9'), true);
});

test('rebaseDraft 使用明确的修订与稳定幂等键，不发送任意覆盖正文', async () => {
  const original = globalThis.fetch;
  const requests: Array<{ url: string; options: RequestInit }> = [];
  globalThis.fetch = (async (url: string, options: RequestInit) => {
    requests.push({ url: String(url), options });
    return new Response(JSON.stringify({ code: 'OK', data: { draft: { entries: [] }, revision: 8, baseContentVersion: 'book@3' } }), {
      status: 200, headers: { 'Content-Type': 'application/json' }
    });
  }) as typeof fetch;
  try {
    const result = await rebaseDraft('book/special', 7, 'stable-rebase-key');
    assert.equal(result.revision, 8);
    await rebaseDraft('book/special', 7, 'stable-rebase-key');
  } finally {
    globalThis.fetch = original;
  }
  assert.equal(requests.length, 2);
  for (const { url, options } of requests) {
    assert.equal(url.endsWith('/textbooks/book%2Fspecial/draft/rebase'), true);
    assert.equal(options.method, 'POST');
    assert.equal(new Headers(options.headers).get('If-Match'), '"draft-7"');
    assert.equal(new Headers(options.headers).get('Idempotency-Key'), 'stable-rebase-key');
    assert.equal(options.body, undefined);
  }
});
