const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const root = path.resolve(__dirname, '../entry/src/main/ets/features/connectivity');
const origin = 'https://direct.example.test:8443';
const candidates = ['2001:4860:1::1', '2001:4860:1::2', '2001:4860:1::3'];
let tcpPeers = [];
let tlsPeers = [];
let badPeer = false;
let badPort = false;
let badCertificate = false;
let badChain = false;
let failedCandidates = new Set();
let bindBarrier;
let tlsBarrier;
let checkedHosts = [];
let publicAnswers;
let dnsBarrier;
let dnsLookups = [];
let boundNetworks = [];
let standalonePeer;
let timerBudgets = [];
const nativeTimeout = global.setTimeout;
global.setTimeout = (callback, delay, ...args) => {
  timerBudgets.push(delay);
  return nativeTimeout(callback, delay, ...args);
};

const publicOrigin = 'https://public.example.test';
const net = {
  netId: 100,
  async getAddressesByName(host) {
    dnsLookups.push(host);
    if (dnsBarrier) await dnsBarrier.promise;
    return publicAnswers.map(address => ({ address }));
  },
  async bindSocket(tcp) {
    boundNetworks.push(tcp);
  }
};

class Tcp {
  constructor() { tcpPeers.push(this); this.closes = 0; }
  async bind(options) { this.bindOptions = options; if (bindBarrier) await bindBarrier.promise; }
  async connect(options) {
    this.options = options;
    if (failedCandidates.has(options.address.address)) throw Error('fixture refused');
    this.connected = true;
  }
  async close() { this.closes++; }
}
class Tls {
  constructor(tcp) { if (tcp) assert.ok(tcp.connected); this.tcp = tcp; this.closes = 0; tlsPeers.push(this); }
  async bind(options) { this.bound = options; if (bindBarrier) await bindBarrier.promise; }
  async connect(options) {
    this.options = options;
    this.resolvedAddress = this.tcp?.options.address.address ?? standalonePeer ??
      publicAnswers.find(address => !address.includes(':'));
    if (!this.tcp && failedCandidates.has(this.resolvedAddress)) throw Error('fixture refused');
    if (badChain) throw Error('fixture chain refused');
    if (tlsBarrier) await tlsBarrier.promise;
  }
  async getRemoteCertificate() { this.certificateRead = true; return { data: new Uint8Array([1]) }; }
  async getRemoteAddress() {
    return { address: badPeer ? (this.options.address.family === 1 ? '8.8.4.4' : '::1') : this.resolvedAddress,
      port: badPort ? 443 : this.options.address.port };
  }
  async close() { this.closes++; if (this.tcp) this.tcp.closes++; }
}
const mocked = {
  '@ohos.net.socket': { default: { constructTCPSocketInstance: () => new Tcp(), constructTLSSocketInstance: tcp => new Tls(tcp) } },
  '@ohos.net.connection': { default: { getDefaultNet: async () => net } },
  './HomeChannelCertificate': { HomeChannelCertificate: { verify: async (_, host) => {
    checkedHosts.push(host);
    if (badCertificate) throw Error('fixture SAN refused');
  } } },
  './SystemIpv6Resolver': { SystemIpv6Resolver: class { async resolve() { return candidates.slice(0, 1); } } }
};
const cache = new Map();
function load(filename) {
  if (cache.has(filename)) return cache.get(filename).exports;
  const compiled = new Module(filename, module);
  cache.set(filename, compiled);
  compiled.require = name => mocked[name] ?? load(path.resolve(path.dirname(filename), name + '.ets'));
  compiled._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText, filename);
  return compiled.exports;
}
const { StrictHomeTlsConnection } = load(path.join(root, 'StrictHomeTlsConnection.ets'));
function reset() {
  tcpPeers = []; tlsPeers = []; badPeer = false; badPort = false; badCertificate = false;
  badChain = false; failedCandidates = new Set(); bindBarrier = undefined; tlsBarrier = undefined; checkedHosts = [];
  publicAnswers = ['8.8.8.8', '1.1.1.1', candidates[0]];
  dnsBarrier = undefined; dnsLookups = []; boundNetworks = []; standalonePeer = undefined; timerBudgets = [];
}
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}
async function settle() { for (let i = 0; i < 8; i++) await new Promise(resolve => setImmediate(resolve)); }

(async () => {
  reset();
  const normal = new StrictHomeTlsConnection();
  const wire = await normal.open(origin);
  assert.equal(tcpPeers[0].options.address.address, candidates[0]);
  assert.equal(tcpPeers[0].options.address.family, 2);
  assert.ok(tcpPeers[0].options.timeout <= 2500, 'Direct candidate budget was enlarged');
  assert.equal(wire.options.address.address, 'direct.example.test');
  assert.equal(wire.options.skipRemoteValidation, false);
  assert.deepEqual(checkedHosts, ['direct.example.test']);
  assert.deepEqual(wire.options.secureOptions, {});
  normal.close();
  assert.ok(wire.closes > 0);

  for (const list of [[], ['::1'], [candidates[0], 'fc00::1'], ['2001:db8::1']]) {
    reset();
    await assert.rejects(StrictHomeTlsConnection.connect(origin, list));
    assert.equal(tcpPeers.length, 0, 'Rejected addresses reached the network');
  }
  for (const setting of ['badPeer', 'badPort', 'badCertificate', 'badChain']) {
    reset();
    if (setting === 'badPeer') badPeer = true;
    if (setting === 'badPort') badPort = true;
    if (setting === 'badCertificate') badCertificate = true;
    if (setting === 'badChain') badChain = true;
    await assert.rejects(StrictHomeTlsConnection.connect(origin));
    assert.ok(tlsPeers[0].closes > 0);
  }

  reset();
  const fixture = new StrictHomeTlsConnection();
  const privateWire = await fixture.open('https://10.0.2.2:18445', undefined, 'explicit-test-ca');
  assert.equal(privateWire.tcp.options.address.family, 1);
  assert.ok(privateWire.tcp.options.timeout <= 2500, 'Fixture candidate budget was enlarged');
  assert.equal(privateWire.options.address.address, '10.0.2.2');
  assert.equal(privateWire.options.secureOptions.ca, 'explicit-test-ca');
  fixture.close();
  reset();
  await assert.rejects(StrictHomeTlsConnection.connect('https://10.0.2.2:18445'));
  assert.equal(tcpPeers.length, 0);

  reset();
  failedCandidates.add(candidates[0]);
  const retry = new StrictHomeTlsConnection();
  await retry.open(origin, candidates);
  assert.equal(tcpPeers.length, 2);
  assert.ok(tcpPeers[0].closes > 0);
  retry.close();
  reset();
  candidates.forEach(value => failedCandidates.add(value));
  await assert.rejects(StrictHomeTlsConnection.connect(origin, candidates));
  assert.equal(tcpPeers.length, 2, 'Candidate attempts exceeded the bound');

  // bind 或 TLS 握手在取消之后才完成时，不能发送任何业务数据或留下句柄。
  for (const stage of ['bind', 'tls']) {
    reset();
    const blocked = deferred();
    if (stage === 'bind') bindBarrier = blocked;
    else tlsBarrier = blocked;
    const connector = new StrictHomeTlsConnection();
    const pending = connector.open(origin);
    await settle();
    connector.close();
    await assert.rejects(pending);
    blocked.resolve();
    await settle();
    assert.ok(tcpPeers[0].closes > 0);
    assert.equal(checkedHosts.length, 0);
    if (stage === 'bind') assert.equal(tlsPeers.length, 0);
    else assert.ok(tlsPeers[0].closes >= 2, 'Late TLS completion was not closed again');
  }
  reset();
  bindBarrier = deferred();
  const timed = new StrictHomeTlsConnection();
  await assert.rejects(timed.open(origin, undefined, '', 15));
  bindBarrier.resolve();
  await settle();
  assert.ok(tcpPeers[0].closes >= 2);

  // 只有完全相同的可信服务来源才允许按当前网络解析并连接全球 IPv4。
  reset();
  const publicConnector = new StrictHomeTlsConnection();
  const publicWire = await publicConnector.open(publicOrigin, undefined, '', 6000, publicOrigin);
  assert.equal(publicWire.tcp, undefined, 'Public TLS unexpectedly used the preconnected TCP constructor');
  assert.equal(tcpPeers.length, 0);
  assert.deepEqual(publicWire.bound, { address: '0.0.0.0', port: 0, family: 1 });
  assert.equal(publicWire.options.address.family, 1);
  assert.ok(Math.max(...timerBudgets) > 2500 && Math.max(...timerBudgets) <= 6000,
    'Public cold TLS handshake lost its bounded extended budget');
  assert.deepEqual(boundNetworks, [], 'Standalone TLS was cast to the unsupported NetHandle socket type');
  assert.deepEqual(dnsLookups, ['public.example.test']);
  assert.equal(publicWire.options.address.address, 'public.example.test');
  assert.equal(publicWire.options.skipRemoteValidation, false);
  assert.deepEqual(publicWire.options.secureOptions, {});
  assert.deepEqual(checkedHosts, ['public.example.test']);
  publicConnector.close();
  assert.ok(publicWire.closes > 0);
  for (const approval of ['https://public.example.test:443', 'https://public.example.tests',
    'https://sub.public.example.test', 'https://public.example.test:8443']) {
    reset();
    await assert.rejects(StrictHomeTlsConnection.connect(publicOrigin, undefined, '', 6000, approval));
    assert.equal(tcpPeers.length, 0);
    assert.equal(dnsLookups.length, 0);
  }
  reset();
  await assert.rejects(StrictHomeTlsConnection.connect(publicOrigin, ['8.8.8.8']));
  assert.equal(tcpPeers.length, 0, 'An unapproved origin enabled IPv4');
  await assert.rejects(StrictHomeTlsConnection.connect(publicOrigin, ['8.8.8.8'], '', 6000, publicOrigin));
  assert.equal(dnsLookups.length, 0, 'Caller-provided addresses bypassed the current network resolver');
  reset();
  await assert.rejects(StrictHomeTlsConnection.connect('https://10.0.2.2:18445', undefined,
    'explicit-test-ca', 6000, 'https://10.0.2.2:18445'));
  assert.equal(tcpPeers.length, 0, 'Public mode inherited the private fixture exception');

  const rejectedIpv4 = ['0.1.2.3', '10.0.0.1', '100.64.0.0', '100.127.255.255', '127.0.0.1',
    '169.254.1.1', '172.16.0.0', '172.31.255.255', '192.168.0.1', '192.0.0.9', '192.0.2.1',
    '192.88.99.1', '198.18.0.0', '198.19.255.255', '198.51.100.1', '203.0.113.1',
    '224.0.0.0', '239.255.255.255', '240.0.0.1', '255.255.255.255', '008.8.8.8', '8.8.8',
    '134744072', '0x08080808', '+8.8.8.8', '8.8.8.8 ', '256.8.8.8'];
  for (const address of rejectedIpv4) {
    reset(); publicAnswers = ['8.8.8.8', address];
    await assert.rejects(StrictHomeTlsConnection.connect(publicOrigin, undefined, '', 6000, publicOrigin));
    assert.equal(tcpPeers.length, 0, 'A rejected DNS address reached TCP');
  }
  for (const answer of [[], ['::ffff:8.8.8.8'], ['::1'], ['fe80::1%test']]) {
    reset(); publicAnswers = answer;
    await assert.rejects(StrictHomeTlsConnection.connect(publicOrigin, undefined, '', 6000, publicOrigin));
    assert.equal(tcpPeers.length, 0);
  }
  for (const address of ['100.63.255.255', '100.128.0.0', '172.15.255.255', '172.32.0.0',
    '198.17.255.255', '198.20.0.0', '223.255.255.255']) {
    reset(); publicAnswers = [address];
    const connector = new StrictHomeTlsConnection();
    await connector.open(publicOrigin, undefined, '', 6000, publicOrigin);
    assert.equal(tlsPeers[0].resolvedAddress, address);
    assert.equal(tcpPeers.length, 0);
    connector.close();
  }
  for (const setting of ['badPeer', 'badPort', 'badCertificate', 'badChain']) {
    reset();
    if (setting === 'badPeer') badPeer = true;
    if (setting === 'badPort') badPort = true;
    if (setting === 'badCertificate') badCertificate = true;
    if (setting === 'badChain') badChain = true;
    // 默认公网 HTTPS 端口就是 443，使用其他端口才能验证端口伪装。
    const service = 'https://public.example.test:8443';
    await assert.rejects(StrictHomeTlsConnection.connect(service, undefined, '', 6000, service));
    assert.ok(tlsPeers.every(peer => peer.closes > 0));
  }
  reset();
  publicAnswers = ['8.8.8.8', '1.1.1.1', '9.9.9.9'];
  publicAnswers.forEach(value => failedCandidates.add(value));
  await assert.rejects(StrictHomeTlsConnection.connect(publicOrigin, undefined, '', 6000, publicOrigin));
  assert.equal(tlsPeers.length, 2, 'Public candidate attempts exceeded the bound');
  assert.equal(tcpPeers.length, 0);

  reset();
  standalonePeer = '1.1.1.1';
  const alternate = new StrictHomeTlsConnection();
  await alternate.open(publicOrigin, undefined, '', 6000, publicOrigin);
  alternate.close();
  reset();
  publicAnswers = ['8.8.8.8', '1.1.1.1', '9.9.9.9'];
  standalonePeer = '9.9.9.9';
  await assert.rejects(StrictHomeTlsConnection.connect(publicOrigin, undefined, '', 6000, publicOrigin));
  assert.ok(tlsPeers.every(peer => peer.closes > 0), 'TLS peer outside the two-address snapshot was accepted');

  // 解析、独立 TLS 绑定及握手晚于取消完成时都不能泄漏后续连接。
  for (const stage of ['dns', 'bind', 'tls']) {
    reset();
    const blocked = deferred();
    if (stage === 'dns') dnsBarrier = blocked;
    else if (stage === 'bind') bindBarrier = blocked;
    else tlsBarrier = blocked;
    const connector = new StrictHomeTlsConnection();
    const pending = connector.open(publicOrigin, undefined, '', 6000, publicOrigin);
    await settle(); connector.close(); await assert.rejects(pending);
    blocked.resolve(); await settle();
    if (stage === 'dns') assert.equal(tcpPeers.length, 0);
    else assert.ok(tlsPeers.every(peer => peer.closes > 0));
    if (stage === 'tls') assert.ok(tlsPeers[0].closes >= 2);
  }
  console.log('Strict home TLS IPv6/public IPv4 approval, identity, peer, cancellation and timeout tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; })
  .finally(() => { global.setTimeout = nativeTimeout; });
