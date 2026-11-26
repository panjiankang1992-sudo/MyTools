const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const root = path.resolve(__dirname, '../entry/src/main/ets');
const publicBase = 'https://public.example.test';
const direct = 'https://direct.example.test:8443';
const ticket = 'ab'.repeat(16);
const ticketPath = '/api/app/v1/media/tickets/' + ticket;
const modules = new Map();
let httpResponse;
let httpCalls = [];
const http = { RequestMethod: { GET: 'GET' }, createHttp() {
  const handlers = {};
  let destroyed = false;
  return {
    on(name, fn) { handlers[name] = fn; }, off() {}, destroy() { destroyed = true; },
    async requestInStream(url, options) {
      httpCalls.push({ url, options });
      const response = httpResponse;
      handlers.headersReceive?.(response.headers);
      if (destroyed) throw Error('cancelled');
      handlers.dataReceive?.(new Uint8Array(response.bytes).buffer);
      if (destroyed) throw Error('cancelled');
      return response.status;
    }
  };
} };
function load(relative) {
  const filename = path.resolve(root, relative + '.ets');
  if (modules.has(filename)) return modules.get(filename).exports;
  const compiled = new Module(filename, module);
  modules.set(filename, compiled);
  compiled.require = name => {
    if (name === '@kit.NetworkKit') return { http };
    if (name.endsWith('/auth/AuthApi')) return { AuthApi: {
      directAddressFamily: (base, publicUrl) => base.startsWith('https://') && base !== publicUrl ? 'CURL_IPRESOLVE_V6' : undefined
    } };
    assert.ok(name.startsWith('.'), 'Unexpected platform dependency');
    return load(path.relative(root, path.resolve(path.dirname(filename), name)));
  };
  compiled._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText, filename);
  return compiled.exports;
}
const { HomeChannelOrigin } = load('features/connectivity/HomeChannelOrigin');
const { DownloadCancellationToken } = load('shared/network/DownloadCancellationToken');
const { MediaTransferRoutes } = load('shared/network/MediaTransferRoutes');
const { videoRangeTime } = load('features/media/MediaVideoProbe');
let nextTimer = 1;
const timers = new Map();
let probes = [];
let measured = [];
function probe(url, token, initial, publicUrl) {
  measured.push({ url, initial, publicUrl });
  const value = probes.shift();
  assert.notEqual(value, undefined, 'Unexpected range probe');
  return typeof value === 'function' ? value(token) : Promise.resolve(value);
}
const page = fs.readFileSync(path.join(root, 'pages/Index.ets'), 'utf8');
const names = ['TrustedVideoOrigin', 'VideoOperationCurrent', 'VideoConnectionFailed', 'TryOriginalVideo',
  'TryPublicVideoFallback', 'ScheduleVideoStartupFallback', 'CompareVideoStartupRoutes',
  'WatchDirectVideoProgress', 'CancelVideoStartupTimer', 'ReconnectRemoteVideo'];
const methods = names.map(name => {
  const start = page.indexOf('  private ' + name + '(') >= 0 ? page.indexOf('  private ' + name + '(') :
    page.indexOf('  private async ' + name + '(');
  assert.ok(start >= 0, 'Missing production method');
  return page.substring(start, page.indexOf('\n  }\n', start) + 5);
}).join('\n');
const contextSource = 'class VideoPage {\n' + methods + '\n}\nmodule.exports = VideoPage;';
const compiled = new Module('video-page-methods', module);
compiled.require = () => { throw Error('Unexpected dependency'); };
const transpiled = ts.transpileModule(contextSource, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText;
const execute = new Function('module', 'HomeChannelOrigin', 'DownloadCancellationToken', 'videoRangeTime',
  'setTimeout', 'clearTimeout', transpiled);
execute(compiled, HomeChannelOrigin, DownloadCancellationToken, probe, (fn, delay) => {
  const id = nextTimer++;
  timers.set(id, { fn, delay });
  return id;
}, id => timers.delete(id));
const VideoPage = compiled.exports;
let state;
let channel;
function subject() {
  HomeChannelOrigin.close();
  channel = HomeChannelOrigin.open(34201, 'cd'.repeat(16), publicBase);
  HomeChannelOrigin.activate(channel);
  state = { session: 1, endpoint: 1 };
  timers.clear(); probes = []; measured = [];
  const auth = {
    publicBusinessBaseUrl: () => publicBase,
    resolveBusinessBaseUrl: () => HomeChannelOrigin.forService(publicBase) || direct,
    sessionRevision: () => state.session, endpointRevision: () => state.endpoint,
    mediaRoutes: new MediaTransferRoutes()
  };
  const page = new VideoPage();
  Object.assign(page, { authManager: auth, authenticated: true, mediaViewerVisible: true,
    currentMediaKind: 'video', currentMediaPath: '/media/fixture', currentMediaTicket: ticket,
    currentMediaUrl: channel + ticketPath, currentMediaSize: 112 * 1024 * 1024,
    mediaAutoPlay: true, videoRetriedDirect: false, videoUsingPublicFallback: false,
    videoStartedOnPreferredPublic: false, mediaOpenRevision: 1, videoSeekRevision: 0,
    playerTimeMs: 49000, playerDurationMs: 540000, currentMediaResumeMs: 0,
    mediaEndpointReconnectPending: false, videoPreparingAt: 0, videoFallbackUrl: '',
    videoStartupTimer: -1, videoCompareTimer: -1, videoCompareToken: undefined,
    mediaPlaybackError: '', ActiveServiceUrl: () => direct,
    StopHomeChannel() { throw Error('A video failure closed the business channel'); }
  });
  return page;
}
function tick(delay) {
  const hit = [...timers].find(([, timer]) => timer.delay === delay);
  assert.ok(hit, 'Expected a bounded video timer');
  timers.delete(hit[0]); hit[1].fn();
}
async function settle() { for (let i = 0; i < 12; i++) await Promise.resolve(); }
(async () => {
  let page = subject();
  for (const variant of ['/mobile.mp4', '/master.m3u8']) {
    page.currentMediaUrl = channel + ticketPath + variant;
    assert.equal(page.TryOriginalVideo(), true);
    assert.equal(page.currentMediaUrl, channel + ticketPath);
    assert.equal(page.currentMediaResumeMs, 49000);
    assert.equal(HomeChannelOrigin.forService(publicBase), channel);
  }
  page = subject(); page.ScheduleVideoStartupFallback();
  assert.ok([...timers.values()].some(timer => timer.delay === 30000));
  probes.push(120); tick(30000); await settle();
  assert.equal(page.currentMediaUrl, publicBase + ticketPath);
  assert.equal(page.currentMediaResumeMs, 49000);
  assert.equal(page.mediaAutoPlay, true);
  assert.equal(HomeChannelOrigin.forService(publicBase), channel);

  page = subject(); probes.push(100); page.WatchDirectVideoProgress(); tick(10000); await settle();
  assert.equal(page.currentMediaUrl, publicBase + ticketPath);
  assert.equal(page.videoUsingPublicFallback, true);
  assert.equal(page.currentMediaResumeMs, 49000);
  assert.equal(measured[0].initial, true);

  page = subject(); page.currentMediaUrl += '/master.m3u8';
  page.WatchDirectVideoProgress(); tick(10000);
  assert.equal(page.currentMediaUrl, channel + ticketPath);
  assert.equal(measured.length, 0, 'A stalled variant skipped the same-origin original');

  page = subject(); probes.push(Infinity);
  assert.equal(page.TryPublicVideoFallback(), true); await settle();
  assert.equal(page.currentMediaUrl, channel + ticketPath);
  assert.ok(page.mediaPlaybackError.length > 0, 'Failed validation left the user waiting');
  assert.equal(HomeChannelOrigin.forService(publicBase), channel);

  for (const cancel of [page => state.session++, page => state.endpoint++,
    page => page.authManager.mediaRoutes.clear(), page => page.mediaOpenRevision++,
    page => page.videoSeekRevision++, page => page.mediaAutoPlay = false]) {
    page = subject(); let finish;
    probes.push(() => new Promise(resolve => { finish = resolve; }));
    assert.equal(page.TryPublicVideoFallback(), true);
    assert.equal(page.TryPublicVideoFallback(), true);
    assert.equal(measured.length, 1, 'A duplicate native error restarted a pending fallback');
    cancel(page); finish(100); await settle();
    assert.equal(page.currentMediaUrl, channel + ticketPath, 'Stale validation changed the video');
  }

  for (const preparing of [false, true]) {
    page = subject(); page.videoPreparingAt = preparing ? Date.now() : 0;
    let finish;
    probes.push(() => new Promise(resolve => { finish = resolve; }));
    page.TryPublicVideoFallback(); page.videoSeekRevision++; finish(100); await settle();
    assert.equal(page.currentMediaUrl, channel + ticketPath);
    assert.ok([...timers.values()].some(timer => timer.delay === (preparing ? 30000 : 10000)),
      'Discarding a pre-seek sample removed all bounded playback monitoring');
  }

  page = subject(); probes.push(1000, 100, 100);
  await page.CompareVideoStartupRoutes(page.currentMediaUrl); await settle();
  assert.equal(page.currentMediaUrl, publicBase + ticketPath);
  assert.equal(measured.length, 3);
  assert.equal(measured[2].initial, true, 'Early comparison bypassed full fallback validation');
  for (const bad of [channel + ticketPath.replace(ticket, 'ef'.repeat(16)), channel + ticketPath + '?other=1',
    channel.replace('cd'.repeat(16), 'ef'.repeat(16)) + ticketPath, 'http://127.0.0.1:34202/a' + ticketPath]) {
    page = subject(); page.currentMediaUrl = bad;
    assert.equal(page.TryOriginalVideo(), false); assert.equal(page.TryPublicVideoFallback(), false);
  }

  for (const faster of ['channel', 'public']) {
    subject(); const routes = new MediaTransferRoutes(); const origins = [];
    const choice = await routes.chooseVideo(channel, publicBase, async origin => {
      origins.push(origin); return (origin === channel) === (faster === 'channel') ? 100 : 1000;
    }, () => true);
    assert.equal(choice, faster === 'channel' ? channel : publicBase);
    assert.deepEqual(origins, [channel, publicBase]);
  }
  subject(); HomeChannelOrigin.close();
  const untrusted = new MediaTransferRoutes();
  assert.equal(await untrusted.chooseVideo(channel, publicBase, async () => { throw Error('Untrusted cap was measured'); }, () => true), channel);

  subject();
  for (const origin of [channel, direct, publicBase]) {
    httpCalls = [];
    httpResponse = { status: 206, headers: { 'content-range': 'bytes 0-262143/1048576' }, bytes: 262144 };
    assert.ok(Number.isFinite(await videoRangeTime(origin + ticketPath, new DownloadCancellationToken(), true, publicBase)));
    assert.equal(httpCalls[0].options.usingProxy, origin === publicBase ? undefined : false);
    assert.equal(httpCalls[0].options.addressFamily, origin === direct ? 'CURL_IPRESOLVE_V6' : undefined);
    assert.equal(httpCalls[0].options.header.Range, 'bytes=0-262143');
  }
  for (const response of [
    { status: 200, headers: { 'content-range': 'bytes 0-65535/1048576' }, bytes: 65536 },
    { status: 206, headers: { 'content-range': 'bytes 0-65535/1048576' }, bytes: 100 },
    { status: 206, headers: { location: 'https://other.example.test', 'content-range': 'bytes 0-65535/1048576' }, bytes: 65536 }
  ]) {
    httpResponse = response;
    assert.equal(await videoRangeTime(channel + ticketPath, new DownloadCancellationToken(), false, publicBase), Infinity);
  }
  HomeChannelOrigin.close();
  console.log('Channel video startup, stall recovery, scoped fallback, trusted ticket, route measurement and bounded probe behavior tests passed');
})().catch(error => { HomeChannelOrigin.close(); console.error(error); process.exitCode = 1; });
