const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const { ChapterAdaptationPolicy } = require(path.join(process.argv[2], 'ChapterAdaptationPolicy.js'));
const file = path.join(__dirname, '../entry/src/main/ets/features/reader/ChapterAdaptationPanel.ets');
const raw = fs.readFileSync(file, 'utf8');
// 执行真实页面的数据与导航方法；原生布局由 ArkTS 编译和设备验收覆盖。
const source = (raw.slice(0, raw.indexOf('  @Builder')) +
  raw.slice(raw.indexOf('  private footerLabel()'), raw.indexOf('  @Builder\n  private Footer()')))
  .replace(/^import[\s\S]*?;\n/gm, '').replace('@Component', '')
  .replace('export struct', 'class').replace(/@State /g, '') + '\n}\nglobalThis.Panel = ChapterAdaptationPanel;';
class Token { cancel() { this.cancelled = true; } isCancelled() { return !!this.cancelled; } }
const context = vm.createContext({ ChapterAdaptationPolicy, DownloadCancellationToken: Token,
  AuthSessionManager: class {}, util: { generateRandomUUID: () => 'fixture' }, Date, setTimeout, clearTimeout,
  $r: value => value });
vm.runInContext(ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context);
const chapter = { chapterId: 'id-12', index: 12, title: 'Chapter 12', eligible: true };
const tick = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
test('missing shelf syncs once and resolves authoritative identity again', async () => {
  let reads = 0; let syncs = 0;
  const value = panel({ shelf: async () => {
    if (++reads === 1) throw new Error('ADAPTATION_SHELF_NOT_SYNCED'); return 'resolved';
  }, features: async () => ({}), capability: async () => ({ status: 'UNAVAILABLE' }) });
  value.syncShelf = async () => { syncs++; };
  await value.open(); assert.equal(reads, 2); assert.equal(syncs, 1);
  assert.equal(value.shelfId, 'resolved'); value.endView();
});
test('ambiguous shelf never triggers a write', async () => {
  const value = panel({ shelf: async () => { throw new Error('ADAPTATION_SHELF_AMBIGUOUS'); } });
  value.syncShelf = async () => { assert.fail('unexpected synchronization'); };
  await value.open(); assert.equal(value.status, 'ADAPTATION_SHELF_AMBIGUOUS'); value.endView();
});
test('successful polling clears stale dispatch text and deduplicates stage', async () => {
  const value = panel({ progress: async () => ({ status: 'ANALYZING', currentStage: 'PLAN', pollAfterMs: 99999 }) });
  value.status = 'PENDING_DISPATCH'; value.label = () => 'plan';
  await value.poll('id', value.policy.advance());
  assert.equal(value.status, ''); assert.equal(value.progressText(), 'plan'); value.endView();
});
test('failure detail explains conflict and retains diagnostic code', () => {
  const value = panel(); value.text = resource => resource;
  value.detail = { version: { lastErrorCode: 'READER_033' } };
  assert.match(value.failureText(), /adaptation_intent_conflict.*READER_033/); value.endView();
});
function panel(api = {}) {
  const value = new context.Panel(); value.alive = true; value.isCurrent = () => true;
  value.shelfId = 'shelf'; value.targetChapterIndex = 12; value.targetChapterTitle = 'Chapter 12';
  value.api = { history: async () => ({ items: [], nextCursor: '' }), ...api }; return value;
}
function catalog(items, nextCursor = '') {
  return { items, nextCursor, bindingRevision: 1, catalogRevision: 1, catalogSha256: 'a'.repeat(64) };
}
test('a chapter-row entry resolves canonical pages and opens only that chapter list', async () => {
  const calls = [];
  const value = panel({ catalog: async (shelf, cursor) => {
    calls.push(cursor); return cursor === '' ? catalog([{ ...chapter, index: 0 }], 'page2') : catalog([chapter]);
  } });
  await value.loadCatalog(''); await tick();
  assert.deepEqual(calls, ['', 'page2']); assert.equal(value.chapter.chapterId, 'id-12');
  assert.equal(value.page, 'LIST'); assert.equal(value.composing, false); value.endView();
});
test('mismatched chapter title fails closed without selecting a different chapter', async () => {
  const value = panel({ catalog: async () => catalog([{ ...chapter, title: 'Different' }]) });
  await value.loadCatalog(''); assert.equal(value.chapter, undefined);
  assert.equal(value.status, 'ADAPTATION_CATALOG_CHANGED'); value.endView();
});
test('repeated catalog cursors stop instead of polling forever', async () => {
  let count = 0;
  const value = panel({ catalog: async () => { count++; return catalog([], 'same'); } });
  await value.loadCatalog(''); await tick(); assert.equal(count, 2);
  assert.equal(value.chapter, undefined); value.endView();
});
test('history pagination appends without replacing the newest version or duplicating rows', async () => {
  const value = panel({ history: async () => ({ items: [{ adaptationId: 'v2' }, { adaptationId: 'v1' }], nextCursor: '' }) });
  value.chapter = chapter; value.history = [{ adaptationId: 'v3' }, { adaptationId: 'v2' }];
  await value.loadHistory('next'); assert.deepEqual(Array.from(value.history, item => item.adaptationId), ['v3', 'v2', 'v1']);
  assert.equal(value.page, 'LIST'); value.endView();
});
test('reading back returns to detail, detail back returns to list, list back exits', async () => {
  const value = panel(); let closes = 0; value.onClose = () => closes++; value.chapter = chapter;
  value.page = 'READ'; value.goBack(); assert.equal(value.page, 'DETAIL');
  value.goBack(); await tick(); assert.equal(value.page, 'LIST'); assert.equal(closes, 0);
  value.goBack(); assert.equal(closes, 1); value.endView();
});
test('form back keeps an uncertain request without trapping navigation or sending it twice', async () => {
  let sends = 0; const value = panel({ create: async () => sends++ }); value.chapter = chapter;
  value.page = 'FORM'; value.composing = true; value.uncertain = true;
  const input = { idempotencyKey: 'same-key', intent: 'Keep the outcome.' };
  value.pending = input;
  value.journal = { read: async () => ({ kind: 'INITIAL', trigger: '', input }) };
  value.goBack(); await tick(); assert.equal(value.page, 'LIST'); assert.equal(value.pending.idempotencyKey, 'same-key');
  assert.equal(sends, 0); value.endView();
});
test('template form, detail and reading are separate builders with no consent UI', () => {
  for (const name of ['VersionList', 'Composer', 'VersionDetail', 'ReadingContent']) assert.ok(raw.includes(`private ${name}()`));
  assert.ok(!raw.includes('AdaptationConsentPanel')); assert.ok(!raw.includes('openConsent'));
  assert.ok(!raw.includes('adaptation_consent_manage'));
});

test('footer availability follows the live busy state and cannot freeze at the initial value', () => {
  const value = panel(); value.page = 'DETAIL'; value.detail = { result: { content: 'Result.' } };
  value.busy = true; assert.equal(value.footerAvailable(), false);
  value.busy = false; assert.equal(value.footerAvailable(), true);
  value.footerAction(); assert.equal(value.page, 'READ');
  assert.match(raw, /enabled\(this.footerAvailable\(\)\)/); value.endView();
});

test('version rows use reference binding for revised history and status', () => {
  assert.match(raw, /private VersionRow\(\$\$: AdaptationVersionRowData\)/);
  assert.ok(raw.includes('this.VersionRow({ item: this.history[0] })'));
  assert.ok(raw.includes('$$.item.revisionNumber'));
  assert.ok(raw.includes('$$.item.status'));
});

test('closing adaptation restores the previous keyboard mode only once', () => {
  const value = panel(); const restored = [];
  value.previousKeyboardMode = 0;
  value.getUIContext = () => ({ setKeyboardAvoidMode: mode => restored.push(mode) });
  value.endView(); value.endView();
  assert.deepEqual(restored, [0]);
  assert.ok(raw.includes('setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE)'));
});
