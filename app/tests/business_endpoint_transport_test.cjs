const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const path = require('node:path');
const ts = require(process.argv[3]);

const sourceRoot = process.argv[2];
const publicBase = 'https://mytools.example.test';
const firstLan = 'http://192.168.20.8:23201';
const secondLan = 'http://192.168.20.9:23201';
const candidateLan = 'http://192.168.20.10:23201';
const apiPath = '/api/app/v1/testing/resource';
const accessToken = `${'a'.repeat(12)}.${'b'.repeat(12)}.${'c'.repeat(12)}`;
const rotatedToken = `${'d'.repeat(12)}.${'e'.repeat(12)}.${'f'.repeat(12)}`;
const requests = [];
const responses = [];
const files = new Map();
const descriptors = new Map();
const fileFailures = { open: false, write: false, shortWrite: false };
let descriptorSequence = 0;
const storeState = { seed: undefined, saves: [], loads: [], clears: 0, failSave: undefined };

class FakeSessionStore {
  async load(baseUrl) {
    storeState.loads.push(baseUrl);
    return storeState.seed === undefined ? undefined : structuredClone(storeState.seed);
  }

  async save(baseUrl, session) {
    if (storeState.failSave?.(session)) throw new Error("secure storage unavailable");
    storeState.saves.push({ baseUrl, session: structuredClone(session) });
    storeState.seed = structuredClone(session);
  }

  async clear() {
    storeState.clears++;
    storeState.seed = undefined;
  }
}

class HarmonyTextEncoder {
  encodeInto(value) { return new TextEncoder().encode(value); }
}

class HarmonyTextDecoder {
  static create(encoding, options) { return new HarmonyTextDecoder(encoding, options); }
  constructor(encoding, options) { this.decoder = new TextDecoder(encoding, options); }
  decodeToString(value) { return this.decoder.decode(value); }
}

const fileMock = {
  OpenMode: { WRITE_ONLY: 1, CREATE: 2, TRUNC: 4 },
  openSync(target) {
    if (fileFailures.open) throw new Error('local open denied');
    const fd = ++descriptorSequence;
    descriptors.set(fd, target);
    files.set(target, new Uint8Array());
    return { fd };
  },
  writeSync(fd, buffer) {
    if (fileFailures.write) throw new Error('local write denied');
    const target = descriptors.get(fd);
    const previous = files.get(target);
    const next = new Uint8Array(previous.length + buffer.byteLength);
    next.set(previous);
    next.set(new Uint8Array(buffer), previous.length);
    files.set(target, next);
    return fileFailures.shortWrite ? buffer.byteLength - 1 : buffer.byteLength;
  },
  closeSync() {}
};

const httpMock = {
  AddressFamily: { ONLY_V6: 'CURL_IPRESOLVE_V6' },
  RequestMethod: { GET: 'GET', POST: 'POST', PUT: 'PUT', DELETE: 'DELETE' },
  createHttp() {
    const handlers = new Map();
    const send = async (url, options, stream) => {
      requests.push({ url, options, stream });
      const response = responses.shift();
      assert.ok(response, `Unexpected request: ${options.method} ${url}`);
      if (response.url !== undefined) assert.equal(url, response.url);
      if (response.method !== undefined) assert.equal(options.method, response.method);
      response.onRequest?.();
      if (response.headers !== undefined) handlers.get('headersReceive')?.(response.headers);
      if (stream && response.bytes !== undefined) {
        const bytes = Uint8Array.from(response.bytes);
        handlers.get('dataReceive')?.(bytes.buffer);
      }
      if (response.error !== undefined) throw response.error;
      return stream ? response.status ?? 200 : {
        responseCode: response.status ?? 200,
        result: response.body ?? JSON.stringify({ code: '0000', message: '', data: {} })
      };
    };
    return {
      on(event, handler) { handlers.set(event, handler); },
      off(event) { handlers.delete(event); },
      destroy() {},
      request(url, options) { return send(url, options, false); },
      requestInStream(url, options) { return send(url, options, true); }
    };
  }
};

const strictCalls = [];
const strictInstances = [];
class FakeStrictHomeHttpClient {
  constructor() { this.closed = false; this.request = httpMock.createHttp(); strictInstances.push(this); }
  async exchange(origin, path, token, deadline) {
    assert.equal(this.closed, false, 'A cancelled strict client was reused');
    strictCalls.push({ origin, path, token, deadline });
    const response = await this.request.request(origin + path, { method: httpMock.RequestMethod.GET,
      header: { Accept: 'application/json', Authorization: 'Bearer ' + token } });
    return { status: response.responseCode, text: response.result };
  }
  bootstrap(origin, token, deadline) { return this.exchange(origin, '/api/app/v1/connectivity/bootstrap', token, deadline); }
  identity(origin, token, deadline) { return this.exchange(origin, '/api/app/v1/identity/me', token, deadline); }
  close() { if (!this.closed) { this.closed = true; this.request.destroy(); } }
}

const modules = new Map();
const systemModules = new Map([
  ['@ohos.deviceInfo', { default: { sdkApiVersion: 24 } }],
  ['@kit.NetworkKit', { http: httpMock }],
  ['@ohos.file.fs', { default: fileMock }],
  ['@ohos.util', { default: { TextEncoder: HarmonyTextEncoder, TextDecoder: HarmonyTextDecoder, generateRandomUUID: () => require('node:crypto').randomUUID() } }],
  ['@ohos.url', { default: { URL: { parseURL: value => new URL(value) } } }]
]);

// 直接执行真实 ETS 业务代码，只替换无法在 Node 中运行的系统能力。
function loadSource(filename) {
  if (filename.endsWith('/features/auth/SecureSessionStore.ets')) return { SecureSessionStore: FakeSessionStore };
  if (filename.endsWith('/features/connectivity/StrictHomeHttpClient.ets')) return { StrictHomeHttpClient: FakeStrictHomeHttpClient };
  if (modules.has(filename)) return modules.get(filename).exports;
  const source = fs.readFileSync(filename, 'utf8');
  const output = ts.transpileModule(source, {
    fileName: filename.replace(/\.ets$/, '.ts'),
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText;
  const compiled = new Module(filename, module);
  modules.set(filename, compiled);
  compiled.require = id => {
    if (systemModules.has(id)) return systemModules.get(id);
    if (id.startsWith('.')) return loadSource(path.resolve(path.dirname(filename), `${id}.ets`));
    throw new Error(`Unexpected system dependency: ${id}`);
  };
  compiled._compile(output, filename);
  return compiled.exports;
}

const { AuthSessionManager } = loadSource(path.join(sourceRoot, 'features/auth/AuthSessionManager.ets'));
const { HomeChannelOrigin } = loadSource(path.join(sourceRoot, 'features/connectivity/HomeChannelOrigin.ets'));
const { AuthorizedApiClient } = loadSource(path.join(sourceRoot, 'shared/network/AuthorizedApiClient.ets'));
const { DownloadCancellationToken } = loadSource(path.join(sourceRoot, 'shared/network/DownloadCancellationToken.ets'));
const { BusinessResponseError } = loadSource(path.join(sourceRoot, 'shared/network/BusinessTransportError.ets'));

function session(expiresAt = Date.now() + 60 * 60 * 1000) {
  return { userId: '7', username: 'tester', nickname: '', avatar: '', role: 'USER', accessToken,
    refreshToken: 'r'.repeat(48), expiresAt };
}

async function manager(expiresAt) {
  requests.length = 0;
  responses.length = 0;
  strictCalls.length = 0;
  strictInstances.length = 0;
  fileFailures.open = false;
  fileFailures.write = false;
  fileFailures.shortWrite = false;
  storeState.seed = session(expiresAt);
  storeState.saves.length = 0;
  storeState.loads.length = 0;
  storeState.clears = 0;
  storeState.failSave = undefined;
  const result = new AuthSessionManager();
  await result.restore(publicBase);
  return result;
}

function envelope(data) {
  return JSON.stringify({ code: '0000', message: '', data });
}

function assertDrained() {
  assert.equal(responses.length, 0, 'Expected requests were not sent');
}

const cases = [];
function test(name, run) { cases.push({ name, run }); }

test('only exact read-only home control URLs enter the strict TLS client', async () => {
  const direct = 'https://direct6.example.test:8443';
  for (const path of ['/api/app/v1/connectivity/bootstrap', '/api/app/v1/identity/me']) {
    const auth = await manager();
    auth.activateBusinessEndpoint(direct);
    responses.push({ url: direct + path });
    await new AuthorizedApiClient(publicBase, auth, false, 4200).get(path);
    assert.equal(strictCalls.length, 1);
    assert.deepEqual(strictCalls[0], { origin: direct, path, token: accessToken, deadline: 4200 });
    assert.equal(strictInstances[0].closed, true);
    assertDrained();
  }
  for (const [base, path, write] of [
    [publicBase, '/api/app/v1/identity/me', false],
    [firstLan, '/api/app/v1/identity/me', false],
    [direct, '/api/app/v1/identity/me?extra=1', false],
    [direct, '/api/app/v1/identity/me/profile', false],
    [direct, apiPath, false],
    [direct, '/api/app/v1/identity/me', true]
  ]) {
    const auth = await manager();
    if (base !== publicBase) auth.activateBusinessEndpoint(base);
    responses.push({ url: base + path });
    const client = new AuthorizedApiClient(base, auth);
    await (write ? client.post(path) : client.get(path));
    assert.equal(strictCalls.length, 0, 'Strict control transport widened to another endpoint or method');
    assertDrained();
  }
  const auth = await manager();
  auth.activateBusinessEndpoint(direct);
  const cap = HomeChannelOrigin.open(34201, 'ab'.repeat(16), publicBase);
  HomeChannelOrigin.activate(cap);
  try {
    responses.push({ url: cap + '/api/app/v1/identity/me' });
    await new AuthorizedApiClient(publicBase, auth).get('/api/app/v1/identity/me');
    assert.equal(strictCalls.length, 0, 'A confirmed loopback channel was sent through direct control transport');
    assert.equal(requests[0].options.usingProxy, false);
    assertDrained();
  } finally { HomeChannelOrigin.close(); }
});

test('strict home control requests retain cancellation, response validation and token refresh', async () => {
  const direct = 'https://direct6.example.test:8443';
  const path = '/api/app/v1/identity/me';
  let auth = await manager();
  auth.activateBusinessEndpoint(direct);
  const cancellation = new DownloadCancellationToken();
  responses.push({ url: direct + path, onRequest() { cancellation.cancel(); } });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(path, cancellation));
  assert.equal(requests.length, 1);
  assert.equal(auth.activeBusinessBaseUrl(), direct);
  assert.equal(strictInstances[0].closed, true);
  assertDrained();

  auth = await manager();
  auth.activateBusinessEndpoint(direct);
  responses.push({ url: direct + path, body: '{' });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(path));
  assert.equal(requests.length, 1, 'Malformed strict control JSON was replayed');
  assert.equal(strictInstances[0].closed, true);
  assertDrained();

  auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: direct + path, error: new BusinessResponseError(direct, 'Invalid control HTTP framing') });
  await assert.rejects(new AuthorizedApiClient(direct, auth, false, 4000, true).get(path), /HTTP framing/);
  assert.equal(requests.length, 1, 'Rejected strict HTTP protocol was replayed');
  assert.equal(auth.activeBusinessBaseUrl(), firstLan, 'An unselected control candidate downgraded the active route');
  assert.equal(strictInstances[0].closed, true);
  assertDrained();

  auth = await manager();
  auth.activateBusinessEndpoint(direct);
  responses.push({ url: direct + path, status: 401, body: JSON.stringify({ code: '401', message: 'expired' }) },
    { url: direct + '/api/app/v1/identity/refresh', method: 'POST',
      body: envelope({ accessToken: rotatedToken, refreshToken: 's'.repeat(48), expiresIn: 3600 }) },
    { url: direct + path });
  await new AuthorizedApiClient(publicBase, auth).get(path);
  assert.equal(strictCalls.length, 2);
  assert.deepEqual(strictCalls.map(call => call.token), [accessToken, rotatedToken]);
  assert.equal(requests.length, 3);
  assert.ok(strictInstances.every(client => client.closed));
  assertDrained();
});

test('a complete channel capacity response never closes the channel or replays a write', async () => {
  const auth = await manager();
  const direct = 'https://direct6.example.test:8443';
  auth.activateBusinessEndpoint(direct);
  const cap = HomeChannelOrigin.open(34201, 'ef'.repeat(16), publicBase);
  HomeChannelOrigin.activate(cap);
  let failures = 0;
  auth.onHomeChannelUnavailable = () => { failures++; HomeChannelOrigin.close(); };
  try {
    responses.push({ url: cap + apiPath, method: 'POST', status: 503,
      body: JSON.stringify({ code: 'CHANNEL_BUSY', message: 'Channel capacity exceeded', data: null }) });
    await assert.rejects(new AuthorizedApiClient(publicBase, auth).post(apiPath), /capacity exceeded/);
    assert.equal(requests.length, 1);
    assert.equal(failures, 0);
    assert.equal(auth.activeBusinessBaseUrl(), direct);
    assert.equal(HomeChannelOrigin.forService(publicBase), cap);
    assert.equal(strictCalls.length, 0);
    assertDrained();
  } finally { HomeChannelOrigin.close(); }
});

test('selected endpoints follow the current route and candidates stay isolated', async () => {
  const auth = await manager();
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  assert.throws(() => auth.normalizeBusinessBaseUrl('http://192.168.20.8:23110'));
  auth.activateBusinessEndpoint(firstLan);
  assert.equal(auth.resolveBusinessBaseUrl(publicBase), firstLan);
  assert.equal(auth.resolveBusinessBaseUrl(candidateLan), candidateLan);
  auth.activateBusinessEndpoint(secondLan);
  assert.equal(auth.resolveBusinessBaseUrl(firstLan), secondLan);
  assert.equal(auth.publicBusinessBaseUrl(), publicBase);
  auth.resetBusinessEndpoint();
  assert.equal(auth.resolveBusinessBaseUrl(firstLan), publicBase);
  assert.equal(auth.resolveBusinessBaseUrl(secondLan), publicBase);
});

test('public resource URLs follow LAN activation and return to the public origin unchanged in content', async () => {
  const auth = await manager();
  const suffix = '/api/app/v1/media/tickets/example?name=a%2Fb&url=https%3A%2F%2Fother.test#chapter-2';
  assert.equal(auth.resolveBusinessResourceUrl(publicBase + suffix), publicBase + suffix);
  auth.activateBusinessEndpoint(firstLan);
  assert.equal(auth.resolveBusinessResourceUrl(publicBase + suffix), firstLan + suffix);
  assert.equal(auth.resolveBusinessResourceUrl(publicBase + '/'), firstLan + '/');
  auth.resetBusinessEndpoint();
  assert.equal(auth.resolveBusinessResourceUrl(publicBase + suffix), publicBase + suffix);
  assert.equal(auth.resolveBusinessResourceUrl(firstLan + suffix), publicBase + suffix);
});

test('resource URLs from previously selected LAN endpoints follow the new LAN and public fallback', async () => {
  const auth = await manager();
  const suffix = '/api/app/v1/drive-tickets/example?download=1#preview';
  auth.activateBusinessEndpoint(firstLan);
  auth.activateBusinessEndpoint(secondLan);
  assert.equal(auth.resolveBusinessResourceUrl(firstLan + suffix), secondLan + suffix);
  assert.equal(auth.resolveBusinessResourceUrl(publicBase + suffix), secondLan + suffix);
  auth.resetBusinessEndpoint();
  assert.equal(auth.resolveBusinessResourceUrl(firstLan + suffix), publicBase + suffix);
  assert.equal(auth.resolveBusinessResourceUrl(secondLan + suffix), publicBase + suffix);
});

test('resource URL routing leaves unselected origins, relative paths and local resources intact', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  for (const value of [candidateLan + '/api/resource', 'https://third-party.example.test/api/resource',
    'http://third-party.example.test/image.png', '/api/app/v1/media/thumbnail',
    'data:image/png;base64,ZmFrZQ==', 'file:///data/storage/cache/image.png']) {
    assert.equal(auth.resolveBusinessResourceUrl(value), value);
  }
});

test('resource URL routing requires a selected base followed by a literal slash', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  for (const value of [publicBase + '.evil/path', publicBase + '@evil.example.test/path',
    firstLan + '0/path', publicBase, firstLan, publicBase + '?url=https://other.example.test/path',
    publicBase + '#fragment', firstLan + '?download=1']) {
    assert.equal(auth.resolveBusinessResourceUrl(value), value);
  }
});

test('resource URLs containing controls or backslashes are never rewritten', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  for (const suffix of ['/api/a\u0000b', '/api/a\rb', '/api/a\nb', '/api/a\u007Fb', '/api/a\\b']) {
    assert.equal(auth.resolveBusinessResourceUrl(publicBase + suffix), publicBase + suffix);
    assert.equal(auth.resolveBusinessResourceUrl(firstLan + suffix), firstLan + suffix);
  }
});

test('binding another public origin removes the previous account resource aliases', async () => {
  const auth = await manager();
  const nextPublic = 'https://other-mytools.example.test';
  const suffix = '/api/app/v1/media/tickets/example';
  auth.activateBusinessEndpoint(firstLan);
  await auth.restore(nextPublic);
  auth.activateBusinessEndpoint(secondLan);
  assert.equal(auth.resolveBusinessResourceUrl(publicBase + suffix), publicBase + suffix);
  assert.equal(auth.resolveBusinessResourceUrl(firstLan + suffix), firstLan + suffix);
  assert.equal(auth.resolveBusinessResourceUrl(nextPublic + suffix), secondLan + suffix);
  assert.equal(auth.publicBusinessBaseUrl(), nextPublic);
});

test('existing clients dynamically follow LAN switches, except pinned bootstrap', async () => {
  const auth = await manager();
  const existing = new AuthorizedApiClient(publicBase, auth);
  const noReplay = new AuthorizedApiClient(publicBase, auth, false);
  const pinned = new AuthorizedApiClient(publicBase, auth, true, 20000, true);
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + apiPath }, { url: firstLan + apiPath }, { url: publicBase + apiPath });
  await existing.get(apiPath);
  await noReplay.get(apiPath);
  await pinned.get(apiPath);
  const stale = new AuthorizedApiClient(firstLan, auth);
  auth.activateBusinessEndpoint(secondLan);
  responses.push({ url: secondLan + apiPath });
  await stale.get(apiPath);
  assertDrained();
});

test('only verified business traffic enters the app channel and only reads replay on failure', async () => {
  const direct = 'https://direct6.example.test:8443';
  for (const write of [false, true]) {
    const auth = await manager();
    auth.activateBusinessEndpoint(direct);
    const local = HomeChannelOrigin.open(34201, 'ab'.repeat(16), publicBase);
    try {
      assert.equal(auth.resolveBusinessBaseUrl(publicBase), direct);
      HomeChannelOrigin.activate(local);
      auth.onHomeChannelUnavailable = () => HomeChannelOrigin.close();
      assert.equal(auth.resolveBusinessBaseUrl(publicBase), local);
      assert.equal(auth.resolveBusinessResourceUrl(publicBase + apiPath), local + apiPath);
      assert.equal(auth.resolveBusinessResourceUrl(publicBase + '/external'), direct + '/external');
      assert.equal(auth.resolveBusinessResourceUrl('https://example.test' + apiPath), 'https://example.test' + apiPath);
      const client = new AuthorizedApiClient(publicBase, auth);
      responses.push({ url: local + apiPath, error: new Error('disconnected') });
      if (write) {
        await assert.rejects(client.post(apiPath));
        assert.equal(requests.length, 1);
      } else {
        responses.push({ url: direct + apiPath });
        await client.get(apiPath);
        assert.equal(requests.length, 2);
      }
      assert.equal(requests[0].options.usingProxy, false);
      assert.equal(auth.resolveBusinessBaseUrl(publicBase), direct);
      assert.throws(() => auth.normalizeBusinessBaseUrl(local));
      assertDrained();
    } finally { HomeChannelOrigin.close(); }
  }
});

test('a failing pinned channel candidate never broadcasts failure or tears down a newly confirmed route', async () => {
  for (const confirmDuringRequest of [false, true]) {
    const auth = await manager();
    const local = HomeChannelOrigin.open(34201, 'ab'.repeat(16), publicBase);
    let failures = 0;
    auth.onHomeChannelUnavailable = () => { failures++; HomeChannelOrigin.close(); };
    responses.push({ url: local + apiPath, error: new Error('candidate unavailable'),
      onRequest() { if (confirmDuringRequest) HomeChannelOrigin.activate(local); } });
    try {
      await assert.rejects(new AuthorizedApiClient(local, auth, false, 20000, true).get(apiPath));
      assert.equal(failures, 0);
      assert.equal(HomeChannelOrigin.current(), local);
      assert.equal(auth.activeBusinessBaseUrl(), publicBase);
      assert.equal(requests.length, 1);
      assertDrained();
    } finally { HomeChannelOrigin.close(); }
  }
});

test('concurrent channel reads all recover while an old failure preserves a replacement lane', async () => {
  for (const replacement of [false, true]) {
    const auth = await manager();
    const direct = 'https://direct6.example.test:8443';
    auth.activateBusinessEndpoint(direct);
    const first = HomeChannelOrigin.open(34201, 'ab'.repeat(16), publicBase);
    HomeChannelOrigin.activate(first);
    let failures = 0;
    let next = direct;
    auth.onHomeChannelUnavailable = () => {
      failures++;
      HomeChannelOrigin.close();
      if (replacement) {
        next = HomeChannelOrigin.open(34202, 'cd'.repeat(16), publicBase);
        HomeChannelOrigin.activate(next);
      }
    };
    const client = new AuthorizedApiClient(publicBase, auth);
    responses.push({ url: first + apiPath, error: new Error('lost') },
      { url: first + apiPath, error: new Error('lost') },
      { onRequest() { assert.equal(requests.at(-1).url, next + apiPath); } },
      { onRequest() { assert.equal(requests.at(-1).url, next + apiPath); } });
    try {
      await Promise.all([client.get(apiPath), client.get(apiPath)]);
      assert.equal(failures, 1);
      assert.equal(requests.length, 4);
      assertDrained();
    } finally { HomeChannelOrigin.close(); }
  }
});

test('a failing old-session channel read cannot replay into a restored account', async () => {
  const auth = await manager();
  const local = HomeChannelOrigin.open(34201, 'ab'.repeat(16), publicBase);
  HomeChannelOrigin.activate(local);
  let restoring;
  let failures = 0;
  auth.onHomeChannelUnavailable = () => failures++;
  responses.push({ url: local + apiPath, error: new Error('old connection'),
    onRequest() { restoring = auth.restore(publicBase); } });
  try {
    await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(apiPath));
    await restoring;
    assert.equal(requests.length, 1);
    assert.equal(failures, 0);
    assertDrained();
  } finally { HomeChannelOrigin.close(); }
});

test('native HTTPS direct requests select IPv6 while public and local requests retain their protocol', async () => {
  const auth = await manager();
  const direct = 'https://direct6.example.test:8443';
  const client = new AuthorizedApiClient(publicBase, auth);
  auth.activateBusinessEndpoint(direct);
  responses.push({ url: direct + apiPath });
  await client.get(apiPath);
  assert.equal(requests.at(-1).options.addressFamily, 'CURL_IPRESOLVE_V6');
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + apiPath });
  await client.get(apiPath);
  assert.equal(requests.at(-1).options.addressFamily, undefined);
  auth.resetBusinessEndpoint();
  responses.push({ url: publicBase + apiPath });
  await client.get(apiPath);
  assert.equal(requests.at(-1).options.addressFamily, undefined);
  systemModules.get('@ohos.deviceInfo').default.sdkApiVersion = 12;
  try {
    auth.activateBusinessEndpoint(direct);
    responses.push({ url: direct + apiPath });
    await client.get(apiPath);
    assert.equal(requests.at(-1).options.addressFamily, undefined);
  } finally { systemModules.get('@ohos.deviceInfo').default.sdkApiVersion = 24; }
  assertDrained();
});

test('unselected validation candidates do not borrow the active endpoint', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  const probe = new AuthorizedApiClient(candidateLan, auth, false);
  responses.push({ url: candidateLan + apiPath });
  await probe.get(apiPath);
  assert.equal(auth.activeBusinessBaseUrl(), firstLan);
});

test('network failure retries GET once and updates all existing clients', async () => {
  const auth = await manager();
  const client = new AuthorizedApiClient(publicBase, auth);
  auth.activateBusinessEndpoint(firstLan);
  const notices = [];
  auth.onBusinessEndpointUnavailable = baseUrl => notices.push(baseUrl);
  responses.push({ url: firstLan + apiPath, error: new Error('connection lost') }, { url: publicBase + apiPath });
  await client.get(apiPath);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  assert.deepEqual(notices, [firstLan]);
  responses.push({ url: publicBase + apiPath });
  await client.get(apiPath);
  assert.equal(requests.length, 3);
});

test('public GET failure is not retried', async () => {
  const auth = await manager();
  responses.push({ error: new Error('public unavailable') });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(apiPath), /public unavailable/);
  assert.equal(requests.length, 1);
});

test('a failed public retry ends the read after two attempts', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + apiPath, error: new Error('LAN unavailable') },
    { url: publicBase + apiPath, error: new Error('public unavailable') });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(apiPath), /public unavailable/);
  assert.equal(requests.length, 2);
});

test('no-replay clients still downgrade subsequent requests after a LAN failure', async () => {
  const auth = await manager();
  const client = new AuthorizedApiClient(publicBase, auth, false);
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + apiPath, error: new Error('connection lost') });
  await assert.rejects(client.get(apiPath), /connection lost/);
  assert.equal(requests.length, 1);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
});

test('POST, PUT and DELETE failures never replay writes', async () => {
  for (const method of ['post', 'putJson', 'delete']) {
    const auth = await manager();
    auth.activateBusinessEndpoint(firstLan);
    responses.push({ url: firstLan + apiPath, error: new Error('response lost') });
    const client = new AuthorizedApiClient(publicBase, auth);
    await assert.rejects(client[method](apiPath, method === 'putJson' ? {} : undefined), /response lost/);
    assert.equal(requests.length, 1, `${method} was replayed`);
    assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  }
});

test('malformed JSON and rejected redirects do not trigger a GET replay', async () => {
  for (const response of [{ body: '{' }, { headers: { location: 'https://other.example.test/' } }]) {
    const auth = await manager();
    auth.activateBusinessEndpoint(firstLan);
    responses.push(response);
    await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(apiPath));
    assert.equal(requests.length, 1);
    assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  }
});

test('an old failed request cannot undo a newer LAN selection', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ error: new Error('old connection lost'), onRequest: () => auth.activateBusinessEndpoint(secondLan) });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth, false).get(apiPath));
  assert.equal(auth.activeBusinessBaseUrl(), secondLan);
});

test('an old failed read cannot bypass a newer LAN selection by retrying publicly', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ error: new Error('old connection lost'), onRequest: () => auth.activateBusinessEndpoint(secondLan) });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(apiPath), /old connection lost/);
  assert.equal(requests.length, 1);
  assert.equal(auth.activeBusinessBaseUrl(), secondLan);
});

test('an unselected candidate failure does not replay or change the active endpoint', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: candidateLan + apiPath, error: new Error('candidate unavailable') });
  await assert.rejects(new AuthorizedApiClient(candidateLan, auth).get(apiPath), /candidate unavailable/);
  assert.equal(requests.length, 1);
  assert.equal(auth.activeBusinessBaseUrl(), firstLan);
});

test('a late pinned candidate failure cannot erase a route activated after its request began', async () => {
  for (const pin of [false, true]) {
    const auth = await manager();
    const direct = 'https://direct6.example.test:8443';
    const candidate = new AuthorizedApiClient(direct, auth, false, 20000, pin);
    let notices = 0;
    auth.onBusinessEndpointUnavailable = () => notices++;
    responses.push({ url: direct + apiPath, error: new Error('old candidate timeout'),
      onRequest() { auth.activateBusinessEndpoint(direct); } });
    await assert.rejects(candidate.get(apiPath), /old candidate timeout/);
    assert.equal(auth.activeBusinessBaseUrl(), direct);
    assert.equal(auth.isBusinessEndpointCoolingDown(direct), false);
    assert.equal(notices, 0);
    assert.equal(requests.length, 1);
  }
});

test('a late reader GET on raw IPv6 recovers through the newly verified channel without downgrading it', async () => {
  for (const stream of [false, true]) {
    const auth = await manager();
    const direct = 'https://direct6.example.test:8443';
    const reader = '/api/app/v1/reader/resource';
    auth.activateBusinessEndpoint(direct);
    const revision = auth.endpointRevision();
    let notices = 0;
    let channelFailures = 0;
    let channel;
    auth.onBusinessEndpointUnavailable = () => notices++;
    auth.onHomeChannelUnavailable = () => channelFailures++;
    responses.push({ url: direct + reader, error: new Error('old reader timeout'),
      onRequest() {
        channel = HomeChannelOrigin.open(34201, 'ab'.repeat(16), publicBase);
        HomeChannelOrigin.activate(channel);
      } }, { onRequest() { assert.equal(requests.at(-1).url, channel + reader); },
      headers: { 'content-length': '3' }, bytes: [1, 2, 3] });
    try {
      const client = new AuthorizedApiClient(publicBase, auth);
      if (stream) assert.equal(await client.downloadToUri(reader, '/test/reader'), 3);
      else await client.get(reader);
      assert.equal(auth.activeBusinessBaseUrl(), direct);
      assert.equal(auth.resolveBusinessBaseUrl(publicBase), channel);
      assert.equal(auth.endpointRevision(), revision);
      assert.equal(notices, 0);
      assert.equal(channelFailures, 0);
      assert.equal(requests.length, 2);
      assert.equal(requests.some(request => request.url === publicBase + reader), false);
      assertDrained();
    } finally { HomeChannelOrigin.close(); }
  }
});

test('raw writes that fail after a channel activates never replay or downgrade the channel', async () => {
  for (const method of ['post', 'putJson', 'delete', 'binary']) {
    const auth = await manager();
    const direct = 'https://direct6.example.test:8443';
    auth.activateBusinessEndpoint(direct);
    let channel;
    let notices = 0;
    auth.onBusinessEndpointUnavailable = () => notices++;
    responses.push({ url: direct + apiPath, error: new Error('write completion lost'),
      onRequest() {
        channel = HomeChannelOrigin.open(34201, 'ab'.repeat(16), publicBase);
        HomeChannelOrigin.activate(channel);
      } });
    try {
      const client = new AuthorizedApiClient(publicBase, auth);
      if (method === 'binary') await assert.rejects(client.postBinary(apiPath, new Uint8Array([1]).buffer, 'image/png'));
      else await assert.rejects(client[method](apiPath, method === 'putJson' ? {} : undefined));
      assert.equal(requests.length, 1);
      assert.equal(notices, 0);
      assert.equal(auth.activeBusinessBaseUrl(), direct);
      assert.equal(auth.resolveBusinessBaseUrl(publicBase), channel);
    } finally { HomeChannelOrigin.close(); }
  }
});

test('a stale failure cannot erase an A to B to A reactivation or a new network on the same URL', async () => {
  for (const networkChange of [false, true]) {
    const auth = await manager();
    auth.activateBusinessEndpoint(firstLan);
    const original = auth.endpointRevision();
    responses.push({ url: firstLan + apiPath, error: new Error('old network failure'),
      onRequest() {
        if (networkChange) auth.invalidateNetworkCandidates();
        else {
          auth.activateBusinessEndpoint(secondLan);
          auth.activateBusinessEndpoint(firstLan);
        }
      } });
    await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(apiPath), /old network failure/);
    assert.ok(auth.endpointRevision() > original);
    assert.equal(auth.activeBusinessBaseUrl(), firstLan);
    assert.equal(auth.isBusinessEndpointCoolingDown(firstLan), false);
    assert.equal(requests.length, 1);
    auth.reportBusinessEndpointUnavailable(firstLan, original);
    assert.equal(auth.activeBusinessBaseUrl(), firstLan, 'An explicit stale report changed the new route');
  }
});

test('an old raw request cannot replay into or downgrade a restored account', async () => {
  for (const method of ['get', 'post', 'binary']) {
    const auth = await manager();
    auth.activateBusinessEndpoint(firstLan);
    let restoring;
    let notices = 0;
    auth.onBusinessEndpointUnavailable = () => notices++;
    responses.push({ url: firstLan + apiPath, error: new Error('old account failure'),
      onRequest() { restoring = auth.restore('https://other-mytools.example.test'); } });
    const client = new AuthorizedApiClient(publicBase, auth);
    if (method === 'binary') await assert.rejects(client.postBinary(apiPath, new Uint8Array([1]).buffer, 'image/png'));
    else await assert.rejects(client[method](apiPath));
    await restoring;
    assert.equal(requests.length, 1);
    assert.equal(notices, 0);
    assert.equal(auth.activeBusinessBaseUrl(), 'https://other-mytools.example.test');
  }
});

test('binary uploads use the active endpoint and never replay after a network failure', async () => {
  const auth = await manager();
  const client = new AuthorizedApiClient(publicBase, auth);
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + apiPath });
  await client.postBinary(apiPath, new Uint8Array([1, 2, 3]).buffer, 'image/png');
  auth.activateBusinessEndpoint(secondLan);
  responses.push({ url: secondLan + apiPath, error: new Error('upload interrupted') });
  await assert.rejects(client.postBinary(apiPath, new Uint8Array([1]).buffer, 'image/png'), /upload interrupted/);
  assert.equal(requests.length, 2);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
});

test('stream downloads dynamically route and truncate a failed LAN attempt before retry', async () => {
  const auth = await manager();
  const client = new AuthorizedApiClient(publicBase, auth);
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + apiPath, bytes: [9, 9], error: new Error('stream interrupted') },
    { url: publicBase + apiPath, headers: { 'content-length': '3' }, bytes: [1, 2, 3] });
  assert.equal(await client.downloadToUri(apiPath, '/test/download'), 3);
  assert.deepEqual(Array.from(files.get('/test/download')), [1, 2, 3]);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
});

test('stream integrity failures are not retried and leave an empty target', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ headers: { 'content-length': '8' }, bytes: [1, 2, 3] });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).downloadToUri(apiPath, '/test/incomplete'));
  assert.equal(requests.length, 1);
  assert.equal(files.get('/test/incomplete').length, 0);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
});

test('local file open failure does not retry or downgrade a working LAN endpoint', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  fileFailures.open = true;
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).downloadToUri(apiPath, '/test/denied'), /local open denied/);
  assert.equal(requests.length, 0);
  assert.equal(auth.activeBusinessBaseUrl(), firstLan);
});

test('local write failures and short writes do not retry or downgrade the endpoint', async () => {
  for (const failure of ['write', 'shortWrite']) {
    const auth = await manager();
    auth.activateBusinessEndpoint(firstLan);
    fileFailures[failure] = true;
    responses.push({ url: firstLan + apiPath, bytes: [1, 2, 3] });
    await assert.rejects(new AuthorizedApiClient(publicBase, auth).downloadToUri(apiPath, '/test/write-denied'));
    assert.equal(requests.length, 1);
    assert.equal(auth.activeBusinessBaseUrl(), firstLan);
    assert.equal(files.get('/test/write-denied').length, 0);
  }
});

test('cancelled requests do not retry or change the active endpoint', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  const cancellation = new DownloadCancellationToken();
  responses.push({ error: new Error('aborted'), onRequest: () => cancellation.cancel() });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).get(apiPath, cancellation));
  assert.equal(requests.length, 1);
  assert.equal(auth.activeBusinessBaseUrl(), firstLan);
});

test('refresh and logout use LAN while session storage remains bound to the public origin', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + '/api/app/v1/identity/refresh',
    body: envelope({ accessToken: rotatedToken, refreshToken: 's'.repeat(48), expiresIn: 3600 }) });
  assert.equal(await auth.forceRefresh(accessToken), rotatedToken);
  assert.equal(storeState.saves.at(-1).baseUrl, publicBase);
  assert.equal(auth.publicBusinessBaseUrl(), publicBase);
  responses.push({ url: firstLan + '/api/app/v1/identity/logout' });
  await auth.logout();
  assert.equal(storeState.clears, 1);
  assertDrained();
});

test('refresh network failure downgrades subsequent requests without replaying token rotation', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + '/api/app/v1/identity/refresh', error: new Error('refresh interrupted') });
  await assert.rejects(auth.forceRefresh(accessToken), /refresh interrupted/);
  assert.equal(requests.length, 1);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  assert.equal(await auth.validAccessToken(), accessToken);
  assert.equal(storeState.clears, 0);
});

test('refresh response parsing and header failures downgrade without replaying token rotation', async () => {
  for (const response of [{ body: '{' }, { headers: { location: 'https://other.example.test/' } }]) {
    const auth = await manager();
    auth.activateBusinessEndpoint(firstLan);
    responses.push({ ...response, url: firstLan + '/api/app/v1/identity/refresh' });
    await assert.rejects(auth.forceRefresh(accessToken));
    assert.equal(requests.length, 1);
    assert.equal(auth.activeBusinessBaseUrl(), publicBase);
    assert.equal(storeState.clears, 0);
  }
});

test('login uses the trusted public origin and stores the same account binding', async () => {
  requests.length = 0;
  responses.length = 0;
  strictCalls.length = 0;
  strictInstances.length = 0;
  storeState.saves.length = 0;
  responses.push({ url: publicBase + '/api/app/v1/identity/login', body: envelope({ userId: '7',
    username: 'tester', role: 'USER', accessToken, refreshToken: 'r'.repeat(48), expiresIn: 3600 }) });
  const auth = new AuthSessionManager();
  await auth.login(publicBase, { account: 'tester', password: 'fake-password', deviceName: 'test-device' });
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  assert.equal(storeState.saves.at(-1).baseUrl, publicBase);
});

test('initial restore refresh stays on the public origin', async () => {
  requests.length = 0;
  responses.length = 0;
  strictCalls.length = 0;
  strictInstances.length = 0;
  storeState.seed = session(Date.now() - 1000);
  responses.push({ url: publicBase + '/api/app/v1/identity/refresh',
    body: envelope({ accessToken: rotatedToken, refreshToken: 's'.repeat(48), expiresIn: 3600 }) });
  const auth = new AuthSessionManager();
  await auth.restore(publicBase);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  assertDrained();
});

test('lost refresh response keeps durable operation across process restart', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ error: new Error('response lost after server commit') });
  await assert.rejects(auth.forceRefresh(accessToken));
  const sent = JSON.parse(requests[0].options.extraData);
  assert.match(sent.rotationId, /^[a-f0-9-]{36}$/);
  assert.equal(storeState.seed.refreshOperationId, sent.rotationId);
  assert.equal(storeState.seed.refreshToken, sent.refreshToken);
  storeState.seed.expiresAt = Date.now() - 24 * 60 * 60 * 1000;
  responses.push({ url: publicBase + '/api/app/v1/identity/refresh',
    body: envelope({ accessToken: rotatedToken, refreshToken: 's'.repeat(48), expiresIn: 900 }) });
  const restarted = new AuthSessionManager();
  await restarted.restore(publicBase);
  assert.deepEqual(JSON.parse(requests[1].options.extraData), sent);
  assert.equal(await restarted.validAccessToken(), rotatedToken);
  assert.equal(storeState.seed.refreshOperationId, undefined);
  assert.equal(storeState.clears, 0);
});

test('failed persistence of rotated tokens is recoverable with original operation', async () => {
  const auth = await manager();
  storeState.failSave = value => value.accessToken === rotatedToken;
  responses.push({ body: envelope({ accessToken: rotatedToken, refreshToken: 's'.repeat(48), expiresIn: 900 }) });
  await assert.rejects(auth.forceRefresh(accessToken), /secure storage/);
  const first = JSON.parse(requests[0].options.extraData);
  assert.equal(storeState.seed.refreshOperationId, first.rotationId);
  storeState.failSave = undefined;
  responses.push({ body: envelope({ accessToken: rotatedToken, refreshToken: 's'.repeat(48), expiresIn: 900 }) });
  assert.equal(await auth.forceRefresh(accessToken), rotatedToken);
  assert.deepEqual(JSON.parse(requests[1].options.extraData), first);
  assert.equal(storeState.clears, 0);
});

test('refresh cannot reach server before operation is safely persisted', async () => {
  const auth = await manager();
  storeState.failSave = () => true;
  await assert.rejects(auth.forceRefresh(accessToken), /secure storage/);
  await assert.rejects(auth.forceRefresh(accessToken), /secure storage/);
  assert.equal(requests.length, 0);
  assert.equal(storeState.clears, 0);
  storeState.failSave = undefined;
});

test('concurrent callers share one durable refresh operation', async () => {
  const auth = await manager();
  responses.push({ body: envelope({ accessToken: rotatedToken, refreshToken: 's'.repeat(48), expiresIn: 900 }) });
  const values = await Promise.all(Array.from({ length: 8 }, () => auth.forceRefresh(accessToken)));
  assert.ok(values.every(value => value === rotatedToken));
  assert.equal(requests.length, 1);
  assert.equal(storeState.saves.length, 2);
});

test('explicit rejected refresh still clears local credentials', async () => {
  const auth = await manager();
  responses.push({ status: 401, body: JSON.stringify({ code: '401', message: 'expired session' }) });
  await assert.rejects(auth.forceRefresh(accessToken), /expired session/);
  assert.equal(storeState.clears, 1);
  assert.equal(storeState.seed, undefined);
});

const ipv6Origin = 'https://direct6.example.test';
test('JSON accepts gzip while binary downloads preserve identity encoding', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(ipv6Origin);
  const client = new AuthorizedApiClient(publicBase, auth);
  responses.push({ url: ipv6Origin + apiPath });
  await client.get(apiPath);
  assert.equal(requests[0].options.header['Accept-Encoding'], 'gzip');
  responses.push({ url: ipv6Origin + apiPath, headers: { 'content-length': '3' }, bytes: [1, 2, 3] });
  assert.equal(await client.downloadToUri(apiPath, '/test/ipv6-download'), 3);
  assert.equal(requests[1].options.header['Accept-Encoding'], 'identity');
  assert.deepEqual(Array.from(files.get('/test/ipv6-download')), [1, 2, 3]);
});

test('LAN read failure tries verified IPv6 then public within one bounded operation', async () => {
  const auth = await manager();
  auth.registerVerifiedIpv6Endpoints([ipv6Origin], Date.now() + 60000);
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + apiPath, error: new Error('LAN lost') },
    { url: ipv6Origin + apiPath, error: new Error('IPv6 lost') },
    { url: publicBase + apiPath });
  await new AuthorizedApiClient(publicBase, auth).get(apiPath);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  assert.equal(requests.length, 3);
  assert.equal(auth.isBusinessEndpointCoolingDown(firstLan), true);
  assert.equal(auth.isBusinessEndpointCoolingDown(ipv6Origin), true);
});

test('writes switch future routing to IPv6 without replaying an uncertain operation', async () => {
  const auth = await manager();
  auth.registerVerifiedIpv6Endpoints([ipv6Origin], Date.now() + 60000);
  auth.activateBusinessEndpoint(firstLan);
  responses.push({ url: firstLan + apiPath, error: new Error('unknown write outcome') });
  await assert.rejects(new AuthorizedApiClient(publicBase, auth).post(apiPath));
  assert.equal(requests.length, 1);
  assert.equal(auth.activeBusinessBaseUrl(), ipv6Origin);
});

test('expired or previous-network IPv6 standbys are not used', async () => {
  const auth = await manager();
  auth.registerVerifiedIpv6Endpoints([ipv6Origin], Date.now() - 1);
  auth.activateBusinessEndpoint(firstLan);
  auth.reportBusinessEndpointUnavailable(firstLan);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
  auth.registerVerifiedIpv6Endpoints([ipv6Origin], Date.now() + 60000);
  auth.activateBusinessEndpoint(firstLan);
  auth.invalidateNetworkCandidates();
  auth.reportBusinessEndpointUnavailable(firstLan);
  assert.equal(auth.activeBusinessBaseUrl(), publicBase);
});

const previewPath = '/api/app/v1/media/items/00000000-0000-4000-8000-000000000001/thumbnail';
test('media route compares response size, keeps LAN first and ignores previous networks', async () => {
  const auth = await manager();
  const r = auth.mediaRoutes;
  for (let i=0; i<2; i++) {
    r.record(r.epoch, ipv6Origin, 65536, 100, 2100);
    r.record(r.epoch, publicBase, 65536, 850, 950);
  }
  assert.equal(r.choose(ipv6Origin, publicBase, 4096, false), ipv6Origin);
  assert.equal(r.choose(ipv6Origin, publicBase, 1024*1024, false), publicBase);
  assert.equal(r.choose(firstLan, publicBase, 1024*1024, false), firstLan);
  const old = r.epoch; auth.invalidateNetworkCandidates();
  for (let i=0; i<3; i++) r.record(old, publicBase, 65536, 1, 2);
  assert.equal(r.choose(ipv6Origin, publicBase, 1024*1024, false), ipv6Origin);
});
test('failed media GET restarts once on public, preserves API route and cools only media', async () => {
  const auth = await manager(); auth.activateBusinessEndpoint(ipv6Origin);
  const client = new AuthorizedApiClient(publicBase, auth);
  const jpg = {headers:{'content-type':'image/jpeg', 'content-length':'4'}, bytes:[255,216,255,1]};
  responses.push({url:ipv6Origin+previewPath,error:new Error('interrupted'),bytes:[1]},
    {...jpg,url:publicBase+previewPath}, {...jpg,url:publicBase+previewPath});
  assert.equal(await client.downloadImageToUri(previewPath, '/p'),4);
  assert.deepEqual([...files.get('/p')],jpg.bytes);
  assert.equal(await client.downloadImageToUri(previewPath, '/p2'),4);
  assert.equal(auth.activeBusinessBaseUrl(),ipv6Origin);
});
test('new network cancels retry of credentials from the previous route', async () => {
  const auth = await manager(); auth.activateBusinessEndpoint(ipv6Origin);
  responses.push({url:ipv6Origin+previewPath, onRequest:()=>auth.invalidateNetworkCandidates(),error:new Error('link lost')});
  await assert.rejects(new AuthorizedApiClient(publicBase,auth).downloadImageToUri(previewPath,'/p'),/network changed/);
  assert.equal(requests.length,1);
});

test('large video uses bounded reads once per network, preserves LAN, and keeps the media decision separate', async () => {
  const auth = await manager();
  auth.activateBusinessEndpoint(ipv6Origin);
  const routes = auth.mediaRoutes, used = [];
  const read = async origin => { used.push(origin); return origin === ipv6Origin ? Infinity : 1500; };
  assert.equal(await routes.chooseVideo(firstLan,publicBase,read,()=>true),firstLan);
  assert.equal(await routes.chooseVideo(publicBase,publicBase,read,()=>true),publicBase);
  assert.deepEqual(used,[]);
  assert.equal(await routes.chooseVideo(ipv6Origin,publicBase,read,()=>true),publicBase);
  assert.equal(await routes.chooseVideo(ipv6Origin,publicBase,read,()=>true),publicBase);
  assert.deepEqual(used,[ipv6Origin,publicBase]);assert.equal(auth.activeBusinessBaseUrl(),ipv6Origin);
  auth.invalidateNetworkCandidates();
  assert.equal(await routes.chooseVideo(ipv6Origin,publicBase,async origin => origin===ipv6Origin?1500:1400,()=>true),ipv6Origin);
});
test('a platform error in a speed probe still lets playback choose a live origin', async () => {
  const auth=await manager();
  const read=async origin=>{ if(origin===ipv6Origin) throw Error('probe unavailable'); return 2400; };
  assert.equal(await auth.mediaRoutes.chooseVideo(ipv6Origin,publicBase,read,()=>true),publicBase);
});
test('cancelled selections and old network comparisons cannot prime the next video', async () => {
  const auth=await manager(), routes=auth.mediaRoutes;
  const read=async origin=>origin===ipv6Origin?4000:800;
  assert.equal(await routes.chooseVideo(ipv6Origin,publicBase,read,()=>false),ipv6Origin);
  let finish;const wait=new Promise(r=>{finish=r});
  const stale=routes.chooseVideo(ipv6Origin,publicBase,async origin=> {await wait;return read(origin);},()=>true);
  routes.clear();finish();assert.equal(await stale,ipv6Origin);
  let samples=0;assert.equal(await routes.chooseVideo(ipv6Origin,publicBase,async origin=>{samples++;return read(origin);},()=>true),publicBase);
  assert.equal(samples,2);
});

(async () => {
  for (const entry of cases) {
    try {
      await entry.run();
      assertDrained();
    } catch (error) {
      error.message = `${entry.name}: ${error.message}`;
      throw error;
    }
  }
  console.log(`Business endpoint transport behavior tests passed (${cases.length} cases)`);
})().catch(error => { console.error(error); process.exitCode = 1; });
