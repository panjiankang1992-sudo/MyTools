import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { NewsApi, NewsEdition, NewsRun, NewsSubscription } from '../../src/service/api/news.types';
import {
  canCancelNewsRun,
  createNewsWorkspace,
  newsTime,
  safeNewsUrl,
  validNewsSchedule
} from '../../src/views/news/model';

const cutoff = '2026-10-18T00:00:00Z';
const subscription: NewsSubscription = {
  id: 'subscription',
  revision: 3,
  enabled: true,
  zoneId: 'Asia/Shanghai',
  cutoffTime: '07:30',
  nextDate: '2026-10-19',
  nextDueAt: cutoff,
  updatedAt: cutoff
};
function edition(id: string) {
  return { id, cutoff, quality: 'DEGRADED' } as NewsEdition;
}
function run(status: string) {
  return { id: 'run', status, editionId: null } as NewsRun;
}
function failure(code: string) {
  return Object.assign(new Error(code), { code });
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function api(overrides: Partial<NewsApi> = {}): NewsApi {
  return {
    capabilities: async () => ({
      latestCutoff: cutoff,
      rankingVersion: 'v2',
      headlineLimit: 20,
      detailLimit: 10,
      detailMethod: 'SOURCE_EXCERPT_V1',
      regionStatus: 'UNRESOLVED',
      deliveryEnabled: false
    }),
    region: async () => ({ status: 'UNRESOLVED', regionCode: null, nodeId: null, resolvedAt: null, details: null }),
    subscription: async () => ({ subscription: { ...subscription }, schedulingEnabled: false, deliveryEnabled: false }),
    saveSubscription: async body => ({ ...subscription, ...body, revision: subscription.revision + 1 }),
    history: async () => [],
    runs: async () => [],
    edition: async id => edition(id),
    preview: async () => edition('preview'),
    cancel: async () => run('CANCELLED'),
    ...overrides
  };
}

test('external evidence URLs reject executable protocols, relative paths and credentials', () => {
  for (const value of [
    'javascript:alert(1)',
    'data:text/html,hi',
    '//example.org',
    'https://u:p@example.org',
    'file:///tmp/x',
    ''
  ])
    assert.equal(safeNewsUrl(value), undefined);
  assert.equal(safeNewsUrl('https://example.org/story?a=1'), 'https://example.org/story?a=1');
});
test('schedule validation and explicit time zones preserve cutoff semantics', () => {
  assert.equal(validNewsSchedule('Asia/Shanghai', '07:30'), true);
  for (const [zone, time] of [
    ['Invalid/Zone', '07:30'],
    ['UTC', '24:00'],
    ['UTC', '7:30'],
    ['', '10:00']
  ])
    assert.equal(validNewsSchedule(zone, time), false);
  assert.match(newsTime(cutoff, 'Asia/Shanghai', 'en-US'), /08:00/);
  assert.equal(newsTime(null), '—');
});
test('failed source panels are not represented as a successful empty dataset', async () => {
  const model = createNewsWorkspace(
    api({
      history: async () => {
        throw failure('UNAVAILABLE');
      }
    })
  );
  await model.initialize();
  assert.equal(model.state.issues.history, 'UNAVAILABLE');
  assert.ok(model.state.subscription);
  assert.equal(model.state.subscription.schedulingEnabled, false);
});
test('preview unknown result keeps original key and cutoff across retries', async () => {
  const bodies: unknown[] = [];
  const model = createNewsWorkspace(
    api({
      preview: async body => {
        bodies.push(body);
        if (bodies.length === 1) throw failure('UNAVAILABLE');
        return edition('preview');
      }
    }),
    () => 'stable-key'
  );
  await model.initialize();
  await model.preview();
  model.state.capabilities!.latestCutoff = '2026-10-19T00:00:00Z';
  await model.preview();
  assert.deepEqual(bodies[0], bodies[1]);
  assert.equal(model.state.edition?.id, 'preview');
  assert.equal(model.state.previewAttempt, null);
});
test('double click cannot create concurrent preview attempts', async () => {
  const pending = deferred<NewsEdition>();
  let calls = 0;
  const model = createNewsWorkspace(
    api({
      preview: () => {
        calls++;
        return pending.promise;
      }
    }),
    () => 'same'
  );
  await model.initialize();
  const first = model.preview();
  await model.preview();
  assert.equal(calls, 1);
  pending.resolve(edition('preview'));
  await first;
});
test('late history response cannot overwrite the latest selection', async () => {
  const first = deferred<NewsEdition>();
  const model = createNewsWorkspace(
    api({ edition: id => (id === 'old' ? first.promise : Promise.resolve(edition(id))) })
  );
  const old = model.selectEdition('old');
  await model.selectEdition('new');
  first.resolve(edition('old'));
  await old;
  assert.equal(model.state.edition?.id, 'new');
  assert.equal(model.state.reading, false);
});
test('preview completion does not replace an edition selected while it was running', async () => {
  const pending = deferred<NewsEdition>();
  const model = createNewsWorkspace(api({ preview: () => pending.promise }));
  await model.initialize();
  const preview = model.preview();
  await model.selectEdition('chosen');
  pending.resolve(edition('preview'));
  await preview;
  assert.equal(model.state.edition?.id, 'chosen');
});
test('revision conflict preserves draft and blocks silent overwrite until explicit reload', async () => {
  let calls = 0;
  const model = createNewsWorkspace(
    api({
      saveSubscription: async body => {
        calls++;
        assert.equal(body.expectedRevision, 3);
        throw failure('NEWS_014');
      }
    })
  );
  await model.initialize();
  model.state.draft.cutoffTime = '09:15';
  await model.save();
  assert.equal(model.state.draft.cutoffTime, '09:15');
  assert.equal(model.state.conflict, true);
  await model.refreshOverview();
  await model.save();
  assert.equal(calls, 1);
  assert.equal(model.state.conflict, true);
  await model.reloadSubscription();
  assert.equal(model.state.conflict, false);
  assert.equal(model.state.draft.cutoffTime, '07:30');
});
test('subscription payload contains only the four editable fields', async () => {
  let keys: string[] = [];
  const model = createNewsWorkspace(
    api({
      subscription: async () => ({ subscription: null, schedulingEnabled: false, deliveryEnabled: false }),
      saveSubscription: async body => {
        keys = Object.keys(body).sort();
        assert.equal(body.expectedRevision, 0);
        return subscription;
      }
    })
  );
  await model.initialize();
  await model.save();
  assert.deepEqual(keys, ['cutoffTime', 'enabled', 'expectedRevision', 'zoneId']);
  assert.equal(model.state.saved, true);
});
test('invalid schedule prevents network mutation', async () => {
  let calls = 0;
  const model = createNewsWorkspace(
    api({
      saveSubscription: async () => {
        calls++;
        return subscription;
      }
    })
  );
  await model.initialize();
  model.state.draft.zoneId = 'Not/AZone';
  await model.save();
  assert.equal(calls, 0);
  assert.equal(model.state.issues.save, 'INVALID_SCHEDULE');
});
test('cancellation race can return READY and must keep the readable outcome', async () => {
  const ready = { ...run('READY'), editionId: 'edition' };
  const model = createNewsWorkspace(api({ cancel: async () => ready, runs: async () => [ready] }));
  await model.cancel(run('QUEUED'));
  assert.equal(model.state.runs[0].editionId, 'edition');
  assert.equal(canCancelNewsRun(ready), false);
});
test('disposed page ignores late data responses', async () => {
  const pending = deferred<NewsEdition>();
  const model = createNewsWorkspace(api({ edition: () => pending.promise }));
  const selected = model.selectEdition('old');
  model.dispose();
  pending.resolve(edition('old'));
  await selected;
  assert.equal(model.state.edition, null);
});

test('key generation failure releases the preview button without sending a request', async () => {
  let calls = 0;
  const model = createNewsWorkspace(
    api({
      preview: async () => {
        calls++;
        return edition('preview');
      }
    }),
    () => {
      throw new Error('Unavailable randomness');
    }
  );
  await model.initialize();
  await model.preview();
  assert.equal(calls, 0);
  assert.equal(model.state.previewing, false);
  assert.equal(model.state.issues.preview, 'UNAVAILABLE');
});
