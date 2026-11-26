const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const ts = require(process.argv[3]);

const sourceRoot = process.argv[2];
const publicBase = 'https://mytools.example.test';
const otherPublic = 'https://other-mytools.example.test';
const firstAddress = '192.168.20.8';
const secondAddress = '192.168.20.9';
const firstLan = `http://${firstAddress}:23201`;
const secondLan = `http://${secondAddress}:23201`;
const bootstrapPath = '/api/app/v1/connectivity/bootstrap';
const challengePath = '/api/public/connectivity/challenge';

class TestFailure extends Error {}
function check(value, message) { if (!value) throw new TestFailure(message); }
function equal(actual, expected, message) { check(actual === expected, message); }
async function rejects(work, message) {
  let rejected = false;
  try { await work(); } catch (_) { rejected = true; }
  check(rejected, message);
}

function fakeClock() {
  const NativeDate = Date;
  let current = 1900000000000;
  let sequence = 0;
  const timers = new Map();
  class ClockDate extends NativeDate { static now() { return current; } }
  return {
    Date: ClockDate,
    now: () => current,
    jump: milliseconds => { current += milliseconds; },
    setTimeout(callback, milliseconds) {
      const id = ++sequence;
      timers.set(id, { callback, due: current + milliseconds });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
    async settle(promise) {
      let done = false;
      let result;
      let failure;
      promise.then(value => { done = true; result = value; }, error => { done = true; failure = error; });
      for (let turns = 0; !done && turns < 200; turns++) {
        // 先排空 Promise 链，再推进虚拟时间，避免慢候选掩盖立即完成的候选。
        for (let microtask = 0; microtask < 40; microtask++) await Promise.resolve();
        if (done) break;
        check(timers.size > 0, 'Operation stalled without a scheduled timeout');
        const [id, timer] = Array.from(timers.entries()).sort((left, right) => left[1].due - right[1].due)[0];
        timers.delete(id);
        current = Math.max(current, timer.due);
        timer.callback();
      }
      check(done, 'Operation exceeded the virtual timer limit');
      if (failure !== undefined) throw failure;
      return result;
    }
  };
}

function harness() {
  const clock = fakeClock();
  const requests = [];
  const issued = [];
  const nonces = new Set();
  const discoveries = [];
  const resolutions = [];
  const notices = [];
  const mockFailures = [];
  const modules = new Map();
  let services = [];
  let responder = () => { throw new Error('Unexpected HTTP request'); };
  let clearedKeys = 0;
  let dnsAddresses = [{ address: '240e:1234::8' }];
  let storedDescriptor;
  const tags = { ALIAS: 1, SECRET: 2, RETURN_TYPE: 3, ACCESSIBILITY: 4 };
  const asset = { Tag: tags, ReturnType: { ALL: 1 }, Accessibility: { DEVICE_FIRST_UNLOCKED: 1 },
    async query() { return storedDescriptor ? [new Map([[tags.SECRET, storedDescriptor]])] : []; },
    async update(_query, values) { storedDescriptor = values.get(tags.SECRET); },
    async add(values) { storedDescriptor = values.get(tags.SECRET); } };

  class HarmonyTextEncoder { encodeInto(value) { return new TextEncoder().encode(value); } }
  class HarmonyTextDecoder {
    static create(encoding, options) { return new HarmonyTextDecoder(encoding, options); }
    constructor(encoding, options) { this.decoder = new TextDecoder(encoding, options); }
    decodeToString(value) { return this.decoder.decode(value); }
  }
  const harmonyUtil = {
    Type: { BASIC_URL_SAFE: 1 },
    TextEncoder: HarmonyTextEncoder,
    TextDecoder: HarmonyTextDecoder,
    Base64Helper: class {
      encodeToStringSync(bytes) { return Buffer.from(bytes).toString('base64url'); }
      decodeSync(value) { return new Uint8Array(Buffer.from(value, 'base64url')); }
    }
  };
  const cryptoStub = {
    createRandom() {
      return { generateRandomSync(size) { return { data: new Uint8Array(crypto.randomBytes(size)) }; } };
    },
    createSymKeyGenerator(algorithm) {
      equal(algorithm, 'HMAC', 'Unexpected key algorithm');
      return { convertKeySync({ data }) {
        return { bytes: Buffer.from(data), clearMem() { this.bytes.fill(0); clearedKeys++; } };
      } };
    },
    createMac(algorithm) {
      equal(algorithm, 'SHA256', 'Unexpected MAC algorithm');
      let mac;
      return {
        initSync(key) { mac = crypto.createHmac('sha256', key.bytes); },
        updateSync({ data }) { mac.update(Buffer.from(data)); },
        doFinalSync() { return { data: new Uint8Array(mac.digest()) }; }
      };
    }
  };
  const http = {
    AddressFamily: { ONLY_V6: 'CURL_IPRESOLVE_V6' },
    RequestMethod: { GET: 'GET', POST: 'POST', PUT: 'PUT', DELETE: 'DELETE' },
    createHttp() {
      const handlers = new Map();
      return {
        on(event, handler) { handlers.set(event, handler); },
        off(event) { handlers.delete(event); },
        destroy() {},
        async request(url, options) {
          try {
            const request = { url, options, at: clock.now() };
            requests.push(request);
            if (url.endsWith(challengePath)) {
              const body = JSON.parse(options.extraData);
              const headerNames = Object.keys(options.header).map(value => value.toLowerCase());
              check(!headerNames.includes('authorization'), 'Challenge must not carry bearer authentication');
              equal(Object.keys(body).sort().join(','), 'nonce,probeId', 'Challenge must contain only its two public fields');
              equal(Buffer.from(body.nonce, 'base64url').length, 24, 'Challenge nonce must contain 24 random bytes');
              check(!nonces.has(body.nonce), 'Every challenge must use an independent nonce');
              nonces.add(body.nonce);
              const sent = url + JSON.stringify(options.header) + options.extraData;
              check(issued.every(value => !sent.includes(value.proofKey)), 'Challenge disclosed private proof material');
              equal(options.method, 'POST', 'Challenge must use POST');
              equal(options.connectTimeout, url.startsWith('https://') ? 4500 : 2500, 'Challenge connection timeout must stay bounded');
              equal(options.readTimeout, url.startsWith('https://') ? 4500 : 2500, 'Challenge read timeout must stay bounded');
            }
            const action = responder(request);
            if (action.delay !== undefined) await new Promise(resolve => clock.setTimeout(resolve, action.delay));
            if (action.headers !== undefined) handlers.get('headersReceive')?.(action.headers);
            if (action.error) throw new Error('Simulated unavailable endpoint');
            return { responseCode: action.status ?? 200,
              result: typeof action.result === 'function' ? action.result(request) : action.result };
          } catch (error) {
            // 候选校验会吞掉网络异常，测试断言必须另行保留，防止错误被当成正常回退。
            if (error instanceof TestFailure) mockFailures.push(error);
            throw error;
          }
        }
      };
    }
  };
  const mdns = {
    createDiscoveryService(_context, serviceType) {
      const handlers = new Map();
      const record = { serviceType, starts: 0, stops: 0, detached: false };
      discoveries.push(record);
      return {
        on(event, handler) { handlers.set(event, handler); },
        off(event) { handlers.delete(event); record.detached = true; },
        startSearchingMDNS() {
          record.starts++;
          services.forEach(service => handlers.get('serviceFound')?.(service));
        },
        stopSearchingMDNS() { record.stops++; }
      };
    },
    resolveLocalService(_context, service) {
      resolutions.push(service);
      if (service.neverResolve) return new Promise(() => {});
      if (service.rejectResolve) return Promise.reject(new Error('Simulated mDNS resolution failure'));
      if (service.resolveDelay !== undefined) return new Promise(resolve => clock.setTimeout(() => resolve(service), service.resolveDelay));
      return Promise.resolve(service);
    }
  };
  const systemModules = new Map([
    ['@kit.NetworkKit', { http, connection: { async getAddressesByName() { return dnsAddresses; } } }],
    ['@ohos.net.connection', { default: { async getAddressesByName() { return dnsAddresses; } } }],
    ['@ohos.net.socket', { default: {} }],
    ['@ohos.deviceInfo', { default: { sdkApiVersion: 24 } }],
    ['@ohos.url', { default: { URL: { parseURL: value => new URL(value) } } }],
    ['@kit.AssetStoreKit', { asset }],
    ['@kit.ArkTS', { util: harmonyUtil }],
    ['@ohos.util', { default: harmonyUtil }],
    ['@ohos.file.fs', { default: {} }],
    ['@ohos.net.mdns', { default: mdns }],
    ['@ohos.security.cryptoFramework', { default: cryptoStub }]
  ]);

  // 每个测试拥有独立模块缓存，真实执行全部协商代码并隔离静态探测材料。
  function load(relative) {
    const filename = path.join(sourceRoot, relative);
    if (filename.endsWith('/features/connectivity/StrictHomeHttpClient.ets')) {
      class StrictClient {
        constructor() {
          this.request = http.createHttp();
          this.redirected = false;
          this.request.on('headersReceive', headers => {
            this.redirected = Object.keys(headers).some(key => key.toLowerCase() === 'location');
          });
        }
        async exchange(origin, path, token, body, deadline) {
          const response = await this.request.request(origin + path, {
            method: body ? 'POST' : 'GET',
            header: token ? { Authorization: 'Bearer ' + token, Accept: 'application/json' } :
              { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' },
            extraData: body || undefined, connectTimeout: deadline, readTimeout: deadline
          });
          if (this.redirected) throw new Error('Invalid home control response');
          return { status: response.responseCode,
            text: typeof response.result === 'string' ? response.result : JSON.stringify(response.result) };
        }
        bootstrap(origin, token, deadline) { return this.exchange(origin, bootstrapPath, token, '', deadline); }
        identity(origin, token, deadline) { return this.exchange(origin, '/api/app/v1/identity/me', token, '', deadline); }
        challenge(origin, body, deadline) { return this.exchange(origin, challengePath, '', body, deadline); }
        close() { this.request.destroy(); }
      }
      return { StrictHomeHttpClient: StrictClient };
    }
    if (modules.has(filename)) return modules.get(filename).exports;
    const output = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      fileName: filename.replace(/\.ets$/, '.ts'),
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
    }).outputText;
    const module = { exports: {} };
    modules.set(filename, module);
    const localRequire = id => {
      if (systemModules.has(id)) return systemModules.get(id);
      check(id.startsWith('.'), 'Unexpected system dependency');
      return load(path.relative(sourceRoot, path.resolve(path.dirname(filename), `${id}.ets`)));
    };
    new Function('require', 'exports', 'module', 'setTimeout', 'clearTimeout', 'Date', output)(
      localRequire, module.exports, module, clock.setTimeout, clock.clearTimeout, clock.Date);
    return module.exports;
  }

  const { EndpointRouter } = load('features/connectivity/EndpointRouter.ets');
  const { ConnectivityResponseNormalizer } = load('features/connectivity/ConnectivityResponseNormalizer.ets');
  const { TrustedLanEndpointPolicy } = load('features/connectivity/TrustedLanEndpointPolicy.ets');
  const { BusinessEndpointState } = load('shared/network/BusinessEndpointState.ets');
  function auth(baseUrl = publicBase) {
    const state = new BusinessEndpointState();
    state.bindPublicBaseUrl(baseUrl);
    return {
      normalizeBaseUrl(value) {
        const parsed = new URL(value);
        check(parsed.protocol === 'https:', 'Public bootstrap must use HTTPS');
        return value.replace(/\/$/, '');
      },
      normalizeBusinessBaseUrl(value) {
        return value.startsWith('https://') ? this.normalizeBaseUrl(value) : new TrustedLanEndpointPolicy().normalize(value);
      },
      isBusinessEndpointCoolingDown: value => state.isEndpointCoolingDown(value),
      activeBusinessBaseUrl: () => state.activeBusinessBaseUrl(),
      publicBusinessBaseUrl: () => baseUrl,
      sessionRevision: () => 0,
      endpointRevision: () => state.endpointRevision(),
      resolveBusinessBaseUrl: value => state.resolveBusinessBaseUrl(value),
      activateBusinessEndpoint: value => state.activateBusinessEndpoint(value),
      reportBusinessEndpointUnavailable(value, revision) {
        if (state.reportBusinessEndpointUnavailable(value, revision)) notices.push(value);
      },
      async validAccessToken() { return 'fake-access-credential'; },
      async forceRefresh() { return 'fake-refreshed-credential'; }
    };
  }
  function material(overrides = {}) {
    const value = { instanceId: 'gateway-test', serviceType: '_mytools-gateway._tcp.local', apiVersion: 'v1',
      probeId: crypto.randomBytes(16).toString('hex'), proofKey: crypto.randomBytes(32).toString('base64url'),
      expiresAt: clock.now() + 600000, lanAddresses: [firstAddress], lanPort: 23201, ...overrides };
    issued.push(value);
    return value;
  }
  function challenge(material, request, overrides = {}, objectResult = false) {
    const { nonce } = JSON.parse(request.options.extraData);
    const message = `${material.instanceId}\n${nonce}\n${material.apiVersion}\n${material.expiresAt}`;
    const proof = crypto.createHmac('sha256', Buffer.from(material.proofKey, 'base64url')).update(message).digest('base64url');
    const value = { code: '0000', data: { instanceId: material.instanceId, apiVersion: material.apiVersion,
      nonce, expiresAt: material.expiresAt, proof, ...overrides } };
    return { result: objectResult ? value : JSON.stringify(value) };
  }
  return {
    clock, requests, discoveries, resolutions, notices, auth, material, challenge,
    setDns(value) { dnsAddresses = value.map(address => ({ address })); },
    clearBootstrap() { EndpointRouter.cachedBootstrap = undefined; EndpointRouter.cachedPublicBaseUrl = ''; },
    normalizer: new ConnectivityResponseNormalizer(),
    clearedKeys: () => clearedKeys,
    setServices(value) { services = value; },
    respond(value) { responder = value; },
    router: (baseUrl = publicBase, authentication = auth(baseUrl)) => new EndpointRouter(baseUrl, {}, authentication),
    async resolve(router) {
      try { return await clock.settle(router.resolve()); }
      finally { if (mockFailures.length > 0) throw mockFailures[0]; }
    }
  };
}

function bootstrap(material) { return { result: JSON.stringify({ code: '0000', data: material }) }; }
function service(address, port = 23201, options = {}) { return { host: { address }, port, ...options }; }
const cases = [];
function test(name, run) { cases.push({ name, run }); }

test('first bootstrap stays pinned to public HTTPS while the session already selects LAN', async () => {
  const h = harness();
  const material = h.material();
  const auth = h.auth();
  auth.activateBusinessEndpoint(firstLan);
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) {
      equal(request.url, publicBase + bootstrapPath, 'Initial bootstrap bypassed its pinned public origin');
      equal(request.options.readTimeout, 8000, 'Bootstrap timeout is not bounded');
      return bootstrap(material);
    }
    return h.challenge(material, request);
  });
  equal((await h.resolve(h.router(publicBase, auth))).baseUrl, firstLan, 'Valid direct HMAC was not selected');
  equal(h.discoveries.length, 0, 'Direct success unnecessarily waited for mDNS');
  equal(h.clearedKeys(), 1, 'Verifier did not clear its converted key');
});

test('each verification uses a new nonce even when valid bootstrap material is cached', async () => {
  const h = harness();
  const material = h.material();
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request));
  const router = h.router();
  await h.resolve(router);
  await h.resolve(router);
  equal(h.requests.filter(value => value.url.endsWith(bootstrapPath)).length, 1, 'Valid cache was not reused');
  equal(h.requests.filter(value => value.url.endsWith(challengePath)).length, 2, 'Cached discovery did not create a fresh challenge');
});

test('parallel direct verification returns the first success before a slow failure', async () => {
  const h = harness();
  const material = h.material({ lanAddresses: [firstAddress, secondAddress] });
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) return bootstrap(material);
    return request.url.startsWith(firstLan) ? { delay: 2500, error: true } : h.challenge(material, request);
  });
  const started = h.clock.now();
  equal((await h.resolve(h.router())).baseUrl, secondLan, 'First successful direct candidate was not selected');
  equal(h.clock.now(), started, 'Successful direct candidate waited for the slow candidate');
  equal(h.requests.filter(value => value.url.endsWith(challengePath)).length, 2, 'Direct candidates were not started concurrently');
});

test('invalid direct proof falls through to an advertised address already issued in bootstrap', async () => {
  const h = harness();
  const material = h.material();
  let attempts = 0;
  h.setServices([service(firstAddress)]);
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) return bootstrap(material);
    attempts++;
    return h.challenge(material, request, attempts === 1 ? { proof: 'A'.repeat(43) } : {});
  });
  equal((await h.resolve(h.router())).baseUrl, firstLan, 'mDNS did not retry the trusted issued address');
  equal(h.discoveries[0].serviceType, '_mytools-gateway._tcp', 'Discovery used the obsolete service type');
  check(h.discoveries[0].detached, 'Discovery handler was not detached');
});

test('all invalid proofs return the trusted public origin', async () => {
  const h = harness();
  const material = h.material();
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request, { proof: 'A'.repeat(43) }));
  equal((await h.resolve(h.router())).baseUrl, publicBase, 'Invalid proof selected a LAN endpoint');
  equal(h.notices.length, 0, 'Anonymous challenge failure changed the authenticated endpoint');
});

test('mDNS rejects old ports and public or loopback addresses before sending a challenge', async () => {
  const h = harness();
  const material = h.material({ lanAddresses: [secondAddress] });
  let attempts = 0;
  h.setServices([service(firstAddress, 23110), service('8.8.8.8'), service('127.0.0.1'), service(secondAddress)]);
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) return bootstrap(material);
    equal(request.url, secondLan + challengePath, 'Unsafe mDNS candidate received a challenge');
    attempts++;
    return h.challenge(material, request, attempts === 1 ? { proof: 'A'.repeat(43) } : {});
  });
  equal((await h.resolve(h.router())).baseUrl, secondLan, 'New Gateway mDNS endpoint was not accepted');
  equal(h.requests.length, 3, 'Rejected mDNS endpoints unexpectedly received traffic');
});

test('same-public unexpired cache works while the public origin is disconnected', async () => {
  const h = harness();
  const material = h.material();
  const router = h.router();
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request));
  await h.resolve(router);
  h.respond(request => {
    check(!request.url.endsWith(bootstrapPath), 'Unexpired cached discovery attempted public bootstrap');
    return h.challenge(material, request);
  });
  equal((await h.resolve(router)).baseUrl, firstLan, 'Cached bootstrap could not discover without public connectivity');
});

test('another public origin never reuses a previous public origin cache', async () => {
  const h = harness();
  const first = h.material();
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(first) : h.challenge(first, request));
  await h.resolve(h.router());
  const second = h.material({ instanceId: 'other-gateway', lanAddresses: [secondAddress] });
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) {
      equal(request.url, otherPublic + bootstrapPath, 'Another origin reused foreign bootstrap materials');
      return bootstrap(second);
    }
    return h.challenge(second, request);
  });
  equal((await h.resolve(h.router(otherPublic))).instanceId, 'other-gateway', 'Another origin retained the previous instance');
});

test('cache is reused until more than five seconds remain and refreshed at the exact threshold', async () => {
  const h = harness();
  const first = h.material({ expiresAt: h.clock.now() + 60000 });
  const router = h.router();
  let current = first;
  let bootstrapRequests = 0;
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) { bootstrapRequests++; return bootstrap(current); }
    return h.challenge(current, request);
  });
  await h.resolve(router);
  h.clock.jump(54999);
  await h.resolve(router);
  equal(bootstrapRequests, 1, 'Bootstrap refreshed before its renewal threshold');
  h.clock.jump(1);
  current = h.material();
  await h.resolve(router);
  equal(bootstrapRequests, 2, 'Bootstrap did not refresh at the five-second threshold');
});

test('expired bootstrap is refreshed rather than used for a new challenge', async () => {
  const h = harness();
  let current = h.material({ expiresAt: h.clock.now() + 60000 });
  const router = h.router();
  let bootstrapRequests = 0;
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) { bootstrapRequests++; return bootstrap(current); }
    return h.challenge(current, request);
  });
  await h.resolve(router);
  h.clock.jump(60001);
  current = h.material();
  await h.resolve(router);
  equal(bootstrapRequests, 2, 'Expired bootstrap was reused');
});

test('verified LAN can renew cached material while public connectivity is unavailable', async () => {
  const h = harness();
  const first = h.material({ expiresAt: h.clock.now() + 60000 });
  const auth = h.auth();
  const router = h.router(publicBase, auth);
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(first) : h.challenge(first, request));
  await h.resolve(router);
  auth.activateBusinessEndpoint(firstLan);
  h.clock.jump(55000);
  const renewed = h.material();
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) {
      equal(request.url, firstLan + bootstrapPath, 'Verified LAN renewal unexpectedly used public connectivity');
      return bootstrap(renewed);
    }
    return h.challenge(renewed, request);
  });
  equal((await h.resolve(router)).baseUrl, firstLan, 'Verified LAN could not renew its trusted material');
  equal(h.notices.length, 0, 'Successful LAN renewal downgraded the session');
});

test('failed verified LAN renewal reports the failure then renews through pinned public HTTPS', async () => {
  const h = harness();
  const first = h.material({ expiresAt: h.clock.now() + 60000 });
  const auth = h.auth();
  const router = h.router(publicBase, auth);
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(first) : h.challenge(first, request));
  await h.resolve(router);
  auth.activateBusinessEndpoint(firstLan);
  h.clock.jump(55000);
  const renewed = h.material({ lanAddresses: [secondAddress] });
  const renewalOrigins = [];
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) {
      renewalOrigins.push(request.url);
      return request.url.startsWith(firstLan) ? { error: true } : bootstrap(renewed);
    }
    return h.challenge(renewed, request);
  });
  equal((await h.resolve(router)).baseUrl, secondLan, 'Public recovery did not use renewed materials');
  equal(renewalOrigins.join(','), `${firstLan}${bootstrapPath},${publicBase}${bootstrapPath}`, 'LAN renewal did not fall back to pinned public HTTPS');
  equal(h.notices.length, 1, 'Failed LAN renewal was not reported exactly once');
  equal(auth.activeBusinessBaseUrl(), publicBase, 'Failed LAN renewal did not downgrade subsequent requests');
});

test('invalid LAN renewal material falls back to public renewal', async () => {
  const h = harness();
  const first = h.material({ expiresAt: h.clock.now() + 60000 });
  const auth = h.auth();
  const router = h.router(publicBase, auth);
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(first) : h.challenge(first, request));
  await h.resolve(router);
  auth.activateBusinessEndpoint(firstLan);
  h.clock.jump(55000);
  const renewed = h.material();
  h.respond(request => request.url.endsWith(bootstrapPath) ?
    bootstrap(request.url.startsWith(firstLan) ? { ...renewed, lanPort: 23110 } : renewed) : h.challenge(renewed, request));
  equal((await h.resolve(router)).baseUrl, publicBase, 'Invalid LAN renewal must observe its failure cooldown');
  h.clock.jump(30001);
  equal((await h.resolve(router)).baseUrl, firstLan, 'LAN did not recover after its cooldown');
  equal(h.notices.length, 1, 'Invalid LAN renewal material did not report the endpoint failure');
});

test('a late invalid raw renewal cannot cool a newly activated route', async () => {
  const h = harness();
  const first = h.material({ expiresAt: h.clock.now() + 60000 });
  const auth = h.auth();
  const router = h.router(publicBase, auth);
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(first) : h.challenge(first, request));
  await h.resolve(router);
  auth.activateBusinessEndpoint(firstLan);
  h.clock.jump(55000);
  const renewed = h.material({ lanAddresses: [secondAddress] });
  h.respond(request => {
    if (request.url === firstLan + bootstrapPath) return {
      result() {
        auth.activateBusinessEndpoint(secondLan);
        return JSON.stringify({ code: '0000', data: { ...renewed, lanPort: 23110 } });
      }
    };
    return request.url.endsWith(bootstrapPath) ? bootstrap(renewed) : h.challenge(renewed, request);
  });
  equal((await h.resolve(router)).baseUrl, secondLan, 'Late invalid renewal replaced the newer route');
  equal(auth.activeBusinessBaseUrl(), secondLan, 'Late invalid renewal downgraded the active route');
  equal(h.notices.length, 0, 'Late invalid renewal reported a stale raw endpoint failure');
});

test('expired material cannot bypass failed public renewal when no LAN was activated', async () => {
  const h = harness();
  const first = h.material({ expiresAt: h.clock.now() + 60000 });
  const router = h.router();
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(first) : h.challenge(first, request));
  await h.resolve(router);
  h.clock.jump(60001);
  const previous = h.requests.length;
  h.respond(() => ({ error: true }));
  await rejects(() => h.resolve(router), 'Expired cache was reused after failed public renewal');
  equal(h.requests.length, previous + 1, 'Failed public renewal continued probing with expired material');
});

for (const activatedLan of [false, true]) {
  test(`rejected cached probe renews through pinned ${activatedLan ? 'LAN' : 'public HTTPS'} and restores direct discovery`, async () => {
    const h = harness();
    const cached = h.material();
    const auth = h.auth();
    const router = h.router(publicBase, auth);
    h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(cached) : h.challenge(cached, request));
    await h.resolve(router);
    if (activatedLan) auth.activateBusinessEndpoint(firstLan);
    const renewed = h.material();
    let renewals = 0;
    let rejected = 0;
    h.respond(request => {
      if (request.url.endsWith(bootstrapPath)) {
        renewals++;
        equal(request.url, (activatedLan ? firstLan : publicBase) + bootstrapPath, 'Forced probe renewal bypassed its pinned trusted endpoint');
        return bootstrap(renewed);
      }
      const probeId = JSON.parse(request.options.extraData).probeId;
      if (probeId === cached.probeId) { rejected++; return { status: 401, result: '' }; }
      equal(probeId, renewed.probeId, 'Discovery did not use the newly issued probe');
      return h.challenge(renewed, request);
    });
    equal((await h.resolve(router)).baseUrl, firstLan, 'Gateway restart did not recover after probe renewal');
    equal(renewals, 1, 'Rejected cached probe was not renewed exactly once');
    equal(rejected, 1, 'Rejected cached probe was unnecessarily retried');
    equal(h.discoveries.length, 1, 'New valid probe did not recover on its first direct attempt');
    equal(h.notices.length, 0, 'Anonymous probe rejection downgraded the authenticated session');
  });
}

test('repeated probe rejection performs at most one forced renewal and two discovery rounds', async () => {
  const h = harness();
  const cached = h.material();
  const router = h.router();
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(cached) : h.challenge(cached, request));
  await h.resolve(router);
  const renewed = h.material();
  h.setServices([service(firstAddress)]);
  const counts = new Map([[cached.probeId, 0], [renewed.probeId, 0]]);
  let renewals = 0;
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) { renewals++; return bootstrap(renewed); }
    const probeId = JSON.parse(request.options.extraData).probeId;
    check(counts.has(probeId), 'Discovery used an unexpected probe');
    counts.set(probeId, counts.get(probeId) + 1);
    return { status: 401, result: '' };
  });
  equal((await h.resolve(router)).baseUrl, publicBase, 'Repeated probe rejection did not finish with public fallback');
  equal(renewals, 1, 'Repeated probe rejection issued more than one forced renewal');
  equal(h.discoveries.length, 2, 'Repeated rejection exceeded two mDNS discovery rounds');
  equal(counts.get(cached.probeId), 2, 'Original probe was not limited to one direct and one mDNS attempt');
  equal(counts.get(renewed.probeId), 2, 'Renewed probe was not limited to one direct and one mDNS attempt');
});

for (const failure of ['invalid HMAC', 'network failure']) {
  test(`${failure} preserves valid cache for a later offline resolve without forced renewal`, async () => {
    const h = harness();
    const material = h.material();
    const router = h.router();
    h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request));
    await h.resolve(router);
    h.respond(request => {
      check(!request.url.endsWith(bootstrapPath), 'Non-rejection failure unnecessarily fetched bootstrap');
      return failure === 'network failure' ? { error: true } : h.challenge(material, request, { proof: 'A'.repeat(43) });
    });
    equal((await h.resolve(router)).baseUrl, publicBase, 'Failed candidate did not finish with public fallback');
    h.respond(request => {
      check(!request.url.endsWith(bootstrapPath), 'Non-rejection failure discarded valid cached material');
      return h.challenge(material, request);
    });
    equal((await h.resolve(router)).baseUrl, firstLan, 'Offline discovery could not reuse retained valid material');
    equal(h.requests.filter(value => value.url.endsWith(bootstrapPath)).length, 1, 'Non-rejection failure refreshed bootstrap');
  });
}

test('one rejected candidate does not renew bootstrap when another candidate succeeds', async () => {
  const h = harness();
  const material = h.material({ lanAddresses: [firstAddress, secondAddress] });
  const router = h.router();
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request));
  await h.resolve(router);
  h.respond(request => {
    check(!request.url.endsWith(bootstrapPath), 'Successful candidate unnecessarily renewed bootstrap');
    return request.url.startsWith(firstLan) ? { status: 401, result: '' } : h.challenge(material, request);
  });
  equal((await h.resolve(router)).baseUrl, secondLan, 'Valid alternate candidate was ignored after another probe rejection');
  equal(h.discoveries.length, 0, 'Alternate direct success unnecessarily started mDNS');
});

test('a previous resolve late rejection cannot trigger renewal in the current resolve', async () => {
  const h = harness();
  const material = h.material({ lanAddresses: [firstAddress, secondAddress] });
  const router = h.router();
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) return bootstrap(material);
    return request.url.startsWith(firstLan) ? { delay: 2500, status: 401, result: '' } : h.challenge(material, request);
  });
  equal((await h.resolve(router)).baseUrl, secondLan, 'Initial direct success waited for a late rejected candidate');
  h.respond(request => {
    check(!request.url.endsWith(bootstrapPath), 'A late rejection from the previous resolve contaminated this attempt');
    return { error: true, delay: request.url.startsWith(secondLan) ? 2600 : 0 };
  });
  equal((await h.resolve(router)).baseUrl, publicBase, 'Current resolve did not complete after independent network failures');
  equal(h.requests.filter(value => value.url.endsWith(bootstrapPath)).length, 1, 'Late previous rejection forced current bootstrap renewal');
  equal(h.discoveries.length, 1, 'Late previous rejection caused an additional discovery round');
});

test('normalizer accepts the exact ten-minute expiry boundary', async () => {
  const h = harness();
  const value = h.material();
  equal(h.normalizer.normalizeBootstrap(value).expiresAt, value.expiresAt, 'Valid maximum bootstrap lifetime was rejected');
});

for (const [name, change] of [
  ['beyond-ten-minute expiry', h => ({ expiresAt: h.clock.now() + 600001 })],
  ['expired material', h => ({ expiresAt: h.clock.now() })],
  ['public IP addresses', () => ({ lanAddresses: [firstAddress, '8.8.8.8'] })],
  ['obsolete service type', () => ({ serviceType: '_mytools._tcp.local' })],
  ['obsolete LAN port', () => ({ lanPort: 23110 })],
  ['oversized address list', () => ({ lanAddresses: Array.from({ length: 65 }, () => firstAddress) })]
]) {
  test(`normalizer rejects ${name}`, async () => {
    const h = harness();
    let rejected = false;
    try { h.normalizer.normalizeBootstrap(h.material(change(h))); } catch (_) { rejected = true; }
    check(rejected, `Normalizer accepted ${name}`);
  });
}

test('direct address verification is capped at sixteen candidates', async () => {
  const h = harness();
  const addresses = Array.from({ length: 20 }, (_, index) => `192.168.30.${index + 1}`);
  const material = h.material({ lanAddresses: addresses });
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request, { proof: 'A'.repeat(43) }));
  equal((await h.resolve(h.router())).baseUrl, publicBase, 'Invalid direct candidate was selected');
  equal(h.requests.filter(value => value.url.endsWith(challengePath)).length, 16, 'Direct verification exceeded sixteen candidates');
});

test('mDNS resolves at most sixteen services and never verifies unissued addresses', async () => {
  const h = harness();
  const material = h.material({ lanAddresses: [] });
  h.setServices(Array.from({ length: 20 }, (_, index) => service(`192.168.40.${index + 1}`)));
  h.respond(request => {
    check(request.url.endsWith(bootstrapPath), 'An unissued mDNS address received a challenge');
    return bootstrap(material);
  });
  await h.resolve(h.router());
  equal(h.resolutions.length, 16, 'mDNS resolution exceeded sixteen services');
  equal(h.requests.filter(value => value.url.endsWith(challengePath)).length, 0, 'mDNS added an unissued challenge candidate');
});

test('a relay-capable mDNS address outside bootstrap never receives a challenge', async () => {
  const h = harness();
  const material = h.material();
  h.setServices([service(secondAddress)]);
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) return bootstrap(material);
    return h.challenge(material, request, request.url.startsWith(secondLan) ? {} : { proof: 'A'.repeat(43) });
  });
  equal((await h.resolve(h.router())).baseUrl, publicBase, 'An unissued relay endpoint was accepted');
  equal(h.requests.filter(value => value.url.endsWith(challengePath)).length, 1, 'mDNS expanded the signed address set');
});

test('duplicate mDNS advertisements verify each issued address only once per scan', async () => {
  const h = harness();
  const material = h.material({ lanAddresses: [firstAddress, secondAddress] });
  h.setServices([service(firstAddress), service(firstAddress), service(firstAddress), service(secondAddress), service(secondAddress)]);
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request, { proof: 'A'.repeat(43) }));
  await h.resolve(h.router());
  equal(h.requests.filter(value => value.url === firstLan + challengePath).length, 2, 'Known address was verified repeatedly in one mDNS scan');
  equal(h.requests.filter(value => value.url === secondLan + challengePath).length, 2, 'Known address was verified repeatedly in one mDNS scan');
});

test('unresolved mDNS services time out at two and a half seconds', async () => {
  const h = harness();
  const material = h.material({ lanAddresses: [] });
  h.setServices([service(firstAddress, 23201, { neverResolve: true })]);
  h.respond(() => bootstrap(material));
  const started = h.clock.now();
  equal((await h.resolve(h.router())).baseUrl, publicBase, 'Unresolved mDNS service was selected');
  equal(h.clock.now() - started, 2500, 'mDNS resolution timeout was not bounded to 2.5 seconds');
  equal(h.requests.length, 1, 'Unresolved mDNS address received a challenge');
  check(h.discoveries[0].detached, 'Timed-out discovery did not detach its handler');
});

test('mDNS resolution failures finish discovery and return public fallback', async () => {
  const h = harness();
  const material = h.material({ lanAddresses: [] });
  h.setServices([service(firstAddress, 23201, { rejectResolve: true })]);
  h.respond(() => bootstrap(material));
  equal((await h.resolve(h.router())).baseUrl, publicBase, 'Failed mDNS resolution selected a service');
  check(h.discoveries[0].detached && h.discoveries[0].stops > 0, 'Failed mDNS discovery did not clean up');
});

test('object challenge responses retain the same proof validation as JSON strings', async () => {
  const h = harness();
  const material = h.material();
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request, {}, true));
  equal((await h.resolve(h.router())).baseUrl, firstLan, 'Object challenge response failed valid proof validation');
});

test('challenge identity, version, nonce and expiry must all match trusted material', async () => {
  for (const change of [{ instanceId: 'other-instance' }, { apiVersion: 'v2' }, { nonce: 'x'.repeat(32) }, { expiresAt: 1 }]) {
    const h = harness();
    const material = h.material();
    h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request, change));
    equal((await h.resolve(h.router())).baseUrl, publicBase, 'Mismatched challenge metadata selected an endpoint');
  }
});

test('oversized challenge payload is rejected before endpoint selection', async () => {
  const h = harness();
  const material = h.material();
  h.respond(request => {
    if (request.url.endsWith(bootstrapPath)) return bootstrap(material);
    const valid = h.challenge(material, request);
    return { result: valid.result + ' '.repeat(65536) };
  });
  equal((await h.resolve(h.router())).baseUrl, publicBase, 'Oversized challenge payload was accepted');
});

const directOrigin = 'https://direct6.example.test';
function directMaterial(h, overrides = {}) {
  return h.material({ ipv6DirectOrigins: [directOrigin], directConfigExpiresAt: h.clock.now() + 86400000, ...overrides });
}

test('verified IPv6 is selected after LAN transport failure and retained as a standby', async () => {
  const h = harness();
  const material = directMaterial(h);
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) :
    request.url.startsWith(directOrigin) ? h.challenge(material, request) : { error: true });
  const result = await h.resolve(h.router());
  equal(result.baseUrl, directOrigin, 'IPv6 was not selected');
  equal(result.verifiedIpv6Origins[0], directOrigin, 'Verified standby missing');
  equal(h.discoveries.length, 0, 'IPv6 selection waited for mDNS');
});

test('LAN outranks available IPv6 while both candidates are verified', async () => {
  const h = harness(); const material = directMaterial(h);
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request));
  const result = await h.resolve(h.router());
  equal(result.baseUrl, firstLan, 'LAN did not retain priority');
  equal(result.verifiedIpv6Origins[0], directOrigin, 'IPv6 standby not verified');
});

test('unsafe or mixed DNS answers cannot receive credentials or challenges', async () => {
  for (const addresses of [['::1'], ['fe80::1'], ['fd00::1'], ['2001:db8::8'], ['240e::8', '8.8.8.8']]) {
    const h = harness(); const material = directMaterial(h, { lanAddresses: [] }); h.setDns(addresses);
    h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : { error: true });
    equal((await h.resolve(h.router())).baseUrl, publicBase, 'Unsafe DNS reached direct path');
    check(!h.requests.some(request => request.url.startsWith(directOrigin)), 'Unsafe direct request sent');
  }
});

test('persisted HTTPS descriptor renews bootstrap after process cache is lost', async () => {
  const h = harness(); const material = directMaterial(h, { lanAddresses: [] });
  h.respond(request => request.url.endsWith(bootstrapPath) ? bootstrap(material) : h.challenge(material, request));
  await h.resolve(h.router());
  h.clearBootstrap(); h.requests.length = 0;
  equal((await h.resolve(h.router())).baseUrl, directOrigin, 'Cold descriptor was not reused');
  equal(h.requests[0].url, directOrigin + bootstrapPath, 'Cold start still required public tunnel');
});

test('malformed direct origins and expired descriptors are rejected', async () => {
  const h = harness();
  for (const origin of ['http://direct6.example.test', 'https://127.0.0.1', 'https://direct6.example.test/api',
    'https://user@direct6.example.test', 'https://direct6.example.test:65536']) {
    await rejects(() => h.normalizer.normalizeBootstrap(directMaterial(h, { ipv6DirectOrigins: [origin] })), 'Invalid origin accepted');
  }
  await rejects(() => h.normalizer.normalizeBootstrap(directMaterial(h, { directConfigExpiresAt: h.clock.now() - 1 })), 'Expired descriptor accepted');
});

(async () => {
  for (const entry of cases) {
    try { await entry.run(); } catch (error) {
      throw new Error(`${entry.name}: ${error instanceof Error ? error.message : 'Unexpected test failure'}`);
    }
  }
  console.log(`Endpoint router behavior tests passed (${cases.length} cases)`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
