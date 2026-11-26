const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const source = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const methods = ['FilteredReaderChapterIndexes', 'ReadDetailBook', 'OpenReaderCatalog'].map(name =>
  source.match(new RegExp(`^  private (?:async )?${name}\\([\\s\\S]*?^  }`, 'm'))[0]).join('\n');
const context = {};
vm.createContext(context);
vm.runInContext(ts.transpileModule(`class Reader { ${methods} } globalThis.Reader = Reader;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText, context);
const reader = new context.Reader();
reader.readerChapters = [{ title: 'First' }, { title: 'Same' }, { title: 'SAME' }, { title: 'End' }];
function search(query) { reader.readerCatalogQuery = query; return Array.from(reader.FilteredReaderChapterIndexes()); }
assert.deepEqual(search(' same '), [1, 2]);
assert.deepEqual(search('3'), [2]);
assert.deepEqual(search('0'), []);
assert.deepEqual(search('99'), []);
assert.deepEqual(search(''), [0, 1, 2, 3]);
assert.deepEqual(search('missing'), []);
async function opening(mode, catalog = true) {
  const r = new context.Reader();
  const book = { id: 'test', origin: 'local' };
  Object.assign(r, { detailBook: book, readerOpenRevision: 1, readerStatus: '', readerCatalogQuery: 'stale',
    DetailBookInShelf: () => true, readerCatalogVisible: false,
    OpenBook: async () => {
      r.readerOpenRevision++;
      r.currentBook = book;
      r.readerVisible = true;
      await Promise.resolve();
      if (mode === 'closed') r.readerVisible = false;
      if (mode === 'replaced') r.readerOpenRevision++;
      if (mode === 'error') r.readerStatus = 'error';
    } });
  await r.ReadDetailBook(catalog);
  return r;
}
(async () => {
  const success = await opening('success');
  assert.equal(success.readerCatalogVisible, true);
  assert.equal(success.readerCatalogQuery, '');
  for (const mode of ['closed', 'replaced', 'error']) assert.equal((await opening(mode)).readerCatalogVisible, false);
  assert.equal((await opening('success', false)).readerCatalogVisible, false);
  const r = new context.Reader();
  Object.assign(r, { detailBook: { id: 'same' }, currentBook: { id: 'same' }, detailReturnToReader: true,
    CloseBookDetail: () => { r.readerVisible = true; }, OpenBook: () => { throw Error('must reuse reader'); } });
  await r.ReadDetailBook(true);
  assert.equal(r.readerCatalogVisible, true);
  console.log('Catalog search preserves source indexes; detail entry, cancellation and reader reuse passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
