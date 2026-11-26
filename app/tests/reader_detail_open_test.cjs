const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const page = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const methods = ['OpenRemoteBookDetail', 'LoadRemoteDetailCover'].map(name =>
  page.match(new RegExp(`^  private async ${name}\\([\\s\\S]*?^  }`, 'm'))[0]).join('\n');

function setup(cachedCover = '') {
  const events = [];
  let finishCover;
  const context = { fs: { accessSync: () => false },
    DownloadCancellationToken: class { cancel() { this.cancelled = true; } isCancelled() { return !!this.cancelled; } },
    RemoteMediaApi: class { downloadEbookCover() {
      events.push('cover'); return new Promise(resolve => { finishCover = resolve; });
    } } };
  vm.createContext(context);
  vm.runInContext(ts.transpileModule(`class Reader { ${methods} } globalThis.Reader = Reader;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText, context);
  const reader = new context.Reader();
  Object.assign(reader, { bookDetailOpenRevision: 0, readerProgressSyncGeneration: 1,
    localAccountScope: 'account', authenticated: true, selectedRemoteBookSourceId: 'local:source',
    remoteBookSources: [{ id: 'local:source' }],
    remoteBookImportPolicy: { candidate: item => ({ sourceId: 'local:source', identityMaterial: item.id, tags: [] }) },
    digestTool: { digestText: async value => value }, authManager: { publicBusinessBaseUrl: () => 'https://example.test' },
    ActiveServiceUrl: () => 'https://example.test', ApplicationContext: () => ({}),
    OpenBookDetail: book => {
      reader.bookDetailOpenRevision++; reader.detailBook = book; reader.bookDetailVisible = true;
      events.push('detail');
    },
    RemoteBookSourceDisplayName: () => 'Library',
    RestoreRemoteBookProgress: () => { events.push('progress'); return new Promise(() => {}); },
    RemoteBookCoverCachePath: () => '/cache/cover',
    SetRemoteBookCover: () => events.push('cover-applied'), CopyBookWithProgress: book => ({ ...book }) });
  return { reader, events, item: { id: 'book', bookCoverAvailable: true, bookCoverUri: cachedCover },
    finishCover: () => finishCover() };
}

(async () => {
  const first = setup();
  await first.reader.OpenRemoteBookDetail(first.item);
  assert.deepEqual(first.events, ['detail', 'progress', 'cover']);
  assert.equal(first.reader.bookDetailVisible, true, 'Details must open while both requests are still pending');
  first.finishCover();
  await new Promise(setImmediate);
  assert.equal(first.reader.detailBook.coverUrl, 'file:///cache/cover');
  const cached = setup('file:///existing');
  await cached.reader.OpenRemoteBookDetail(cached.item);
  assert.deepEqual(cached.events, ['detail', 'progress']);
  assert.equal(cached.reader.detailBook.coverUrl, 'file:///existing');
  const closed = setup();
  await closed.reader.OpenRemoteBookDetail(closed.item);
  closed.reader.bookDetailOpenRevision++;
  closed.reader.bookDetailVisible = false;
  closed.reader.activeRemoteDetailCoverCancellation.cancel();
  closed.finishCover();
  await new Promise(setImmediate);
  assert.equal(closed.events.includes('cover-applied'), false);
  const replaced = setup('file:///existing');
  const pending = [];
  replaced.reader.digestTool.digestText = value => new Promise(resolve => pending.push(() => resolve(value)));
  const openingA = replaced.reader.OpenRemoteBookDetail({ ...replaced.item, id: 'a' });
  const openingB = replaced.reader.OpenRemoteBookDetail({ ...replaced.item, id: 'b' });
  pending[1](); await openingB;
  pending[0](); await openingA;
  assert.equal(replaced.reader.detailBook.id, 'remote:sha256:b');
  assert.equal(replaced.events.filter(value => value === 'detail').length, 1);
  console.log('Remote details: immediate metadata, cached cover reuse, late-cover rejection and rapid selection passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
