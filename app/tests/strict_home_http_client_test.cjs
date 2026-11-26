const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const sourceRoot = path.resolve(__dirname, '../entry/src/main/ets/features/connectivity');

function load(name, mocks = {}) {
  const file = path.resolve(sourceRoot, name + '.ets');
  const moduleObject = new Module(file, module);
  moduleObject.require = key => {
    if (mocks[key]) return mocks[key];
    if (key.startsWith('.')) return load(key, mocks);
    throw Error('Unexpected dependency');
  };
  moduleObject._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText, file);
  return moduleObject.exports;
}

const { BoundedHomeHttpResponse } = load('BoundedHomeHttpResponse');
const json = '{"code":"0000","data":{"nonce":"fixture"}}';
const payload = Buffer.from(json);
const fixed = Buffer.concat([Buffer.from('HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ' + payload.length + '\r\n\r\n'), payload]);
const chunked = Buffer.from('HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n' +
  payload.subarray(0, 9).length.toString(16) + ';fixture=1\r\n' + payload.subarray(0, 9) + '\r\n' +
  payload.subarray(9).length.toString(16) + '\r\n' + payload.subarray(9) + '\r\n0\r\nX-Completed: yes\r\n\r\n');

// 每个分割位置均实际执行两次feed，覆盖长度行、正文、分块CRLF及trailer跨TLS回调。
for (const wire of [fixed, chunked]) {
  for (let cut = 0; cut <= wire.length; cut++) {
    const parser = new BoundedHomeHttpResponse();
    const first = parser.feed(wire.subarray(0, cut));
    const result = cut === wire.length ? first : parser.feed(wire.subarray(cut));
    assert.equal(result.status, 200);
    assert.equal(Buffer.from(result.body).toString(), json);
  }
  const parser = new BoundedHomeHttpResponse();
  let result;
  for (let i = 0; i < wire.length; i++) result = parser.feed(wire.subarray(i, i + 1));
  assert.equal(Buffer.from(result.body).toString(), json);
}
const eof = new BoundedHomeHttpResponse();
assert.equal(eof.feed(Buffer.from('HTTP/1.0 401 Unauthorized\r\nContent-Type: application/json\r\n\r\n' + json)), undefined);
assert.equal(eof.end().status, 401);
assert.equal(Buffer.from(eof.end().body).toString(), json);

for (const wire of [
  'HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n',
  'HTTP/1.1 200 OK\r\nLocation: https://untrusted.invalid/\r\nContent-Length: 0\r\n\r\n',
  'HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\n\r\n',
  'HTTP/1.1 200 OK\r\nContent-Length: 1\r\ncontent-length: 1\r\n\r\na',
  'HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\na',
  'HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n',
  'HTTP/1.1 200 OK\r\nContent-Length: 65537\r\n\r\n',
  'HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip,chunked\r\n\r\n',
  'HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nab',
  'HTTP/1.1 200 OK\r\nX-Bad: a\rb\r\nContent-Length: 0\r\n\r\n',
  'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\naZZ',
  'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10001\r\n',
  'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nContent-Length: 4\r\n\r\n',
  'HTTP/1.1 200 OK\r\nX-Limit: ' + 'x'.repeat(16384)
]) {
  assert.throws(() => new BoundedHomeHttpResponse().feed(Buffer.from(wire)), 'Unsafe response accepted');
}
for (const wire of [fixed.subarray(0, fixed.length - 1), chunked.subarray(0, chunked.length - 1)]) {
  const parser = new BoundedHomeHttpResponse();
  parser.feed(wire);
  assert.throws(() => parser.end());
}
const exact = new BoundedHomeHttpResponse();
const full = exact.feed(Buffer.concat([Buffer.from('HTTP/1.1 200 OK\r\nContent-Length: 65536\r\n\r\n'), Buffer.alloc(65536, 65)]));
assert.equal(full.body.length, 65536);
assert.throws(() => exact.feed(Buffer.from('x')));


// 身份资料可包含既有内联头像，只有该端点允许一 MiB 正文。
const identityMaximum = 1048576;
const larger = Buffer.alloc(identityMaximum, 65);
const identityParser = new BoundedHomeHttpResponse(identityMaximum);
const identityResponse = identityParser.feed(Buffer.concat([
  Buffer.from('HTTP/1.1 200 OK\r\nContent-Length: ' + larger.length + '\r\n\r\n'), larger
]));
assert.equal(identityResponse.body.length, identityMaximum);
assert.throws(() => new BoundedHomeHttpResponse(identityMaximum + 1));

const repeated = new BoundedHomeHttpResponse();
assert.equal(repeated.feed(Buffer.from('HTTP/1.1 200 OK\r\nVary: Origin\r\nVary: Accept-Encoding\r\nSet-Cookie: a=b\r\nSet-Cookie: c=d\r\nContent-Length: 2\r\n\r\n{}')).status, 200);

const sockets = [];
const connectors = [];
let refusal = false;
let pendingOpen;
let replyMode = 'chunked';
const token = 'fixture.token.valid';
const origin = 'https://direct.example.test:8443';
const addresses = ['2001:4860::1'];
class FakeTls {
  constructor() { this.handlers = {}; this.sent = []; this.closed = false; sockets.push(this); }
  on(name, callback) { this.handlers[name] = callback; }
  receive(bytes) {
    this.handlers.message?.({ message: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) });
  }
  async send(request) {
    this.sent.push(request);
    const line = request.split('\r\n')[0];
    assert.ok(['GET /api/app/v1/identity/me HTTP/1.1', 'GET /api/app/v1/connectivity/bootstrap HTTP/1.1',
      'POST /api/public/connectivity/challenge HTTP/1.1'].includes(line));
    assert.match(request, /Accept-Encoding: identity\r\n/);
    assert.match(request, /Connection: close\r\n/);
    const post = line.startsWith('POST');
    if (post) {
      assert.doesNotMatch(request.toLowerCase(), /authorization|cookie/);
      const body = request.split('\r\n\r\n')[1];
      assert.equal(Buffer.byteLength(body), Number(/Content-Length: ([0-9]+)/.exec(request)[1]));
      assert.deepEqual(JSON.parse(body), { probeId: 'probe', nonce: 'n'.repeat(24) });
    } else {
      assert.match(request, /Authorization: Bearer fixture.token.valid\r\n/);
      assert.equal(request.split('\r\n\r\n')[1], '');
    }
    if (replyMode === 'pending') return;
    if (replyMode === 'error') { this.handlers.error?.(); return; }
    if (replyMode === 'emptyClose') { this.handlers.close?.(); return; }
    if (replyMode === 'partial') {
      this.receive(Buffer.from('HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nshort'));
      this.handlers.close?.(); return;
    }
    if (replyMode === 'invalid') {
      this.receive(Buffer.from('HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 1\r\n\r\nx')); return;
    }
    if (replyMode === 'invalidUtf8') {
      this.receive(Buffer.concat([Buffer.from('HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\n'), Buffer.from([255])])); return;
    }
    if (replyMode === 'eof') {
      this.receive(Buffer.from('HTTP/1.0 401 Unauthorized\r\n\r\n' + json));
      this.handlers.close?.(); return;
    }
    for (let i = 0; i < chunked.length; i += 7) this.receive(chunked.subarray(i, i + 7));
    if (replyMode === 'lateSendReject') throw Error('send completed after reply');
  }
  async close() { this.closed = true; this.handlers.close?.(); }
}
class FakeConnector {
  constructor() { this.closed = false; connectors.push(this); }
  async open(candidateOrigin, candidateAddresses, ca, deadline) {
    assert.equal(candidateOrigin, origin);
    assert.equal(candidateAddresses, addresses);
    assert.equal(ca, '');
    assert.ok(deadline > 0 && deadline <= 6000);
    if (refusal) throw Error('TLS refused before headers');
    this.tls = pendingOpen ? await pendingOpen : new FakeTls();
    return this.tls;
  }
  close() { this.closed = true; this.tls?.close(); }
}
class Encoder { encodeInto(value) { return new TextEncoder().encode(value); } }
class Decoder { static create() { return { decodeToString: bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes) }; } }
const mocks = {
  '@ohos.net.socket': { default: {} },
  '@ohos.util': { default: { TextEncoder: Encoder, TextDecoder: Decoder } },
  './StrictHomeTlsConnection': { StrictHomeTlsConnection: FakeConnector }
};
const settle = () => new Promise(resolve => setImmediate(resolve));
const clientModule = load('StrictHomeHttpClient', mocks);
const { StrictHomeHttpClient } = clientModule;
const { DownloadCancellationToken } = load('../../shared/network/DownloadCancellationToken', mocks);
(async () => {
  const client = new StrictHomeHttpClient();
  const challengeBody = JSON.stringify({ probeId: 'probe', nonce: 'n'.repeat(24) });
  for (const method of ['bootstrap', 'identity']) {
    const response = await client[method](origin, token, 200, addresses);
    assert.equal(response.status, 200);
    assert.equal(response.text, json);
    assert.equal(sockets.at(-1).closed, true);
  }
  assert.equal((await client.challenge(origin, challengeBody, 200, addresses)).text, json);
  for (const invalidToken of ['', ' token', 'token ', 'bad\r\nInjected: true', 'nonascii-é', 'x'.repeat(8193)]) {
    const before = connectors.length;
    await assert.rejects(client.identity(origin, invalidToken, 200, addresses));
    assert.equal(connectors.length, before, 'Invalid credential reached a network connector');
  }
  for (const body of ['', '{}', '{"probeId":"x","nonce":"n"}',
    JSON.stringify({ probeId: 'probe', nonce: 'n'.repeat(24), token }), ' '.repeat(65537)]) {
    const before = connectors.length;
    await assert.rejects(client.challenge(origin, body, 200, addresses));
    assert.equal(connectors.length, before, 'Invalid challenge reached a network connector');
  }
  refusal = true;
  const beforeRefusal = sockets.length;
  await assert.rejects(client.identity(origin, token, 200, addresses), error => error.name !== 'BusinessResponseError');
  assert.equal(sockets.length, beforeRefusal, 'TLS refusal sent an HTTP request');
  refusal = false;
  for (const mode of ['invalid', 'invalidUtf8', 'partial']) {
    replyMode = mode;
    await assert.rejects(client.identity(origin, token, 200, addresses), error => error.name === 'BusinessResponseError');
  }
  for (const mode of ['error', 'emptyClose']) {
    replyMode = mode;
    await assert.rejects(client.identity(origin, token, 200, addresses), error => error.name !== 'BusinessResponseError');
  }
  replyMode = 'eof';
  assert.equal((await client.identity(origin, token, 200, addresses)).status, 401, '401 was not returned for caller refresh policy');
  replyMode = 'lateSendReject';
  assert.equal((await client.identity(origin, token, 200, addresses)).status, 200, 'Late send rejection overwrote complete reply');

  // 截止时间和取消均释放pending连接；晚完成的TLS也不得发送认证头。
  replyMode = 'pending';
  let finishOpen;
  pendingOpen = new Promise(resolve => { finishOpen = resolve; });
  await assert.rejects(client.identity(origin, token, 20, addresses));
  assert.equal(connectors.at(-1).closed, true);
  const late = new FakeTls();
  finishOpen(late);
  await settle();
  assert.equal(late.closed, true);
  assert.equal(late.sent.length, 0);
  pendingOpen = undefined;
  const cancelled = new DownloadCancellationToken();
  cancelled.cancel();
  const beforeCancelled = connectors.length;
  await assert.rejects(client.identity(origin, token, 200, addresses, cancelled));
  assert.equal(connectors.length, beforeCancelled);

  // 同实例并发互不干扰；取消其中一笔不能杀掉另一笔。
  const firstToken = new DownloadCancellationToken();
  const first = client.identity(origin, token, 500, addresses, firstToken);
  const second = client.bootstrap(origin, token, 500, addresses);
  const results = Promise.allSettled([first, second]);
  await settle();
  const firstSocket = sockets.at(-2), secondSocket = sockets.at(-1);
  firstToken.cancel();
  await settle();
  assert.equal(firstSocket.closed, true);
  assert.equal(secondSocket.closed, false);
  secondSocket.receive(fixed);
  const isolated = await results;
  assert.equal(isolated[0].status, 'rejected');
  assert.equal(isolated[1].status, 'fulfilled');

  const all = new StrictHomeHttpClient();
  const pending = Promise.allSettled([all.identity(origin, token, 500, addresses), all.bootstrap(origin, token, 500, addresses)]);
  await settle();
  all.close();
  assert.ok((await pending).every(value => value.status === 'rejected'));
  await assert.rejects(all.identity(origin, token, 200, addresses));
  console.log('Strict home HTTP framing, endpoint bounds, TLS-before-token, cancellation and failure semantics tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
