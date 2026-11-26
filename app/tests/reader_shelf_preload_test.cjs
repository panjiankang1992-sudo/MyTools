const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const page = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const names = ['ScheduleShelfBookLocalCache', 'DrainShelfFileCaches', 'CancelShelfFileCaches',
  'CacheShelfFileBook', 'CacheShelfBookWindow', 'ShelfBookCacheCurrent', 'MarkShelfBookCached'];
const methods = names.map(name => page.match(new RegExp(`^  private (?:async )?${name}\\([\\s\\S]*?^  }`, 'm'))[0]).join('\n');
let events = [];
const box = { Error, setTimeout, clearTimeout,
  DownloadCancellationToken: class { cancel() { this.cancelled = true; } isCancelled() { return !!this.cancelled; } },
  ReaderContentLoader: class { cleanup() {} cancel() {} },
  BookSourceRuntimeReaderApi: class {
    async catalog() { throw Error('Stored catalog must be reused'); }
    async content(source, chapter, index, cancellation) {
      assert.equal(cancellation.isCancelled(), false);
      events.push(index); return { kind: 'text', text: 'body' };
    }
  },
  RemoteMediaApi: class {
    async listBookSources() { events.push('sources'); return [{ id: 'source' }]; }
    async resolveBookPlayback() { events.push('ticket'); return { url: 'https://example.test/book' }; }
  }
};
vm.createContext(box);
vm.runInContext(ts.transpileModule(`class Shelf { ${methods} } globalThis.Shelf = Shelf;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, box);
const identity = (id, format = 'txt', origin = 'remote') => ({ id, format, origin, sourceId: 'source', resourceUri: '/' + id });
function shelf(books) {
  return Object.assign(new box.Shelf(), {
    localAccountScope: 'account', readerStorageScope: 'account', accountOperationRevision: 1, books,
    serviceUrl: 'stable-server', ActiveServiceUrl: () => 'current-lan',
    shelfFileCacheQueue: [], shelfPreparedCatalogs: new Map(), shelfFileCacheGeneration: 0,
    shelfCachedBookIds: [], shelfCacheFailedBookIds: [], shelfCachingBookId: '', remoteBookSources: [],
    ApplicationContext: () => ({}), AccountOperationCurrent: (revision, owner) => revision === 1 && owner === 'account'
  });
}
const flush = () => new Promise(resolve => setImmediate(resolve));
async function drain(s) { while (s.shelfFileCachePromise) await s.shelfFileCachePromise; }

(async () => {
  const first = identity('a'), second = identity('b'), third = identity('c');
  const s = shelf([first, second, third]);
  let release;
  s.CacheShelfFileBook = async b => {
    events.push(b.id);
    if (b.id === 'a') await new Promise(resolve => { release = resolve; });
    if (b.id === 'b') throw Error('disk full');
  };
  for (const b of [first, first, second, third]) s.ScheduleShelfBookLocalCache(b);
  assert.deepEqual(events, ['a'], 'Only one preload runs at once');
  release(); await drain(s);
  assert.deepEqual(events, ['a', 'b', 'c']);
  assert.equal(s.shelfCachedBookIds.join(','), 'a,c');
  assert.equal(s.shelfCacheFailedBookIds.join(','), 'b');
  s.CacheShelfFileBook = async () => {};
  s.ScheduleShelfBookLocalCache(second); await drain(s);
  assert.equal(s.shelfCacheFailedBookIds.length, 0);
  const cancelled = shelf([first, second]);
  cancelled.CacheShelfFileBook = async () => new Promise(resolve => { release = resolve; });
  cancelled.ScheduleShelfBookLocalCache(first); cancelled.ScheduleShelfBookLocalCache(second);
  cancelled.CancelShelfFileCaches(); release(); await flush();
  assert.equal(cancelled.shelfCachedBookIds.length, 0);
  assert.equal(cancelled.shelfFileCacheQueue.length, 0);

  // 真实分流函数覆盖每种来源/格式；命中时不得取票、验证网络地址或激活原文件权限。
  for (const format of ['txt', 'epub', 'cbz', 'pdf', 'mobi', 'azw3']) {
    for (const origin of ['local', 'remote', 'source']) {
      for (const hit of [false, true]) {
        const b = identity(format, format, origin), r = shelf([b]); events = [];
        r.localBookPicker = { activate: async () => events.push('permission') };
        r.sourceUrlPolicy = { assertSafe: async () => events.push('url') };
        r.bookSourceCredentialStore = { headersFor: async () => ({}) };
        const loader = { shelfFileCache: { prepare: async (context, scope, server) => {
          assert.equal(scope, 'account'); assert.equal(server, 'stable-server');
          return hit ? '/persistent' : '';
        }, available: () => true } };
        for (const name of ['loadLocalText', 'loadLocalArchive', 'loadLocalMobi', 'prepareLocalPdf',
          'loadRemoteText', 'loadRemoteArchive', 'loadRemoteMobi', 'prepareRemotePdf']) {
          loader[name] = async () => events.push(name);
        }
        await r.CacheShelfFileBook(b, loader, new box.DownloadCancellationToken(), 1, 'account');
        assert.equal(events.at(-1).includes(hit || origin === 'local' ? 'Local' : 'Remote'), true);
        if (hit) assert.equal(events.length, 1);
        else assert.equal(events[0], origin === 'local' ? 'permission' : origin === 'remote' ? 'sources' : 'url');
      }
    }
  }
  const sourceBook = identity('novel', 'unknown', 'source'), sourceShelf = shelf([sourceBook]);
  const document = { chapters: Array.from({ length: 30 }, (_, i) => ({ title: String(i), resourceUri: '/' + i })) };
  const stored = new Map([[5, { kind: 'text', text: 'already saved' }]]);
  Object.assign(sourceShelf, {
    bookSources: [{ bookSourceUrl: 'source' }], readingProgress: [{ bookId: 'novel' }],
    FindSavedReaderChapterIndex: () => 5, readerChapterPrefetchCount: 20,
    bookSourceLocalCache: { catalog: async () => ({ document }), putCatalog: async () => {},
      chapter: async (...args) => stored.get(args[5]),
      putChapter: async (...args) => stored.set(args[5], args[6]) }
  });
  events = [];
  sourceShelf.ScheduleShelfBookLocalCache(sourceBook); await drain(sourceShelf);
  assert.equal(events.length, 20);
  assert.equal(events[0], 6); assert.equal(events.at(-1), 25);
  assert.equal(sourceShelf.shelfCachedBookIds[0], 'novel');
  console.log('Shelf preload: deduplication, serial queue, retries, cancellation, six formats/three origins and persisted chapter window passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
