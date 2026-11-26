const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const page = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const names = ['ShowReaderFeedback', 'ClearReaderFeedback', 'ChangeReaderSearchText', 'SearchReaderContent', 'AddCurrentReaderBookmark'];
const methods = names.map(name => page.match(new RegExp(`^  private (?:async )?${name}\\([\\s\\S]*?^  }`, 'm'))[0]).join('\n');
const timers = new Map(); let timerId = 0;
const context = { setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id) };
vm.createContext(context);
vm.runInContext(ts.transpileModule(`class Reader { ${methods} } globalThis.Reader = Reader;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText, context);
async function run() {
  const r = new context.Reader();
  Object.assign(r, { readerVisible: true, readerFeedbackTimer: -1, readerSearchRevision: 0,
    readerOpenRevision: 1, currentBook: {id:'a'}, readerChapters: [{title:'Chapter',content:'body'}],
    readerChapterIndex: 0, comicImageUris: [], CurrentReaderLocator: () => 100012345 });
  r.ShowReaderFeedback('saved'); r.ShowReaderFeedback('new');
  assert.equal(timers.size, 1); assert.equal(r.readerFeedback, 'new');
  [...timers.values()][0](); assert.equal(r.readerFeedback, ''); assert.equal(timers.size, 0);
  r.readerVisible = false; r.ShowReaderFeedback('stale'); assert.equal(r.readerFeedback, '');
  r.readerVisible = true;
  let complete;
  r.readerContentLoader = { searchIndexedTextChapters: () => new Promise(resolve => complete = resolve) };
  r.ChangeReaderSearchText('first'); const pending = r.SearchReaderContent();
  assert.equal(r.readerSearchBusy, true);
  r.ChangeReaderSearchText('second'); complete([{chapterTitle:'old'}]); await pending;
  assert.equal(r.readerSearchBusy, false); assert.equal(r.readerSearchResults.length, 0);
  assert.equal(r.readerSearchStatus, '');
  r.readerContentLoader.searchIndexedTextChapters = async () => [];
  await r.SearchReaderContent(); assert.match(r.readerSearchStatus, /second/);
  r.readerContentLoader.searchIndexedTextChapters = async () => { throw 'failed'; };
  await r.SearchReaderContent(); assert.equal(r.readerSearchBusy, false); assert.ok(r.readerSearchStatus);
  let savedLocator;
  r.AddCopilotBookmark = async (_book, _title, locator) => { savedLocator = locator; };
  await r.AddCurrentReaderBookmark();
  assert.equal(savedLocator, 100012345); assert.ok(r.readerFeedback.includes('Chapter'));
  assert.equal(r.readerMoreVisible, false);
  r.ClearReaderFeedback();
  r.AddCopilotBookmark = () => new Promise(resolve => complete = resolve);
  const save = r.AddCurrentReaderBookmark(); r.readerOpenRevision++; complete({}); await save;
  assert.equal(r.readerFeedback, '', 'Completion from another reading session must stay silent');
  console.log('Feedback lifetime, search cancellation/empty/error, versioned bookmark locator and stale completion passed');
}
run().catch(e => { console.error(e); process.exitCode = 1; });
// 使用真实位置编码验证首、中、末页，不通过桩函数掩盖分页边界。
const locatorBox = { exports: {} };
vm.runInNewContext(ts.transpileModule(fs.readFileSync('app/entry/src/main/ets/features/reader/ReaderTextLocatorPolicy.ets', 'utf8'), {
  compilerOptions: {module: ts.ModuleKind.CommonJS}
}).outputText, locatorBox);
context.ReaderTextLocatorPolicy = locatorBox.exports.ReaderTextLocatorPolicy;
const locatorMethod = page.match(/^  private CurrentReaderLocator\([\s\S]*?^  }/m)[0];
vm.runInContext(ts.transpileModule(`class LocatorReader { ${locatorMethod} } globalThis.LocatorReader = LocatorReader;`, {
  compilerOptions:{target:ts.ScriptTarget.ES2020}
}).outputText, context);
const locatorReader = new context.LocatorReader();
Object.assign(locatorReader, {comicImageUris:[], ReaderUsesContinuousText:()=>false});
const policy = new context.ReaderTextLocatorPolicy();
for (const count of [1,3,7,100]) {
  locatorReader.readerTextPages = Array(count).fill('page');
  for (let index=0; index<count; index++) {
    locatorReader.readerTextPageIndex=index;
    const decoded=policy.decode(locatorReader.CurrentReaderLocator(),1000);
    assert.equal(Math.floor(decoded.fraction*count),index);
  }
}
locatorReader.ReaderUsesContinuousText=()=>true;
locatorReader.CurrentTextChapterFraction=()=>0.42;
assert.equal(policy.decode(locatorReader.CurrentReaderLocator(),1000).fraction,0.42);
locatorReader.pdfMode=true;locatorReader.pdfPageIndex=12;assert.equal(locatorReader.CurrentReaderLocator(),12);
console.log('Actual locator round trips preserve every page across 1, 3, 7 and 100 page chapters');
