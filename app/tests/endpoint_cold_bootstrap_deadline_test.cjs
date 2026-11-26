const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const sourceRoot = path.resolve(__dirname, '../entry/src/main/ets');
const source = path.join(sourceRoot, 'features/connectivity/EndpointRouter.ets');
const publicBase = 'https://public.example.test';
const direct = 'https://direct6.example.test';

function scenario(mode) {
  const NativeDate = Date;
  let now = 1900000000000;
  let sequence = 0;
  const timers = new Map();
  const requests = [];
  let cancelled = false;
  const schedule = (callback, delay) => { const id = ++sequence; timers.set(id, { due: now + delay, callback }); return id; };
  const cancelTimer = id => timers.delete(id);
  class ClockDate extends NativeDate { static now() { return now; } }
  const material = { instanceId: 'fixture', directConfigExpiresAt: now + 60000, expiresAt: now + 60000, ipv6DirectOrigins: [direct] };
  class Client {
    constructor(base, _auth, _fallback, timeout, pinned) {
      this.base = base;
      if (base === direct) { assert.equal(timeout, 4000); assert.equal(pinned, true); }
    }
    async get(_path, cancellation) {
      const request = { base: this.base, start: now, cancelledBeforeStart: cancellation?.isCancelled() || false };
      requests.push(request);
      if (this.base === publicBase) return { data: material };
      assert.equal(request.cancelledBeforeStart, false, 'RPC started with an already-expired DNS timer');
      return new Promise((resolve, reject) => {
        let pending = true;
        cancellation.bind(() => {
          if (!pending) return;
          cancelled = true;
          // 挂起模式模拟等待共享刷新令牌，取消只能关资源，外层仍须独立截止。
          if (mode !== 'hanging-credential-or-rpc') reject(new Error('RPC cancelled'));
        });
        if (mode !== 'hanging-credential-or-rpc') schedule(() => {
          pending = false;
          resolve({ data: material });
        }, 1500);
      });
    }
  }
  const store = { load: async () => ({ instanceId: 'fixture', origins: [direct] }), save: async () => {} };
  const mocks = {
    '@ohos.app.ability.common': { default: {} },
    '@ohos.net.mdns': { default: {} },
    '@kit.NetworkKit': { http: {}, connection: {} },
    '@kit.ArkTS': { util: {} },
    '@ohos.security.cryptoFramework': { default: {} },
    '../../shared/network/AuthorizedApiClient': { AuthorizedApiClient: Client },
    '../auth/AuthApi': { AuthApi: class {} },
    './ConnectivityResponseNormalizer': { ConnectivityResponseNormalizer: class { normalizeBootstrap(data) { return data; } } },
    './LanProofVerifier': { LanProofVerifier: class {} },
    './TrustedLanEndpointPolicy': { TrustedLanEndpointPolicy: class {} },
    './Ipv6EndpointPolicy': { Ipv6EndpointPolicy: class {} },
    './SystemIpv6Resolver': { SystemIpv6Resolver: class {} },
    './StrictHomeHttpClient': { StrictHomeHttpClient: class {} },
    './DirectEndpointStore': { DirectEndpointStore: class { load(...args) { return store.load(...args); } save(...args) { return store.save(...args); } } }
  };
  const modules = new Map();
  function load(filename) {
    if (modules.has(filename)) return modules.get(filename).exports;
    const compiled = new Module(filename, module);
    modules.set(filename, compiled);
    compiled.require = dependency => {
      if (mocks[dependency]) return mocks[dependency];
      assert.ok(dependency.startsWith('.'), 'Unexpected platform dependency');
      return load(path.resolve(path.dirname(filename), dependency + '.ets'));
    };
    const output = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
    }).outputText;
    new Function('require', 'exports', 'module', 'setTimeout', 'clearTimeout', 'Date', output)(
      compiled.require, compiled.exports, compiled, schedule, cancelTimer, ClockDate);
    return compiled.exports;
  }
  const { EndpointRouter } = load(source);
  const auth = { normalizeBaseUrl: value => value, activeBusinessBaseUrl: () => publicBase,
    isBusinessEndpointCoolingDown: () => false, publicBusinessBaseUrl: () => publicBase };
  const router = new EndpointRouter(publicBase, {}, auth);
  // 1900ms 地址发现仍在2100ms预算内，随后必须保留独立的4000ms RPC预算。
  router.hasIpv6 = () => new Promise(resolve => schedule(() => resolve(true), 1900));
  async function settle(work) {
    let done = false, value, failure;
    work.then(result => { value = result; done = true; }, error => { failure = error; done = true; });
    for (let step = 0; step < 20 && !done; step++) {
      for (let i = 0; i < 40; i++) await Promise.resolve();
      if (done) break;
      const next = [...timers].sort((a, b) => a[1].due - b[1].due)[0];
      assert.ok(next, 'Bootstrap hung without a deadline');
      timers.delete(next[0]); now = next[1].due; next[1].callback();
    }
    assert.ok(done, 'Bootstrap exceeded timer steps');
    if (failure) throw failure;
    return value;
  }
  return { router, requests, settle, elapsed: () => now - 1900000000000, cancelled: () => cancelled };
}

(async () => {
  const normal = scenario('normal');
  const result = await normal.settle(normal.router.bootstrap());
  assert.equal(result.instanceId, 'fixture');
  assert.equal(normal.requests.length, 1, 'An in-budget direct RPC unnecessarily fell back to public');
  assert.equal(normal.requests[0].base, direct);
  assert.equal(normal.requests[0].start, 1900000001900);
  assert.equal(normal.elapsed(), 3400);
  assert.equal(normal.cancelled(), false, 'In-budget direct RPC was cancelled during address discovery');

  const stalled = scenario('hanging-credential-or-rpc');
  const fallback = await stalled.settle(stalled.router.bootstrap());
  assert.equal(fallback.instanceId, 'fixture');
  assert.deepEqual(stalled.requests.map(request => request.base), [direct, publicBase]);
  assert.equal(stalled.elapsed(), 5900, 'A hung direct attempt exceeded the independent DNS and HTTP budgets');
  assert.equal(stalled.cancelled(), true, 'Deadline fallback did not close the pending direct request');
  console.log('Cold bootstrap independent DNS/RPC budgets and bounded public fallback tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
