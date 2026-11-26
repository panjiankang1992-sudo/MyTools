const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const crypto = require('node:crypto');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const root = path.resolve('app/entry/src/main/ets');
const disk = new Map();
const files = { accessSync: p => disk.has(p), mkdirSync: p => disk.set(p, { size: 0, mtime: 0 }),
  statSync: p => { if (!disk.has(p)) throw Error('missing'); return disk.get(p); },
  renameSync: (a,b) => { disk.set(b,disk.get(a)); disk.delete(a); }, unlinkSync: p => disk.delete(p),
  listFile: async d => [...disk.keys()].filter(k => k.startsWith(d + '/')).map(k => path.basename(k)),
  stat: async p => { asyncStats++; return disk.get(p); }, unlink: async p => disk.delete(p) };
let asyncStats=0;
class Encoder { encodeInto(s) { return new TextEncoder().encode(s); } }
const system = { '@ohos.file.fs': { default: files }, '@ohos.util': { default: { generateRandomUUID: () => crypto.randomUUID() } } };
const modules = new Map();
function load(file) {
  if (file.endsWith('/tools/DigestTool.ets')) return { DigestTool: class { async digestText(s) { return crypto.createHash('sha256').update(s).digest('hex'); } } };
  if (modules.has(file)) return modules.get(file).exports;
  const m = new Module(file,module); modules.set(file,m);
  m.require = id => { if(system[id]) return system[id]; if(id.startsWith('.')) return load(path.resolve(path.dirname(file),id+'.ets')); throw Error(id); };
  m._compile(ts.transpileModule(fs.readFileSync(file,'utf8'),{ compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,file);
  return m.exports;
}
const {RemoteMediaThumbnailCache} = load(root+'/features/media/RemoteMediaThumbnailCache.ets');
const {DownloadCancellationToken:Token} = load(root+'/shared/network/DownloadCancellationToken.ets');
const tick=()=>new Promise(r=>setTimeout(r,5));
const pending=[];let downloads=0,aborts=0;
const api={downloadThumbnail:(_owner,_path,target,token)=>new Promise((resolve,reject)=>{
  downloads++; token.bind(()=>{aborts++;reject(Error('aborted'));});
  pending.push(()=>{ disk.set(target,{size:100,mtime:Date.now()});resolve(100);});
}),downloadPreviewImage:async(_owner,_path,target)=>{ disk.set(target,{size:200,mtime:Date.now()});return 200; }};
const source=fs.readFileSync(root+'/pages/Index.ets','utf8');
function method(name) {const m=new RegExp(`  private (?:async )?${name}\\(`).exec(source);assert.ok(m,name);return source.slice(m.index,source.indexOf('\n  }',m.index)+4);}
const vm=require('node:vm');const ctx={console,setTimeout,clearTimeout,DownloadCancellationToken:Token,RemoteMediaApi:class {}};vm.createContext(ctx);
vm.runInContext(ts.transpileModule(`class Page { ${['CreateRemotePreloadDescriptor','ResolveRemotePlaybackDescriptor','MediaPreloadKey','RememberMediaPreload','UpgradeCurrentRemoteImage','StartAdjacentMediaPreload','DrainAdjacentMediaPreload','CancelMediaPreloads','UpdateAdjacentMediaPreviews','ChangeRemoteMedia','CacheMediaCatalogThumbnails'].map(method).join('\n')} }; globalThis.Page=Page;`,{compilerOptions:{target:ts.ScriptTarget.ES2020}}).outputText,ctx);
(async()=>{
 const {MediaImageSlots} = load(path.join(root,'features/media/MediaImageSlots.ets'));
 const slots=new MediaImageSlots(), starts=[];
 const activeBg=await slots.acquire({level:3},new Token());
 const blocker=new Token();
 const skipped=slots.acquire({level:3},blocker);
 const skippedRejected=assert.rejects(skipped,/cancelled/);
 const nextBg=slots.acquire({level:2},new Token()).then(release=>{starts.push('next');release();});
 const foreground=await slots.acquire({level:0},new Token()); starts.push('visible'); foreground();
 blocker.cancel(); await skippedRejected;
 slots.pauseBackground(true); activeBg(); await tick(); assert.deepEqual(starts,['visible']);
 slots.pauseBackground(false); await nextBg; assert.deepEqual(starts,['visible','next']);
 const cache=new RemoteMediaThumbnailCache(),context={cacheDir:'/cache'},a=new Token(),b=new Token();
 const first=cache.cache(context,api,'library','/same',a),second=cache.cache(context,api,'library','/same',b);
 const rejected=assert.rejects(first,/cancelled/);await tick();assert.equal(downloads,1);
 a.cancel();await rejected;assert.equal(aborts,0,'One consumer cannot abort another');
 pending.shift()();const uri=await second;assert.ok(uri.startsWith('file://'));assert.equal(aborts,0);
 assert.equal(await cache.cache(context,api,'library','/same',new Token()),uri);assert.equal(downloads,1);
 const c=new Token();const last=cache.cache(context,api,'library','/cancel',c);const lastRejected=assert.rejects(last,/cancelled/);await tick();c.cancel();await lastRejected;await tick();assert.equal(aborts,1);
 assert.ok((await cache.cachePreview(context,api,'library','/preview',new Token())).startsWith('file://'));
 for(let i=0;i<520;i++)disk.set('/cache/remote-media-thumbnails/old-'+i+'.jpg',{size:100,mtime:i});
 asyncStats=0;await cache.trim('/cache/remote-media-thumbnails',uri.slice(7));assert.equal(asyncStats,521,'Eviction reads each timestamp once');
 assert.equal([...disk.keys()].filter(k=>k.includes('/remote-media-thumbnails/') && k.endsWith('.jpg')).length,512);

 const page=new ctx.Page();Object.assign(page,{mediaPreloadDescriptors:new Map(),mediaPreloadTasks:new Map(),mediaPreloadPolicy:{reusable:()=>true},BrowseMediaThumbnailUri:()=> 'file:///catalog.jpg'});
 const item={path:'/media/test',kind:'image'},sourceItem={id:'library',type:'MYTOOLS_LIBRARY'};
 assert.equal((await page.ResolveRemotePlaybackDescriptor({},sourceItem,item,new Token())).url,'file:///catalog.jpg','Opening uses visible cache without another request');
 page.BrowseMediaThumbnailUri=()=>'';let fresh=0;page.mediaPreloadTasks.set('7:library/media/test',new Promise(()=>{}));
 page.CreateRemotePreloadDescriptor=async()=>{fresh++;return {url:'file:///fresh.jpg'};};
 assert.equal((await page.ResolveRemotePlaybackDescriptor({},sourceItem,item,new Token())).url,'file:///fresh.jpg','Foreground cannot wait behind a queued preload');assert.equal(fresh,1);
 let upgrades=0;Object.assign(page,{getUIContext:()=>({getHostContext:()=>({})}),mediaThumbnailCache:{cachePreview:async()=>{upgrades++;return 'file:///hd.jpg';}},mediaOpenRevision:1,mediaViewerDragging:false,currentMediaPath:item.path,currentMediaKind:'image'});
 const oldUpgrade=page.UpgradeCurrentRemoteImage({},sourceItem,item,1);page.activeImageUpgradeCancellation.cancel();await oldUpgrade;assert.equal(upgrades,0,'Fast swipe does not fetch high-resolution image');
 await page.UpgradeCurrentRemoteImage({},sourceItem,item,1);assert.equal(upgrades,1);assert.equal(page.currentMediaUrl,'file:///hd.jpg');
 page.mediaViewerHandoffUri='';await page.UpgradeCurrentRemoteImage({},sourceItem,item,1);
 assert.equal(page.mediaViewerHandoffUri,'','Already displayed HD source cannot leave a permanent cover');
 page.mediaOpenRevision=2;await page.UpgradeCurrentRemoteImage({},sourceItem,item,1);assert.equal(upgrades,2,'Stale image cannot start an upgrade');
 const sequence=Array.from({length:12},(_,i)=>({path:'/item/'+i,kind:'image'}));
 const view=new ctx.Page();let running=0,peak=0,started=[];const finishes=[];
 Object.assign(view,{mediaPreloadDescriptors:new Map(),mediaPreloadTasks:new Map(),mediaPreloadRevision:0,
   mediaPreloadWorkers:0,mediaPreloadQueue:[],mediaPreloadSourceId:'',mediaPreloadPolicy:{reusable:()=>true,
   neighborIndexes:(_size,index)=>[index+1,index-1,index+2,index-2].filter(i=>i>=0&&i<12)},
   IsCatalogMediaPath:()=>false,VisualMediaViewerSequence:()=>sequence,UpdateAdjacentMediaPreviews:()=>{},ActiveServiceUrl:()=>'',
   CreateRemotePreloadDescriptor:async(_api,_source,value,token)=>{started.push(value.path);running++;peak=Math.max(peak,running);
     return new Promise(resolve=>finishes.push(()=>{running--;resolve({url:'file://'+value.path});}));}});
 view.StartAdjacentMediaPreload(sourceItem,sequence[3]);const sharedToken=view.activeMediaPreloadCancellation;
 assert.equal(running,2);view.StartAdjacentMediaPreload(sourceItem,sequence[8]);
 assert.equal(view.activeMediaPreloadCancellation,sharedToken,'Swipes keep already running downloads');
 assert.equal(sharedToken.isCancelled(),false);assert.equal(view.mediaPreloadQueue.length,4);
 finishes.shift()();await tick();assert.equal(started[2],'/item/9','Available worker follows the newest window');
 assert.equal(peak,2,'At most two preload workers');
 view.CancelMediaPreloads();for(const finish of finishes.splice(0))finish();await tick();
 assert.equal(view.mediaPreloadWorkers,0);assert.equal(view.mediaPreloadTasks.size,0);
 assert.equal(sharedToken.isCancelled(),true);
 let opened='';Object.assign(view,{mediaOpening:true,mediaOpeningPath:'/item/4',currentMediaPath:'/item/3',
   currentMediaKind:'image',PersistMediaPlaybackProgress:()=>{},OpenRemoteMedia:value=>{opened=value.path;}});
 await view.ChangeRemoteMedia(1);assert.equal(opened,'/item/5','New swipe skips a still loading image');
 // 临时分页末尾会补页后继续前进，不能循环回当前已加载列表的第一张。
 let catalogItems=sequence.slice(0,3);opened='';Object.assign(view,{mediaOpening:false,currentMediaPath:'/item/2',
   mediaOpenRevision:1,IsCatalogMediaPath:()=>true,MediaCatalogHasNextPage:()=>true,
   VisualMediaViewerSequence:()=>catalogItems.slice(),LoadMoreMediaCatalog:async()=>{catalogItems.push(sequence[3]);}});
 await view.ChangeRemoteMedia(1);assert.equal(opened,'/item/3','Append before crossing a temporary page boundary');
 opened='';view.currentMediaPath='/item/3';view.LoadMoreMediaCatalog=async()=>{};
 await view.ChangeRemoteMedia(1);assert.equal(opened,'','Pending pages cannot wrap to the first item');
 view.LoadMoreMediaCatalog=async()=>{view.mediaOpenRevision++;catalogItems.push(sequence[4]);};
 await view.ChangeRemoteMedia(1);assert.equal(opened,'','Stale pagination completion cannot replace a newer selection');
 // 列表与全屏针对同一图片并发预取时，实际共享缓存类只发起一次下载。
 pending.length=0;const beforeShared=downloads;
 ctx.fs=files;ctx.RemoteMediaApi=class {constructor(){return api;}};ctx.MediaCatalogApi=class {};
 const catalog=new ctx.Page();Object.assign(catalog,{ApplicationContext:()=>context,ActiveServiceUrl:()=>'',
   mediaThumbnailCache:cache,mediaCatalogVisibleThumbnails:new Set(),mediaCatalogThumbnailQueue:[{itemId:'shared',thumbnailUrl:'/api/app/v1/media/items/shared/thumbnail'}],
   ReadMediaCatalogImageSize:async()=>{},mediaCatalogThumbnailRevision:1,mediaCatalogThumbnailWorkers:1,mediaBrowseThumbnailEntries:[],
   mediaBrowseThumbnailFailedPaths:[],SetMediaBrowseThumbnailEntries(values){this.mediaBrowseThumbnailEntries=values;}});
 const galleryTask=catalog.CacheMediaCatalogThumbnails(1,new Token());
 const opening=new ctx.Page();Object.assign(opening,{BrowseMediaThumbnailUri:()=>'',
   getUIContext:()=>({getHostContext:()=>context}),mediaThumbnailCache:cache});
 const viewerTask=opening.CreateRemotePreloadDescriptor(api,sourceItem,{path:'/media/shared',kind:'image'},new Token());
 await tick();assert.equal(downloads,beforeShared+1,'Gallery and fullscreen use one network request');
 pending.shift()();const descriptor=await viewerTask;await galleryTask;
 assert.equal(catalog.mediaBrowseThumbnailEntries[0].uri,descriptor.url,'Both views publish the same local file');
 console.log('Media image performance behavior tests passed (cache, cancellation, upgrade, concurrency, latest navigation)');
})().catch(e=>{console.error(e);process.exitCode=1;});
