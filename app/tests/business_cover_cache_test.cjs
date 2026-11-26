const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const ts = require(process.argv[3]);

const sourceRoot = process.argv[2];
const publicBase = 'https://mytools.example.test';
const firstLan = 'http://192.168.20.8:23201';
const secondLan = 'http://192.168.20.9:23201';
const unknownLan = 'http://192.168.20.10:23201';
const coverPath = '/api/app/v1/media/items/example/thumbnail?size=small';
const fakeCredential = 'fake-business-access-credential';
const png = new Uint8Array(Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/at0AAAAASUVORK5CYII=', 'base64'));

function check(value, message) { if (!value) throw new Error(message); }
function equal(actual, expected, message) { check(actual === expected, message); }
async function rejects(work, message) {
  let rejected = false;
  try { await work(); } catch (_) { rejected = true; }
  check(rejected, message);
}

function harness() {
  const requests = [];
  const dnsCalls = [];
  const dnsAnswers = new Map();
  const files = new Map();
  const directories = new Set(['/cache']);
  const descriptors = new Map();
  const modules = new Map();
  let descriptorSequence = 0;
  let modificationSequence = 0;
  let onToken = () => {};
  let tokenCalls = 0;
  let responder = () => ({ bytes: png, headers: { 'content-type': 'image/png', 'content-length': `${png.length}` } });

  const fileSystem = {
    OpenMode: { CREATE: 1, WRITE_ONLY: 2, TRUNC: 4 },
    accessSync(target) { return directories.has(target) || files.has(target); },
    mkdirSync(target) { directories.add(target); },
    listFileSync(directory) {
      return Array.from(files.keys()).filter(value => value.startsWith(directory + '/') &&
        !value.substring(directory.length + 1).includes('/')).map(value => value.substring(directory.length + 1));
    },
    openSync(target) {
      check(directories.has(path.dirname(target)), 'Cache directory was not created');
      const fd = ++descriptorSequence;
      descriptors.set(fd, target);
      files.set(target, { bytes: new Uint8Array(), mtime: ++modificationSequence });
      return { fd };
    },
    writeSync(fd, data) {
      const target = descriptors.get(fd);
      const previous = files.get(target);
      check(previous !== undefined, 'Writing an unopened cache file');
      const bytes = new Uint8Array(previous.bytes.length + data.byteLength);
      bytes.set(previous.bytes);
      bytes.set(new Uint8Array(data), previous.bytes.length);
      files.set(target, { bytes, mtime: ++modificationSequence });
      return data.byteLength;
    },
    closeSync() {},
    statSync(target) {
      const file = files.get(target);
      check(file !== undefined, 'Stat requested for an absent cache file');
      return { size: file.bytes.length, mtime: file.mtime };
    },
    renameSync(source, target) {
      check(files.has(source), 'Cache commit source was absent');
      files.set(target, files.get(source));
      files.delete(source);
    },
    unlinkSync(target) { files.delete(target); }
  };
  const http = {
    RequestMethod: { GET: 'GET' },
    createHttp() {
      const handlers = new Map();
      let destroyed = false;
      return {
        on(event, handler) { handlers.set(event, handler); },
        off(event) { handlers.delete(event); },
        destroy() { destroyed = true; },
        async requestInStream(url, options) {
          const request = { url, options };
          requests.push(request);
          const response = responder(request);
          response.onRequest?.();
          if (response.headers) handlers.get('headersReceive')?.(response.headers);
          if (!destroyed) {
            const chunks = response.chunks ?? (response.bytes === undefined ? [] : [response.bytes]);
            for (const chunk of chunks) {
              if (destroyed) break;
              handlers.get('dataReceive')?.(Uint8Array.from(chunk).buffer);
            }
          }
          if (response.error) throw new Error('Simulated image network failure');
          return response.status ?? 200;
        }
      };
    }
  };
  const systemModules = new Map([
    ['@ohos.file.fs', { default: fileSystem }],
    ['@ohos.net.http', { default: http }],
    ['@ohos.util', { default: { generateRandomUUID: () => crypto.randomUUID().replaceAll('-', '') } }],
    ['@ohos.net.connection', { default: { async getAddressesByName(host) {
      dnsCalls.push(host);
      return (dnsAnswers.get(host) ?? ['93.184.216.34']).map(address => ({ address }));
    } } }]
  ]);

  // 真实执行缓存、SSRF 策略和会话端点状态，仅替换 Harmony 系统能力。
  function load(relative) {
    const filename = path.join(sourceRoot, relative);
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
    new Function('require', 'exports', 'module', output)(localRequire, module.exports, module);
    return module.exports;
  }

  const { BusinessEndpointState } = load('shared/network/BusinessEndpointState.ets');
  const { SafeRemoteCoverCache } = load('shared/network/SafeRemoteCoverCache.ets');
  const { DownloadCancellationToken } = load('shared/network/DownloadCancellationToken.ets');
  const state = new BusinessEndpointState();
  state.bindPublicBaseUrl(publicBase);
  state.activateBusinessEndpoint(firstLan);
  const auth = {
    activeBusinessBaseUrl: () => state.activeBusinessBaseUrl(),
    resolveBusinessResourceUrl: value => state.resolveBusinessResourceUrl(value),
    async validAccessToken() { tokenCalls++; await onToken(); return fakeCredential; }
  };
  return {
    requests, dnsCalls, dnsAnswers, files, state, auth,
    context: { cacheDir: '/cache' },
    cache: new SafeRemoteCoverCache(),
    cancellation: () => new DownloadCancellationToken(),
    tokenCalls: () => tokenCalls,
    duringTokenRefresh(callback) { onToken = callback; },
    respond(callback) { responder = callback; },
    business(resourceUrl = publicBase + coverPath, cancellation) {
      return this.cache.cacheBusinessCover(this.context, resourceUrl, auth, cancellation);
    },
    assertImage(uri) {
      check(uri.startsWith('file:///cache/'), 'Business cover did not return a local cache URI');
      const target = files.get(uri.substring('file://'.length));
      check(target !== undefined && target.bytes.length === png.length, 'Committed image bytes were incomplete');
      check(Array.from(files.keys()).every(value => !value.endsWith('.part')), 'Temporary image file was retained');
    },
    assertNoFiles() { equal(files.size, 0, 'Rejected image content was committed or left in a temporary file'); }
  };
}

const cases = [];
function test(name, run) { cases.push({ name, run }); }

test('canonical public cover maps to verified LAN and only that request receives bearer authentication', async () => {
  const h = harness();
  h.assertImage(await h.business());
  equal(h.requests.length, 1, 'Business cover unexpectedly repeated its request');
  equal(h.requests[0].url, firstLan + coverPath, 'Canonical resource did not use the verified LAN');
  equal(h.requests[0].options.header.Authorization, `Bearer ${fakeCredential}`, 'Verified business request lacked its credential');
  equal(h.dnsCalls.length, 0, 'Verified LAN cover incorrectly entered external-source DNS validation');
});

test('a direct verified LAN API cover is cached and can reuse the validated local file', async () => {
  const h = harness();
  const first = await h.business(firstLan + coverPath);
  const second = await h.business(firstLan + coverPath);
  h.assertImage(second);
  equal(first, second, 'Identical business cover did not reuse its cache file');
  equal(h.requests.length, 1, 'Validated cache hit repeated the network request');
  equal(h.tokenCalls(), 2, 'Cache access bypassed current authentication validation');
});

test('previously selected LAN resources follow the newly selected LAN', async () => {
  const h = harness();
  h.state.activateBusinessEndpoint(secondLan);
  h.assertImage(await h.business(firstLan + coverPath));
  equal(h.requests[0].url, secondLan + coverPath, 'Old selected LAN resource bypassed the current endpoint');
});

test('authentication refresh can change the endpoint before the resource URL is resolved', async () => {
  const h = harness();
  h.duringTokenRefresh(() => h.state.activateBusinessEndpoint(secondLan));
  h.assertImage(await h.business());
  equal(h.requests[0].url, secondLan + coverPath, 'Cover routing was resolved before token refresh completed');
});

test('authentication refresh downgrade sends a canonical business cover only to public HTTPS', async () => {
  const h = harness();
  h.duringTokenRefresh(() => h.state.resetBusinessEndpoint());
  h.assertImage(await h.business(firstLan + coverPath));
  equal(h.requests[0].url, publicBase + coverPath, 'Cover stayed on stale LAN after authentication downgrade');
});

for (const [name, resource] of [
  ['third-party HTTPS', 'https://third-party.example.test/api/cover.png'],
  ['third-party HTTP', 'http://third-party.example.test/api/cover.png'],
  ['similar public domain prefix', publicBase + '.evil/api/cover.png'],
  ['similar LAN authority prefix', firstLan + '0/api/cover.png'],
  ['unselected LAN address', unknownLan + '/api/cover.png'],
  ['non-API path', firstLan + '/images/cover.png'],
  ['API prefix without its slash', firstLan + '/api?cover=1'],
  ['raw control character', publicBase + '/api/a\u0000b.png'],
  ['raw newline', publicBase + '/api/a\nb.png'],
  ['backslash', publicBase + '/api/a\\b.png'],
  ['relative API resource', '/api/cover.png'],
  ['inline image', 'data:image/png;base64,ZmFrZQ=='],
  ['local file', 'file:///data/storage/cache/cover.png']
]) {
  test(`business cover rejects ${name} before sending any credential`, async () => {
    const h = harness();
    await rejects(() => h.business(resource), 'Untrusted cover resource was accepted');
    equal(h.requests.length, 0, 'Untrusted cover resource received an authenticated request');
    h.assertNoFiles();
  });
}

for (const method of ['cache', 'cacheReaderPage']) {
  test(`ordinary ${method} still blocks private addresses before the network`, async () => {
    const h = harness();
    await rejects(() => h.cache[method](h.context, firstLan + '/api/cover.png'), 'Ordinary image loader bypassed private-address protection');
    equal(h.requests.length, 0, 'Ordinary image loader sent a request to a private address');
    h.assertNoFiles();
  });
}

test('ordinary cover cache still rejects public names that resolve partly to private addresses', async () => {
  const h = harness();
  h.dnsAnswers.set('third-party.example.test', ['93.184.216.34', '192.168.20.20']);
  await rejects(() => h.cache.cache(h.context, 'https://third-party.example.test/cover.png'), 'Mixed private DNS answers were accepted');
  equal(h.requests.length, 0, 'Mixed private DNS answer reached the network');
  equal(h.dnsCalls.length, 1, 'Real external-source DNS validation was not executed');
});

for (const [name, response] of [
  ['redirect', () => ({ headers: { location: 'https://third-party.example.test/cover.png' }, bytes: png })],
  ['oversized declared length', () => ({ headers: { 'content-length': `${2 * 1024 * 1024 + 1}` }, bytes: png })],
  ['oversized streamed content', () => ({ headers: { 'content-type': 'image/png' }, bytes: new Uint8Array(2 * 1024 * 1024 + 1) })],
  ['unsupported signature', () => ({ headers: { 'content-type': 'image/png' }, bytes: new Uint8Array([1, 2, 3, 4]) })],
  ['mismatched MIME type', () => ({ headers: { 'content-type': 'text/html' }, bytes: png })],
  ['excessive pixel dimensions', () => {
    const oversized = Uint8Array.from(png);
    oversized.set([0, 0, 0x4E, 0x21], 16);
    return { headers: { 'content-type': 'image/png' }, bytes: oversized };
  }]
]) {
  test(`business cover retains ${name} rejection and removes temporary files`, async () => {
    const h = harness();
    h.respond(response);
    await rejects(() => h.business(), `Business cover accepted ${name}`);
    equal(h.requests.length, 1, 'Rejected business image unexpectedly followed or repeated a request');
    h.assertNoFiles();
  });
}

test('cancelled business cover does not issue a network request', async () => {
  const h = harness();
  const cancellation = h.cancellation();
  cancellation.cancel();
  await rejects(() => h.business(publicBase + coverPath, cancellation), 'Cancelled business cover was accepted');
  equal(h.requests.length, 0, 'Cancelled business cover issued a network request');
  h.assertNoFiles();
});

(async () => {
  for (const entry of cases) {
    try { await entry.run(); } catch (error) {
      throw new Error(`${entry.name}: ${error instanceof Error ? error.message : 'Unexpected test failure'}`);
    }
  }
  console.log(`Business cover cache behavior tests passed (${cases.length} cases)`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
