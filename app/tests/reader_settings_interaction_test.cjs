const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const page = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const methods = ['OpenReaderSettings', 'BackReaderSettings', 'CloseReaderSettings', 'AdjustReaderFontSize', 'CommitReaderSetting'].map(name =>
  page.match(new RegExp(`^  private ${name}\\([\\s\\S]*?^  }`, 'm'))[0]).join('\n');
const context = { SliderChangeMode: { Begin: 0, Moving: 1, End: 2, Click: 3 } };
vm.createContext(context);
vm.runInContext(ts.transpileModule(`class Reader { ${methods} } globalThis.Reader = Reader;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText, context);
const r = new context.Reader();
let rebuilds = 0, saves = 0;
Object.assign(r, { readerSettings: { fontSize: 19, lineHeight: 1.8, theme: 'paper' },
  CopyReaderSettings: fontSize => ({ ...r.readerSettings, fontSize }),
  RebuildReaderPages: () => rebuilds++, PersistReaderState: () => saves++ });
r.AdjustReaderFontSize(1);
assert.equal(r.readerSettings.fontSize, 20);
assert.equal(r.readerSettings.lineHeight, 1.8);
r.AdjustReaderFontSize(-1);
assert.equal(r.readerSettings.fontSize, 19);
r.readerSettings.fontSize = 32;
r.AdjustReaderFontSize(1);
assert.equal(r.readerSettings.fontSize, 32);
r.readerSettings.fontSize = 14;
r.AdjustReaderFontSize(-1);
assert.equal(r.readerSettings.fontSize, 14);
assert.equal(rebuilds, 2);
assert.equal(saves, 2);
const changes = [];
for (const mode of [0, 1, 1, 2, 3]) r.CommitReaderSetting(1.55, mode, v => changes.push(v));
assert.deepEqual(changes, [1.55, 1.55]);
assert.equal(saves, 4);
r.readerSettingsVisible = true;
r.CloseReaderSettings();
assert.equal(r.readerSettingsVisible, false);
assert.equal(saves, 5);
console.log('Font bounds and step, deferred slider commits, and settings close persistence passed');

Object.assign(r, { comicImageUris: [], pdfMode: false, readerMoreVisible: true, readerSearchVisible: true });
r.OpenReaderSettings();
assert.equal(r.readerSettingsDetailed, false);
assert.equal(r.readerSettingsVisible, true);
assert.equal(r.readerMoreVisible, false);
assert.equal(r.readerSearchVisible, false);
r.readerSettingsDetailed = true;
r.BackReaderSettings();
assert.equal(r.readerSettingsDetailed, false);
assert.equal(r.readerSettingsVisible, true);
r.BackReaderSettings();
assert.equal(r.readerSettingsVisible, false);
r.comicImageUris = ['page'];
r.OpenReaderSettings(); assert.equal(r.readerSettingsDetailed, true);
r.BackReaderSettings(); assert.equal(r.readerSettingsVisible, false);
r.comicImageUris = []; r.pdfMode = true;
r.OpenReaderSettings(); assert.equal(r.readerSettingsDetailed, true);
r.CloseReaderSettings(); assert.equal(r.readerSettingsDetailed, false);
r.pdfMode = false; r.OpenReaderSettings(); assert.equal(r.readerSettingsDetailed, false);
console.log('Quick/detail settings navigation, format entry and fresh reopen passed');
