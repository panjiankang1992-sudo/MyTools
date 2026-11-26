const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');

const page = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const openBook = page.match(/^  private async OpenBook\([\s\S]*?^  }/m)[0];
const progressOpen = page.match(/^  private async PrepareRemoteReaderProgress\([\s\S]*?^  }/m)[0];
const applyOpen = page.match(/^  private ApplyOpenedReaderDocument\([\s\S]*?^  }/m)[0];
const completeOpen = page.match(/^  private CompleteReaderOpen\([\s\S]*?^  }/m)[0];

async function run({ format = 'txt', cached = true, corrupt = false, cancel = false, slowProgress = false, origin = 'remote' } = {}) {
  const events = [];
  let current = true;
  let finishProgress;
  const progressPending = new Promise(resolve => { finishProgress = resolve; });
  const document = { chapters: [{ content: 'cached content' }], imageUris: [] };
  class Loader {
    shelfFileCache = {
      prepare: async () => { events.push('cache'); return cached ? '/cached-book' : ''; },
      invalidate: () => events.push('invalidate'), available: () => true
    };
    async local() {
      events.push('local');
      this.onParsing();
      if (cancel) current = false;
      if (corrupt || cancel) throw Error('read failed');
      return document;
    }
    loadLocalText() { return this.local(); }
    loadLocalArchive() { return this.local(); }
    loadLocalMobi() { return this.local(); }
    prepareLocalPdf() { return this.local(); }
    async loadRemoteText() {
      events.push('download');
      this.onDownloadProgress(1048576, 2097152);
      this.onParsing();
      if (slowProgress) finishProgress();
      return document;
    }
    cleanup() { events.push('cleanup'); }
  }
  const context = {
    ReaderContentLoader: Loader,
    $r: value => value,
    Scroller: class {},
    DownloadCancellationToken: class { cancel() { this.cancelled = true; } isCancelled() { return this.cancelled === true; } },
    setTimeout, clearTimeout,
    RemoteMediaApi: class {
      async resolveBookPlayback() { events.push('ticket'); return { url: 'https://example.test/book' }; }
    }
  };
  vm.createContext(context);
  vm.runInContext(ts.transpileModule(`class Reader { ${openBook}\n${completeOpen}\n${progressOpen}\n${applyOpen} }\nglobalThis.Reader = Reader;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText, context);
  const reader = new context.Reader();
  const book = { id: 'book', origin, format, resourceUri: '/book', sourceId: 'source' };
  Object.assign(reader, {
    readerSearchRevision: 0, readerOpenRevision: 0, accountOperationRevision: 0,
    sourceChapterLoadRevision: 0, pageLifecycleRevision: 0, readerScrollEpoch: 0,
    localAccountScope: 'account', readerSettings: {}, authenticated: true, readingProgress: [],
    shelfFileCacheQueue: [], books: [book], remoteBookSources: [{ id: 'source' }],
    getUIContext: () => ({ getHostContext: () => ({}) }),
    ActiveServiceUrl: () => 'https://example.test',
    ReaderOpenCurrent: () => current,
    ReaderUiText: value => value,
    AdoptReaderContentLoader: loader => { reader.readerContentLoader = loader; },
    MarkShelfBookCached: () => {},
    RestoreRemoteBookProgress: async () => { events.push('progress'); if (slowProgress) await progressPending; },
    ApplyReaderDocument: () => { events.push('display'); reader.readerContent = 'cached content'; },
    OpenPreparedPdf: async (...args) => { reader.readerContentLoader = args[5]; events.push('display'); reader.readerStatus = ''; }
  });
  for (const name of ['ClearReaderFeedback', 'CancelReaderChapterPrefetch', 'FinishReadingSession', 'ApplyReaderSystemBars',
    'ApplyReaderOrientation', 'StartReaderPersistenceTimer', 'ScheduleReaderChapterPrefetch']) {
    reader[name] = () => {};
  }
  await reader.OpenBook(book);
  assert.equal(reader.readerOpening, false);
  if (!cancel) assert.equal(reader.readerStatus, '', 'Loaded content must leave the loading screen');
  return events;
}

(async () => {
  // 即使本地没有阅读进度，各种已缓存文件也必须在零网络请求的情况下显示。
  for (const format of ['txt', 'epub', 'cbz', 'pdf', 'mobi', 'azw3']) {
    for (const origin of ['local', 'remote', 'source']) assert.deepEqual(await run({ format, origin }), ['cache', 'local', 'display'], format);
  }
  assert.deepEqual(await run({ cached: false }), ['cache', 'progress', 'ticket', 'download', 'display']);
  assert.deepEqual(await run({ corrupt: true }),
    ['cache', 'local', 'invalidate', 'progress', 'ticket', 'download', 'display']);
  assert.deepEqual(await run({ cancel: true }), ['cache', 'local', 'cleanup']);
  assert.deepEqual(await run({ cached: false, slowProgress: true }), ['cache', 'progress', 'ticket', 'download', 'display']);
  console.log('Cached reader opens without progress/ticket requests; miss, corruption and cancellation passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
