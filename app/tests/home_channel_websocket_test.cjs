const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');

const sockets = [];
let rejectCertificate = false;
let rejectHostname = false;
let wrongAccept = false;
let responseLine = 'HTTP/1.1 101 Switching Protocols';
let responseExtras = '';
const upgradeLogs = [];
const realInfo = console.info;
console.info = message => upgradeLogs.push(message);
const opens = [];

function serverFrame(opcode, bytes, final = true) {
  const payload = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  const header = Buffer.alloc(payload.length < 126 ? 2 : 4);
  header[0] = (final ? 128 : 0) | opcode;
  header[1] = payload.length < 126 ? payload.length : 126;
  if (header.length === 4) header.writeUInt16BE(payload.length, 2);
  return Buffer.concat([header, payload]);
}

class FakeTlsSocket {
  constructor() { this.handlers = {}; this.sent = []; sockets.push(this); }
  on(name, callback) { this.handlers[name] = callback; }
  async bind(options) { this.bound = options; }
  async connect(options) {
    this.options = options;
    if (rejectCertificate) throw Error('fixture certificate refused');
    this.validated = true;
  }
  async send(data) {
    assert.equal(this.validated, true, 'Authorization was sent before TLS validation');
    this.sent.push(data);
    if (typeof data === 'string') {
      const key = /Sec-WebSocket-Key: ([^\r]+)/.exec(data)[1];
      const accept = wrongAccept ? 'invalid' : crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
      const head = Buffer.from(responseLine + '\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n' + responseExtras + '\r\n');
      this.receive(head.subarray(0, 9));
      this.receive(head.subarray(9));
    }
  }
  async getRemoteCertificate() { return { data: new Uint8Array() }; }
  async close() { this.closed = true; }
  receive(bytes) {
    const message = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    this.handlers.message({ message, remoteInfo: {} });
  }
}

class Encoder { encodeInto(value) { return new TextEncoder().encode(value); } }
class Decoder {
  static create(encoding, options) { return new Decoder(encoding, options); }
  constructor(encoding, options) { this.decoder = new TextDecoder(encoding, options); }
  decodeToString(bytes) { return this.decoder.decode(bytes); }
}

const mocks = {
  './HomeChannelCertificate': { HomeChannelCertificate: { verify: async () => { if (rejectHostname) throw Error('fixture hostname refused'); } } },
  './StrictHomeTlsConnection': { StrictHomeTlsConnection: class {
    async open(origin, addresses, ca, timeoutMs, approvedPublicOrigin) {
      opens.push({ origin, addresses, ca, timeoutMs, approvedPublicOrigin });
      const url = new URL(origin);
      this.peer = new FakeTlsSocket();
      await this.peer.bind({ address: '::', port: 0, family: 2 });
      await this.peer.connect({ address: { address: url.hostname, port: Number(url.port), family: 2 }, skipRemoteValidation: false });
      await mocks['./HomeChannelCertificate'].HomeChannelCertificate.verify();
      return this.peer;
    }
    close() { this.peer?.close(); }
  } },
  '@ohos.net.socket': { default: { constructTLSSocketInstance: () => new FakeTlsSocket() } },
  '@ohos.net.connection': { default: { getAddressesByName: async () => [{ address: '2001:db8::1' }] } },
  '@ohos.security.cryptoFramework': { default: {
    createRandom: () => ({ generateRandomSync: n => ({ data: new Uint8Array(crypto.randomBytes(n)) }) }),
    createMd: () => {
      const hash = crypto.createHash('sha1');
      return { update: async input => hash.update(input.data), digest: async () => ({ data: hash.digest() }) };
    }
  } },
  '@ohos.util': { default: {
    TextEncoder: Encoder, TextDecoder: Decoder,
    Base64Helper: class { encodeToStringSync(value) { return Buffer.from(value).toString('base64'); } }
  } }
};
const source = path.resolve(__dirname, '../entry/src/main/ets/features/connectivity/HomeChannelWebSocket.ets');
const mod = new Module(source, module);
mod.require = name => { assert.ok(mocks[name], 'Unexpected dependency'); return mocks[name]; };
mod._compile(ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText, source);
const { HomeChannelWebSocket } = mod.exports;
const options = { header: { Authorization: 'Bearer fixture', 'X-MyTools-Channel': '1' } };
const target = 'wss://channel.example.test:8443/_mytools/app-channel/v1';

function clientPayload(data) {
  const frame = Buffer.from(data);
  assert.ok(frame[1] & 128, 'Client frame was not masked');
  let size = frame[1] & 127;
  let prefix = 2;
  if (size === 126) { size = frame.readUInt16BE(2); prefix = 4; }
  if (size === 127) { assert.equal(frame.readUInt32BE(2), 0); size = frame.readUInt32BE(6); prefix = 10; }
  const body = Buffer.alloc(size);
  for (let i = 0; i < size; i++) body[i] = frame[prefix + 4 + i] ^ frame[prefix + i % 4];
  return { opcode: frame[0] & 15, body };
}

async function settle() { for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve)); }

(async () => {
  for (const extra of [{ 'X-MyTools-Channel-Wakeup': '1' },
    { Authorization: 'Bearer fixture\r\nInjected: 1' }, { 'X-Unapproved-Header': '1' }]) {
    const before = opens.length;
    await assert.rejects(new HomeChannelWebSocket().connect(target, { header: { ...options.header, ...extra } }));
    assert.equal(opens.length, before, 'Rejected header reached TLS or Authorization');
  }
  rejectCertificate = true;
  const rejected = new HomeChannelWebSocket();
  await assert.rejects(rejected.connect(target, options));
  assert.equal(sockets.at(-1).sent.length, 0, 'Failed TLS connection leaked an upgrade request');
  rejectCertificate = false;

  rejectHostname = true;
  const wrongHost = new HomeChannelWebSocket();
  await assert.rejects(wrongHost.connect(target, options));
  assert.equal(sockets.at(-1).sent.length, 0, 'Hostname rejection leaked Authorization');
  assert.equal(sockets.at(-1).closed, true);
  rejectHostname = false;

  wrongAccept = true;
  const invalidUpgrade = new HomeChannelWebSocket();
  await assert.rejects(invalidUpgrade.connect(target, options));
  assert.equal(sockets.at(-1).closed, true);
  wrongAccept = false;
  assert.equal(upgradeLogs.at(-1), 'Home channel upgrade rejected: kind=required-headers');
  for (const [line, extras, expected] of [
    ['HTTP/1.1 403 private-fixture-reason', '', 'kind=status, status=403'],
    ['HTTP/1.1 101 Switching Protocols', 'Connection: private-fixture-critical\r\n',
      'kind=duplicate-header, category=critical'],
    ['HTTP/1.1 101 Switching Protocols', 'Upgrade: private-fixture-upgrade\r\n',
      'kind=duplicate-header, category=critical'],
    ['HTTP/1.1 101 Switching Protocols', 'Sec-WebSocket-Accept: private-fixture-accept\r\n',
      'kind=duplicate-header, category=critical'],
    ['HTTP/1.1 101 Switching Protocols', 'Sec-WebSocket-Extensions: one\r\nsec-websocket-extensions: two\r\n',
      'kind=duplicate-header, category=critical'],
    ['HTTP/1.1 101 Switching Protocols', 'Sec-WebSocket-Protocol: one\r\nSEC-WEBSOCKET-PROTOCOL: two\r\n',
      'kind=duplicate-header, category=critical'],
    ['HTTP/1.1 101 Switching Protocols', 'Sec-WebSocket-Extensions: private-fixture-extension\r\n', 'kind=extension'],
    ['HTTP/1.1 101 Switching Protocols', 'Sec-WebSocket-Protocol: private-fixture-subprotocol\r\n', 'kind=subprotocol'],
    ...['Bad Name', 'Bad@Name', ' Leading-Name', 'Trailing-Name\t', ''].map(name =>
      ['HTTP/1.1 101 Switching Protocols', name + ': private-fixture-header-name\r\n', 'kind=header-name'])
  ]) {
    responseLine = line;
    responseExtras = extras;
    const before = upgradeLogs.length;
    await assert.rejects(new HomeChannelWebSocket().connect(target, options));
    assert.deepEqual(upgradeLogs.slice(before), ['Home channel upgrade rejected: ' + expected]);
    assert.equal(sockets.at(-1).closed, true);
  }
  responseLine = 'HTTP/1.1 101 Switching Protocols';
  for (const extras of [
    'Set-Cookie: private-fixture-one\r\nSet-Cookie: private-fixture-two\r\n',
    'Date: private-fixture-one\r\ndate: private-fixture-two\r\n',
    'Server: private-fixture-one\r\nServer: private-fixture-two\r\n',
    'X-Fixture-Secret: private-fixture-one\r\nx-fixture-secret: private-fixture-two\r\n'
  ]) {
    responseExtras = extras;
    const before = upgradeLogs.length;
    const ordinary = new HomeChannelWebSocket();
    await ordinary.connect(target, options);
    assert.equal(upgradeLogs.length, before, 'An ignored ordinary header influenced handshake validation');
    await ordinary.close();
  }
  responseExtras = '';
  assert.ok(upgradeLogs.every(value => /^Home channel upgrade rejected: (kind=status, status=\d{1,3}|kind=duplicate-header, category=critical|kind=(required-headers|extension|subprotocol|header-name))$/.test(value)));
  assert.doesNotMatch(upgradeLogs.join('\n'), /private-fixture|X-Fixture-Secret|Set-Cookie:/);

  const wire = new HomeChannelWebSocket();
  const messages = [];
  let faults = 0;
  wire.on('message', (error, value) => { assert.equal(error, undefined); messages.push(value); });
  wire.on('error', () => faults++);
  wire.resetReadStats();
  await wire.connect(target, options);
  const peer = sockets.at(-1);
  assert.doesNotMatch(peer.sent[0], /x-mytools-channel-wakeup/i);
  assert.deepEqual(peer.bound, { address: '::', port: 0, family: 2 });
  assert.equal(peer.options.address.address, 'channel.example.test', 'DNS lookup replaced TLS hostname');
  assert.equal(peer.options.skipRemoteValidation, false);
  assert.equal(opens.at(-1).approvedPublicOrigin, '');
  peer.receive(serverFrame(1, 'READY'));
  assert.deepEqual(messages, ['READY']);
  messages.length = 0;
  // 首次转发重置只保留三个基础输入计数，重复重置不依赖原生句柄。
  wire.resetReadStats();
  wire.resetReadStats();
  await settle();
  wire.resetReadStats();
  assert.deepEqual(wire.readStats(), { bytes: 0, callbacks: 0, spanMs: 0 });

  await wire.send('BEGIN');
  assert.deepEqual(clientPayload(peer.sent.at(-1)), { opcode: 1, body: Buffer.from('BEGIN') });
  const large = new Uint8Array(65536).fill(99);
  await wire.send(large.buffer);
  assert.deepEqual(clientPayload(peer.sent.at(-1)), { opcode: 2, body: Buffer.from(large) });
  await assert.rejects(wire.send(new ArrayBuffer(65537)));

  // 控制帧可以穿插在片段之间，二进制分片必须复原成同一个消息。
  const frames = Buffer.concat([serverFrame(2, Buffer.from([1, 2]), false),
    serverFrame(9, Buffer.from([8, 9])), serverFrame(0, Buffer.from([3, 4]))]);
  for (let offset = 0; offset < frames.length; offset += 3) peer.receive(frames.subarray(offset, offset + 3));
  await settle();
  assert.deepEqual(new Uint8Array(messages[0]), new Uint8Array([1, 2, 3, 4]));
  assert.deepEqual(clientPayload(peer.sent.at(-1)), { opcode: 10, body: Buffer.from([8, 9]) });
  assert.equal(wire.readStats().bytes, frames.length);
  assert.equal(wire.readStats().callbacks, Math.ceil(frames.length / 3));
  assert.ok(wire.readStats().spanMs >= 0);

  // 声明超大消息、无起始帧的续帧与掩码服务器帧都必须拒绝。
  const malformed = Buffer.from([130, 127, 0, 0, 0, 0, 0, 1, 0, 1]);
  peer.receive(malformed);
  assert.equal(faults, 1);
  assert.equal(peer.closed, true);
  const frozenStats = wire.readStats();
  peer.receive(Buffer.from('late-after-close'));
  assert.deepEqual(wire.readStats(), frozenStats, 'Late TLS callbacks changed the closed snapshot');
  await assert.rejects(wire.send('after close'));
  for (const frame of [Buffer.from([128, 0]), Buffer.from([130, 128, 0, 0, 0, 0])]) {
    const bad = new HomeChannelWebSocket();
    await bad.connect(target, options);
    sockets.at(-1).receive(frame);
    assert.equal(sockets.at(-1).closed, true);
  }
  const publicWire = new HomeChannelWebSocket();
  const service = 'https://public.example.test:443';
  await publicWire.connect('wss://public.example.test:443/_mytools/app-channel/v1', {
    ...options, approvedPublicOrigin: service
  });
  assert.deepEqual(opens.at(-1), { origin: service, addresses: undefined, ca: '', timeoutMs: 6000,
    approvedPublicOrigin: service }, 'Public approval or its exact port was lost before strict TLS');
  await publicWire.close();

  console.log('Strict channel TLS ordering, upgrade, masking, fragmented messages, control frames and bounds tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; })
  .finally(() => { console.info = realInfo; });
