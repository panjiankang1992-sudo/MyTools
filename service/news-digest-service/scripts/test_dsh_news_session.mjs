import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { apply } from './dsh_news_session.mjs';

test('title and durable session precede grouping; a rejected group stops the hook', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'news-dsh-'));
  const socketPath = join(directory, 'group.sock');
  const order = [];
  let accept = true;
  let handler;
  const previousTitle = process.env.NEWS_DSH_SESSION_TITLE;
  const previousSocket = process.env.NEWS_DSH_SOCKET;
  const server = createServer({ allowHalfOpen: true }, socket => {
    let body = '';
    socket.on('data', data => { body += data; });
    socket.on('end', () => {
      assert.equal(JSON.parse(body).sessionId, 'session-fixture');
      order.push('group');
      socket.end(JSON.stringify({ ok: accept }));
    });
  });
  try {
    await new Promise(resolve => server.listen(socketPath, resolve));
    process.env.NEWS_DSH_SESSION_TITLE = '2026-11-14 TECH Top10';
    process.env.NEWS_DSH_SOCKET = socketPath;
    apply({
      on: (name, callback) => { assert.equal(name, 'agent/created'); handler = callback; },
      sessionTitle: { rename: (_session, title) => { assert.equal(title, process.env.NEWS_DSH_SESSION_TITLE); order.push('title'); } },
      sessions: { flush: async () => { order.push('flush'); } },
    });
    await handler({ agent: { session: { id: 'session-fixture' } } });
    assert.deepEqual(order, ['title', 'flush', 'group']);
    accept = false;
    await assert.rejects(handler({ agent: { session: { id: 'session-fixture' } } }), /DSH_NEWS_GROUP_FAILED/);
  } finally {
    await new Promise(resolve => server.close(resolve));
    await rm(directory, { recursive: true });
    if (previousTitle === undefined) delete process.env.NEWS_DSH_SESSION_TITLE;
    else process.env.NEWS_DSH_SESSION_TITLE = previousTitle;
    if (previousSocket === undefined) delete process.env.NEWS_DSH_SOCKET;
    else process.env.NEWS_DSH_SOCKET = previousSocket;
  }
});
