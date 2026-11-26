const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const page = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const methods = ['FilteredShelfBooks', 'ModeShelfBooks', 'HandleReaderProgressSlider', 'OpenReaderCatalog']
  .map(name => page.match(new RegExp(`^  private ${name}\\([\\s\\S]*?^  }`, 'm'))[0]).join('\n');
const context = { SliderChangeMode: { Begin: 0, Moving: 1, End: 2, Click: 3 } };
vm.createContext(context);
vm.runInContext(ts.transpileModule(`class Reader { ${methods} } globalThis.Reader = Reader;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText, context);
const reader = new context.Reader();
const books = [
  { id: 'a', name: 'Alpha', author: 'Writer', origin: 'local', tags: ['TXT'], chapter: 'Chapter one' },
  { id: 'b', name: 'Alpha Two', author: 'Other', origin: 'remote', tags: ['EPUB'], chapter: 'Chapter two' }
];
Object.assign(reader, { books, bookModeIndex: 3, shelfSearchText: ' ALPHA ', shelfSelectedTag: 'TXT',
  BookShelfTags: b => b.tags, BookShelfChapterTitle: b => b.chapter });
const ids = () => Array.from(reader.FilteredShelfBooks(), b => b.id);
assert.deepEqual(ids(), ['a']);
reader.shelfSelectedTag = 'EPUB';
assert.deepEqual(ids(), ['b']);
assert.equal(reader.shelfSearchText, ' ALPHA ');
reader.shelfSelectedTag = 'EP';
assert.deepEqual(ids(), []);
reader.shelfSelectedTag = '';
assert.deepEqual(ids(), ['a', 'b']);
reader.bookModeIndex = 2;
assert.deepEqual(ids(), ['a']);
reader.shelfSearchText = 'Chapter one';
assert.deepEqual(ids(), ['a']);
reader.shelfSearchText = '';
assert.deepEqual(ids(), ['a']);
assert.equal(books.length, 2);
const seeks = [];
reader.SeekReaderProgress = value => { seeks.push(value); return Promise.resolve(); };
for (const mode of [0, 1, 1, 1, 2, 3]) reader.HandleReaderProgressSlider(42, mode);
assert.deepEqual(seeks, [42, 42]);
reader.readerControlsVisible = true;
reader.OpenReaderCatalog();
assert.equal(reader.readerCatalogVisible, true);
assert.equal(reader.readerControlsVisible, false);
assert.equal(reader.readerCatalogMode, 'catalog');
console.log('Combined shelf filters, mode isolation, slider commit and catalog actions passed');
