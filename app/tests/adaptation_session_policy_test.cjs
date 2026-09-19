const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const fs = require('node:fs');
const root = process.argv[2];
const { AdaptationConsentController } = require(path.join(root, 'features/reader/AdaptationConsentController.js'));
const { AdaptationPendingJournal } = require(path.join(root, 'features/reader/AdaptationPendingJournal.js'));
const { AdaptationChunkedStorage } = require(path.join(root, 'features/reader/AdaptationChunkedStorage.js'));
const { DownloadCancellationToken } = require(path.join(root, 'shared/network/DownloadCancellationToken.js'));
const scope = `account:v2:${'a'.repeat(64)}`;
const otherScope = `account:v2:${'b'.repeat(64)}`;
const uuid = value => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`;
function features(patch = {}) {
  return { readEnabled: true, createEnabled: false, consentStatus: 'REQUIRED', consentRevision: 7,
    hasActiveConsent: false, acceptedAt: '', reasonCode: '', disclosure: { version: 'v1',
      disclosureSha256: 'a'.repeat(64), rightsAttestationVersion: 'r1', payload: {} }, ...patch };
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function consent(overrides = {}) {
  const calls = [], changes = [], lifetime = new DownloadCancellationToken();
  const current = { value: true };
  const port = {
    features: async token => { calls.push(['get', token]); return features(); },
    acceptConsent: async (...args) => { calls.push(['accept', ...args]); return features({ consentStatus: 'ACCEPTED', consentRevision: 8 }); },
    revokeConsent: async (...args) => { calls.push(['revoke', ...args]); return features({ consentRevision: 8 }); },
    ...overrides
  };
  const controller = new AdaptationConsentController(port, lifetime, () => current.value, state => changes.push(state));
  return { controller, calls, changes, lifetime, current };
}

test('opening consent is read-only and requires two explicit choices, not create capability', async () => {
  const value = consent(); await value.controller.open();
  assert.deepEqual(value.calls.map(call => call[0]), ['get']);
  assert.equal(value.controller.canAccept(), false);
  value.controller.processing(true); await value.controller.accept();
  assert.equal(value.calls.length, 1);
  value.controller.rights(true); assert.equal(value.controller.canAccept(), true);
  const displayed = value.controller.snapshot().features; await value.controller.accept();
  const call = value.calls[1]; assert.equal(call[0], 'accept'); assert.equal(call[1], displayed);
  assert.equal(call[1].consentRevision, 7); assert.deepEqual(call.slice(2, 4), [true, true]);
  assert.equal(value.controller.snapshot().processingAccepted, false);
  assert.equal(value.controller.snapshot().rightsAccepted, false); assert.equal(value.controller.canAccept(), false);
});

test('busy consent mutation is sent once and does not trigger a refresh or accept retry', async () => {
  const pending = deferred(); let mutations = 0;
  const value = consent({ acceptConsent: () => { mutations++; return pending.promise; } });
  await value.controller.open(); value.controller.processing(true); value.controller.rights(true);
  const operation = value.controller.accept();
  await value.controller.accept(); await value.controller.revoke(); await value.controller.refresh();
  value.controller.processing(true); value.controller.rights(true);
  assert.equal(mutations, 1); assert.equal(value.calls.length, 1);
  pending.reject(new Error('READER_062 private disclosure')); await operation;
  assert.equal(value.controller.snapshot().errorCode, 'READER_062');
  assert.equal(value.controller.snapshot().features, undefined);
  await value.controller.accept(); assert.equal(mutations, 1);
  await value.controller.refresh(); assert.equal(value.controller.canAccept(), false);
});

test('read-disabled and unavailable disclosure still allow account-only revision-bound revocation', async () => {
  const value = consent({ features: async () => features({ readEnabled: false, consentStatus: 'UNAVAILABLE', disclosure: undefined, hasActiveConsent: true }) });
  await value.controller.open(); value.controller.processing(true); value.controller.rights(true);
  assert.equal(value.controller.canAccept(), false); assert.equal(value.controller.canRevoke(), true);
  await value.controller.revoke(); assert.equal(value.calls[0][0], 'revoke'); assert.equal(value.calls[0][1], 7);
});

test('late read cannot overwrite a newer disclosure or preserve old checkboxes', async () => {
  const reads = [deferred(), deferred()]; let index = 0;
  const value = consent({ features: () => reads[index++].promise });
  const old = value.controller.open(); const recent = value.controller.refresh();
  reads[1].resolve(features({ consentRevision: 9 })); await recent;
  value.controller.processing(true); value.controller.rights(true);
  reads[0].resolve(features({ consentRevision: 1 })); await old;
  assert.equal(value.controller.snapshot().features.consentRevision, 9);
  assert.equal(value.controller.snapshot().processingAccepted, true);
});

test('account lifetime cancels immediately and drops late mutation replies without revoking remotely', async () => {
  const pending = deferred(); let token, mutations = 0;
  const value = consent({ acceptConsent: async (...args) => { mutations++; token = args[3]; return pending.promise; } });
  await value.controller.open(); value.controller.processing(true); value.controller.rights(true);
  const operation = value.controller.accept(); const observed = value.changes.length;
  value.lifetime.cancel(); assert.equal(token.isCancelled(), true);
  pending.resolve(features({ consentStatus: 'ACCEPTED' })); await operation;
  assert.equal(value.changes.length, observed); assert.equal(value.controller.snapshot().features, undefined);
  await value.controller.open(); await value.controller.revoke(); assert.equal(mutations, 1);
  assert.equal(value.calls.some(call => call[0] === 'revoke'), false);
});

test('closed controller cannot reopen and stale account predicate blocks network and observer updates', async () => {
  const value = consent(); await value.controller.open(); value.controller.close();
  await value.controller.open(); assert.equal(value.calls.length, 1);
  const pending = deferred(); const old = consent({ features: () => pending.promise });
  const operation = old.controller.open(); const observed = old.changes.length; old.current.value = false;
  pending.resolve(features()); await operation;
  await old.controller.revoke(); await old.controller.accept();
  assert.equal(old.changes.length, observed); assert.equal(old.controller.snapshot().features, undefined);
});

test('timeouts clear consent state and require an explicit refresh, never automatic mutation replay', async () => {
  let count = 0; const value = consent({ revokeConsent: async () => { count++; throw new Error('network private detail'); } });
  await value.controller.open(); await value.controller.revoke(); await value.controller.revoke();
  assert.equal(count, 1); assert.equal(value.controller.snapshot().errorCode, 'ADAPTATION_UNAVAILABLE');
  assert.equal(value.controller.canRevoke(), false); await value.controller.refresh();
  assert.equal(value.controller.canRevoke(), true); assert.equal(count, 1);
});

function storage(raw) {
  return { raw, writes: 0, failRead: false, failWrite: false,
    async load() { if (this.failRead) throw new Error('storage unavailable'); return this.raw; },
    async save(value) { if (this.failWrite) throw new Error('storage unavailable'); this.writes++; this.raw = value; }
  };
}
function record(chapter = 2, key = `fixture-${chapter}`) {
  return { scope, shelf: uuid(1), chapter: uuid(chapter), kind: 'INITIAL', trigger: '',
    input: { idempotencyKey: key, intent: 'More atmosphere', expectedBindingRevision: 1, expectedCatalogRevision: 2 } };
}

test('pending request survives a new journal instance and replay preserves exact semantic input', async () => {
  const store = storage(); const journal = new AdaptationPendingJournal(scope, store);
  const request = record(); request.kind = 'OPTIMIZE'; request.trigger = uuid(3); request.input.expectedSourceSha256 = 'c'.repeat(64);
  await journal.put(request);
  const restarted = new AdaptationPendingJournal(scope, store); const restored = await restarted.read(uuid(1), uuid(2));
  assert.deepEqual(restored, request); await restarted.put(restored); assert.equal(store.writes, 1);
  assert.equal(await restarted.read(uuid(4), uuid(2)), undefined);
  assert.equal(await restarted.read(uuid(1), uuid(4)), undefined);
});

test('pending request cannot be replaced by a new key, changed intention, lineage or revision', async () => {
  const store = storage(); const journal = new AdaptationPendingJournal(scope, store); await journal.put(record());
  for (const patch of [{ idempotencyKey: 'new-key' }, { intent: 'Different intent' }, { expectedCatalogRevision: 3 }]) {
    await assert.rejects(journal.put({ ...record(), input: { ...record().input, ...patch } }), /PENDING_CONFLICT/);
  }
  await assert.rejects(journal.put({ ...record(), kind: 'REGENERATE', trigger: uuid(3) }), /PENDING_CONFLICT/);
  assert.deepEqual(await journal.read(uuid(1), uuid(2)), record()); assert.equal(store.writes, 1);
});

test('pending template version survives restart and cannot drift on retry', async () => {
  const store = storage(); const journal = new AdaptationPendingJournal(scope, store);
  const value = record(); value.input.templateCode = 'cinematic'; value.input.templateVersion = 1;
  await journal.put(value);
  const restarted = new AdaptationPendingJournal(scope, store);
  assert.deepEqual(await restarted.read(uuid(1), uuid(2)), value);
  for (const patch of [{ templateVersion: 2 }, { templateCode: 'suspense' }])
    await assert.rejects(restarted.put({ ...value, input: { ...value.input, ...patch } }), /PENDING_CONFLICT/);
  assert.equal(store.writes, 1);
});

test('corrupt, cross-account and non-canonical journals fail closed without erasing data', async () => {
  assert.throws(() => new AdaptationPendingJournal('guest', storage()));
  for (const raw of ['', '{}', 'null', 'bad json', JSON.stringify([null]), JSON.stringify([record(), record()]),
    JSON.stringify([{ ...record(), scope: otherScope }]), JSON.stringify([{ ...record(), trigger: uuid(3) }]),
    JSON.stringify([{ ...record(), kind: 'HACK' }]), JSON.stringify([{ ...record(), input: null }]), 'x'.repeat(64001)]) {
    const store = storage(raw); const journal = new AdaptationPendingJournal(scope, store);
    await assert.rejects(journal.read(uuid(1), uuid(2))); await assert.rejects(journal.put(record()));
    assert.equal(store.raw, raw); assert.equal(store.writes, 0);
  }
});

test('failed durable write prevents send and a failed operation does not poison later writes', async () => {
  const store = storage(); store.failWrite = true; const journal = new AdaptationPendingJournal(scope, store); let sends = 0;
  async function guardedSend() { await journal.put(record()); sends++; }
  await assert.rejects(guardedSend()); assert.equal(sends, 0); assert.equal(store.raw, undefined);
  store.failWrite = false; await guardedSend(); assert.equal(sends, 1);
  store.failRead = true; await assert.rejects(journal.put(record(3))); assert.equal(store.writes, 1);
});

test('late acknowledgement only removes the matching key and a cleanup error preserves the receipt', async () => {
  const store = storage(); const journal = new AdaptationPendingJournal(scope, store); await journal.put(record());
  await journal.remove(uuid(2), 'older-key'); assert.deepEqual(await journal.read(uuid(1), uuid(2)), record());
  store.failWrite = true; await assert.rejects(journal.remove(uuid(2), 'fixture-2')); store.failWrite = false;
  assert.deepEqual(await journal.read(uuid(1), uuid(2)), record());
  await journal.remove(uuid(2), 'fixture-2'); await journal.put(record(2, 'new-key'));
  await journal.remove(uuid(2), 'fixture-2'); assert.equal((await journal.read(uuid(1), uuid(2))).input.idempotencyKey, 'new-key');
});

test('concurrent chapter and account writes preserve all records while capacity never silently evicts', async () => {
  const store = storage(), otherStore = storage();
  const first = new AdaptationPendingJournal(scope, store), second = new AdaptationPendingJournal(scope, store);
  const other = new AdaptationPendingJournal(otherScope, otherStore);
  await Promise.all([first.put(record(2)), second.put(record(3)), other.put({ ...record(2), scope: otherScope })]);
  assert.equal(JSON.parse(store.raw).length, 2); assert.equal(JSON.parse(otherStore.raw).length, 1);
  for (let i = 4; i <= 9; i++) await first.put(record(i));
  await assert.rejects(second.put(record(10)), /PENDING_CAPACITY/); assert.equal(JSON.parse(store.raw).length, 8);
  await first.remove(uuid(2), 'fixture-2'); await second.put(record(10)); assert.equal(JSON.parse(store.raw).length, 8);
});

test('pending input is copied and never persists caller-added text or authentication fields', async () => {
  const store = storage(); const journal = new AdaptationPendingJournal(scope, store); const request = record();
  request.input.body = 'private body'; request.input.token = 'private token'; await journal.put(request);
  request.input.intent = 'Changed after writing';
  assert.equal(store.raw.includes('private'), false); assert.equal((await journal.read(uuid(1), uuid(2))).input.intent, 'More atmosphere');
});

function assetPort() {
  return { entries: new Map(), writes: 0, failAt: -1, failAfterCommit: false,
    encode: text => new TextEncoder().encode(text), decode: bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes),
    digest: async bytes => require('node:crypto').createHash('sha256').update(bytes).digest('hex'),
    async read(alias) { return this.entries.get(alias)?.slice(); },
    async write(alias, bytes) {
      assert.ok(bytes.length >= 1 && bytes.length <= 1024); this.writes++;
      if (this.writes === this.failAt) throw new Error('storage crash');
      this.entries.set(alias, bytes.slice()); bytes.fill(0);
      if (this.failAfterCommit && alias.endsWith(scope)) throw new Error('commit acknowledgement lost');
    }
  };
}

test('platform-sized encrypted chunks round-trip maximum Chinese intent across restart and never exceed one KB', async () => {
  const port = assetPort(); const chunks = new AdaptationChunkedStorage(scope, port);
  const journal = new AdaptationPendingJournal(scope, chunks); const request = record();
  request.input.intent = '\u6c49\u5b57'.repeat(1000); await journal.put(request);
  assert.ok(port.entries.size > 6); assert.ok([...port.entries.values()].every(value => value.length <= 1024));
  const restart = new AdaptationPendingJournal(scope, new AdaptationChunkedStorage(scope, port));
  assert.deepEqual(await restart.read(uuid(1), uuid(2)), request);
});

test('every pre-commit crash preserves the previous journal, including partial multibyte chunks', async () => {
  const old = 'old committed request'; const next = '\ud83d\ude00'.repeat(1100);
  for (let failure = 1; failure <= 6; failure++) {
    const port = assetPort(); const chunks = new AdaptationChunkedStorage(scope, port); await chunks.save(old);
    port.failAt = port.writes + failure; await assert.rejects(chunks.save(next), /storage crash/);
    assert.equal(await new AdaptationChunkedStorage(scope, port).load(), old);
    port.failAt = -1; await chunks.save(next); assert.equal(await chunks.load(), next);
  }
});

test('first partial write is not committed and lost final acknowledgement restores exactly the new journal', async () => {
  const port = assetPort(); const chunks = new AdaptationChunkedStorage(scope, port);
  port.failAt = 2; await assert.rejects(chunks.save('x'.repeat(3000))); assert.equal(await chunks.load(), undefined);
  port.failAt = -1; port.failAfterCommit = true;
  await assert.rejects(chunks.save('new committed request'), /acknowledgement lost/);
  assert.equal(await new AdaptationChunkedStorage(scope, port).load(), 'new committed request');
});

test('missing or tampered chunks, invalid index and bad UTF-8 all fail closed', async () => {
  const prefix = `mytools.adaptation.pending.v1.${scope}`;
  for (const mutation of ['missing', 'tampered', 'index', 'utf8']) {
    const port = assetPort(); const chunks = new AdaptationChunkedStorage(scope, port); await chunks.save('x'.repeat(2000));
    const key = `${prefix}.a.0`;
    if (mutation === 'missing') port.entries.delete(key);
    if (mutation === 'tampered') port.entries.get(key)[0] = 0;
    if (mutation === 'index') port.entries.set(prefix, port.encode('{"version":1,"bank":"z","size":2}'));
    if (mutation === 'utf8') {
      const bytes = Uint8Array.from([0xc3, 0x28]); port.entries.set(key, bytes);
      port.entries.set(prefix, port.encode(JSON.stringify({ version: 1, bank: 'a', size: 2, sha256: await port.digest(bytes) })));
    }
    await assert.rejects(chunks.load());
  }
});

test('two fixed slots remain bounded and byte capacity rejects oversized input before any write', async () => {
  const port = assetPort(); const chunks = new AdaptationChunkedStorage(scope, port);
  await chunks.save('x'.repeat(65536)); await chunks.save('y'.repeat(65536));
  await chunks.save('[]'); assert.equal(await chunks.load(), '[]'); assert.equal(port.entries.size, 129);
  const writes = port.writes; await assert.rejects(chunks.save('x'.repeat(65537)), /PENDING_CAPACITY/);
  await assert.rejects(chunks.save(''), /PENDING_CAPACITY/); assert.equal(port.writes, writes);
});

test('journal serializes reads with writes so a slow read cannot observe reused slot fragments', async () => {
  const store = storage(JSON.stringify([record()])); const pending = deferred(); let loading;
  const started = new Promise(resolve => { loading = resolve; }); const original = store.load.bind(store);
  store.load = async () => { store.load = original; loading(); return pending.promise; };
  const first = new AdaptationPendingJournal(scope, store), second = new AdaptationPendingJournal(scope, store);
  const read = first.read(uuid(1), uuid(2)); await started;
  let written = false; const write = second.put(record(3)).then(() => { written = true; });
  await Promise.resolve(); assert.equal(written, false);
  pending.resolve(JSON.stringify([record()])); assert.deepEqual(await read, record()); await write;
  assert.equal(JSON.parse(store.raw).length, 2);
});

test('source wiring binds account lifetime and writes before creation; comparison is fixed authenticated read', () => {
  const source = name => fs.readFileSync(path.join(process.argv[3], 'entry/src/main/ets', name), 'utf8');
  const page = source('features/reader/ChapterAdaptationPanel.ets'); const index = source('pages/Index.ets');
  const api = source('features/reader/ChapterAdaptationApi.ets'); const native = source('features/reader/AdaptationPendingAssetStorage.ets');
  assert.ok(page.indexOf('await journal.put(') < page.indexOf('await this.api.create('));
  assert.match(page, /!wasUncertain && this.policy.rejected/);
  assert.match(page, /this.lifetime.bind\(this.stopLifetime\)/); assert.match(page, /this.journal.read\(this.shelfId, chapter.chapterId\)/);
  assert.match(index, /accountScope: this.chapterAdaptationAccountOwner/);
  assert.match(index, /this.chapterAdaptationLifetime.cancel\(\)/);
  assert.ok(!index.includes('AdaptationConsentPanel'));
  assert.match(api, /this.client.get\(`\$\{this.adaptationPath\(id\)\}\/comparison`/);
  assert.match(native, /asset\.query/); assert.match(native, /asset\.update/); assert.match(native, /asset\.add/);
  assert.doesNotMatch(native, /from\s+['"].*preferences|console\.|apiKey|Bearer/i);
});
