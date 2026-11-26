const assert = require('node:assert/strict');
const fs = require('node:fs');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const moduleValue = { exports: {} };
const compiled = ts.transpileModule(fs.readFileSync(
  'app/entry/src/main/ets/features/media/MediaCatalogPrefetchPolicy.ets', 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText;
new Function('module', 'exports', compiled)(moduleValue, moduleValue.exports);
const Policy = moduleValue.exports.MediaCatalogPrefetchPolicy;
const policy = new Policy();
assert.equal(policy.shouldPrefetch(0, 1000), false);
assert.equal(policy.shouldPrefetch(0, 1000, 6), true, 'Initial buffer fills without a scroll or layout event');
assert.equal(policy.shouldPrefetch(0, 1000, 23), true);
assert.equal(policy.shouldPrefetch(0, 1000, 24), false, 'Unknown viewport cannot drain the library');
policy.updateViewport(600); policy.updateContent(6000, 60);
assert.equal(policy.shouldPrefetch(3600, 1000, 60), false, 'Three screens already reserved');
assert.equal(policy.shouldPrefetch(3601, 1000, 60), true, 'Refill before entering the three-screen reserve');
policy.observeScroll(0, 1000); policy.observeScroll(100, 1100);
assert.equal(policy.shouldPrefetch(3100, 1100, 60), true, 'Fast scrolling starts earlier');
assert.equal(policy.shouldPrefetch(3100, 1800, 60), false, 'Idle speed expires');
policy.observeScroll(50, 1150);
assert.equal(policy.shouldPrefetch(3100, 1150, 60), false, 'Upward scrolling drops extra reserve');
const slow = new Policy(), fast = new Policy();
for (const p of [slow, fast]) {
  p.updateViewport(600); p.updateContent(6000, 60); p.observeScroll(0, 1000); p.observeScroll(20, 1100);
}
for (let i = 0; i < 8; i++) { slow.recordRequest(3000); fast.recordRequest(100); }
assert.equal(slow.shouldPrefetch(3400, 1100, 60), true, 'Slow requests need earlier prefetch');
assert.equal(fast.shouldPrefetch(3400, 1100, 60), false);
slow.observeScroll(420, 1200); slow.recordRequest(1000000);
assert.equal(slow.shouldPrefetch(1800, 1200, 60), false, 'Reserve is capped at six screens');
assert.equal(slow.shouldPrefetch(1801, 1200, 60), true);
slow.resetScroll(); assert.equal(slow.shouldPrefetch(3400, 1200, 60), false);
assert.equal(slow.shouldPrefetch(NaN, 1200, 60), false);
slow.updateViewport(NaN); assert.equal(slow.shouldPrefetch(3500, 1200, 60), false);
const stale = new Policy(); stale.updateViewport(600); stale.updateContent(600, 6);
assert.equal(stale.shouldPrefetch(0, 1000, 24), false, 'Stale layout must account for already appended items');
assert.equal(stale.shouldPrefetchViewer(6, 0), true);
assert.equal(stale.shouldPrefetchViewer(30, 5), false, 'Twenty-four future items are already available');
assert.equal(stale.shouldPrefetchViewer(30, 6), true);
assert.equal(stale.shouldPrefetchViewer(30, -1), false);
assert.equal(stale.shouldPrefetchViewer(30, 30), false);
const source = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const view = source.slice(source.indexOf('  private MediaPage()'), source.indexOf('  private MediaCatalogToolbar()'));
assert.ok(!view.includes('media_load_more') && !view.includes('this.LoadMoreMediaCatalog()'), 'No manual load-more control');
assert.ok(view.includes('.onDidScroll(') && view.includes('updateViewport(') && view.includes('updateContent('));
const directories = source.slice(source.indexOf('  private MediaCatalogVideos()'), source.indexOf('  private MediaCatalogTopItem('));
assert.ok(directories.includes('ForEach(this.MediaCatalogDirectoryItems(directory.directoryId)'),
  'Video and audio batches must increase rendered height instead of draining a fixed three-preview layout');
console.log('Media catalog adaptive prefetch policy tests passed');
