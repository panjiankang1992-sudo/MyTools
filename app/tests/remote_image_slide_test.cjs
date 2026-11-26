const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const source = fs.readFileSync('app/entry/src/main/ets/pages/Index.ets', 'utf8');
const policy = fs.readFileSync('app/entry/src/main/ets/features/media/RemoteImageGesturePolicy.ets', 'utf8').replaceAll('export ', '');
const methods = ['BeginRemoteViewerVerticalSwipe', 'AdjacentMediaSwipeItem', 'UpdateRemoteViewerVerticalSwipe',
  'EndRemoteViewerVerticalSwipe', 'CancelRemoteViewerVerticalSwipe', 'SettleRemoteViewerSwipe',
  'CommitRemoteViewerSwipe', 'ResetRemoteViewerSwipe', 'RemoteViewerImageReady', 'RemoteViewerImageFailed'];
function method(name) {
  const start = new RegExp(`  private (?:async )?${name}\\(`).exec(source).index;
  return source.slice(start, source.indexOf('\n  }', start) + 4);
}
const ctx = vm.createContext({ console, Curve: { EaseOut: 'easeOut' }, $r: () => 'imageError' });
vm.runInContext(ts.transpileModule(`${policy}\nclass Page { ${methods.map(method).join('\n')} }
  globalThis.Page=Page; globalThis.Policy=RemoteImageGesturePolicy;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText, ctx);
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
function fixture() {
  const page = new ctx.Page(), animations = [], opened = [];
  const items = ['a', 'b', 'c'].map(path => ({ path, kind: 'image' }));
  Object.assign(page, { mediaViewerVisible: true, currentMediaKind: 'image', imageScale: 1,
    currentMediaPath: 'b', currentMediaUrl: 'b.jpg', mediaViewerViewportHeight: 800,
    mediaViewerDragging: false, mediaViewerSettling: false, mediaViewerSwipeRevision: 0,
    mediaViewerDragOffsetY: 0, mediaPreviousPreviewUri: 'a.jpg', mediaNextPreviewUri: 'c.jpg',
    imageGesturePolicy: new ctx.Policy(), VisualMediaViewerSequence: () => items,
    IsCatalogMediaPath: () => true, MediaCatalogHasNextPage: () => false,
    ClearMediaViewerChromeTimer() {}, ResumeCurrentImageUpgrade() { this.resumed = true; },
    getUIContext: () => ({ animateTo(options, update) { animations.push(options); update(); } }),
    ReaderUiText: () => 'Image unavailable',
    OpenRemoteMedia: async item => { opened.push(item.path); page.currentMediaPath = item.path; page.currentMediaUrl = item.path + '.jpg'; }
  });
  return { page, animations, opened, items };
}
(async () => {
  const policy = new ctx.Policy();
  assert.equal(policy.pageDragOffset(-400, 800, true), -400, 'Follow the finger beyond the former 220 limit');
  assert.equal(policy.pageDragOffset(-900, 800, true), -800);
  assert.equal(policy.pageDragOffset(400, 800, false), 80, 'Unavailable neighbor has resistance');
  assert.equal(policy.pageDragOffset(NaN, 800, true), 0);
  assert.equal(policy.pageSwipeDirection(0, -60, -900, 1, 800), 1, 'Fast short flick advances');
  assert.equal(policy.pageSwipeDirection(0, -60, 900, 1, 800), 0, 'Velocity must match direction');
  assert.equal(policy.pageSwipeDirection(0, 25, 1500, 1, 800), 0, 'Tiny flick does not advance');
  assert.equal(policy.pageSwipeDirection(0, -100, -30, 1, 800), 0, 'Short slow drag returns');
  assert.equal(policy.pageSwipeDirection(0, 200, 0, 1, 800), -1);
  assert.equal(policy.pageSwipeDirection(200, -200, -900, 1, 800), 0);
  assert.equal(policy.pageSwipeDirection(0, -300, -900, 2, 800), 0);
  const { page: p, animations, opened } = fixture();
  p.BeginRemoteViewerVerticalSwipe(); p.UpdateRemoteViewerVerticalSwipe(0, -350);
  assert.equal(p.mediaViewerDragOffsetY, -350); assert.equal(p.currentMediaPath, 'b');
  assert.equal(p.mediaViewerSlideNextUri, 'c.jpg');
  p.EndRemoteViewerVerticalSwipe(0, -350, -800);
  assert.equal(opened.length, 0, 'Do not replace the image before the slide completes');
  assert.equal(p.mediaViewerDragOffsetY, -800, 'Animation targets a whole screen');
  assert.equal(p.mediaViewerSettling, true);
  p.BeginRemoteViewerVerticalSwipe(); p.EndRemoteViewerVerticalSwipe(0, -350, -800);
  assert.equal(animations.length, 1, 'Duplicate gestures cannot double advance');
  animations.shift().onFinish(); await tick();
  assert.deepEqual(opened, ['c']); assert.equal(p.mediaViewerDragOffsetY, 0);
  assert.equal(p.mediaViewerSettling, false); assert.equal(p.mediaViewerHandoffUri, 'c.jpg');
  p.RemoteViewerImageReady('b.jpg', 'b'); assert.equal(p.mediaViewerHandoffUri, 'c.jpg');
  p.RemoteViewerImageReady('c.jpg', 'c'); assert.equal(p.mediaViewerHandoffUri, '');
  const short = fixture(); short.page.BeginRemoteViewerVerticalSwipe();
  short.page.UpdateRemoteViewerVerticalSwipe(0, 60); short.page.EndRemoteViewerVerticalSwipe(0, 60, 20);
  assert.equal(short.page.mediaViewerDragOffsetY, 0); short.animations.shift().onFinish();
  assert.equal(short.opened.length, 0); assert.equal(short.page.resumed, true);
  const cancel = fixture(); cancel.page.BeginRemoteViewerVerticalSwipe();
  cancel.page.UpdateRemoteViewerVerticalSwipe(0, -180); cancel.page.CancelRemoteViewerVerticalSwipe();
  cancel.animations.shift().onFinish(); assert.equal(cancel.opened.length, 0);
  const closed = fixture(); closed.page.BeginRemoteViewerVerticalSwipe();
  closed.page.EndRemoteViewerVerticalSwipe(0, -180, -800);
  closed.page.ResetRemoteViewerSwipe(); closed.page.mediaViewerVisible = false;
  closed.animations.shift().onFinish(); await tick(); assert.equal(closed.opened.length, 0);
  const failed = fixture(); failed.page.OpenRemoteMedia = async () => {};
  failed.page.BeginRemoteViewerVerticalSwipe(); failed.page.EndRemoteViewerVerticalSwipe(0, -180, -800);
  failed.animations.shift().onFinish(); await tick();
  assert.equal(failed.animations.length, 1, 'Failed opening animates back');
  assert.equal(failed.page.mediaViewerDragOffsetY, 0); failed.animations.shift().onFinish();
  assert.equal(failed.page.currentMediaPath, 'b'); assert.equal(failed.page.mediaViewerSettling, false);
  const zoom = fixture(); zoom.page.imageScale = 2; zoom.page.BeginRemoteViewerVerticalSwipe();
  assert.equal(zoom.page.mediaViewerDragging, false);
  const boundary = fixture(); boundary.page.currentMediaPath = 'c'; boundary.page.MediaCatalogHasNextPage = () => true;
  let more = 0; boundary.page.LoadMoreMediaCatalog = () => more++;
  boundary.page.BeginRemoteViewerVerticalSwipe(); boundary.page.UpdateRemoteViewerVerticalSwipe(0, -200);
  assert.equal(boundary.page.mediaViewerDragOffsetY, -40);
  boundary.page.EndRemoteViewerVerticalSwipe(0, -200, -800); boundary.animations.shift().onFinish();
  assert.equal(more, 1); assert.equal(boundary.opened.length, 0, 'Temporary page end never shows or opens the first item');
  const frozen = fixture(); frozen.page.BeginRemoteViewerVerticalSwipe();
  frozen.items.splice(2, 0, { path: 'inserted', kind: 'image' });
  frozen.page.EndRemoteViewerVerticalSwipe(0, -180, -800); frozen.animations.shift().onFinish(); await tick();
  assert.deepEqual(frozen.opened, ['c'], 'Pagination cannot change the target midway through a gesture');
  const viewer = source.slice(source.indexOf('  private RemoteVisualMediaViewerPage()'), source.indexOf('  private MediaControlsUseDarkSurface()'));
  assert.ok(!viewer.includes('.opacity(') && !viewer.includes('MediaViewerDragProgress'), 'Pages slide without old cross-fade/shrink');
  assert.ok(viewer.includes('.onActionCancel(') && viewer.includes('.clip(true)'));
  assert.ok(source.includes('this.mediaViewerDragOffsetY + direction * this.mediaViewerViewportHeight'));
  console.log('Remote image continuous-slide behavior tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
