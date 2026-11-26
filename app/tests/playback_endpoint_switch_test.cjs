const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const raw = fs.readFileSync(path.join(__dirname, '../entry/src/main/ets/pages/Index.ets'), 'utf8');
const start = raw.indexOf('  private ReconnectPlaybackAfterEndpointSwitch(');
const end = raw.indexOf('  private async OpenRemoteMedia(', start);
const source = `class Page { ${raw.slice(start, end)} }\nglobalThis.Page = Page;`;
const publicRoot = 'https://mytools.example';
const lanRoot = 'http://192.168.1.2:23201';
const streamPath = '/api/app/v1/media/tickets/' + 'a'.repeat(32);
const tick = async () => { for (let index = 0; index < 30; index++) await Promise.resolve(); };
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
function fixture() {
  const calls = [];
  const tickets = [];
  const timers = new Map(); let timerId = 0;
  const context = vm.createContext({ setTimeout(fn, ms) { fn.delay=ms; assert.ok([4500,10000,15000,30000,60000].includes(ms)); timers.set(++timerId, fn); return timerId; },
    clearTimeout(id) { timers.delete(id); },
    HomeChannelOrigin: { forService: () => '', owns: () => false, playerUrl: () => false },
    DownloadCancellationToken: class { cancel() { this.done = true; } isCancelled() { return !!this.done; } },
    videoRangeTime: async () => 1000,
    AudiobookApi: class {
    constructor(endpoint) { this.endpoint = endpoint; }
    async playback(generation, chapter) {
      tickets.push([this.endpoint, generation, chapter]);
      if (this.constructor.pending) await this.constructor.pending;
      return { url: this.endpoint + '/api/app/v1/audiobook-playback/tickets/' + 'b'.repeat(32) };
    }
  } });
  vm.runInContext(ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText, context);
  const value = new context.Page();
  let auth;
  Object.defineProperty(value, 'authManager', {
    get() { return auth; },
    set(options) {
      auth = { publicBusinessBaseUrl: () => publicRoot, resolveBusinessBaseUrl: url => url,
        sessionRevision: () => 1, endpointRevision: () => 1,
        mediaRoutes: { epoch: 1, preferVideoForNetwork() {} }, ...options };
    }
  });
  Object.assign(value, {
    mediaThumbnailCache: {slots: {pauseBackground() {}}}, authenticated: true, endpointPlaybackRevision: 0, mediaOpenRevision: 0, audiobookPlaybackRevision: 0,
    audiobookVoicePreviewOperationRevision: 0, audiobookVoicePreviewState: 'idle', audiobookVoicePreviewBusy: false,
    mediaOpening: false, currentMediaKind: 'video', currentMediaUrl: publicRoot + streamPath,
    currentMediaName: 'Example', currentMediaPath: '/media/test', currentMediaResumeMs: 0,
    mediaAutoPlay: true, playerDurationMs: 60000, videoController: { start() { calls.push(['video-start']); }, pause() { calls.push(['video-pause']); } },
    SeekVideoTo: ms => calls.push(['seek', ms]), playerTimeMs: 23000, playerState: 'playing', videoExpectedPlaying: true,
    audiobookCurrentChapterIndex: -1, audiobookPlayerState: 'idle', audiobookPlayerTimeMs: 31000,
    audiobookSourceTitle: 'Book', authManager: {}, mediaEndpointReconnectPending: false,
    audiobookEndpointReconnectPending: false,
    currentMediaSize: 0, mediaPlaybackError: '', ActiveServiceUrl: () => publicRoot,
    ClearMediaPreloads: () => calls.push(['clear']), ApplicationContext: () => ({})
  });
  value.audioPlayer = {
    async release() { calls.push(['audio-release']); value.playerState = 'idle'; },
    async open(...args) { calls.push(['audio-open', ...args]); }
  };
  value.audiobookPlayer = {
    async release() { calls.push(['book-release']); value.audiobookPlayerState = 'idle'; },
    async open(...args) { calls.push(['book-open', ...args]); }
  };
  value.audiobookVoicePreviewPlayer = { async release() { calls.push(['preview-release']); } };
  return { value, calls, tickets, context, timers };
}
test('video changes same-gateway ticket origin and retains paused intent and position', () => {
  const { value, calls } = fixture(); value.RemoteVideoPaused();
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot);
  assert.equal(value.currentMediaUrl, lanRoot + streamPath);
  assert.equal(value.mediaAutoPlay, false); assert.equal(value.currentMediaResumeMs, 23000);
  value.RemoteVideoPrepared(60);
  assert.deepEqual(calls, [['clear'], ['seek', 23000], ['video-pause']], 'An explicitly paused video stays paused after reconnect');
});
test('route switch during initial buffering starts the new source and retains a pending resume point', () => {
  const { value, calls } = fixture();
  value.videoExpectedPlaying = false; value.playerTimeMs = 0; value.playerDurationMs = 0; value.currentMediaResumeMs = 9000;
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot);
  value.RemoteVideoPaused(); value.RemoteVideoPrepared(0);
  assert.equal(value.mediaEndpointReconnectPending, true, 'Old source callbacks do not complete a switch');
  value.ReconnectPlaybackAfterEndpointSwitch(lanRoot, publicRoot);
  value.RemoteVideoPrepared(60);
  assert.equal(value.mediaEndpointReconnectPending, false);
  assert.equal(value.mediaAutoPlay, true);
  assert.deepEqual(calls, [['clear'], ['clear'], ['seek', 9000], ['video-start']]);
});
test('a slow catalog video falls back once without changing the active API origin', async () => {
  const { value, timers } = fixture();
  Object.assign(value, { mediaViewerVisible: true, videoStartupTimer: -1, currentMediaTicket: 'a'.repeat(32),
    currentMediaUrl: lanRoot + streamPath, ActiveServiceUrl: () => lanRoot,
    authManager: { publicBusinessBaseUrl: () => publicRoot } });
  value.ScheduleVideoStartupFallback(); assert.equal(timers.size, 1);
  const [id, callback] = [...timers.entries()][0]; timers.delete(id); callback();
  await tick();
  assert.equal(value.currentMediaUrl, publicRoot + streamPath); assert.equal(value.ActiveServiceUrl(), lanRoot);
  assert.equal(value.videoUsingPublicFallback, true); assert.equal(value.TryPublicVideoFallback(), false);
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot);
  assert.equal(value.currentMediaUrl, publicRoot + streamPath, 'Rediscovery cannot bounce the fallback video back');
});
test('ready, paused, closed and untrusted videos cannot be switched by a stale startup timeout', () => {
  const { value, timers } = fixture();
  Object.assign(value, { mediaViewerVisible: true, videoStartupTimer: -1, currentMediaTicket: 'a'.repeat(32),
    currentMediaUrl: lanRoot + streamPath, ActiveServiceUrl: () => lanRoot,
    authManager: { publicBusinessBaseUrl: () => publicRoot } });
  value.ScheduleVideoStartupFallback(); value.RemoteVideoPrepared(60); assert.equal(timers.size, 0);
  value.ScheduleVideoStartupFallback(); value.RemoteVideoPaused(); assert.equal(timers.size, 0);
  assert.equal(value.TryPublicVideoFallback(), false);
  value.mediaAutoPlay = true; value.mediaViewerVisible = false; assert.equal(value.TryPublicVideoFallback(), false);
  value.mediaViewerVisible = true; value.currentMediaUrl = 'https://untrusted.example' + streamPath;
  assert.equal(value.TryPublicVideoFallback(), false);
});
test('external URLs stay untouched while stale preloads are cleared', () => {
  const { value, calls } = fixture(); value.currentMediaUrl = 'https://external.example/audio.mp3';
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot);
  assert.equal(value.currentMediaUrl, 'https://external.example/audio.mp3'); assert.deepEqual(calls, [['clear']]);
});
test('paused audio releases and reopens with the old position and autoplay disabled', async () => {
  const { value, calls } = fixture(); value.currentMediaKind = 'audio'; value.playerState = 'paused';
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot); await tick();
  const opened = calls.find(call => call[0] === 'audio-open');
  assert.equal(opened[1], lanRoot + streamPath); assert.equal(opened[2], 23000); assert.equal(opened[5], false);
  assert.equal(value.mediaEndpointReconnectPending, false);
});
test('an audio selection made during release prevents the old reconnect from opening', async () => {
  const { value, calls } = fixture(); const wait = deferred(); value.currentMediaKind = 'audio';
  value.audioPlayer.release = () => wait.promise;
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot);
  value.mediaOpenRevision++; value.currentMediaUrl = 'https://external.example/new.mp3';
  wait.resolve(); await tick();
  assert.ok(!calls.some(call => call[0] === 'audio-open'));
});
test('a rapid second route switch reconnects audio even while the first release reports idle', async () => {
  const { value, calls } = fixture(); const wait = deferred(); value.currentMediaKind = 'audio';
  value.playerState = 'paused'; let releases = 0;
  value.audioPlayer.release = () => {
    value.playerState = 'idle'; return ++releases === 1 ? wait.promise : Promise.resolve();
  };
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot);
  value.ReconnectPlaybackAfterEndpointSwitch(lanRoot, publicRoot);
  wait.resolve(); await tick();
  const opened = calls.filter(call => call[0] === 'audio-open');
  assert.equal(opened.length, 1); assert.equal(opened[0][1], publicRoot + streamPath);
  assert.equal(opened[0][2], 23000); assert.equal(opened[0][5], false);
});
test('paused audiobook gets a fresh endpoint ticket and resumes at its paused position', async () => {
  const { value, calls, tickets } = fixture(); value.currentMediaUrl = '';
  value.audiobookGeneration = { id: 'generation' }; value.audiobookCurrentChapterIndex = 4;
  value.audiobookPlayerState = 'paused';
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot); await tick();
  assert.deepEqual(tickets, [[lanRoot, 'generation', 4]]);
  const opened = calls.find(call => call[0] === 'book-open');
  assert.ok(opened[1].startsWith(lanRoot + '/api/app/v1/audiobook-playback/tickets/'));
  assert.equal(opened[2], 31000); assert.equal(opened[5], false);
});
test('new audiobook chapter selection prevents an old ticket reply from replacing it', async () => {
  const { value, calls, context } = fixture(); const wait = deferred();
  context.AudiobookApi.pending = wait.promise;
  value.audiobookGeneration = { id: 'generation' }; value.audiobookCurrentChapterIndex = 4;
  value.audiobookPlayerState = 'playing';
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot);
  value.audiobookPlaybackRevision++; value.audiobookCurrentChapterIndex = 5;
  wait.resolve(); await tick(); assert.ok(!calls.some(call => call[0] === 'book-open' || call[0] === 'book-release'));
});
test('active voice preview stops and asks for an explicit replay after switching', async () => {
  const { value, calls } = fixture(); value.audiobookVoicePreviewState = 'playing';
  value.ReconnectPlaybackAfterEndpointSwitch(publicRoot, lanRoot); await tick();
  assert.equal(value.audiobookVoicePreviewOperationRevision, 1);
  assert.ok(calls.some(call => call[0] === 'preview-release')); assert.match(value.audiobookReviewStatus, /试听/);
});
test('both Video surfaces bind live autoplay and normal open paths restore autoplay', () => {
  assert.equal((raw.match(/\.autoPlay\(this\.mediaAutoPlay\)/g) || []).length, 2);
  const detailStart = raw.indexOf('  private async PlayMediaVideoDetail(');
  assert.match(raw.slice(detailStart, raw.indexOf('\n  }', detailStart)), /await this\.OpenRemoteMedia\(/);
  for (const name of ['OpenDriveFile', 'OpenRemoteMedia']) {
    const method = raw.slice(raw.indexOf(`  private async ${name}(`));
    assert.ok(method.indexOf('this.mediaAutoPlay = true;') < method.indexOf('    try {'));
  }
});

test('prepared native audio seeks without playing when the restored session was paused', async () => {
  const playerRaw = fs.readFileSync(path.join(__dirname, '../entry/src/main/ets/features/media/RemoteMediaPlayer.ets'), 'utf8');
  const playerSource = playerRaw.replace(/^import .*;\n/gm, '').replace('export class', 'class') +
    '\nglobalThis.Player = RemoteMediaPlayer;';
  const context = vm.createContext({
    media: { PlaybackSpeed: { SPEED_FORWARD_1_00_X: 1 }, SeekMode: { SEEK_CLOSEST: 1 } },
    MediaSeekPolicy: class {}, RemoteMediaPlaybackUrlPolicy: class {},
    MediaOperationRevisionPolicy: class { current(event, active, same) { return event === active && same; } }
  });
  vm.runInContext(ts.transpileModule(playerSource, {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText, context);
  const value = new context.Player(); const calls = [];
  const native = { setSpeed() {}, seek: position => calls.push(['seek', position]),
    async play() { calls.push(['play']); } };
  value.player = native; value.operationRevision = 2; value.pendingStartMs = 23000;
  value.autoPlayOnPrepared = false; value.state = 'prepared';
  await value.playCurrentPlayer(native, 2);
  assert.deepEqual(calls, [['seek', 23000]]);
  await value.toggle(); assert.deepEqual(calls, [['seek', 23000], ['play']]);
});

test('compare the same bounded range and switch only while this video still prepares', async () => {
  const {value,context} = fixture();
  const direct = 'https://direct.example';
  Object.assign(value, {mediaViewerVisible:true, currentMediaUrl:direct+streamPath, currentMediaTicket:'a'.repeat(32),
    ActiveServiceUrl:()=>direct, authManager:{publicBusinessBaseUrl:()=>publicRoot}});
  context.DownloadCancellationToken = class { cancel(){this.done=true;} isCancelled(){return !!this.done;} };
  const paths=[]; context.videoRangeTime=async url=>{paths.push(url);return url.startsWith(direct) ? 3200 : 700;};
  await value.CompareVideoStartupRoutes(value.currentMediaUrl);
  await tick();
  assert.deepEqual(paths,[direct+streamPath,publicRoot+streamPath,publicRoot+streamPath]);
  assert.equal(value.currentMediaUrl,publicRoot+streamPath);
  value.videoUsingPublicFallback=false; value.currentMediaUrl=direct+streamPath;
  const gate=deferred(); context.videoRangeTime=async url=>{await gate.promise;return url.startsWith(direct)?3200:700;};
  const pending=value.CompareVideoStartupRoutes(value.currentMediaUrl);
  value.RemoteVideoPrepared(60);gate.resolve();await pending;
  assert.equal(value.currentMediaUrl,direct+streamPath);
});
test('unsupported adaptive package falls back to the same original ticket only once', () => {
  const {value}=fixture();
  Object.assign(value, {mediaViewerVisible:true, currentMediaUrl:publicRoot+streamPath+'/master.m3u8',
    currentMediaTicket:'a'.repeat(32), ActiveServiceUrl:()=>publicRoot, authManager:{publicBusinessBaseUrl:()=>publicRoot}});
  assert.equal(value.TryOriginalVideo(),true);
  assert.equal(value.currentMediaUrl,publicRoot+streamPath);
  assert.equal(value.TryOriginalVideo(),false);
});

test('a segmented stream gets its own preparation budget and cancels fallback once ready', () => {
  const {value,timers}=fixture();
  Object.assign(value,{mediaViewerVisible:true,videoStartupTimer:-1,currentMediaTicket:'a'.repeat(32),
    currentMediaUrl:publicRoot+streamPath+'/master.m3u8',ActiveServiceUrl:()=>publicRoot,
    authManager:{publicBusinessBaseUrl:()=>publicRoot}});
  value.ScheduleVideoStartupFallback();
  assert.deepEqual([...timers.values()].map(fn=>fn.delay),[60000]);
  value.RemoteVideoPrepared(542);
  assert.equal(timers.size,0);assert.ok(value.currentMediaUrl.endsWith('/master.m3u8'));
});

test('a stalled direct HLS stream tries its original before a validated public original at the same seek point', async () => {
  const {value,timers,context}=fixture(), direct='https://direct.example', seen=[];
  Object.assign(value,{mediaViewerVisible:true,videoCompareTimer:-1,currentMediaSize:110*1024*1024,
    currentMediaTicket:'a'.repeat(32), currentMediaUrl:direct+streamPath+'/master.m3u8',
    ActiveServiceUrl:()=>direct, authManager:{publicBusinessBaseUrl:()=>publicRoot,
      mediaRoutes:{epoch:1,preferVideoForNetwork:(...v)=>seen.push(v)}}});
  context.DownloadCancellationToken=class { cancel(){this.done=true;} isCancelled(){return !!this.done;} };
  context.videoRangeTime=async url=>{assert.equal(url,publicRoot+streamPath);return 1200;};
  value.WatchDirectVideoProgress();const [id,timer]=[...timers][0];timers.delete(id);
  value.playerTimeMs=24000;timer();await tick();
  assert.equal(value.currentMediaUrl,direct+streamPath);
  assert.equal(seen.length,0);
  value.RemoteVideoPrepared(60);
  const [originalId, originalTimer] = [...timers][0]; timers.delete(originalId); originalTimer(); await tick();
  assert.equal(value.currentMediaUrl,publicRoot+streamPath);assert.equal(value.currentMediaResumeMs,24000);
  assert.equal(value.videoUsingPublicFallback,true);assert.equal(seen.length,1);
});
test('an advancing stream does not probe and a pause cancels the next stall check', () => {
  const {value,timers}=fixture(),direct='https://direct.example';
  Object.assign(value,{mediaViewerVisible:true,videoCompareTimer:-1,currentMediaSize:110*1024*1024,
    currentMediaTicket:'a'.repeat(32),currentMediaUrl:direct+streamPath,ActiveServiceUrl:()=>direct,
    authManager:{publicBusinessBaseUrl:()=>publicRoot,mediaRoutes:{epoch:1}}});
  value.WatchDirectVideoProgress();const [id,fn]=[...timers][0];timers.delete(id);value.playerTimeMs+=9000;fn();
  assert.equal(timers.size,1);assert.equal(value.currentMediaUrl,direct+streamPath);
  value.RemoteVideoPaused();assert.equal(timers.size,0);
});
