const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const source = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
function method(name) {
  const match = new RegExp(`  private (?:async )?${name}\\(`).exec(source);
  assert.ok(match, name);
  const end = source.indexOf('\n  }', match.index);
  return source.slice(match.index, end + 4);
}
const methods = ['LoadMediaCatalog', 'LoadMoreMediaCatalog', 'ApplyMediaCatalogPage', 'GroupLoadedMediaDirectories',
  'ScheduleMediaCatalogPrefetch', 'CancelMediaCatalogPrefetch', 'ActiveMediaCatalogItems', 'MediaCatalogHasNextPage', 'RetryMediaCatalog', 'RefreshMediaCatalog',
  'OpenMediaVideoDirectory', 'LeaveMediaVideoDirectory', 'PrepareMediaCatalogThumbnails',
  'ResetMediaCatalogThumbnailQueue', 'QueueMediaCatalogThumbnail', 'CacheMediaCatalogThumbnails',
  'RestoreMediaCatalogThumbnail', 'SetMediaCatalogThumbnailVisible','RefreshAfterEndpointSwitch'];
class Token { cancel() { this.cancelled = true; } isCancelled() { return !!this.cancelled; } }
let request;
let download;
const calls = [];
class Api {
  async browsePage(...args) { calls.push(args); return request(...args); }
  async downloadImage(...args) { return download(...args); }
}
const disk = new Set(['/cache/media-catalog-thumbnails']);
let unique = 0;
const context = { console: { info() {} }, RemoteMediaApi: class {}, MediaCatalogApi: Api, DownloadCancellationToken: Token, setTimeout, clearTimeout, Error,
  fs: { accessSync: path => disk.has(path), statSync: () => ({ size: 8 }), mkdirSync: path => disk.add(path),
    unlinkSync: path => disk.delete(path), renameSync: (from, to) => { disk.delete(from); disk.add(to); } },
  util: { generateRandomUUID: () => String(++unique) } };
vm.createContext(context);
const prefetchSource = fs.readFileSync('app/entry/src/main/ets/features/media/MediaCatalogPrefetchPolicy.ets', 'utf8');
vm.runInContext(ts.transpileModule(prefetchSource.replace('export class', 'class') +
  '\nglobalThis.MediaCatalogPrefetchPolicy = MediaCatalogPrefetchPolicy;', {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText, context);
vm.runInContext(ts.transpileModule(`class Page { ${methods.map(method).join('\n')} }; globalThis.Page = Page;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText, context);
function item(index, directoryId = 'day') {
  return { itemId: `item-${index}`, directoryId, directoryName: directoryId, sizeBytes: 10,
    modifiedAt: '', thumbnailUrl: `/thumbnail/${index}`, kind: 'IMAGE', name: `name-${index}`, tags: [] };
}
function batch(start = 0, count = 6, total = 18, page = 1) {
  return { items: Array.from({ length: count }, (_, i) => item(start + i)), total, page, pageSize: 6,
    filters: { directories: [{ directoryId: 'day', name: 'day' }], tags: [] } };
}
function page(thumbnails = false) {
  const p = new context.Page();
  Object.assign(p, { authenticated: true, currentIndex: 0, mediaViewerVisible: false,
    mediaCatalogPrefetchTimer: -1, mediaCatalogPrefetchPolicy: new context.MediaCatalogPrefetchPolicy(), mediaCatalogMode: 'gallery', mediaCatalogKeyword: '',
    mediaCatalogTag: '', hideAdultContent: false, mediaCatalogDirectoryId: '', mediaCatalogFilterContext: '',
    mediaCatalogQueryContext: '', mediaCatalogPage: 1, mediaCatalogTotal: 0, mediaCatalogHasMore: false,
    mediaDirectoryPage: 1, mediaDirectoryTotal: 0, mediaDirectoryHasMore: false,
    mediaCatalogFilters: { directories: [], tags: [] }, mediaCatalogItems: [], mediaVideoDirectories: [],
    selectedVideoDirectoryId: '', selectedVideoDirectoryName: '', mediaVideoDirectoryItems: [],
    mediaThumbnailCache: { promoteCatalog() {} }, mediaCatalogThumbnailRevision: 0, mediaCatalogThumbnailQueue: [], mediaCatalogThumbnailQueued: new Set(), mediaCatalogVisibleThumbnails: new Set(),
    mediaCatalogThumbnailWorkers: 0, mediaBrowseThumbnailEntries: [], mediaBrowseThumbnailFailedPaths: [],
    mediaItems: [], mediaDirectoryItems: [], mediaCatalogThumbnailAccepting: false,
    mediaGalleryLayoutPolicy: { reconcile: () => [] }, mediaGalleryPlacements: [], ReadMediaCatalogImageSize: async () => {},
    ApplicationContext: () => ({ cacheDir: '/cache' }),
    SetMediaBrowseThumbnailEntries(entries) { this.mediaBrowseThumbnailEntries = entries; },
    CatalogRemoteItem: value => ({ path: `/media/${value.itemId}` }),
    ActiveServiceUrl: () => '', MediaCatalogUsesGrid() { return this.mediaCatalogMode === 'gallery'; },
    tabScrollers: [null, null, null, { currentOffset: () => ({ yOffset: 50 }), scrollTo() {} }] });
  if (!thumbnails) p.PrepareMediaCatalogThumbnails = () => {};
  return p;
}
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject }; }
const tick = () => new Promise(resolve => setTimeout(resolve, 10));
async function main() {
  for (const mode of ['gallery', 'video', 'audio']) {
    calls.length = 0;
    request = (_mode, _dir, _tag, _word, number) => batch((number - 1) * 6, 6, 18, number);
    const p = page(); p.mediaCatalogMode = mode;
    await p.LoadMediaCatalog();
    assert.equal(calls.length, 1, 'First paint must require only one request');
    assert.equal(p.ActiveMediaCatalogItems().length, 6);
    assert.equal(p.mediaCatalogLoading, false);
    assert.equal(p.mediaCatalogHasMore, true);
    p.ScheduleMediaCatalogPrefetch(); await tick();
    assert.equal(calls.length, 1, 'Hidden pages must not prefetch');
    await p.LoadMoreMediaCatalog();
    assert.equal(p.ActiveMediaCatalogItems().length, 12);
    assert.equal(calls[1][4], 2);
    if (mode !== 'gallery') {
      assert.equal(p.mediaVideoDirectories[0].fileCount, 12);
      assert.equal(p.mediaVideoDirectories[0].topItems.length, 3);
      await p.OpenMediaVideoDirectory(p.mediaVideoDirectories[0]);
      assert.equal(calls.at(-1)[1], 'day', 'Audio must query only the selected directory');
      assert.equal(p.mediaVideoDirectoryItems.length, 6);
      await p.LoadMoreMediaCatalog();
      assert.equal(p.mediaVideoDirectoryItems.length, 12);
      p.LeaveMediaVideoDirectory();
      assert.equal(p.mediaCatalogPage, 2, 'Returning must preserve root pagination');
      assert.equal(p.mediaCatalogItems.length, 12);
    }
    await p.LoadMoreMediaCatalog();
    assert.equal(p.mediaCatalogHasMore, false);
    const count = calls.length; await p.LoadMoreMediaCatalog(); assert.equal(calls.length, count);
  }
  const p = page(); request = () => batch(); await p.LoadMediaCatalog();
  request = () => { throw new Error('offline'); }; await p.LoadMoreMediaCatalog();
  assert.equal(p.mediaCatalogItems.length, 6); assert.equal(p.mediaCatalogPage, 1);
  assert.equal(p.mediaCatalogStatus, 'offline');
  request = () => batch(6, 6, 18, 2); p.RetryMediaCatalog(); await tick();
  assert.equal(p.mediaCatalogPage, 2); assert.equal(p.mediaCatalogStatus, '');
  request = () => batch(12, 0, 18, 3); await p.LoadMoreMediaCatalog();
  assert.equal(p.mediaCatalogHasMore, false, 'An empty page cannot cause an infinite request loop');
  request = () => batch(); await p.LoadMediaCatalog();
  request = () => batch(0, 6, 18, 2); await p.LoadMoreMediaCatalog();
  assert.equal(p.mediaCatalogItems.length, 6); assert.equal(p.mediaCatalogHasMore, false);
  const stale = deferred(); request = () => stale.promise;
  const oldLoad = p.LoadMediaCatalog(); const oldToken = calls.at(-1).at(-1);
  p.mediaCatalogTag = 'new'; request = () => batch(100, 1, 1); await p.LoadMediaCatalog();
  stale.resolve(batch()); await oldLoad;
  assert.equal(oldToken.isCancelled(), true); assert.equal(p.mediaCatalogItems[0].itemId, 'item-100');
  const pending = deferred(); request = () => pending.promise;
  p.mediaCatalogHasMore = true; p.mediaCatalogTotal = 18; const more = p.LoadMoreMediaCatalog();
  const before = calls.length; await p.LoadMoreMediaCatalog(); assert.equal(calls.length, before);
  p.mediaCatalogTag = 'fresh'; request = () => batch(200, 1, 1); await p.LoadMediaCatalog();
  pending.resolve(batch(6, 6, 18, 2)); await more;
  assert.equal(p.mediaCatalogItems[0].itemId, 'item-200', 'Stale append cannot cross filters');
  // 打开中的目录直接刷新本目录，不依赖它是否出现在根目录首批中。
  p.selectedVideoDirectoryId = 'older-day'; p.mediaCatalogMode = 'audio';
  request = () => batch(300, 1, 1); await p.LoadMediaCatalog(true);
  assert.equal(p.mediaVideoDirectoryItems[0].itemId, 'item-300');
  assert.equal(calls.at(-1)[1], 'older-day');
  const leaving = deferred(); request = () => leaving.promise; const entering = p.LoadMediaCatalog();
  p.LeaveMediaVideoDirectory(); leaving.resolve(batch(400, 1, 1)); await entering;
  assert.equal(p.mediaVideoDirectoryItems.length, 0); assert.equal(p.mediaCatalogLoading, false);

  // 自动补屏、接近底部预取和停止缓冲均通过实际分页方法验证。
  const automatic = page(); automatic.currentIndex = 3; let offset = 0;
  automatic.tabScrollers[3].currentOffset = () => ({ yOffset: offset });
  automatic.mediaCatalogPrefetchPolicy.updateViewport(600);
  const apply = automatic.ApplyMediaCatalogPage.bind(automatic);
  automatic.ApplyMediaCatalogPage = (value, append) => {
    apply(value, append);
    automatic.mediaCatalogPrefetchPolicy.updateContent(automatic.ActiveMediaCatalogItems().length * 100, automatic.ActiveMediaCatalogItems().length);
  };
  let autoCalls = 0;
  request = (_mode, _dir, _tag, _word, number) => { autoCalls++; return batch((number - 1) * 6, 6, 600, number); };
  await automatic.LoadMediaCatalog();
  assert.equal(automatic.mediaCatalogItems.length, 6, 'First batch paints before prefetch');
  await new Promise(resolve => setTimeout(resolve, 180));
  assert.equal(autoCalls, 4, 'Initial view fills twenty-four items without scrolling');
  assert.equal(automatic.mediaCatalogItems.length, 24);
  offset = 400;
  for (let i = 0; i < 20; i++) automatic.ScheduleMediaCatalogPrefetch();
  await new Promise(resolve => setTimeout(resolve, 180));
  assert.equal(autoCalls, 5, 'Refill starts with multiple screens remaining and coalesces callbacks');
  offset = 1600; request = () => { autoCalls++; throw new Error('offline'); };
  automatic.ScheduleMediaCatalogPrefetch();
  await new Promise(resolve => setTimeout(resolve, 180));
  assert.equal(autoCalls, 6); assert.equal(automatic.mediaCatalogItems.length, 30);
  automatic.ScheduleMediaCatalogPrefetch();
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(autoCalls, 6, 'An error cannot create an automatic retry loop');
  automatic.mediaCatalogStatus = ''; automatic.ScheduleMediaCatalogPrefetch(); automatic.CancelMediaCatalogPrefetch();
  await new Promise(resolve => setTimeout(resolve, 50)); assert.equal(autoCalls, 6);
  automatic.currentIndex = 0; automatic.ScheduleMediaCatalogPrefetch();
  await new Promise(resolve => setTimeout(resolve, 50)); assert.equal(autoCalls, 6, 'Hidden tabs never prefetch');

  // 全屏使用当前索引补齐后方记录，即使列表已卸载，也不会被旧高度拖入无界预取。
  const viewer = page(); viewer.currentIndex = 3; viewer.mediaViewerVisible = true;
  viewer.currentMediaPath = '/media/item-2'; viewer.IsCatalogMediaPath = value => value.startsWith('/media/');
  viewer.CurrentRemoteMediaItem = () => ({path:viewer.currentMediaPath});
  viewer.CatalogLocalMediaSource = () => ({id:'library'});let windows=0;
  viewer.StartAdjacentMediaPreload = () => { windows++; };
  let viewerCalls=0;
  request=(_mode,_dir,_tag,_word,number)=>{viewerCalls++;return batch((number-1)*6,6,600,number);};
  await viewer.LoadMediaCatalog(); await new Promise(resolve=>setTimeout(resolve,240));
  assert.equal(viewerCalls,5); assert.equal(viewer.mediaCatalogItems.length,30);
  assert.equal(windows,5,'Every appended page updates the viewer prefetch window');
  viewer.currentMediaPath='/media/item-24'; viewer.ScheduleMediaCatalogPrefetch();
  await new Promise(resolve=>setTimeout(resolve,220));assert.equal(viewer.mediaCatalogItems.length,54);
  assert.equal(viewerCalls,9,'Viewer keeps at least twenty-four future items without reaching the end');
  let firstPageReloads=0;viewer.LoadMediaCatalog=()=>{firstPageReloads++;};
  const obsolete=new Token();viewer.activeMediaCatalogCancellation=obsolete;viewer.mediaCatalogLoading=true;
  viewer.RefreshAfterEndpointSwitch();await new Promise(resolve=>setTimeout(resolve,60));
  assert.equal(obsolete.isCancelled(),true);assert.equal(viewer.mediaCatalogLoading,false);
  assert.equal(viewer.mediaCatalogItems.length,54);assert.equal(viewer.currentMediaPath,'/media/item-24');
  assert.equal(firstPageReloads,0,'Changing routes in fullscreen preserves the extended sequence');
  viewer.mediaViewerVisible=false;viewer.RefreshAfterEndpointSwitch();
  await new Promise(resolve=>setTimeout(resolve,60));assert.equal(viewer.mediaCatalogItems.length,54);
  assert.equal(firstPageReloads,0,'Route recovery also preserves the list buffer and pagination cursor');
  viewer.currentIndex=0; viewer.CancelMediaCatalogPrefetch();

  const t = page(true); const gates = []; let inFlight = 0, maximum = 0;
  download = (_path, target) => { const gate = deferred(); gates.push({ ...gate, target });
    maximum = Math.max(maximum, ++inFlight);
    return gate.promise.then(() => { inFlight--; disk.add(target); }); };
  t.PrepareMediaCatalogThumbnails(Array.from({ length: 120 }, (_, i) => item(i)));
  assert.equal(gates.length, 1); assert.equal(t.mediaCatalogThumbnailQueue.length, 23);
  disk.add('/cache/media-catalog-thumbnails/v2-item-110.jpg');
  t.QueueMediaCatalogThumbnail(item(110));
  assert.ok(t.mediaBrowseThumbnailEntries.some(e => e.path === '/media/item-110'), 'Cache bypasses slow downloads');
  t.SetMediaCatalogThumbnailVisible(item(119), true);
  gates[1].resolve(); await tick();
  assert.ok(t.mediaBrowseThumbnailEntries.some(e => e.path === '/media/item-119'), 'Visible image uses reserved capacity');
  assert.equal(gates.length, 2, 'Second worker returns its slot once visible cards are ready');
  for (let i = 0; i < 26; i++) { gates.forEach(g => g.resolve()); await tick(); }
  assert.equal(maximum, 2); assert.equal(gates.length, 25, 'Initial twenty-four and newly visible cards are requested');
  assert.ok(t.mediaBrowseThumbnailEntries.some(e => e.path === '/media/item-119'), 'Items after 90 can load');
  const oldRevision = t.mediaCatalogThumbnailRevision;
  t.PrepareMediaCatalogThumbnails(Array.from({ length: 144 }, (_, i) => item(i)), true);
  assert.equal(t.mediaCatalogThumbnailRevision, oldRevision, 'Appending never cancels thumbnail downloads');
  t.QueueMediaCatalogThumbnail(item(130)); const late = gates.at(-1);
  t.ResetMediaCatalogThumbnailQueue(); late.reject(new Error('cancelled')); await tick();
  assert.equal(t.mediaBrowseThumbnailFailedPaths.length, 0, 'Cancelled workers cannot publish failure states');
  assert.equal(t.mediaCatalogThumbnailWorkers, 0);
  t.QueueMediaCatalogThumbnail(item(131)); assert.equal(gates.at(-1), late, 'Leaving disables new work');
  const resumed = page(true); resumed.mediaCatalogVisibleThumbnails.add('item-720');
  resumed.PrepareMediaCatalogThumbnails(Array.from({ length: 24 }, (_, i) => item(700 + i)));
  assert.ok(gates.at(-1).target.includes('item-720'), 'Refresh prioritizes the still-visible card');
  resumed.ResetMediaCatalogThumbnailQueue(); gates.forEach(g => g.resolve()); await tick();
  const warming = page(true);
  const warmingItems = Array.from({ length: 12 }, (_, i) => item(800 + i));
  warming.PrepareMediaCatalogThumbnails(warmingItems.slice(0, 6));
  warming.PrepareMediaCatalogThumbnails(warmingItems, true, warmingItems.slice(6));
  assert.equal(warming.mediaCatalogThumbnailQueue.length, 11, 'New batch images prefetch before becoming visible');
  warming.SetMediaCatalogThumbnailVisible(warmingItems[11], true);
  assert.ok(gates.at(-1).target.includes('item-811'), 'Visible image acquires the reserved worker immediately');
  warming.ResetMediaCatalogThumbnailQueue(); gates.forEach(g => g.resolve()); await tick();
  const unavailable = page(true); unavailable.ApplicationContext = () => undefined;
  unavailable.PrepareMediaCatalogThumbnails([item(500)]);
  assert.equal(unavailable.mediaCatalogThumbnailWorkers, 0, 'Missing cache context cannot spin forever');
  console.log('Media catalog batching, refresh, cancellation, retry and thumbnail tests passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
