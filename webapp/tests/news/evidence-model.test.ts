import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { NewsEvidenceSnapshot } from '../../src/service/api/news-evidence.types';
import { createEvidenceWorkspace } from '../../src/views/news/evidence-model';

const checkedAt = '2026-10-18T00:00:00Z';
function snapshot(id: string): NewsEvidenceSnapshot {
  return { editionId: id, checkedAt, items: [{ articleId: 'a', status: 'AVAILABLE', expiresAt: checkedAt }] };
}
function deferred() {
  let resolve!: (value: NewsEvidenceSnapshot) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<NewsEvidenceSnapshot>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

test('current snapshot uses only returned statuses and supports all retention outcomes', async () => {
  const value = snapshot('one');
  value.items.push({ articleId: 'b', status: 'RETENTION_EXPIRED', expiresAt: checkedAt }, { articleId: 'c', status: 'UNAVAILABLE', expiresAt: null });
  const model = createEvidenceWorkspace({ inspect: async () => value });
  await model.load('one');
  assert.equal(model.state.failed, false);
  assert.equal(model.state.checkedAt, checkedAt);
  assert.equal(model.state.items.b.status, 'RETENTION_EXPIRED');
  assert.equal(model.state.items.c.expiresAt, null);
});

test('changing edition clears old state and ignores late success', async () => {
  const first = deferred();
  const model = createEvidenceWorkspace({ inspect: id => id === 'one' ? first.promise : Promise.resolve(snapshot(id)) });
  const pending = model.load('one');
  await model.load('two');
  first.resolve(snapshot('one'));
  await pending;
  assert.equal(model.state.editionId, 'two');
  assert.equal(model.state.failed, false);
  assert.equal(model.state.loading, false);
});

test('late failure cannot invalidate a new edition and refresh failure clears stale availability', async () => {
  const first = deferred();
  let fail = false;
  const model = createEvidenceWorkspace({ inspect: id => {
    if (id === 'one') return first.promise;
    if (fail) return Promise.reject(new Error('private response'));
    return Promise.resolve(snapshot(id));
  } });
  const pending = model.load('one');
  await model.load('two');
  first.reject(new Error('old failure'));
  await pending;
  assert.equal(model.state.failed, false);
  fail = true;
  await model.load('two');
  assert.equal(model.state.failed, true);
  assert.equal(model.state.checkedAt, '');
  assert.deepEqual(model.state.items, {});
  assert.equal(JSON.stringify(model.state).includes('private'), false);
  fail = false;
  await model.load('two');
  assert.equal(model.state.failed, false);
});

test('mismatched edition malformed dates duplicate ids and unknown statuses are rejected', async () => {
  const variants: NewsEvidenceSnapshot[] = [
    snapshot('wrong'),
    { ...snapshot('one'), checkedAt: 'invalid' },
    { ...snapshot('one'), items: [...snapshot('one').items, ...snapshot('one').items] },
    { ...snapshot('one'), items: [{ articleId: 'a', status: 'UNAVAILABLE', expiresAt: 'invalid' }] },
    { ...snapshot('one'), items: [{ articleId: 'a', status: 'MADE_UP' as 'AVAILABLE', expiresAt: null }] }
  ];
  for (const value of variants) {
    const model = createEvidenceWorkspace({ inspect: async () => value });
    await model.load('one');
    assert.equal(model.state.failed, true);
    assert.deepEqual(model.state.items, {});
  }
});

test('unmounted reader ignores pending responses and starts no new calls', async () => {
  const wait = deferred();
  let calls = 0;
  const model = createEvidenceWorkspace({ inspect: () => { calls++; return wait.promise; } });
  const pending = model.load('one');
  model.dispose();
  wait.resolve(snapshot('one'));
  await pending;
  await model.load('two');
  assert.equal(calls, 1);
  assert.equal(model.state.editionId, 'one');
  assert.equal(model.state.checkedAt, '');
});
