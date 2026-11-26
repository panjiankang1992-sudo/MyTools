const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');

const root = path.resolve(__dirname, '../entry/src/main/ets');
const publicOrigin = 'https://bound.example.test';
const directOrigin = 'https://direct6.example.test:8443';
const alternateDirect = 'https://alternate6.example.test:8443';
const token = 'private-fixture-header.private-fixture-payload.private-fixture-signature';
const probePath = '/api/app/v1/connectivity/transport-probe';
const expectedBytes = 512 * 1024;
const tests = [];
const test = (name, run) => tests.push({ name, run });

function deferred() {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

async function flush() { for (let index = 0; index < 12; index++) await Promise.resolve(); }

function fixture({ tokenTask, initiallyCurrent = true, foreignBase = false, throwRequest = false,
  throwListener = false, alreadyCancelled = false } = {}) {
  let now = 1900000000000;
  let nextTimer = 0;
  let session = 1;
  let current = initiallyCurrent;
  let tokenCalls = 0;
  const timers = new Map();
  const logs = [];
  const requests = [];
  const wires = [];
  const modules = new Map();
  const http = {
    RequestMethod: { GET: 'GET' },
    createHttp() {
      const wire = { listeners: new Map(), destroyed: false, destroys: 0, delivered: 0,
        pending: undefined,
        on(event, handler) {
          if (throwListener && event === 'dataReceive') throw Error('private-fixture-listener');
          this.listeners.set(event, handler);
        },
        off(event) { this.listeners.delete(event); },
        destroy() {
          this.destroyed = true;
          this.destroys++;
          this.pending?.reject({ code: 2300028, message: token + publicOrigin });
        },
        requestInStream(url, options) {
          requests.push({ url, options });
          if (throwRequest) throw Error(token + publicOrigin);
          this.pending = deferred();
          return this.pending.promise;
        },
        headers(values = {}) {
          this.listeners.get('headersReceive')?.({
            'Content-Length': String(expectedBytes), 'Cache-Control': 'private, no-store', ...values
          });
        },
        chunk(length, badAt = -1) {
          const bytes = new Uint8Array(length);
          for (let index = 0; index < length; index++) bytes[index] = (this.delivered + index) % 256;
          if (badAt >= 0) bytes[badAt] ^= 255;
          this.listeners.get('dataReceive')?.(bytes.buffer);
          this.delivered += length;
          // 原生回调结束后复用分块，防止实现依赖保留的正文缓冲。
          bytes.fill(255);
        },
        pattern(total = expectedBytes, parts = [1, 65531, 3, 32771]) {
          let remaining = total;
          let index = 0;
          while (remaining > 0 && !this.destroyed) {
            const length = Math.min(remaining, parts[index++ % parts.length]);
            this.chunk(length);
            remaining -= length;
          }
        },
        end(status = 200) { this.pending?.resolve(status); },
        fail() { this.pending?.reject({ code: 2300028, message: token + publicOrigin }); }
      };
      wires.push(wire);
      return wire;
    }
  };
  const context = vm.createContext({ ArrayBuffer, Uint8Array, Date: { now: () => now },
    console: { info: line => logs.push(line) },
    setTimeout(callback, delay) {
      assert.equal(delay, 2500, 'A candidate quality probe changed its total time budget');
      const id = ++nextTimer;
      timers.set(id, { due: now + delay, callback });
      return id;
    },
    clearTimeout(id) { timers.delete(id); }
  });
  function load(relative) {
    const filename = path.resolve(root, relative + '.ets');
    if (modules.has(filename)) return modules.get(filename).exports;
    const module = { exports: {} };
    modules.set(filename, module);
    const output = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
    }).outputText;
    context.__module = module;
    context.__require = dependency => {
      if (dependency === '@kit.NetworkKit') return { http };
      if (dependency.endsWith('/auth/AuthSessionManager')) return {};
      assert.ok(dependency.startsWith('.'), 'Unexpected quality-probe dependency');
      return load(path.relative(root, path.resolve(path.dirname(filename), dependency)));
    };
    vm.runInContext('(function(require,module,exports){' + output + '\n})(__require,__module,__module.exports);', context);
    return module.exports;
  }
  const { HomeChannelOrigin } = load('features/connectivity/HomeChannelOrigin');
  const { DownloadCancellationToken } = load('shared/network/DownloadCancellationToken');
  const { HomeChannelThroughputProbe } = load('features/connectivity/HomeChannelThroughputProbe');
  const { HomeChannelRoutePolicy } = load('features/connectivity/HomeChannelRoutePolicy');
  const base = HomeChannelOrigin.open(34201, 'ab'.repeat(16), publicOrigin);
  const cancellation = new DownloadCancellationToken();
  if (alreadyCancelled) cancellation.cancel();
  const auth = { sessionRevision: () => session,
    validAccessToken() { tokenCalls++; return tokenTask?.promise || Promise.resolve(token); } };
  let work;
  return {
    HomeChannelOrigin, HomeChannelRoutePolicy, DownloadCancellationToken, cancellation, base, requests, wires, logs,
    start() {
      work = HomeChannelThroughputProbe.accepts(foreignBase ? publicOrigin : base,
        auth, () => current, cancellation);
      return work;
    },
    async ready() { await flush(); return wires.at(-1); },
    changeSession() { session++; },
    changeCurrent() { current = false; },
    replaceCap() { HomeChannelOrigin.open(34202, 'cd'.repeat(16), publicOrigin); },
    advance(milliseconds, dispatch = true) {
      const target = now + milliseconds;
      if (dispatch) {
        for (;;) {
          const next = [...timers.entries()].sort((left, right) => left[1].due - right[1].due)[0];
          if (!next || next[1].due > target) break;
          now = next[1].due;
          timers.delete(next[0]);
          next[1].callback();
        }
      }
      now = target;
    },
    async result(expected) {
      assert.equal(await work, expected);
      await flush();
      assert.equal(logs.length, 1, 'A completed quality probe logged or settled more than once');
      assert.match(logs[0], /^Home channel quality: accepted=(?:true|false), bytes=\d+, ms=\d+$/);
      for (const secret of [publicOrigin, directOrigin, base, token, probePath, 'Authorization', 'Bearer', 'private-fixture']) {
        assert.equal(logs[0].includes(secret), false, 'The quality probe exported protected request data');
      }
      assert.equal(timers.size, 0, 'The quality probe left its total deadline running');
      for (const wire of wires) {
        assert.equal(wire.listeners.size, 0, 'The quality probe retained a native body or header listener');
        assert.equal(wire.destroyed, true);
      }
      for (const { url, options } of requests) {
        assert.equal(url, base + probePath);
        assert.equal(options.method, 'GET');
        assert.equal(options.usingProxy, false);
        assert.equal(options.usingCache, false);
        assert.equal(options.header.Authorization, 'Bearer ' + token);
        assert.equal(options.header['Accept-Encoding'], 'identity');
        assert.equal(options.connectTimeout, 2500);
        assert.equal(options.readTimeout, 2500);
      }
      const destroys = wires.map(wire => wire.destroys);
      cancellation.cancel();
      await flush();
      assert.deepEqual(wires.map(wire => wire.destroys), destroys, 'Completed probe retained a cancellation handler');
      return logs[0];
    },
    get tokenCalls() { return tokenCalls; }
  };
}

function pageFixture(scenarios, { active = publicOrigin, discovery } = {}) {
  const types = fixture();
  const { HomeChannelOrigin, HomeChannelRoutePolicy, DownloadCancellationToken } = types;
  HomeChannelOrigin.close();
  let now = 1900000000000;
  let nextTimer = 0;
  let generation = 0;
  let keySequence = 0;
  const state = { account: 1, endpoint: 1, active };
  const timers = new Map();
  const starts = [], stops = [], profiles = [], probes = [], committed = [], reconnects = [];
  const scheduled = [], registrations = [], refreshes = [];
  const attempts = new Map();
  const homeChannel = {
    async start(origin, service, access) {
      this.stop();
      const ownGeneration = generation;
      const scenario = scenarios.shift();
      assert.ok(scenario, 'Unexpected channel candidate');
      assert.equal(origin, scenario.origin, 'Candidates lost verified-direct/public priority');
      const attempt = { origin, service, scenario, started: now, base: '' };
      starts.push(attempt);
      await access();
      if (scenario.handshake) await scenario.handshake.promise;
      if (generation !== ownGeneration) throw Error('Fixture channel changed');
      attempt.base = HomeChannelOrigin.open(34300 + ++keySequence, keySequence.toString(16).padStart(32, '0'), service);
      attempts.set(attempt.base, attempt);
      return attempt.base;
    },
    stop() {
      stops.push({ base: HomeChannelOrigin.current(), at: now });
      generation++;
      HomeChannelOrigin.close();
    }
  };
  class ProfileApi {
    constructor(base, auth, allowFallback, pinned) {
      this.base = base;
      assert.equal(auth, authManager);
      assert.equal(allowFallback, false);
      assert.equal(pinned, true);
    }
    async current(cancellation) {
      profiles.push({ base: this.base, cancellation });
      const attempt = attempts.get(this.base);
      if (attempt?.scenario.profile) await attempt.scenario.profile.promise;
      if (attempt?.scenario.profileFails) throw Error('Fixture identity unavailable');
      return { nickname: 'fixture' };
    }
  }
  class EndpointRouter {
    constructor(base, _context, auth) { assert.equal(base, publicOrigin); assert.equal(auth, authManager); }
    async resolve() {
      return typeof discovery === 'function' ? discovery() : discovery || { baseUrl: publicOrigin };
    }
  }
  const authManager = {
    sessionRevision: () => state.account, endpointRevision: () => state.endpoint,
    validAccessToken: async () => token, publicBusinessBaseUrl: () => publicOrigin,
    activeBusinessBaseUrl: () => state.active,
    resolveBusinessBaseUrl: () => HomeChannelOrigin.forService(publicOrigin) || state.active,
    activateBusinessEndpoint(base) {
      if (state.active !== base) state.endpoint++;
      state.active = base;
      committed.push(base);
    },
    resetBusinessEndpoint() {
      if (state.active !== publicOrigin) state.endpoint++;
      state.active = publicOrigin;
      committed.push(publicOrigin);
    },
    registerVerifiedIpv6Endpoints(values, expires) { registrations.push({ values, expires }); }
  };
  const throughput = {
    async accepts(base, auth, current, cancellation) {
      assert.equal(auth, authManager);
      assert.equal(current(), true, 'Quality probe started outside its account or endpoint scope');
      assert.equal(cancellation.isCancelled(), false);
      const attempt = attempts.get(base);
      assert.ok(attempt, 'Quality probe used a foreign capability');
      probes.push(attempt.origin);
      if (attempt.scenario.qualityTask) await attempt.scenario.qualityTask.promise;
      return attempt.scenario.quality === true;
    }
  };
  const source = fs.readFileSync(path.join(root, 'pages/Index.ets'), 'utf8');
  const names = ['ActiveServiceUrl', 'ApplyBusinessEndpoint', 'StopHomeChannel', 'ConnectHomeChannel',
    'TryHomeChannelCandidate', 'DiscoverLanEndpoint'];
  const methods = names.map(name => {
    let start = source.indexOf('  private ' + name + '(');
    if (start < 0) start = source.indexOf('  private async ' + name + '(');
    assert.ok(start >= 0, 'Missing production connection method: ' + name);
    const end = source.indexOf('\n  }\n', start);
    assert.ok(end > start, 'Unable to extract the complete production method');
    return source.substring(start, end + 5);
  }).join('\n');
  const output = ts.transpileModule('class AdaptivePage {\n' + methods + '\n}\nmodule.exports = AdaptivePage;', {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText;
  const compiled = { exports: {} };
  new Function('module', 'HomeChannelOrigin', 'DownloadCancellationToken', 'HomeChannelThroughputProbe',
    'ProfileApi', 'EndpointRouter', 'setTimeout', 'clearTimeout', 'Date', 'console', output)(
    compiled, HomeChannelOrigin, DownloadCancellationToken, throughput, ProfileApi, EndpointRouter,
    (callback, delay) => {
      assert.ok(delay > 0 && delay <= 10000, 'A candidate lost its bounded handshake/profile deadline');
      const id = ++nextTimer; timers.set(id, { callback, due: now + delay }); return id;
    }, id => timers.delete(id), { now: () => now }, { info() {} });
  const page = new compiled.exports();
  Object.assign(page, { serviceUrl: publicOrigin, activeServiceUrl: active, authManager,
    authenticated: true, homeChannelEnabled: true, homeChannelActive: false, homeChannelRelayed: false,
    homeChannelConnecting: false, homeChannelRevision: 0, homeChannelRetryAt: 0,
    homeChannelAttempt: undefined, homeChannel, homeChannelRoutePolicy: new HomeChannelRoutePolicy(),
    lanModeActive: active.startsWith('http://'), ipv6ModeActive: active !== publicOrigin,
    lanDiscoveryRevision: 0, dshWebRevision: 0, dshWebConnecting: false,
    dshWebLoadMonitor: { close() {} }, dshDownloads: { close() {} }, dshWebUrl: '',
    dshWebController: { loadUrl() {} },
    ReconnectPlaybackAfterEndpointSwitch(previous, next) { reconnects.push({ previous, next }); },
    RefreshAfterEndpointSwitch() { refreshes.push(now); },
    ScheduleEndpointRediscovery(delay) { scheduled.push(delay); },
    ApplicationContext: () => ({})
  });
  return { page, state, starts, stops, profiles, probes, committed, reconnects, scheduled, registrations,
    refreshes, HomeChannelOrigin,
    async advance(milliseconds) {
      const target = now + milliseconds;
      for (;;) {
        await flush();
        const next = [...timers.entries()].sort((left, right) => left[1].due - right[1].due)[0];
        if (!next || next[1].due > target) break;
        now = next[1].due; timers.delete(next[0]); next[1].callback();
      }
      now = target;
      await flush();
    },
    clean() { assert.equal(timers.size, 0, 'Connection integration left a candidate deadline active'); }
  };
}

test('complete pattern crosses arbitrary chunk boundaries without retaining response buffers', async () => {
  const item = fixture();
  item.start();
  const wire = await item.ready();
  wire.headers();
  wire.chunk(0);
  wire.pattern();
  wire.chunk(0);
  item.advance(2499);
  wire.end();
  assert.match(await item.result(true), /bytes=524288, ms=2499$/);
  assert.equal(item.requests.length, 1);
  assert.equal(item.HomeChannelOrigin.current(), item.base, 'Quality selection changed the capability route');
});

test('identity encoding and case-insensitive no-store metadata remain valid', async () => {
  const item = fixture(); item.start(); const wire = await item.ready();
  wire.headers({ 'Content-Encoding': ' Identity ', 'Cache-Control': 'PRIVATE, NO-STORE, max-age=0' });
  wire.pattern(); wire.end(); await item.result(true);
});

for (const [name, values] of [
  ['redirect', { Location: 'https://untrusted.example.test/private' }],
  ['compressed response', { 'Content-Encoding': 'gzip' }],
  ['incorrect length', { 'Content-Length': '524287' }],
  ['oversized length', { 'Content-Length': '524289' }],
  ['conflicting case-variant lengths', { 'content-length': '42' }],
  ['noncanonical length', { 'Content-Length': '0524288' }],
  ['missing no-store', { 'Cache-Control': 'private, max-age=0' }]
]) {
  test('rejects ' + name, async () => {
    const item = fixture(); item.start(); const wire = await item.ready();
    wire.headers(values); wire.pattern(); wire.end(); await item.result(false);
  });
}

test('missing content length is rejected even for an exact pattern', async () => {
  const item = fixture(); item.start(); const wire = await item.ready();
  wire.listeners.get('headersReceive')({ 'Cache-Control': 'no-store' });
  wire.pattern(); wire.end(); await item.result(false);
});

for (const status of [201, 204, 301, 401, 503]) {
  test('rejects complete pattern with status ' + status + ' without refreshing or replaying', async () => {
    const item = fixture(); item.start(); const wire = await item.ready();
    wire.headers(); wire.pattern(); wire.end(status); await item.result(false);
    assert.equal(item.tokenCalls, 1); assert.equal(item.requests.length, 1);
  });
}

test('wrong pattern aborts immediately after a previously verified odd-sized chunk', async () => {
  const item = fixture(); item.start(); const wire = await item.ready();
  wire.headers(); wire.chunk(39); wire.chunk(65536, 123);
  assert.equal(wire.destroyed, true);
  assert.match(await item.result(false), /bytes=39,/);
});

test('truncated stream fails its exact length guard', async () => {
  const item = fixture(); item.start(); const wire = await item.ready();
  wire.headers(); wire.pattern(expectedBytes - 1); wire.end(); await item.result(false);
});

test('an extra byte aborts before accepting an otherwise complete stream', async () => {
  const item = fixture(); item.start(); const wire = await item.ready();
  wire.headers(); wire.pattern(); wire.chunk(1); await item.result(false);
});

test('a single oversized chunk fails without processing or accepting its bytes', async () => {
  const item = fixture(); item.start(); const wire = await item.ready();
  wire.headers(); wire.chunk(expectedBytes + 1);
  assert.match(await item.result(false), /bytes=0,/);
});

test('request failure rejects the candidate and preserves its capability for caller cleanup', async () => {
  const item = fixture(); item.HomeChannelOrigin.activate(item.base);
  item.start(); const wire = await item.ready(); wire.fail(); await item.result(false);
  assert.equal(item.HomeChannelOrigin.forService(publicOrigin), item.base,
    'A quality probe failure closed a confirmed channel globally');
});

test('total deadline includes a hung normal token acquisition and discards its late result', async () => {
  const tokenTask = deferred(); const item = fixture({ tokenTask });
  item.start(); await item.ready(); item.advance(2500);
  await item.result(false);
  tokenTask.resolve(token); await flush();
  assert.equal(item.requests.length, 0); assert.equal(item.logs.length, 1);
});

test('total deadline includes token delay before the native request starts', async () => {
  const tokenTask = deferred(); const item = fixture({ tokenTask });
  item.start(); await item.ready(); item.advance(2200); tokenTask.resolve(token);
  const wire = await item.ready(); wire.headers(); wire.pattern(); item.advance(299); wire.end();
  assert.match(await item.result(true), /ms=2499$/);
});

test('delayed timer delivery cannot accept a response beyond the wall-clock budget', async () => {
  const item = fixture(); item.start(); const wire = await item.ready();
  wire.headers(); wire.pattern(); item.advance(2501, false); wire.end(); await item.result(false);
});

test('a hung partial body is bounded and late completion cannot reactivate its route', async () => {
  const item = fixture(); item.start(); const wire = await item.ready();
  wire.headers(); wire.chunk(65536); item.advance(2500); await item.result(false);
  wire.pattern(expectedBytes - 65536); wire.end(); await flush();
  assert.equal(item.logs.length, 1);
});

test('failed token acquisition returns false without an unauthenticated request', async () => {
  const tokenTask = deferred(); const item = fixture({ tokenTask });
  item.start(); tokenTask.reject(Error(token + publicOrigin)); await item.result(false);
  assert.equal(item.requests.length, 0);
});

for (const change of ['changeSession', 'changeCurrent', 'replaceCap']) {
  test(change + ' during token acquisition prevents a request to the old candidate', async () => {
    const tokenTask = deferred(); const item = fixture({ tokenTask });
    item.start(); await item.ready(); item[change](); tokenTask.resolve(token);
    await item.result(false); assert.equal(item.requests.length, 0);
  });
  test(change + ' while receiving chunks rejects the candidate', async () => {
    const item = fixture(); item.start(); const wire = await item.ready();
    wire.headers(); wire.chunk(41); item[change](); wire.chunk(1); await item.result(false);
  });
  test(change + ' after body completion cannot commit a stale successful response', async () => {
    const item = fixture(); item.start(); const wire = await item.ready();
    wire.headers(); wire.pattern(); item[change](); wire.end(); await item.result(false);
  });
}

test('foreign origin and already-obsolete caller are rejected before token acquisition', async () => {
  for (const options of [{ foreignBase: true }, { initiallyCurrent: false }, { alreadyCancelled: true }]) {
    const item = fixture(options); item.start(); await item.result(false);
    assert.equal(item.tokenCalls, 0); assert.equal(item.requests.length, 0);
  }
});

test('cancellation while waiting for token suppresses every late native request', async () => {
  const tokenTask = deferred(); const item = fixture({ tokenTask });
  item.start(); await item.ready(); item.cancellation.cancel(); await item.result(false);
  tokenTask.resolve(token); await flush(); assert.equal(item.requests.length, 0);
});

test('cancellation during stream aborts and cleans only its own handler', async () => {
  const item = fixture(); let otherCancellation = 0;
  item.cancellation.bind(() => otherCancellation++);
  item.start(); const wire = await item.ready(); wire.headers(); wire.chunk(17);
  item.cancellation.cancel(); await item.result(false); assert.equal(otherCancellation, 1);
});

test('native request and listener setup exceptions are contained without private error output', async () => {
  for (const options of [{ throwRequest: true }, { throwListener: true }]) {
    const item = fixture(options); item.start(); await item.result(false);
  }
});

test('route order uses one verified direct candidate followed by the bound public origin', async () => {
  const item = fixture(); const policy = new item.HomeChannelRoutePolicy();
  assert.deepEqual(Array.from(policy.candidates([
    'http://192.0.2.1', publicOrigin, directOrigin, alternateDirect
  ], publicOrigin)), [directOrigin, publicOrigin]);
  assert.deepEqual(Array.from(policy.candidates([], publicOrigin)), [publicOrigin],
    'A trusted public channel must be available without an IPv6 verification result');
});

test('direct rejection persists until network or manual retry resets quality history', async () => {
  const item = fixture(); const policy = new item.HomeChannelRoutePolicy();
  policy.reject(directOrigin, publicOrigin);
  assert.deepEqual(Array.from(policy.candidates([directOrigin, alternateDirect], publicOrigin)),
    [alternateDirect, publicOrigin]);
  policy.reject(alternateDirect, publicOrigin); policy.reject(publicOrigin, publicOrigin);
  assert.deepEqual(Array.from(policy.candidates([directOrigin, alternateDirect], publicOrigin)), [publicOrigin]);
  assert.deepEqual(Array.from(policy.candidates([directOrigin], publicOrigin)), [publicOrigin]);
  policy.reset();
  assert.deepEqual(Array.from(policy.candidates([directOrigin], publicOrigin)), [directOrigin, publicOrigin]);
});

test('invalid public origins cannot introduce a channel destination', async () => {
  const item = fixture(); const policy = new item.HomeChannelRoutePolicy();
  for (const value of ['http://public.example.test', publicOrigin + '/path',
    'https://user@public.example.test', publicOrigin + '?x=1', 'wss://public.example.test']) {
    assert.deepEqual(Array.from(policy.candidates([directOrigin], value)), []);
  }
});

test('production page commits only the relay after the direct candidate fails quality', async () => {
  const item = pageFixture([{ origin: directOrigin, quality: false }, { origin: publicOrigin, quality: true }]);
  await item.page.ConnectHomeChannel([directOrigin]);
  assert.deepEqual(item.starts.map(attempt => attempt.origin), [directOrigin, publicOrigin]);
  assert.deepEqual(item.probes, [directOrigin, publicOrigin]);
  assert.deepEqual(item.committed, [publicOrigin], 'Unconfirmed direct candidate reached the shared business route');
  assert.equal(item.page.homeChannelActive, true);
  assert.equal(item.page.homeChannelRelayed, true, 'The committed relay must drive the relay mode badge');
  assert.equal(item.page.homeChannelConnecting, false);
  assert.equal(item.HomeChannelOrigin.forService(publicOrigin), item.starts[1].base);
  assert.equal(item.reconnects.length, 1);
  assert.equal(item.reconnects[0].next, item.starts[1].base);
  assert.equal(item.scheduled.length, 0);
  item.clean();
});

test('production page can verify and activate the bound public relay without IPv6', async () => {
  const item = pageFixture([{ origin: publicOrigin, quality: true }], {
    discovery: { baseUrl: publicOrigin, verifiedIpv6Origins: [] }
  });
  await item.page.DiscoverLanEndpoint();
  assert.deepEqual(item.starts.map(attempt => attempt.origin), [publicOrigin]);
  assert.equal(item.page.homeChannelRelayed, true);
  assert.equal(item.HomeChannelOrigin.forService(publicOrigin), item.starts[0].base);
  item.clean();
});

test('production page keeps a healthy relay when rediscovery finds a direct candidate', async () => {
  const item = pageFixture([{ origin: publicOrigin, quality: true }], {
    discovery: { baseUrl: directOrigin, verifiedIpv6Origins: [directOrigin] }
  });
  await item.page.ConnectHomeChannel([]);
  const relay = item.HomeChannelOrigin.forService(publicOrigin);
  const stopCount = item.stops.length;
  await item.page.DiscoverLanEndpoint();
  assert.equal(item.starts.length, 1, 'Rediscovery remeasured a known slower direct path');
  assert.equal(item.stops.length, stopCount, 'Rediscovery closed the healthy relay');
  assert.equal(item.HomeChannelOrigin.forService(publicOrigin), relay);
  assert.equal(item.page.homeChannelRelayed, true);
  assert.deepEqual(item.committed, [publicOrigin]);
  item.clean();
});

test('production page preserves LAN priority over an already working relay', async () => {
  const lan = 'http://192.0.2.1:23201';
  const item = pageFixture([{ origin: publicOrigin, quality: true }], {
    discovery: { baseUrl: lan, verifiedIpv6Origins: [directOrigin] }
  });
  await item.page.ConnectHomeChannel([]);
  await item.page.DiscoverLanEndpoint();
  assert.equal(item.state.active, lan);
  assert.equal(item.page.lanModeActive, true);
  assert.equal(item.page.homeChannelActive, false);
  assert.equal(item.page.homeChannelRelayed, false);
  assert.equal(item.HomeChannelOrigin.current(), '');
  assert.equal(item.starts.length, 1);
  item.clean();
});

test('all candidates failing returns to PUBLIC and only retries relay while direct rejection is remembered', async () => {
  const item = pageFixture([
    { origin: directOrigin, quality: false }, { origin: publicOrigin, quality: false },
    { origin: publicOrigin, quality: true }
  ], { active: directOrigin });
  await item.page.ConnectHomeChannel([directOrigin]);
  assert.equal(item.state.active, publicOrigin);
  assert.equal(item.page.homeChannelActive, false);
  assert.equal(item.page.homeChannelRelayed, false);
  assert.equal(item.page.homeChannelConnecting, false);
  assert.equal(item.HomeChannelOrigin.current(), '');
  assert.deepEqual(item.scheduled, [31000]);
  assert.deepEqual(Array.from(item.page.homeChannelRoutePolicy.candidates([directOrigin], publicOrigin)), [publicOrigin]);
  await item.page.ConnectHomeChannel([directOrigin]);
  assert.equal(item.starts.length, 2, 'A failed candidate ignored its retry deadline');
  await item.advance(31000);
  await item.page.ConnectHomeChannel([directOrigin]);
  assert.deepEqual(item.starts.map(attempt => attempt.origin), [directOrigin, publicOrigin, publicOrigin]);
  assert.equal(item.page.homeChannelActive, true);
  assert.equal(item.page.homeChannelRelayed, true);
  item.clean();
});

test('a hung profile is cancelled at five seconds before trying the relay', async () => {
  const profile = deferred();
  const item = pageFixture([{ origin: directOrigin, profile, quality: true }, { origin: publicOrigin, quality: true }]);
  const work = item.page.ConnectHomeChannel([directOrigin]);
  await flush();
  assert.equal(item.starts.length, 1);
  assert.equal(item.profiles.length, 1);
  await item.advance(5000);
  await work;
  const relay = item.HomeChannelOrigin.forService(publicOrigin);
  assert.equal(item.profiles[0].cancellation.isCancelled(), true);
  assert.equal(item.starts[1].started - item.starts[0].started, 5000);
  assert.deepEqual(item.probes, [publicOrigin], 'A timed-out profile advanced into its throughput probe');
  assert.equal(item.page.homeChannelRelayed, true);
  const stopCount = item.stops.length;
  profile.resolve(); await flush();
  assert.equal(item.HomeChannelOrigin.forService(publicOrigin), relay);
  assert.equal(item.stops.length, stopCount, 'A late profile completion closed the newly accepted relay');
  item.clean();
});

test('a hung handshake has a five-second candidate budget and cannot replace the accepted relay later', async () => {
  const handshake = deferred();
  const item = pageFixture([{ origin: directOrigin, handshake, quality: true }, { origin: publicOrigin, quality: true }]);
  const work = item.page.ConnectHomeChannel([directOrigin]); await flush();
  await item.advance(5000); await work;
  const relay = item.HomeChannelOrigin.forService(publicOrigin);
  assert.equal(item.starts[1].started - item.starts[0].started, 5000);
  assert.equal(item.profiles.length, 1);
  assert.equal(item.page.homeChannelRelayed, true);
  handshake.resolve(); await flush();
  assert.equal(item.HomeChannelOrigin.forService(publicOrigin), relay);
  assert.equal(item.committed.length, 1);
  item.clean();
});

test('two hung candidates finish within fifteen seconds and leave ordinary PUBLIC available', async () => {
  const directProfile = deferred(), relayProfile = deferred();
  const item = pageFixture([
    { origin: directOrigin, profile: directProfile, quality: true },
    { origin: publicOrigin, profile: relayProfile, quality: true }
  ], { active: directOrigin });
  const work = item.page.ConnectHomeChannel([directOrigin]); await flush();
  await item.advance(14999);
  assert.equal(item.page.homeChannelConnecting, true, 'The cold relay lost its ten-second handshake/profile budget');
  await item.advance(1); await work;
  assert.equal(item.page.homeChannelConnecting, false);
  assert.equal(item.page.homeChannelActive, false);
  assert.equal(item.state.active, publicOrigin);
  assert.equal(item.HomeChannelOrigin.current(), '');
  assert.deepEqual(item.scheduled, [31000]);
  directProfile.resolve(); relayProfile.resolve(); await flush();
  assert.equal(item.page.homeChannelActive, false);
  assert.equal(item.HomeChannelOrigin.current(), '');
  item.clean();
});

test('a cold public TLS handshake can finish after six seconds before its unchanged quality gate', async () => {
  const handshake = deferred();
  const item = pageFixture([{ origin: publicOrigin, handshake, quality: true }]);
  const work = item.page.ConnectHomeChannel([]); await flush();
  await item.advance(6000);
  assert.equal(item.page.homeChannelConnecting, true);
  assert.equal(item.probes.length, 0, 'A pending TLS handshake was treated as a quality sample');
  handshake.resolve(); await work;
  assert.equal(item.page.homeChannelActive, true);
  assert.equal(item.page.homeChannelRelayed, true);
  assert.deepEqual(item.probes, [publicOrigin]);
  item.clean();
});

test('a hung public-only candidate is cancelled at ten seconds and its late handshake cannot activate', async () => {
  const handshake = deferred();
  const item = pageFixture([{ origin: publicOrigin, handshake, quality: true }]);
  const work = item.page.ConnectHomeChannel([]); await flush();
  await item.advance(9999);
  assert.equal(item.page.homeChannelConnecting, true);
  await item.advance(1); await work;
  assert.equal(item.page.homeChannelConnecting, false);
  assert.equal(item.page.homeChannelActive, false);
  assert.equal(item.state.active, publicOrigin);
  handshake.resolve(); await flush();
  assert.equal(item.HomeChannelOrigin.current(), '');
  assert.equal(item.probes.length, 0);
  item.clean();
});

for (const field of ['account', 'endpoint']) {
  test('production page refuses late candidate success after ' + field + ' changes', async () => {
    const profile = deferred();
    const item = pageFixture([{ origin: directOrigin, profile, quality: true }]);
    const work = item.page.ConnectHomeChannel([directOrigin]); await flush();
    item.state[field]++;
    profile.resolve(); await work;
    assert.equal(item.page.homeChannelActive, false);
    assert.equal(item.page.homeChannelConnecting, false);
    assert.equal(item.HomeChannelOrigin.current(), '');
    assert.equal(item.probes.length, 0);
    assert.equal(item.committed.length, 0);
    assert.equal(item.starts.length, 1, 'An obsolete candidate attempted another account or network route');
    item.clean();
  });
}

for (const hangingPhase of ['profile', 'handshake', 'qualityTask']) {
  test('explicit stop isolates a late ' + hangingPhase + ' result from a replacement channel', async () => {
    const pending = deferred();
    const item = pageFixture([
      { origin: directOrigin, [hangingPhase]: pending, quality: true },
      { origin: publicOrigin, quality: true }
    ]);
    const oldWork = item.page.ConnectHomeChannel([directOrigin]); await flush();
    item.page.StopHomeChannel(false);
    item.state.account++;
    await item.page.ConnectHomeChannel([]);
    const relay = item.HomeChannelOrigin.forService(publicOrigin);
    const stopCount = item.stops.length;
    assert.equal(item.page.homeChannelActive, true);
    pending.resolve(); await oldWork; await flush();
    assert.equal(item.HomeChannelOrigin.forService(publicOrigin), relay);
    assert.equal(item.page.homeChannelRelayed, true);
    assert.equal(item.stops.length, stopCount, 'An obsolete connection task closed the replacement route');
    assert.deepEqual(item.committed, [publicOrigin]);
    item.clean();
  });
}

(async () => {
  for (const item of tests) {
    try { await item.run(); }
    catch (error) { console.error('Failed adaptive channel test: ' + item.name); throw error; }
  }
  console.log(`Adaptive home channel quality and route policy behavior tests passed (${tests.length} cases)`);
})().catch(error => { console.error(error); process.exitCode = 1; });
