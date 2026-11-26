const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { performance } = require('node:perf_hooks');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const source = fs.readFileSync('app/entry/src/main/ets/features/reader/ReaderTextDocumentCache.ets', 'utf8');
const context = { exports: {} };
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, context);
const Cache = context.exports.ReaderTextDocumentCache;
const cache = new Cache();
const document = { kind: 'text', encoding: 'UTF-8', imageUris: [],
  chapters: Array.from({ length: 1000 }, (_, index) => ({ title: `Chapter ${index}`, content: 'a'.repeat(4096) })) };
cache.put('account-a:file-v1', document);
document.chapters[0].content = 'mutated';
const restored = cache.get('account-a:file-v1');
assert.equal(restored.chapters[0].content.length, 4096);
restored.chapters[0].content = 'changed';
assert.equal(cache.get('account-a:file-v1').chapters[0].content.length, 4096);
assert.equal(cache.get('account-b:file-v1'), undefined);
assert.equal(cache.get('account-a:file-v2'), undefined);
cache.put('indexed', { ...document, chapters: [{ title: 'One', content: 'text', resourceUri: 'txt-index:deleted' }] });
assert.equal(cache.get('indexed'), undefined);
cache.put('rich', { ...document, chapters: [{ title: 'One', content: 'text', blocks: [] }] });
assert.equal(cache.get('rich'), undefined);
cache.put('too-large', { ...document, chapters: [{ title: 'One', content: 'a'.repeat(9 * 1024 * 1024) }] });
assert.equal(cache.get('too-large'), undefined);
const tiny = { kind: 'text', imageUris: [], chapters: [{ title: 'One', content: 'body' }] };
cache.clear();
for (const key of ['a', 'b', 'c', 'd']) cache.put(key, tiny);
cache.get('a');
cache.put('e', tiny);
assert.equal(cache.get('b'), undefined);
assert.ok(cache.get('a'));
cache.clear();
for (const key of ['a', 'b', 'c', 'd']) cache.put(key, document);
assert.equal(cache.get('a'), undefined, 'Total character budget must evict old documents');
cache.clear();
assert.equal(cache.get('d'), undefined);

const loaderSource = fs.readFileSync('app/entry/src/main/ets/features/reader/ReaderContentLoader.ets', 'utf8');
const methods = ['loadLocalText', 'loadRemoteText'].map(name => loaderSource.match(new RegExp(`^  async ${name}\\([\\s\\S]*?^  }`, 'm'))[0]).join('\n');
let mtime = 1;
let parses = 0;
let reads = 0;
const box = { ReaderTextDocumentCache: Cache,
  fs: { OpenMode: { READ_ONLY: 0 }, statSync: () => ({ size: 1024, mtime }), openSync: () => ({ fd: 1 }),
    readSync: () => { reads++; return 1024; }, closeSync: () => {} } };
vm.createContext(box);
vm.runInContext(ts.transpileModule(`class Loader {
 static textDocumentCache = new ReaderTextDocumentCache();
 static MAX_LARGE_TEXT_BYTES = 500 * 1024 * 1024;
 static MAX_TEXT_BYTES = 20 * 1024 * 1024;
 ${methods}
} globalThis.Loader = Loader;`, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText.replaceAll('ReaderContentLoader.', 'Loader.'), box);
(async () => {
  const loader = new box.Loader();
  Object.assign(loader, { documentCacheScope: 'a', preparedCache: { read: async () => undefined },
    shelfFileCache: { path: () => '' }, persistPrepared: async document => document, onParsing: () => {},
    parseTextBytes: () => { parses++; return document; } });
  await loader.loadLocalText({}, '/book');
  await loader.loadLocalText({}, '/book');
  assert.equal(parses, 1);
  assert.equal(reads, 1);
  mtime++;
  await loader.loadLocalText({}, '/book');
  assert.equal(parses, 2, 'Changing file version must force parsing');
  loader.documentCacheScope = 'b';
  await loader.loadLocalText({}, '/book');
  assert.equal(parses, 3, 'Account scope must isolate parsed content');
  Object.assign(loader, { prepareWorkspace: () => '/temporary', isMyToolsTicketUrl: () => true,
    downloadTicketTextRanges: async () => 1024, loadTextFile: () => document,
    shelfFileCache: { path: () => '/saved' } });
  await loader.loadRemoteText({}, 'url');
  await loader.loadLocalText({}, '/saved');
  assert.equal(parses, 3, 'First download must seed the persistent-file parse cache');
  const key = 'benchmark';
  cache.put(key, document);
  const serialized = JSON.stringify(document);
  const start = performance.now();
  for (let i = 0; i < 20; i++) JSON.parse(serialized);
  const oldMs = performance.now() - start;
  const next = performance.now();
  for (let i = 0; i < 20; i++) cache.get(key);
  const newMs = performance.now() - next;
  console.log(JSON.stringify({ environment: 'Node synthetic benchmark, not device startup timing',
    characters: serialized.length, iterations: 20, oldJsonParseMs: +oldMs.toFixed(2), cachedCloneMs: +newMs.toFixed(2) }));
  console.log('TXT cache: mutation isolation, account/version invalidation, memory limits, LRU and first-download reuse passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
