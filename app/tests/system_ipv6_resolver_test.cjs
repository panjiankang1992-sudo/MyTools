const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const root = path.resolve(__dirname, '../entry/src/main/ets/features/connectivity');
const hostname = 'home.example.test';
const origin = 'https://' + hostname + ':8443';
const transaction = 0x391a;
const globalAddress = '3001:1234:0:0:0:0:0:1';
const configuredResolver = '192.0.2.53';
const wires = [];
const counts = { legacy: 0, explicit: 0, properties: 0, bound: 0 };
let legacy;
let explicit;
let servers;
let onSend;
let apiVersion = 24;

class UdpSocket {
  constructor() { this.handlers = new Map(); this.closed = false; wires.push(this); }
  async bind(options) { assert.equal(options.port, 0); this.bound = options; }
  on(type, callback) { this.handlers.set(type, callback); }
  async send(options) { assert.equal(options.address.port, 53); await onSend(this, options); }
  async close() { this.closed = true; }
  message(data, address = configuredResolver, port = 53) {
    this.handlers.get('message')?.({ message: Uint8Array.from(data).buffer, remoteInfo: { address, port, family: 'IPv4' } });
  }
}
const network = { async bindSocket(wire) { assert.ok(wire.bound); counts.bound++; } };
const mocks = {
  '@ohos.net.connection': { default: {
    getAddressesByName: async () => { counts.legacy++; return legacy(); },
    getAddressesByNameWithOptions: async (host, options) => {
      assert.equal(host, hostname); assert.equal(options.family, 2); counts.explicit++; return explicit();
    },
    FamilyType: { FAMILY_TYPE_IPV6: 2 },
    getDefaultNet: async () => network,
    getConnectionProperties: async actual => { assert.equal(actual, network); counts.properties++; return { dnses: servers }; }
  } },
  '@ohos.net.socket': { default: { constructUDPSocketInstance: () => new UdpSocket() } },
  '@ohos.security.cryptoFramework': { default: { createRandom: () => ({ generateRandomSync: () => ({ data: Uint8Array.of(0x39, 0x1a) }) }) } },
  '@ohos.deviceInfo': { default: { get sdkApiVersion() { return apiVersion; } } }
};
const modules = new Map();
function load(name) {
  const file = path.join(root, name + '.ets');
  if (modules.has(file)) return modules.get(file).exports;
  const compiled = new Module(file, module);
  modules.set(file, compiled);
  compiled.require = dependency => mocks[dependency] || load(dependency.slice(2));
  compiled._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText, file);
  return compiled.exports;
}
const { SystemIpv6Resolver } = load('SystemIpv6Resolver');
function u16(value) { return Buffer.from([value >> 8, value & 255]); }
function name(value) {
  const labels = value.split('.').filter(Boolean).map(label => Buffer.concat([Buffer.from([label.length]), Buffer.from(label)]));
  return Buffer.concat([...labels, Buffer.from([0])]);
}
function ip(value) {
  const body = Buffer.alloc(16); value.split(':').forEach((part, i) => body.writeUInt16BE(parseInt(part, 16), i * 2)); return body;
}
function rr(owner, type, body) {
  return Buffer.concat([Buffer.isBuffer(owner) ? owner : name(owner), u16(type), u16(1), Buffer.from([0, 0, 0, 60]), u16(body.length), body]);
}
function response(records, question = hostname, flags = 0x8180, id = transaction) {
  return Buffer.concat([u16(id), u16(flags), u16(1), u16(records.length), u16(0), u16(0), name(question), u16(28), u16(1), ...records]);
}
const pointerToQuestion = Buffer.from([0xc0, 12]);
const direct = () => response([rr(pointerToQuestion, 28, ip(globalAddress))]);
function parse(data) { return SystemIpv6Resolver.responseAddresses(data, hostname, transaction); }
function reset() {
  wires.length = 0;
  for (const key of Object.keys(counts)) counts[key] = 0;
  apiVersion = 24;
  legacy = async () => { throw new Error('legacy unavailable'); };
  explicit = async () => { throw new Error('explicit unavailable'); };
  servers = [{ address: configuredResolver, family: 1 }];
  onSend = async (wire, options) => { wire.message(direct()); };
}

(async () => {
  // 来自真实格式的压缩问题名、多个 AAAA 及乱序 CNAME 链都可正确解析。
  assert.deepEqual(parse(direct()), [globalAddress]);
  assert.deepEqual(parse(response([rr(pointerToQuestion, 28, ip(globalAddress)), rr(pointerToQuestion, 28, ip('3001:1234:0:0:0:0:0:2'))])),
    [globalAddress, '3001:1234:0:0:0:0:0:2']);
  const alias = 'target.example.test';
  assert.deepEqual(parse(response([rr(alias, 28, ip(globalAddress)), rr(pointerToQuestion, 5, name(alias))])), [globalAddress]);
  const unrelated = rr('other.example.test', 28, ip('fd00:0:0:0:0:0:0:1'));
  assert.deepEqual(parse(response([unrelated, rr(pointerToQuestion, 28, ip(globalAddress))])), [globalAddress]);
  for (const flags of [0x0180, 0x8380, 0x8980, 0x81c0, 0x8183]) assert.throws(() => parse(response([], hostname, flags)));
  for (const malformed of [
    response([rr(pointerToQuestion, 28, ip(globalAddress))], 'wrong.example.test'),
    response([rr(pointerToQuestion, 28, ip(globalAddress))], hostname, 0x8180, transaction + 1),
    response([rr(pointerToQuestion, 28, Buffer.alloc(15))]),
    response([rr(pointerToQuestion, 28, ip(globalAddress)), rr(pointerToQuestion, 28, ip('fd00:0:0:0:0:0:0:1'))]),
    response([rr(pointerToQuestion, 5, name(alias)), rr(pointerToQuestion, 5, name('second.example.test')), rr(alias, 28, ip(globalAddress))]),
    response([rr(pointerToQuestion, 5, name(alias)), rr(alias, 5, name(hostname))]),
    response([rr(pointerToQuestion, 5, name(alias)), rr(pointerToQuestion, 28, ip(globalAddress))]),
    response([rr(Buffer.from([0xc0, 255]), 28, ip(globalAddress))]),
    response([rr(pointerToQuestion, 5, Buffer.from([0xc0, 255]))]),
    Buffer.concat([direct().subarray(0, 12), name(hostname).subarray(0, -1), Buffer.from([0xc0, 12]), u16(28), u16(1)]),
    Buffer.concat([direct(), Buffer.from([0])]),
    Buffer.alloc(4097)
  ]) assert.throws(() => parse(malformed));
  for (let i = 0; i < direct().length; i++) assert.throws(() => parse(direct().subarray(0, i)), 'Truncated packet accepted');
  const changedQuestion = Buffer.from(direct()); changedQuestion.writeUInt16BE(1, name(hostname).length + 12);
  assert.throws(() => parse(changedQuestion));
  const tooLongChain = [];
  for (let i = 0; i < 9; i++) tooLongChain.push(rr(i ? 'alias' + i + '.example.test' : hostname, 5, name('alias' + (i + 1) + '.example.test')));
  tooLongChain.push(rr('alias9.example.test', 28, ip(globalAddress)));
  assert.throws(() => parse(response(tooLongChain)));
  const query = SystemIpv6Resolver.queryPacket(hostname, transaction);
  assert.equal(query.readUInt16BE?.(0) ?? ((query[0] << 8) | query[1]), transaction);
  assert.equal(query.at(-3), 28);
  assert.throws(() => SystemIpv6Resolver.queryPacket('https://home.example.test', transaction));
  assert.throws(() => SystemIpv6Resolver.queryPacket(hostname, -1));

  reset(); legacy = async () => [{ address: globalAddress }];
  assert.deepEqual(await new SystemIpv6Resolver().resolve(origin), [globalAddress]);
  assert.equal(counts.properties, 0);
  for (const addresses of [[], [{ address: '127.0.0.1' }], [{ address: globalAddress }, { address: 'fd00::1' }]]) {
    reset(); legacy = async () => addresses;
    await assert.rejects(new SystemIpv6Resolver().resolve(origin));
    assert.equal(counts.explicit, 0, 'Invalid successful DNS answer triggered fallback');
    assert.equal(counts.properties, 0);
  }
  reset(); explicit = async () => [{ address: globalAddress }];
  assert.deepEqual(await new SystemIpv6Resolver().resolve(origin), [globalAddress]);
  assert.equal(counts.explicit, 1); assert.equal(wires.length, 0);
  reset(); explicit = async () => [];
  await assert.rejects(new SystemIpv6Resolver().resolve(origin)); assert.equal(wires.length, 0);

  // 旧系统不调用新接口，UDP 源严格沿用当前网络的解析器。
  reset(); apiVersion = 12;
  onSend = async (wire, options) => {
    assert.equal(options.address.address, configuredResolver);
    assert.equal(options.address.port, 53);
    assert.deepEqual(Buffer.from(options.data), Buffer.from(query));
    wire.message(direct(), '198.51.100.53');
    wire.message(direct(), configuredResolver, 54);
    wire.message(response([], hostname, 0x8180, transaction + 1));
    queueMicrotask(() => wire.message(direct()));
  };
  assert.deepEqual(await new SystemIpv6Resolver().resolve(origin), [globalAddress]);
  assert.equal(counts.explicit, 0); assert.equal(counts.bound, 1); assert.ok(wires.every(wire => wire.closed));

  reset(); servers = [{ address: configuredResolver }, { address: '192.0.2.54' }];
  onSend = async (wire, options) => { if (options.address.address.endsWith('.54')) wire.message(direct(), '192.0.2.54'); };
  assert.deepEqual(await new SystemIpv6Resolver().resolve(origin), [globalAddress]);
  assert.equal(wires.length, 2); assert.ok(wires.every(wire => wire.closed), 'Other pending resolvers were retained after success');

  reset(); onSend = async wire => wire.message(response([rr(pointerToQuestion, 28, ip('fe80:0:0:0:0:0:0:1'))]));
  await assert.rejects(new SystemIpv6Resolver().resolve(origin)); assert.ok(wires.every(wire => wire.closed));
  reset(); legacy = () => new Promise(() => {});
  const fallbackStarted = Date.now();
  assert.deepEqual(await new SystemIpv6Resolver().resolve(origin), [globalAddress]);
  assert.ok(Date.now() - fallbackStarted < 900, 'A stalled system API prevented the resolver fallback');
  assert.ok(wires.every(wire => wire.closed));

  reset(); onSend = async () => {};
  const started = Date.now();
  await assert.rejects(new SystemIpv6Resolver().resolve(origin));
  assert.ok(Date.now() - started < 2200); assert.ok(wires.every(wire => wire.closed), 'DNS timeout retained sockets');
  console.log('System IPv6 DNS response validation, same-network fallback, explicit-family API and deadline cleanup tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
