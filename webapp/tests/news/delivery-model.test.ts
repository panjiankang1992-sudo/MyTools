import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { DeliveryBatch, DeliveryRetry, DeliveryTargets, NewsDeliveryApi } from '../../src/service/api/news-delivery.types';
import { canRetryDelivery, canStopDelivery, createNewsDeliveryWorkspace, needsDeliveryRefresh } from '../../src/views/news/delivery-model';

function batch(status = 'UNKNOWN', generation = 1): DeliveryBatch {
  return { id: 'batch', runId: 'run', editionId: 'edition', destinationId: 'dest', channel: 'EMAIL', mode: 'FULL', status,
    cancelRequested: false, expiresAt: '2099-01-01T00:00:00Z', createdAt: '2026-10-18T00:00:00Z', errorCode: null,
    parts: [{ id: 'part', index: 1, count: 1, status, generation, errorCode: null, retryPending: false }] };
}
function targets(revision = 1): DeliveryTargets {
  return { revision, targets: [{ destinationId: 'dest', channel: 'EMAIL', mode: 'FULL', enabled: true }] };
}
function api(overrides: Partial<NewsDeliveryApi> = {}): NewsDeliveryApi {
  return {
    capabilities: async () => ({ enabled: true, supportedChannels: ['EMAIL', 'FEISHU'], qqStatus: 'PROACTIVE_PUSH_UNAVAILABLE', bindingMode: 'ADMIN_VERIFIED', targetLimit: 5 }),
    destinations: async () => [{ id: 'dest', channel: 'EMAIL', label: 'Verified target', enabled: true, createdAt: '' }],
    targets: async () => targets(), saveTargets: async body => ({ revision: 2, targets: body.targets.map(item => ({ ...item, channel: 'EMAIL' })) }),
    history: async () => [batch()], cancel: async () => ({ ...batch('SENDING'), cancelRequested: true }),
    retry: async () => ({ ...batch(), parts: [{ ...batch().parts[0], retryPending: true }] }),
    redrive: async () => batch('SUBMITTING'), ...overrides
  };
}
function failure(code: string) { return Object.assign(new Error(code), { code }); }
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

test('refresh preserves dirty targets and independent failures do not look empty', async () => {
  let fail = false;
  const model = createNewsDeliveryWorkspace(api({ history: async () => { if (fail) throw failure('NEWS_023'); return [batch()]; } }));
  await model.initialize();
  model.state.draft[0].mode = 'BRIEF';
  fail = true;
  await model.refreshOverview();
  assert.equal(model.state.draft[0].mode, 'BRIEF');
  assert.equal(model.state.history.length, 1);
  assert.equal(model.state.issues.history, 'NEWS_023');
});
test('optimistic conflict retains draft until explicit reload and blocks blind overwrite', async () => {
  let calls = 0;
  const model = createNewsDeliveryWorkspace(api({ saveTargets: async () => { calls++; throw failure('NEWS_022'); } }));
  await model.initialize();
  model.state.draft[0].mode = 'BRIEF';
  await model.save(); await model.save();
  assert.equal(calls, 1);
  assert.equal(model.state.conflict, true);
  assert.equal(model.state.draft[0].mode, 'BRIEF');
  await model.reloadTargets();
  assert.equal(model.state.conflict, false);
  assert.equal(model.state.draft[0].mode, 'FULL');
});
test('save body contains only revision and selections, with no client owner or credentials', async () => {
  let body: unknown;
  const model = createNewsDeliveryWorkspace(api({ saveTargets: async value => { body = value; return targets(2); } }));
  await model.initialize(); await model.save();
  assert.deepEqual(body, { expectedRevision: 1, targets: [{ destinationId: 'dest', mode: 'FULL', enabled: true }] });
  assert.equal(model.state.targets?.revision, 2);
});
test('unsupported, duplicate, disabled, and excess targets cannot be added', async () => {
  const model = createNewsDeliveryWorkspace(api({ destinations: async () => [
    { id: 'qq', channel: 'QQ', label: '', enabled: true, createdAt: '' },
    { id: 'off', channel: 'FEISHU', label: '', enabled: false, createdAt: '' },
    ...Array.from({ length: 8 }, (_, i) => ({ id: `target-${i}`, channel: 'EMAIL' as const, label: '', enabled: true, createdAt: '' }))
  ] }));
  await model.initialize();
  for (const id of ['qq', 'off', 'missing', 'dest', ...Array.from({ length: 8 }, (_, i) => `target-${i}`)]) model.addTarget(id);
  assert.equal(model.state.draft.length, 5);
  assert.deepEqual(model.state.draft.map(item => item.destinationId), ['dest', 'target-0', 'target-1', 'target-2', 'target-3']);
});
test('disabled transport still loads settings and permits removing all targets', async () => {
  let destinationCalls = 0;
  const base = api();
  const model = createNewsDeliveryWorkspace(api({ capabilities: async () => ({ ...await base.capabilities(), enabled: false }),
    destinations: async () => { destinationCalls++; throw failure('NEWS_021'); } }));
  await model.initialize();
  assert.equal(destinationCalls, 0);
  model.state.draft = [];
  await model.save();
  assert.equal(model.state.saved, true);
  assert.deepEqual(model.state.targets?.targets, []);
});
test('unknown delivery requires explicit duplicate risk acceptance', async () => {
  let calls = 0;
  const model = createNewsDeliveryWorkspace(api({ retry: async () => { calls++; return batch('ACCEPTED', 2); } }));
  await model.initialize();
  const current = model.state.history[0];
  await model.retry(current, current.parts[0]);
  assert.equal(calls, 0);
  await model.retry(current, current.parts[0], true);
  assert.equal(calls, 1);
  assert.equal(model.state.history[0].parts[0].generation, 2);
});
test('lost retry response resumes identical generation and risk acknowledgement', async () => {
  const bodies: DeliveryRetry[] = [];
  const model = createNewsDeliveryWorkspace(api({ retry: async (_id, _part, body) => {
    bodies.push(body); if (bodies.length === 1) throw failure('UNAVAILABLE'); return batch('FAILED', 2);
  } }));
  await model.initialize();
  const current = model.state.history[0];
  await model.retry(current, current.parts[0], true);
  assert.ok(model.state.retryAttempts.part);
  await model.retry(current, current.parts[0]);
  assert.deepEqual(bodies, [{ expectedGeneration: 1, acknowledgeDuplicateRisk: true }, { expectedGeneration: 1, acknowledgeDuplicateRisk: true }]);
  assert.equal(model.state.retryAttempts.part, undefined);
  assert.equal(model.state.history[0].parts[0].generation, 2);
});
test('persisted retry pending is accepted as acknowledgement without creating another attempt', async () => {
  const model = createNewsDeliveryWorkspace(api()); await model.initialize();
  await model.retry(model.state.history[0], model.state.history[0].parts[0], true);
  assert.equal(model.state.retryAttempts.part, undefined);
  assert.equal(canRetryDelivery(model.state.history[0], model.state.history[0].parts[0]), false);
});
test('refresh reconciles lost response using generation before a new user action', async () => {
  let value = batch();
  const model = createNewsDeliveryWorkspace(api({ history: async () => [value], retry: async () => { value = batch('FAILED', 2); throw failure('UNAVAILABLE'); } }));
  await model.initialize();
  await model.retry(model.state.history[0], model.state.history[0].parts[0], true);
  await model.refreshHistory();
  assert.equal(model.state.retryAttempts.part, undefined);
  assert.equal(model.state.history[0].parts[0].generation, 2);
});
test('cancel request never pretends an in-flight provider call was cancelled', async () => {
  const model = createNewsDeliveryWorkspace(api()); await model.initialize();
  await model.cancel(model.state.history[0]);
  assert.equal(model.state.history[0].cancelRequested, true);
  assert.equal(model.state.history[0].status, 'SENDING');
  assert.equal(canStopDelivery(model.state.history[0]), false);
  assert.equal(canRetryDelivery(model.state.history[0], model.state.history[0].parts[0]), false);
});
test('stale polling response cannot undo a completed cancel mutation', async () => {
  const pending = deferred<DeliveryBatch[]>(); let slow = false;
  const model = createNewsDeliveryWorkspace(api({ history: () => slow ? pending.promise : Promise.resolve([batch()]) }));
  await model.initialize(); slow = true;
  const refresh = model.refreshHistory();
  await model.cancel(model.state.history[0]);
  pending.resolve([batch()]); await refresh;
  assert.equal(model.state.history[0].cancelRequested, true);
});
test('poll started during a mutation cannot overwrite its later result', async () => {
  const pending = deferred<DeliveryBatch[]>(); const cancel = deferred<DeliveryBatch>(); let slow = false;
  const model = createNewsDeliveryWorkspace(api({ history: () => slow ? pending.promise : Promise.resolve([batch()]), cancel: () => cancel.promise }));
  await model.initialize(); slow = true;
  const operation = model.cancel(model.state.history[0]);
  const refresh = model.refreshHistory();
  cancel.resolve({ ...batch('SENDING'), cancelRequested: true }); await operation;
  pending.resolve([batch()]); await refresh;
  assert.equal(model.state.history[0].cancelRequested, true);
});
test('concurrent retries submit once and disposal ignores late responses', async () => {
  const pending = deferred<DeliveryBatch>(); let calls = 0;
  const model = createNewsDeliveryWorkspace(api({ retry: async () => { calls++; return pending.promise; } }));
  await model.initialize(); const current = model.state.history[0];
  const first = model.retry(current, current.parts[0], true);
  await model.retry(current, current.parts[0], true);
  model.dispose(); pending.resolve(batch('ACCEPTED', 2)); await first;
  assert.equal(calls, 1);
  assert.equal(model.state.history[0].status, 'UNKNOWN');
});
test('terminal, expired, maximum-generation and pending attempts cannot be retried', () => {
  for (const value of [batch('PROVIDER_ACCEPTED'), batch('CANCELLED'), batch('EXPIRED'), batch('FAILED', 100),
    { ...batch(), expiresAt: '2000-01-01T00:00:00Z' }, { ...batch(), cancelRequested: true }]) {
    assert.equal(canRetryDelivery(value, value.parts[0]), false);
  }
  assert.equal(needsDeliveryRefresh(batch('UNKNOWN')), true);
  assert.equal(needsDeliveryRefresh(batch('PROVIDER_ACCEPTED')), false);
});
test('redrive preserves the original part and only permits rejected records', async () => {
  const calls: string[] = [];
  const model = createNewsDeliveryWorkspace(api({ history: async () => [batch('REJECTED')], redrive: async (id, part) => { calls.push(`${id}/${part}`); return batch('SUBMITTING'); } }));
  await model.initialize();
  await model.redrive(model.state.history[0], model.state.history[0].parts[0]);
  await model.redrive(model.state.history[0], model.state.history[0].parts[0]);
  assert.deepEqual(calls, ['batch/part']);
});
