const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const source = fs.readFileSync(process.argv[2], 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020,
  module: ts.ModuleKind.CommonJS } }).outputText;
const tick = () => new Promise(resolve => setImmediate(resolve));

function setup(delayOpen = false) {
  let delegate, active = true, pickerResolve, openResolve;
  const writes = [], removed = [], notices = [], closed = [];
  class Delegate {
    onBeforeDownload(f) { this.before = f; }
    onDownloadFinish(f) { this.finish = f; }
    onDownloadFailed(f) { this.failed = f; }
  }
  const output = { exports: {} };
  const imports = {
    '@ohos.web.webview': { default: { WebDownloadDelegate: Delegate } },
    '@ohos.file.fs': { default: { OpenMode: { READ_WRITE: 1 },
      open: async () => delayOpen ? new Promise(resolve => { openResolve = resolve; }) : ({ fd: 4 }),
      close: async file => { closed.push(file.fd); },
      copyFile: async (source, destination) => { writes.push([source, destination]); },
      unlinkSync: path => removed.push(path) } },
    '@ohos.file.picker': { default: { DocumentSaveOptions: class {}, DocumentViewPicker: class {
      save() { return new Promise(resolve => { pickerResolve = resolve; }); }
    } } },
    './DshWebCapabilityPolicy': { DshWebCapabilityPolicy: {
      download: url => url.startsWith('https://dsh-app.yuyutian.top/'), filename: name => name
    } }
  };
  vm.runInNewContext(compiled, { exports: output.exports, require: name => {
    if (!imports[name]) throw new Error('Unexpected import ' + name);
    return imports[name];
  }, $r: value => value, Date });
  const bridge = new output.exports.DshDownloadBridge({ cacheDir: '/cache' },
    { setDownloadDelegate: value => { delegate = value; } }, () => 'https://dsh-app.yuyutian.top/',
    () => '', () => active, value => notices.push(value));
  const item = id => ({ cancelled: false, started: '', getGuid: () => id,
    getUrl: () => 'https://dsh-app.yuyutian.top/export', getSuggestedFileName: () => 'test.zip',
    start(path) { this.started = path; }, cancel() { this.cancelled = true; } });
  return { bridge, delegate, item, writes, removed, notices, closed,
    opened: () => openResolve({ fd: 4 }),
    select: uris => pickerResolve(uris), deactivate: () => { active = false; } };
}

(async () => {
  const ok = setup(), a = ok.item('a');
  ok.delegate.before(a);
  assert.match(a.started, /^\/cache\/dsh-download-.*\.tmp$/);
  const busy = ok.item('busy'); ok.delegate.before(busy); assert.equal(busy.cancelled, true);
  ok.delegate.finish(a); ok.select(['file://selected/test.zip']); await tick();
  assert.equal(ok.writes.length, 1); assert.equal(ok.removed.length, 1);
  assert.equal(ok.notices.at(-1), 'app.string.dsh_web_download_saved');

  const cancelled = setup(), b = cancelled.item('b'); cancelled.delegate.before(b);
  cancelled.delegate.finish(b); cancelled.select([]); await tick();
  assert.equal(cancelled.writes.length, 0); assert.equal(cancelled.removed.length, 1);

  const stale = setup(), c = stale.item('c'); stale.delegate.before(c); stale.delegate.finish(c);
  stale.bridge.close(); stale.deactivate(); stale.select(['file://selected/late.zip']); await tick();
  assert.equal(c.cancelled, true); assert.equal(stale.writes.length, 0);
  assert.equal(stale.removed.length, 1);

  // 保存位置返回后打开文件仍可能较慢，离页必须阻止接下来的复制并关闭句柄。
  const opening = setup(true), delayed = opening.item('delayed');
  opening.delegate.before(delayed); opening.delegate.finish(delayed);
  opening.select(['file://selected/late-open.zip']); await tick();
  opening.bridge.close(); opening.deactivate(); opening.opened(); await tick();
  assert.equal(opening.writes.length, 0); assert.equal(opening.removed.length, 1);
  assert.deepEqual(opening.closed, [4]);

  const retry = setup(), old = retry.item('old'); retry.delegate.before(old); retry.bridge.close();
  const fresh = retry.item('fresh'); retry.delegate.before(fresh);
  retry.delegate.failed(old); retry.delegate.finish(old);
  retry.delegate.finish(fresh); retry.select(['file://selected/new.zip']); await tick();
  assert.equal(retry.writes.length, 1); assert.equal(fresh.cancelled, false);

  const denied = setup(), external = denied.item('external'); external.getUrl = () => 'https://evil.test/file';
  denied.delegate.before(external); assert.equal(external.cancelled, true); assert.equal(external.started, '');
  console.log('PASS: download completion, cancellation, leaving page, stale callbacks, and origin rejection');
})().catch(error => { console.error(error); process.exitCode = 1; });
