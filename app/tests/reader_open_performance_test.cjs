const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const root = path.resolve(__dirname, '../..');
const page = fs.readFileSync(path.join(root, 'app/entry/src/main/ets/pages/Index.ets'), 'utf8');
const names = ['PrepareReaderContinuousWindow', 'PrepareReaderContinuousWindowAtChapter',
  'ReaderContinuousWindowIndexes', 'EnsureContinuousReaderContent', 'ExtendReaderContinuousWindow',
  'ApplyReaderDocument', 'RestoreRemoteBookProgress', 'LoadSourceChapter'];
const methods = names.map(name => page.match(new RegExp(`^  private (?:async )?${name}\\([\\s\\S]*?^  }`, 'm'))[0]).join('\n');
let respond;
const timers = [];
const context = { setTimeout: fn => timers.push(fn), console,
  ReaderTextLocatorPolicy: class { decode() { return { versioned: true, fraction: 0.4 }; } },
  ReaderProgressApi: class { find() { return new Promise(resolve => { respond = resolve; }); } },
  DownloadCancellationToken: class { isCancelled() { return false; } },
  BookSourceRuntimeReaderApi: class { async content() { return { kind: 'text', text: 'Ready now', imageUris: [], remoteImageUrls: [] }; } }
};
vm.createContext(context);
vm.runInContext(ts.transpileModule(`class Reader { ${methods} } globalThis.Reader = Reader;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText, context);
const r = new context.Reader();
const reads = [];
Object.assign(r, { readerChapters: Array.from({ length: 1000 }, (_, index) =>
  ({ title: `Chapter ${index}`, content: '', resourceUri: `txt-index:${index}` })),
  readerChapterPrefetchCount: 20,
  readerContentLoader: { loadIndexedTextChapter: uri => { reads.push(uri); return 'Body '.repeat(100); } },
  ReaderUsesWindowedContinuousText: () => true,
  EstimatedReaderChapterHeight: () => 100,
  readerScroller: { scrollBy: () => {}, scrollTo: () => {} }
});
r.PrepareReaderContinuousWindow(500);
assert.deepEqual(Array.from(r.ReaderContinuousWindowIndexes()), [500, 501]);
assert.equal(reads.length, 2, 'Opening must not read twenty future chunks');
r.ExtendReaderContinuousWindow(1);
assert.deepEqual(Array.from(r.ReaderContinuousWindowIndexes()), [500, 501, 502]);
assert.equal(reads.length, 3);
r.ExtendReaderContinuousWindow(-1);
assert.equal(r.readerContinuousStartIndex, 499);
assert.equal(reads.length, 4);
r.PrepareReaderContinuousWindowAtChapter(999);
assert.deepEqual(Array.from(r.ReaderContinuousWindowIndexes()), [999]);
const saved = { bookId: 'book', chapterTitle: 'Chapter 800', locator: 400 };
Object.assign(r, { currentBook: { id: 'book' }, readingProgress: [saved],
  FindSavedReaderChapterIndex: () => 800, EstimatedReaderTextHeight: () => 1000,
  RebuildReaderPages: (_percentage, fraction) => assert.equal(fraction, 0.4),
  ReaderUsesContinuousText: () => true });
r.ApplyReaderDocument({ kind: 'text', chapters: r.readerChapters, imageUris: [] });
assert.equal(r.readerChapterIndex, 800);
assert.equal(r.readerContinuousStartIndex, 800);
assert.equal(r.readerScrollValue, 400);
assert.ok(r.readerContent.startsWith('Body'), 'Restored indexed content must be available before display');
assert.equal(r.readerProgressReady, true);

(async () => {
  const reader = new context.Reader();
  const book = { id: 'remote' };
  Object.assign(reader, { readingProgress: [], books: [book], authenticated: true,
    readerProgressSyncGeneration: 1, localAccountScope: 'account', ActiveServiceUrl: () => 'endpoint',
    ReplaceReadingProgress: progress => reader.readingProgress.push(progress), RememberRemoteBookProgress: () => {} });
  const cancelled = { value: false, isCancelled() { return this.value; } };
  const pending = reader.RestoreRemoteBookProgress(book, 1, 'account', cancelled);
  cancelled.value = true;
  respond({ bookId: book.id, percentage: 80 });
  await pending;
  assert.equal(reader.readingProgress.length, 0, 'Cancelled progress must not apply late');
  const pending2 = reader.RestoreRemoteBookProgress(book, 1, 'account');
  reader.readingProgress.push({ bookId: book.id, percentage: 10 });
  respond({ bookId: book.id, percentage: 80 });
  await pending2;
  assert.equal(reader.readingProgress.length, 1);
  assert.equal(reader.readingProgress[0].percentage, 10, 'New local checkpoint wins');
  const sourceBook = { id: 'source', origin: 'source', resourceUri: 'book', sourceId: 'source' };
  const chapter = { title: 'One', content: '' };
  Object.assign(reader, { currentBook: sourceBook, readerChapters: [chapter], readerChapterIndex: 0,
    bookSources: [{ bookSourceUrl: 'source', bookSourceType: 0 }], sourceChapterLoadRevision: 0,
    ApplicationContext: () => ({}), SourceBookLocalCacheEnabled: () => true,
    AccountOperationCurrent: () => true,
    bookSourceLocalCache: { chapter: async () => undefined, putChapter: async () => { throw Error('disk full'); } },
    ApplySourceTextChapter: (_book, _chapter, text) => { reader.readerContent = text; reader.readerStatus = ''; } });
  await reader.LoadSourceChapter();
  assert.equal(reader.readerContent, 'Ready now');
  assert.equal(reader.readerStatus, '');
  for (const fn of timers.splice(0)) fn();
  await Promise.resolve();
  assert.equal(reader.readerStatus, '', 'Cache write failure must not hide downloaded content');
  reader.readerOpening = true;
  reader.readerContent = '';
  chapter.content = '';
  context.BookSourceRuntimeReaderApi = class { async content() { throw Error('network unavailable'); } };
  await reader.LoadSourceChapter();
  assert.equal(reader.readerOpenFailed, true, 'Initial chapter failure must expose retry actions');
  console.log('Reader opening: 22 -> 2 initial chapters; incremental loading, indexed resume, stale progress and cache-write failure passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
