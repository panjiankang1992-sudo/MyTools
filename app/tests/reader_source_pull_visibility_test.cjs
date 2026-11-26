const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const root = path.resolve(__dirname, '../entry/src/main/ets');
const source = fs.readFileSync(path.join(root, 'pages/Index.ets'), 'utf8');
const firstScope = 'account:v2:' + 'a'.repeat(64);
const secondScope = 'account:v2:' + 'b'.repeat(64);
const tests = [];
const test = (name, run) => tests.push({ name, run });

function deferred() {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

async function flush() { for (let index = 0; index < 16; index++) await Promise.resolve(); }

function syncedSource(name = 'fixture-source') {
  return { sourceUrl: 'https://' + name + '.example.test', snapshotJson: '{}',
    updatedAt: 1, deleted: false, revision: 1 };
}

function extract(name) {
  let start = source.indexOf('  private ' + name + '(');
  if (start < 0) start = source.indexOf('  private async ' + name + '(');
  assert.ok(start >= 0, 'Missing production method: ' + name);
  const end = source.indexOf('\n  }\n', start);
  assert.ok(end > start, 'Incomplete production method: ' + name);
  return source.substring(start, end + 5);
}

function fixture({ currentIndex = 1, authenticated = true, local = [] } = {}) {
  const reads = [], writes = [], applied = [], persisted = [], shelfPulls = [], statuses = [];
  const state = { session: 1 };
  class BookSourceSyncApi {
    constructor(base, auth) { assert.equal(base, 'https://fixture.example.test'); assert.equal(auth, authManager); }
    list(cancellation) {
      const task = deferred();
      reads.push({ ...task, cancellation });
      return task.promise;
    }
    save(item, ...extra) {
      assert.equal(extra.length, 0, 'Read cancellation leaked into an already-started write');
      const task = deferred();
      writes.push({ ...task, item });
      return task.promise;
    }
  }
  const compiledCancellation = new Module('reader-source-cancellation-fixture', module);
  compiledCancellation._compile(ts.transpileModule(fs.readFileSync(
    path.join(root, 'shared/network/DownloadCancellationToken.ets'), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText, 'reader-source-cancellation-fixture');
  const { DownloadCancellationToken } = compiledCancellation.exports;
  const methods = ['PullBookSources', 'CancelBookSourcePull', 'CancelReaderPulls', 'ChangeMainTab',
    'PrepareReadingSyncForAccount', 'DrainBookSourceQueue'].map(extract).join('\n');
  const compiled = { exports: {} };
  const output = ts.transpileModule('class SourcePage {\n' + methods + '\n}\nmodule.exports = SourcePage;', {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText;
  new Function('module', 'BookSourceSyncApi', 'DownloadCancellationToken', output)(
    compiled, BookSourceSyncApi, DownloadCancellationToken);
  const authManager = { sessionRevision: () => state.session };
  const page = new compiled.exports();
  Object.assign(page, { currentIndex, authenticated, serviceUrl: 'https://fixture.example.test',
    authManager, localAccountScope: firstScope, readerProgressQueueOwner: firstScope,
    readerProgressSyncGeneration: 1, pageLifecycleRevision: 1,
    pendingReaderProgress: [], pendingReaderMarkers: [], pendingShelfBooks: [], pendingBookSources: [],
    readerProgressPullPending: false, readerMarkerPullPending: false, shelfPullPending: false,
    bookSourcePullPending: false, bookSourcePullDeferred: false, bookSourcePullEpoch: 0,
    bookSourceSyncing: false, activeBookSourcePullCancellation: undefined,
    activeProgressPullCancellation: undefined, activeMarkerPullCancellation: undefined,
    activeShelfPullCancellation: undefined, tabs: Array.from({ length: 5 }, () => ({})),
    toolMode: 'home', dshWebRevision: 0, dshWebConnecting: false, dshExternalNotice: '',
    dshWebLoadMonitor: { close() {} }, dshDownloads: { close() {} }, dshWebController: { loadUrl() {} },
    CloseDshPopup() {}, RefreshDshWeb() {}, CancelMarketOperation() {},
    CancelMediaCatalogPrefetch() {}, mediaCatalogPrefetchPolicy: { resetScroll() {} },
    activeMediaCatalogCancellation: undefined, ResetMediaCatalogThumbnailQueue() {},
    activeDriveCancellation: undefined, ActiveMediaCatalogItems: () => [],
    ActiveServiceUrl: () => 'https://fixture.example.test',
    AllLocalBookSourceItems: () => local,
    ValidateSyncedBookSource: () => ({}),
    ApplyBookSourceSyncItems(items) { applied.push(...items); },
    ApplyBookSourceSyncItem(item) { applied.push(item); },
    PersistReaderState() { persisted.push(true); },
    MarkReaderSyncPending() { statuses.push('pending'); },
    UpdateReaderSyncStatus() { statuses.push('updated'); },
    PullShelfBooks() { shelfPulls.push(true); }
  });
  return { page, state, reads, writes, applied, persisted, shelfPulls, statuses,
    DownloadCancellationToken,
    async resolveRead(index, items = []) { reads[index].resolve(items); await flush(); },
    async rejectRead(index) { reads[index].reject(Error('Fixture read cancelled')); await flush(); },
    async resolveWrite(index, receipt) {
      writes[index].resolve(receipt || { accepted: true, source: writes[index].item });
      await flush();
    }
  };
}

test('off-reader requests defer the large GET and start the small synchronization chain once', async () => {
  const item = fixture();
  await item.page.PullBookSources();
  await item.page.PullBookSources();
  await item.page.PullBookSources();
  assert.equal(item.reads.length, 0);
  assert.equal(item.page.bookSourcePullDeferred, true);
  assert.equal(item.page.bookSourceSyncing, false);
  assert.equal(item.shelfPulls.length, 1);
  assert.equal(item.applied.length, 0);
  assert.equal(item.persisted.length, 0);
});

test('entering the reader consumes its deferred pull and performs one real GET', async () => {
  const item = fixture();
  await item.page.PullBookSources();
  item.page.ChangeMainTab(0);
  await flush();
  assert.equal(item.reads.length, 1);
  assert.equal(item.page.bookSourcePullDeferred, false);
  assert.equal(item.page.bookSourceSyncing, true);
  await item.resolveRead(0);
  assert.equal(item.page.bookSourceSyncing, false);
  assert.equal(item.page.activeBookSourcePullCancellation, undefined);
});

test('entering the reader also consumes an explicitly pending pull', async () => {
  const item = fixture(); item.page.bookSourcePullPending = true;
  item.page.ChangeMainTab(0); await flush();
  assert.equal(item.reads.length, 1);
  await item.resolveRead(0);
  assert.equal(item.reads.length, 1, 'The pending entry request replayed itself after completing');
});

test('leaving cancels only the current GET and reentering before finally schedules exactly one replacement', async () => {
  const item = fixture({ currentIndex: 0 });
  const oldWork = item.page.PullBookSources(); await flush();
  const oldToken = item.reads[0].cancellation;
  item.page.ChangeMainTab(1);
  assert.equal(oldToken.isCancelled(), true);
  assert.equal(item.page.bookSourcePullDeferred, true);
  item.page.ChangeMainTab(0);
  await flush();
  assert.equal(item.reads.length, 1, 'Reader entry started a concurrent replacement before old finally released ownership');
  assert.equal(item.page.bookSourcePullDeferred, true);
  await item.rejectRead(0); await oldWork;
  assert.equal(item.reads.length, 2);
  assert.equal(item.page.activeBookSourcePullCancellation, item.reads[1].cancellation);
  assert.equal(item.page.bookSourceSyncing, true);
  assert.equal(item.reads[1].cancellation.isCancelled(), false);
  await item.resolveRead(1);
  assert.equal(item.reads.length, 2);
  assert.equal(item.page.bookSourceSyncing, false);
});

test('leaving and remaining off-reader preserves a deferred pull without starting another large GET', async () => {
  const item = fixture({ currentIndex: 0 });
  const work = item.page.PullBookSources(); await flush();
  item.page.ChangeMainTab(1); await item.resolveRead(0, [syncedSource()]); await work;
  assert.equal(item.reads.length, 1);
  assert.equal(item.page.bookSourcePullDeferred, true);
  assert.equal(item.page.bookSourceSyncing, false);
  assert.equal(item.applied.length, 0, 'The cancelled GET applied a late response after leaving the reader');
  assert.equal(item.persisted.length, 0);
});

test('explicit same-tab refreshes coalesce into one follow-up GET', async () => {
  const item = fixture({ currentIndex: 0 });
  const work = item.page.PullBookSources(); await flush();
  await item.page.PullBookSources();
  await item.page.PullBookSources();
  await item.page.PullBookSources();
  assert.equal(item.reads.length, 1);
  assert.equal(item.page.bookSourcePullPending, true);
  await item.resolveRead(0); await work;
  assert.equal(item.reads.length, 2);
  assert.equal(item.page.bookSourcePullPending, false);
  await item.resolveRead(1);
  assert.equal(item.reads.length, 2, 'A single merged refresh caused repeated list requests');
});

test('ordinary reader GET applies a valid remote result and persists it', async () => {
  const item = fixture({ currentIndex: 0 });
  const work = item.page.PullBookSources(); await flush();
  const remote = syncedSource();
  await item.resolveRead(0, [remote]); await work;
  assert.deepEqual(item.applied, [remote]);
  assert.equal(item.persisted.length, 1);
  assert.equal(item.shelfPulls.length, 1);
});

for (const change of ['account', 'epoch', 'unmount', 'session']) {
  for (const completion of ['success', 'failure']) {
    test('old GET ' + completion + ' after ' + change + ' cannot release or replay a newer request', async () => {
      const item = fixture({ currentIndex: 0 });
      const oldWork = item.page.PullBookSources(); await flush();
      if (change === 'account') {
        item.state.session++;
        item.page.localAccountScope = secondScope;
        item.page.PrepareReadingSyncForAccount(secondScope);
      } else if (change === 'unmount') {
        item.page.pageLifecycleRevision++;
        item.page.CancelReaderPulls();
      } else {
        item.page.CancelBookSourcePull(false);
        if (change === 'session') item.state.session++;
      }
      const newWork = item.page.PullBookSources(); await flush();
      assert.equal(item.reads.length, 2, 'Invalidating an old pull failed to release its own read busy state');
      await item.page.PullBookSources();
      const newToken = item.page.activeBookSourcePullCancellation;
      const before = { pending: item.page.bookSourcePullPending, deferred: item.page.bookSourcePullDeferred,
        shelf: item.shelfPulls.length, updates: item.statuses.length };
      if (completion === 'success') await item.resolveRead(0, [syncedSource('old-source')]);
      else await item.rejectRead(0);
      await oldWork;
      assert.equal(item.page.activeBookSourcePullCancellation, newToken);
      assert.equal(item.page.bookSourceSyncing, true, 'An old finally released the newer GET busy flag');
      assert.equal(item.page.bookSourcePullPending, before.pending);
      assert.equal(item.page.bookSourcePullDeferred, before.deferred);
      assert.equal(item.shelfPulls.length, before.shelf);
      assert.equal(item.statuses.length, before.updates, 'An old finally changed the new synchronization status');
      assert.equal(item.reads.length, 2, 'An old finally started a new GET for another scope');
      assert.equal(item.applied.length, 0);
      assert.equal(item.persisted.length, 0);
      item.page.CancelBookSourcePull(false);
      await item.rejectRead(1); await newWork;
    });
  }
}

test('read invalidation keeps pending writes and does not cancel or replay an active save', async () => {
  const item = fixture({ currentIndex: 0 });
  const first = syncedSource('first-write'), second = syncedSource('second-write');
  item.page.pendingBookSources = [first, second];
  const writeWork = item.page.DrainBookSourceQueue(); await flush();
  assert.equal(item.writes.length, 1);
  assert.deepEqual(item.page.pendingBookSources, [second]);
  item.page.ChangeMainTab(1);
  await item.page.PullBookSources();
  item.page.CancelReaderPulls();
  assert.equal(item.page.bookSourceSyncing, true, 'Cancelling read ownership released an unrelated active writer');
  assert.deepEqual(item.page.pendingBookSources, [second]);
  assert.equal(item.reads.length, 0);
  await item.resolveWrite(0);
  assert.equal(item.writes.length, 2, 'Read cancellation stopped the remaining write queue');
  await item.resolveWrite(1); await writeWork;
  assert.deepEqual(item.writes.map(value => value.item), [first, second]);
  assert.equal(item.page.pendingBookSources.length, 0);
  assert.equal(item.reads.length, 0);
});

test('a reader refresh during save waits for the write and requests the list once afterwards', async () => {
  const item = fixture({ currentIndex: 0 });
  item.page.pendingBookSources = [syncedSource()];
  const writeWork = item.page.DrainBookSourceQueue(); await flush();
  await item.page.PullBookSources(); await item.page.PullBookSources();
  assert.equal(item.reads.length, 0);
  await item.resolveWrite(0); await writeWork;
  assert.equal(item.reads.length, 1);
  assert.equal(item.writes.length, 1);
  await item.resolveRead(0);
  assert.equal(item.reads.length, 1);
});

for (const change of ['account', 'epoch', 'unmount', 'session']) {
  test('old writer finally after ' + change + ' cannot start a deferred GET in the new scope', async () => {
    const item = fixture({ currentIndex: 0 });
    item.page.pendingBookSources = [syncedSource()];
    const work = item.page.DrainBookSourceQueue(); await flush();
    await item.page.PullBookSources();
    if (change === 'account') {
      item.state.session++;
      item.page.localAccountScope = secondScope;
      item.page.PrepareReadingSyncForAccount(secondScope);
    } else if (change === 'unmount') {
      item.page.pageLifecycleRevision++;
      item.page.CancelReaderPulls();
    } else {
      item.page.CancelBookSourcePull(false);
      if (change === 'session') item.state.session++;
    }
    item.page.bookSourcePullPending = true;
    item.page.bookSourcePullDeferred = true;
    await item.resolveWrite(0); await work;
    assert.equal(item.reads.length, 0, 'An obsolete writer finally initiated a large GET in a newer scope');
    assert.equal(item.writes.length, 1, 'A read scope change replayed a completed write');
    assert.equal(item.page.bookSourcePullPending, true);
    assert.equal(item.page.bookSourcePullDeferred, true);
  });
}

test('anonymous calls do not defer or start either synchronization chain', async () => {
  const item = fixture({ authenticated: false });
  await item.page.PullBookSources();
  item.page.ChangeMainTab(0); await flush();
  assert.equal(item.reads.length, 0);
  assert.equal(item.shelfPulls.length, 0);
  assert.equal(item.page.bookSourcePullDeferred, false);
});

(async () => {
  for (const item of tests) {
    try { await item.run(); }
    catch (error) { console.error('Failed source visibility test: ' + item.name); throw error; }
  }
  console.log(`Reader source pull visibility behavior tests passed (${tests.length} cases)`);
})().catch(error => { console.error(error); process.exitCode = 1; });
