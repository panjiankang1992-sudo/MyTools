const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const { ChapterAdaptationPolicy } = require(path.join(process.argv[2], 'ChapterAdaptationPolicy.js'));
const { ChapterAdaptationResponseNormalizer } = require(path.join(process.argv[2], 'ChapterAdaptationResponseNormalizer.js'));
const policy = new ChapterAdaptationPolicy();
const normal = new ChapterAdaptationResponseNormalizer();
const shelf = '00000000-0000-4000-8000-000000000001';
const chapter = '00000000-0000-4000-8000-000000000002';
const id = '00000000-0000-4000-8000-000000000003';
const attempt = '00000000-0000-4000-8000-000000000004';
const other = '00000000-0000-4000-8000-000000000005';
const sha = 'a'.repeat(64);
const instant = '2026-09-10T10:00:00Z';
test('style selection is paired and survives whitelist projection', () => {
  const input = { idempotencyKey: 'style-test', intent: 'Change the rhythm.', expectedBindingRevision: 1, expectedCatalogRevision: 1,
    templateCode: 'cinematic', templateVersion: 2, prompt: 'must never forward' };
  const result = policy.input(input);
  assert.equal(result.templateCode, 'cinematic'); assert.equal(result.templateVersion, 2); assert.equal(result.prompt, undefined);
  for (const patch of [{ templateVersion: undefined }, { templateCode: undefined }, { templateVersion: 0 }, { templateVersion: 1.5 }, { templateCode: '../admin' }])
    assert.throws(() => policy.input({ ...input, ...patch }));
});
test('style catalog and historical snapshots retain immutable version metadata', () => {
  const style = { code: 'cinematic', version: 1, name: 'Cinematic', description: 'Visual narration', promptSha256: sha };
  assert.equal(normal.styles({ items: [style] })[0].version, 1);
  assert.throws(() => normal.styles({ items: [style, style] }));
  assert.throws(() => normal.styles({ items: [{ ...style, version: 0 }] }));
  const value = detail(); value.version.styleTemplate = style;
  assert.deepEqual(normal.detail(value, id, shelf, chapter).version.styleTemplate, style);
});
const book = { id: 'source:test-book', origin: 'source', format: 'unknown', sourceId: 'https://source.test', resourceUri: 'https://source.test/book' };
function historyItem() {
  return { adaptationId: id, shelfBookId: shelf, chapterId: chapter, chapterTitle: 'Chapter', revisionNumber: 1,
    requestKind: 'INITIAL', lineage: { childAdaptationId: id, rootAdaptationId: id, parentAdaptationId: null, triggerAdaptationId: null },
    intent: 'More atmosphere', status: 'COMPLETED', currentStage: 'COMPLETED', selectedAttemptId: attempt,
    attemptCount: 3, modelId: 'fixture', lastErrorCode: null, createdAt: instant, finishedAt: instant };
}
function detail() {
  return { version: historyItem(), sourceRelation: 'CURRENT', sourceCheckedAt: instant,
    result: { attemptId: attempt, content: 'A restrained chapter.', contentSha256: sha, viewStatus: 'SELECTED' },
    attempts: [{ attemptId: attempt, attemptNo: 2, callKind: 'GENERATE', callStatus: 'SUCCEEDED', disposition: 'SELECTED', selected: true, hasViewableOutput: true }] };
}
function catalog() {
  return { shelfBookId: shelf, bindingRevision: 1, catalogRevision: 2, catalogSha256: sha,
    items: [{ chapterId: chapter, index: 0, title: 'Chapter', contentKind: 'TEXT', sourceSha256: null, eligible: true, ineligibleReason: null }], nextCursor: null };
}

function comparison() {
  return { adaptationId: id, attemptId: attempt, originalSha256: sha, resultSha256: sha, mode: 'HUNKS',
    original: null, adapted: null, fallbackReason: null,
    hunks: [{ kind: 'EQUAL', originalStart: 0, adaptedStart: 0, original: ['A '], adapted: [] },
      { kind: 'REPLACE', originalStart: 1, adaptedStart: 1, original: ['plain chapter.'], adapted: ['restrained chapter.'] }] };
}

test('comparison reconstructs frozen text and exactly matches the displayed selected result', () => {
  const value = normal.comparison(comparison(), id, detail().result);
  assert.equal(value.original, 'A plain chapter.'); assert.equal(value.adapted, detail().result.content);
  assert.equal(value.hunks[0].adapted, 'A '); assert.equal(value.hunks[1].kind, 'REPLACE');
  const diff = comparison(); diff.hunks = [
    { kind: 'DELETE', originalStart: 0, adaptedStart: 0, original: ['Old'], adapted: [] },
    { kind: 'INSERT', originalStart: 1, adaptedStart: 0, original: [], adapted: [detail().result.content] }];
  assert.equal(normal.comparison(diff, id, detail().result).original, 'Old');
});

test('comparison rejects cross-version responses, non-contiguous indices, invalid shapes and mismatched content', () => {
  for (const patch of [{ adaptationId: other }, { attemptId: other }, { resultSha256: 'b'.repeat(64) },
    { originalSha256: '' }, { mode: 'HTML' }, { hunks: [] }, { original: 'injected full body' }])
    assert.throws(() => normal.comparison({ ...comparison(), ...patch }, id, detail().result));
  for (const patch of [{ originalStart: 1 }, { adaptedStart: 1 }, { kind: 'SCRIPT' }, { adapted: ['unexpected'] },
    { original: [] }, { original: ['B '] }, { original: [null] }]) {
    const diff = comparison(); diff.hunks[0] = { ...diff.hunks[0], ...patch };
    assert.throws(() => normal.comparison(diff, id, detail().result));
  }
});

test('comparison fallback uses bounded full frozen text and cannot mix hunks or unrelated results', () => {
  const value = { ...comparison(), mode: 'SIDE_BY_SIDE', hunks: [], original: 'Frozen original.', adapted: detail().result.content, fallbackReason: 'TIME_BUDGET' };
  assert.equal(normal.comparison(value, id, detail().result).original, value.original);
  for (const patch of [{ hunks: comparison().hunks }, { original: 'x'.repeat(120001) }, { adapted: 'Wrong result' },
    { original: '\ud800' }, { original: '<script>\u0000' }])
    assert.throws(() => normal.comparison({ ...value, ...patch }, id, detail().result));
});

test('only a current shelf member with a supported text origin can enter', () => {
  assert.equal(policy.eligible(book, [book]), true);
  for (const invalid of [undefined, { ...book, origin: 'local' }, { ...book, id: 'local:1' }, { ...book, format: 'cbz' }, { ...book, resourceUri: '/changed' }]) assert.equal(policy.eligible(invalid, [book]), false);
  assert.equal(policy.eligible(book, []), false);
});
test('server shelf UUID comes from exact current book identity, never title or array position', () => {
  const row = { id: shelf, deleted: false, metadata: { bookId: book.id, origin: 'source', name: 'Chapter' } };
  assert.equal(normal.shelf([row], book.id), shelf);
  assert.throws(() => normal.shelf([row], 'Chapter'));
  assert.throws(() => normal.shelf([row, { ...row, id: other }], book.id));
  assert.throws(() => normal.shelf([{ ...row, deleted: true }], book.id));
  assert.throws(() => normal.shelf([{ ...row, id: book.id }], book.id));
});
test('mandatory intent is bounded in code points, preserving inner whitespace', () => {
  assert.equal(policy.intent('\u3000More\n atmosphere\u00a0'), 'More\n atmosphere');
  assert.equal(policy.intent('\ud83d\ude00'.repeat(5)), '\ud83d\ude00'.repeat(5));
  for (const value of ['', 'four', ' '.repeat(10), 'a'.repeat(2001), 'x\u0000xxxx', 'xxxx\ud800', 'xxxx\udc00', ' '.repeat(4097)]) assert.throws(() => policy.intent(value));
});
test('input serialization keeps only the exact five allowed semantic fields', () => {
  const input = { idempotencyKey: 'fixture-1', intent: 'More atmosphere', expectedBindingRevision: 1, expectedCatalogRevision: 2,
    expectedSourceSha256: sha, body: 'secret', ownerId: 999, model: 'untrusted' };
  assert.deepEqual(Object.keys(policy.input(input)), ['idempotencyKey', 'intent', 'expectedBindingRevision', 'expectedCatalogRevision', 'expectedSourceSha256']);
  for (const key of ['', 'bad key', '\u4e2d', 'x'.repeat(129)]) assert.throws(() => policy.input({ ...input, idempotencyKey: key }));
  assert.throws(() => policy.input({ ...input, expectedCatalogRevision: Number.MAX_SAFE_INTEGER + 1 }));
  assert.throws(() => policy.input({ ...input, expectedSourceSha256: 'A'.repeat(64) }));
});
test('capability and catalog reject a mismatched resource or changed identity', () => {
  const capability = { shelfBookId: shelf, status: 'READY', bindingRevision: 1, catalogRevision: 2, catalogSha256: sha, reasonCode: null, pollAfterMs: null };
  assert.equal(normal.capability(capability, shelf).pollAfterMs, 1500);
  assert.throws(() => normal.capability(capability, other));
  assert.throws(() => normal.capability({ ...capability, status: 'SUCCESS' }, shelf));
  assert.equal(normal.catalog(catalog(), shelf).items[0].chapterId, chapter);
  assert.throws(() => normal.catalog({ ...catalog(), items: [catalog().items[0], catalog().items[0]] }, shelf));
  assert.throws(() => normal.catalog({ ...catalog(), catalogRevision: 0 }, shelf));
});
test('accepted and progress responses must match the original chapter and operation', () => {
  const reply = { adaptationId: id, chapterId: chapter, revisionNumber: 1, requestKind: 'INITIAL', status: 'PENDING_DISPATCH', currentStage: 'CONTEXT_PENDING', pollAfterMs: 1500, createdAt: instant };
  assert.equal(normal.accepted(reply, chapter, 'INITIAL').adaptationId, id);
  assert.throws(() => normal.accepted(reply, other, 'INITIAL'));
  assert.throws(() => normal.accepted(reply, chapter, 'OPTIMIZE'));
  const progress = { adaptationId: id, status: 'GENERATING', currentStage: 'GENERATE', version: 3, candidateCount: 0, lastErrorCode: null, pollAfterMs: 1, createdAt: instant, startedAt: instant, finishedAt: null };
  assert.equal(normal.progress(progress, id).pollAfterMs, 1000);
  assert.throws(() => normal.progress(progress, other));
});
test('history cannot cross chapter boundaries or silently duplicate an immutable version', () => {
  assert.equal(normal.history({ items: [historyItem()], nextCursor: null }, shelf, chapter).items[0].revisionNumber, 1);
  assert.throws(() => normal.history({ items: [historyItem()], nextCursor: null }, shelf, other));
  assert.throws(() => normal.history({ items: [historyItem(), historyItem()], nextCursor: null }, shelf, chapter));
  const bad = historyItem(); bad.lineage.parentAdaptationId = other;
  assert.throws(() => normal.history({ items: [bad], nextCursor: null }, shelf, chapter));
});
test('unknown or stale source cannot enable optimization, and result selection is bound', () => {
  const parsed = normal.detail(detail(), id, shelf, chapter); assert.equal(policy.canDerive(parsed), true);
  for (const relation of ['STALE', 'UNKNOWN']) assert.equal(policy.canDerive({ ...parsed, sourceRelation: relation }), false);
  assert.equal(policy.canDerive({ ...parsed, result: undefined }), false);
  const bad = detail(); bad.result.attemptId = other; assert.throws(() => normal.detail(bad, id, shelf, chapter));
  assert.throws(() => normal.detail(detail(), other, shelf, chapter));
});
test('model plans and critics are not exposed as public candidate records', () => {
  for (const kind of ['PLAN', 'CRITIC']) {
    const bad = detail(); bad.attempts[0].callKind = kind;
    assert.throws(() => normal.detail(bad, id, shelf, chapter));
  }
  const bad = detail(); bad.result.viewStatus = 'REJECTED_BY_CONSTRAINTS';
  assert.throws(() => normal.detail(bad, id, shelf, chapter));
  assert.equal(normal.output({ ...detail().result, viewStatus: 'REJECTED_BY_CONSTRAINTS' }, attempt).viewStatus, 'REJECTED_BY_CONSTRAINTS');
});
test('source-check proof requires matching version, complete hash and at most a sixty-second window', () => {
  const value = { adaptationId: id, status: 'CURRENT', sourceCheckedAt: instant, validUntil: '2026-09-10T10:01:00Z',
    pollAfterMs: 0, reasonCode: null, bindingRevision: 1, catalogRevision: 2, sourceSha256: sha };
  const proof = normal.sourceCheck(value, id); const now = Date.parse(instant);
  assert.equal(policy.canDeriveVerified(detail(), proof, now), true);
  assert.equal(policy.canDeriveVerified(detail(), proof, now + 59999), true);
  assert.equal(policy.canDeriveVerified(detail(), proof, now + 60000), false);
  assert.equal(policy.canDeriveVerified(detail(), proof, now - 1), false);
  assert.equal(policy.canDeriveVerified(detail(), undefined, now), false);
  assert.equal(policy.canDeriveVerified(detail(), { ...proof, adaptationId: other }, now), false);
  assert.equal(policy.canDeriveVerified({ ...detail(), sourceRelation: 'STALE' }, proof, now), false);
  for (const patch of [{ sourceSha256: null }, { validUntil: null }, { sourceCheckedAt: null },
    { validUntil: '2026-09-10T10:01:01Z' }, { validUntil: instant }, { bindingRevision: 0 }, { adaptationId: other }]) {
    assert.throws(() => normal.sourceCheck({ ...value, ...patch }, id));
  }
});
test('unverified source-check states cannot smuggle a usable hash or expiry', () => {
  for (const status of ['QUEUED', 'CHECKING', 'UNKNOWN', 'STALE']) {
    const value = { adaptationId: id, status, sourceCheckedAt: null, validUntil: null, pollAfterMs: 1500,
      reasonCode: null, bindingRevision: 1, catalogRevision: 2, sourceSha256: null };
    assert.equal(policy.canDeriveVerified(detail(), normal.sourceCheck(value, id), Date.parse(instant)), false);
    assert.throws(() => normal.sourceCheck({ ...value, sourceSha256: sha }, id));
    assert.throws(() => normal.sourceCheck({ ...value, validUntil: instant }, id));
  }
  const fs = require('node:fs');
  const panel = fs.readFileSync(path.join(__dirname, '../entry/src/main/ets/features/reader/ChapterAdaptationPanel.ets'), 'utf8');
  assert.match(panel, /input\.expectedSourceSha256 = this\.sourceProof\.sourceSha256/);
  assert.match(panel, /if \(this\.pending === undefined\) \{\s*if \(!this\.policy\.canCreate\(this\.features\)\) throw new Error\('READER_052'\);\s*if \(this\.kind !== 'INITIAL' && !this\.canDerive\(\)\)/);
});
function features(status = 'REQUIRED') {
  return { readEnabled: true, createEnabled: true, consentStatus: status, consentRevision: status === 'ACCEPTED' ? 1 : 0,
    hasActiveConsent: status === 'ACCEPTED', acceptedAt: status === 'ACCEPTED' ? instant : null, reasonCode: null,
    disclosure: { version: 'v1', disclosureSha256: sha, rightsAttestationVersion: 'r1', payload: {
      schemaVersion: 'adaptation-disclosure-v1', providerCode: 'fixture', contractSha256: sha, providerName: 'Fixture',
      providerOrigin: 'https://api.sillytraven.dev', dataUseNotice: 'Fixture data use.', retentionNotice: 'Fixture retention.', rightsNotice: 'Fixture rights.' } } };
}
test('direct creation needs no consent and rejects fabricated acceptance metadata', () => {
  const direct = { readEnabled: true, createEnabled: true, consentStatus: 'NOT_REQUIRED', consentRevision: 0,
    hasActiveConsent: false, acceptedAt: null, reasonCode: null, disclosure: null };
  assert.equal(policy.canCreate(normal.features(direct)), true);
  assert.equal(policy.canCreate(normal.features({ ...direct, createEnabled: false })), false);
  for (const change of [{ hasActiveConsent: true }, { consentRevision: 1 }, { acceptedAt: '2026-09-13T00:00:00Z' }])
    assert.throws(() => normal.features({ ...direct, ...change }));
});

test('explicit consent requires both current confirmations and uses exactly the displayed revision and hash', () => {
  const current = normal.features(features());
  for (const flags of [[false, false], [true, false], [false, true]]) assert.throws(() => policy.consentInput(current, ...flags));
  const input = policy.consentInput(current, true, true);
  assert.deepEqual(Object.keys(input).sort(), ['accepted', 'disclosureSha256', 'disclosureVersion', 'expectedConsentRevision', 'rightsAttested'].sort());
  assert.equal(input.expectedConsentRevision, 0); assert.equal(input.disclosureSha256, sha);
  assert.equal(policy.consentInput({ ...current, consentRevision: 7 }, true, true).expectedConsentRevision, 7);
  assert.equal(policy.canCreate(current), false);
  assert.equal(policy.canCreate(normal.features(features('ACCEPTED'))), true);
  assert.equal(policy.canCreate(normal.features({ ...features('ACCEPTED'), createEnabled: false })), false);
  assert.equal(policy.canCreate(undefined), false);
  assert.equal(policy.rejected(new Error('READER_052')), true);
});
test('malformed or unavailable disclosures never enable consent or creation', () => {
  for (const change of [{ consentRevision: -1 }, { consentRevision: 9007199254740992 }, { consentStatus: 'TRUSTED' }, { disclosure: null }]) {
    assert.throws(() => normal.features({ ...features(), ...change }));
  }
  for (const change of [{ hasActiveConsent: false }, { acceptedAt: null }, { consentRevision: 0 }]) assert.throws(() => normal.features({ ...features('ACCEPTED'), ...change }));
  const invalid = features(); invalid.disclosure.payload.providerOrigin = 'https://other.invalid'; assert.throws(() => normal.features(invalid));
  const unavailable = normal.features({ ...features(), createEnabled: false, disclosure: null, consentStatus: 'UNAVAILABLE' });
  assert.equal(policy.canCreate(unavailable), false); assert.throws(() => policy.consentInput(unavailable, true, true));
});
test('lifecycle revisions reject responses from a hidden or replaced chapter', () => {
  const first = policy.advance(); assert.equal(policy.current(first), true);
  const next = policy.advance(); assert.equal(policy.current(first), false); assert.equal(policy.current(next), true);
  for (const state of ['COMPLETED', 'CANCELLED', 'FAILED']) assert.equal(policy.terminal(state), true);
  assert.equal(policy.terminal('CANCEL_REQUESTED'), false);
});
test('network ambiguity is distinct from explicit request rejection and messages are redacted', () => {
  assert.equal(policy.rejected(new Error('GATEWAY_002')), true);
  assert.equal(policy.rejected(new Error('READER_032')), true);
  assert.equal(policy.rejected(new Error('GATEWAY_004')), false);
  assert.equal(policy.rejected(new Error('READER_059')), false);
  assert.equal(policy.error(new Error('private content and credentials')), 'ADAPTATION_UNAVAILABLE');
  assert.equal(policy.error(new Error('Reader rejected (READER_034) private text')), 'READER_034');
});
test('source files bind the tested API and cancellation gates without overwriting reading state', () => {
  const fs = require('node:fs');
  const root = path.resolve(__dirname, '../entry/src/main/ets');
  const api = fs.readFileSync(path.join(root, 'features/reader/ChapterAdaptationApi.ets'), 'utf8');
  const panel = fs.readFileSync(path.join(root, 'features/reader/ChapterAdaptationPanel.ets'), 'utf8');
  const index = fs.readFileSync(path.join(root, 'pages/Index.ets'), 'utf8');
  assert.ok(api.includes('this.policy.input(input)')); assert.ok(api.includes('new AuthorizedApiClient(baseUrl, auth, false)'));
  assert.ok(panel.includes('this.current(revision)')); assert.ok(panel.includes('this.cancellation.cancel()'));
  assert.ok(panel.includes('const pending = this.pending;'));
  assert.ok(panel.includes('this.triggerId, pending, this.cancellation')); assert.ok(index.includes('ChapterAdaptationPanel({'));
  assert.ok(index.includes('this.CloseChapterAdaptation(false)'));
  assert.ok(!panel.includes('readingProgress')); assert.ok(!panel.includes('readerChapters'));
  assert.ok(!api.includes('sillytraven')); assert.ok(!api.includes('apiKey'));
});
