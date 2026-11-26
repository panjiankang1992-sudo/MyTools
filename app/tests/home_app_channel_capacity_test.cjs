const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');

const typescript = require(process.argv[2] ||
  '/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const sourceRoot = path.resolve(__dirname, '../entry/src/main/ets/features/connectivity');
const key = '0123456789abcdef0123456789abcdef';
const sockets = [];
const readers = new Map();
const ended = [];
const delayedReadRejections = [];
let accepted;
let failWrite = false;
let readFailureId = -1;
let fastPoolDeadline = false;
const logs = [];
const writes = new Map();
const realInfo = console.info;
const nativeTimeout = global.setTimeout;
global.setTimeout = (callback, delay, ...args) => nativeTimeout(callback,
  fastPoolDeadline && delay === 15000 ? 20 : delay, ...args);
console.info = message => logs.push(message);

function bytes(text) {
  return new TextEncoder().encode(text).buffer;
}

function finishReader(id, action) {
  const pending = readers.get(id);
  assert.ok(pending, 'Expected the request to be waiting for local bytes');
  readers.delete(id);
  action(pending);
}

const loopback = {
  start(callback) { accepted = callback; return 32131; },
  read(id) {
    const pending = readers.get(id);
    if (pending?.initial) {
      readers.delete(id);
      return Promise.resolve(pending.initial);
    }
    if (id === readFailureId) throw Error('fixture synchronous read failure');
    assert.equal(pending, undefined, 'Concurrent read on a local connection');
    return new Promise((resolve, reject) => readers.set(id, { resolve, reject }));
  },
  write: async (id, data) => {
    if (failWrite) throw Error('fixture local socket closed');
    if (!writes.has(id)) writes.set(id, []);
    writes.get(id).push(Buffer.from(data));
  },
  end(id) {
    ended.push(id);
    const pending = readers.get(id);
    readers.delete(id);
    if (id === 6 && pending?.reject) {
      delayedReadRejections.push(() => pending.reject(new Error('Channel closed')));
      return;
    }
    pending?.reject?.(new Error('Channel closed'));
  },
  stop() {
    for (const id of [...readers.keys()]) loopback.end(id);
  }
};

class WebSocket {
  constructor() {
    this.events = new Map();
    this.sent = [];
    this.closed = false;
    sockets.push(this);
    assert.ok(sockets.filter(item => !item.closed).length <= 5, 'More than five remote lanes were opened');
  }

  on(event, callback) { this.events.set(event, callback); }
  resetReadStats() { this.readBytes = 0; this.readCallbacks = 0; }
  readStats() {
    assert.equal(this.closed, false, 'Lane snapshot was read after closing the wire');
    return { bytes: this.readBytes ?? 0, callbacks: this.readCallbacks ?? 0, spanMs: 0 };
  }

  async connect(url, options) {
    this.url = url;
    this.options = options;
    queueMicrotask(() => this.message('READY'));
    return true;
  }

  async send(data) {
    assert.equal(this.closed, false, 'A closed lane was reused');
    this.sent.push(data);
    return true;
  }

  async close() {
    this.closed = true;
    queueMicrotask(() => this.events.get('close')?.());
    return true;
  }

  message(data) {
    this.readBytes = (this.readBytes ?? 0) + (typeof data === 'string' ? Buffer.byteLength(data) : data.byteLength);
    this.readCallbacks = (this.readCallbacks ?? 0) + 1;
    this.events.get('message')?.(undefined, data);
  }
}

const mocks = {
  './HomeChannelWebSocket': { HomeChannelWebSocket: WebSocket },
  '@ohos.util': { default: { generateRandomUUID: () => key,
    TextEncoder: class { encodeInto(value) { return new TextEncoder().encode(value); } } } },
  'libhome_channel.so': { default: loopback }
};
const modules = new Map();

function load(name) {
  if (modules.has(name)) return modules.get(name).exports;
  const filename = path.join(sourceRoot, name + '.ets');
  const compiled = new Module(filename, module);
  modules.set(name, compiled);
  compiled.require = dependency => {
    if (mocks[dependency]) return mocks[dependency];
    assert.ok(dependency.startsWith('./'), 'Unexpected platform dependency');
    return load(dependency.substring(2));
  };
  compiled._compile(typescript.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: {
      module: typescript.ModuleKind.CommonJS,
      target: typescript.ScriptTarget.ES2020
    }
  }).outputText, filename);
  return compiled.exports;
}

async function settle() {
  for (let i = 0; i < 12; i++) await Promise.resolve();
  await new Promise(resolve => setImmediate(resolve));
}

async function dispatch(id, target, method = 'GET') {
  readers.set(id, { initial: bytes(method + ' /' + key + target +
    ' HTTP/1.1\r\nHost: localhost\r\nX-Qos-Id: ' + id + '\r\n\r\n') });
  accepted(id);
  await settle();
}
function laneFor(id) {
  return sockets.find(lane => lane.sent.some(data => data instanceof ArrayBuffer &&
    new TextDecoder().decode(data).includes('X-Qos-Id: ' + id + '\r\n')));
}
async function eventually(check) {
  for (let i = 0; i < 20; i++) {
    if (check()) return;
    await new Promise(resolve => nativeTimeout(resolve, 10));
  }
  assert.fail('Expected bounded queue progress');
}
async function complete(id) {
  const lane = laneFor(id);
  assert.ok(lane, 'Expected a forwarded request');
  lane.message(bytes('HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n'));
  lane.message('END');
  await settle();
}
function busyResponse(id, headOnly = false) {
  const reply = Buffer.concat(writes.get(id) ?? []).toString('utf8');
  const [head, body] = reply.split('\r\n\r\n');
  assert.match(head, /^HTTP\/1\.1 503 Service Unavailable/);
  assert.match(head, /\r\nRetry-After: 1\r\n/);
  assert.match(head, /\r\nConnection: close$/);
  if (headOnly) assert.equal(body, '');
  else {
    assert.equal(Number(/Content-Length: ([0-9]+)/.exec(head)[1]), Buffer.byteLength(body));
    assert.equal(JSON.parse(body).code, 'CONNECTIVITY_BUSY');
  }
  return reply;
}
const ticket = '/api/app/v1/media/tickets/' + 'ab'.repeat(16);
const thumbnail = '/api/app/v1/media/items/01234567-89ab-cdef-0123-456789abcdef/thumbnail';

(async () => {
  const { HomeAppChannel } = load('HomeAppChannel');
  const channel = new HomeAppChannel();
  try {
    await channel.start('https://home.example.test:8443', 'https://public.example.test', async () => 'fixture');
    assert.equal(sockets.at(-1).options.approvedPublicOrigin, '');
    assert.deepEqual(Object.keys(sockets.at(-1).options.header).sort(), ['Authorization', 'X-MyTools-Channel']);
    for (const id of [101, 102, 103, 104]) {
      await dispatch(id, id === 101 ? thumbnail + '?route=/api/app/v1/identity/me' : ticket);
      assert.ok(laneFor(id), 'The first four media requests did not start');
    }
    await dispatch(105, thumbnail);
    await dispatch(106, ticket + '/master.m3u8');
    assert.equal(laneFor(105), undefined);
    assert.equal(laneFor(106), undefined);

    // 四条慢媒体流不能占用控制请求的保留容量，查询参数也不能改变分类。
    await dispatch(107, '/api/app/v1/identity/me?route=' + encodeURIComponent(thumbnail));
    assert.ok(laneFor(107), 'Saturated media requests blocked the profile');
    await complete(107);
    await dispatch(108, ticket + '/metrics');
    assert.ok(laneFor(108), 'Small playback metadata lost control capacity');
    await complete(108);
    assert.equal(laneFor(105), undefined, 'A fifth bulk request used the reserved control slot');

    // 排队取消应清理队首，后续媒体保持先来先服务。
    finishReader(105, pending => pending.resolve(new ArrayBuffer(0)));
    await eventually(() => ended.includes(105));
    assert.equal(laneFor(105), undefined);
    await complete(101);
    await eventually(() => Boolean(laneFor(106)));
    await dispatch(109, ticket.replace('/media/', '/%6dedia/'));
    await dispatch(110, thumbnail);
    await complete(102);
    await eventually(() => Boolean(laneFor(109)));
    assert.equal(laneFor(110), undefined, 'Newer media skipped an earlier media request');

    // 活跃媒体取消会回收连接，待处理媒体不必等待远端超时。
    finishReader(106, pending => pending.resolve(new ArrayBuffer(0)));
    await eventually(() => Boolean(laneFor(110)));
    await dispatch(111, '/api/app/v1/identity/me');
    assert.ok(laneFor(111), 'Control capacity was lost after cancellation');
    await complete(111);

    // 使用加速时钟验证队列截止，实际源码仍保留十五秒的固定上限。
    fastPoolDeadline = true;
    await dispatch(112, thumbnail);
    await eventually(() => ended.includes(112));
    fastPoolDeadline = false;
    assert.equal(laneFor(112), undefined);
    busyResponse(112);
    assert.ok(logs.includes('Home channel event: pooldeadline'));
    await dispatch(113, '/api/app/v1/identity/me');
    assert.ok(laneFor(113), 'Queue expiry leaked control capacity');
    failWrite = true;
    laneFor(113).message(bytes('HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}'));
    await settle();
    failWrite = false;
    assert.ok(logs.includes('Home channel event: localwrite'));
    await dispatch(114, '/api/app/v1/identity/me');
    assert.ok(laneFor(114));
    laneFor(114).events.get('close')();
    await settle();
    assert.ok(logs.includes('Home channel event: remoteclose'));
    readFailureId = 115;
    await dispatch(115, '/api/app/v1/identity/me');
    await eventually(() => ended.includes(115));
    await dispatch(116, '/api/app/v1/identity/me');
    assert.ok(laneFor(116), 'Synchronous local read failure retained a queue head');
    fastPoolDeadline = true;
    await dispatch(117, '/api/app/v1/testing/write', 'POST');
    await eventually(() => ended.includes(117));
    fastPoolDeadline = false;
    busyResponse(117);
    assert.equal(laneFor(117), undefined, 'An expired write request was replayed upstream');
    fastPoolDeadline = true;
    await dispatch(119, thumbnail, 'HEAD');
    await eventually(() => ended.includes(119));
    fastPoolDeadline = false;
    busyResponse(119, true);
    assert.equal(laneFor(119), undefined);
    await complete(116);
    await dispatch(118, '/api/app/v1/identity/me');
    await eventually(() => Boolean(laneFor(118)));
    assert.ok(laneFor(118), 'A busy response destroyed the healthy control lane');

    // 队列满时返回相同的完整容量响应，取消排队写操作时不发送忙碌响应或重放请求。
    for (let id = 200; id < 212; id++) await dispatch(id, '/api/app/v1/testing/write', 'POST');
    await dispatch(212, '/api/app/v1/testing/write', 'POST');
    await eventually(() => ended.includes(212));
    assert.equal(busyResponse(212), busyResponse(117));
    assert.equal(laneFor(212), undefined);
    for (let id = 200; id < 212; id++) finishReader(id, pending => pending.resolve(new ArrayBuffer(0)));
    await eventually(() => Array.from({ length: 12 }, (_, i) => i + 200).every(id => ended.includes(id)));
    for (let id = 200; id < 212; id++) {
      assert.equal(laneFor(id), undefined, 'A cancelled write reached the remote channel');
      assert.equal(writes.has(id), false, 'A cancelled request received a late busy response');
    }

    // 精确书源列表同样只占四条批量通道，近似路径或查询内容不能挤占控制保留槽。
    channel.stop();
    const publicOrigin = 'https://public.example.test';
    await channel.start(publicOrigin, publicOrigin, async () => 'fixture');
    assert.equal(sockets.at(-1).options.approvedPublicOrigin, publicOrigin);
    assert.equal(sockets.at(-1).url, 'wss://public.example.test/_mytools/app-channel/v1');
    const sources = '/api/app/v1/reader/sources';
    await dispatch(301, sources + '?route=/api/app/v1/identity/me');
    await dispatch(302, sources, 'HEAD');
    await dispatch(303, thumbnail);
    await dispatch(304, ticket);
    for (const id of [301, 302, 303, 304]) assert.ok(laneFor(id));
    await dispatch(305, sources);
    assert.equal(laneFor(305), undefined, 'The fifth source response took the reserved control lane');
    await dispatch(306, '/api/app/v1/identity/me?route=' + encodeURIComponent(sources));
    assert.ok(laneFor(306), 'Slow source lists blocked profile control traffic');
    await complete(306);
    await dispatch(307, sources + '/metadata');
    assert.ok(laneFor(307), 'A source path prefix was treated as the exact bulk route');
    await complete(307);
    await dispatch(308, sources, 'POST');
    assert.ok(laneFor(308), 'A source write was treated as a GET response');
    await complete(308);
    await complete(301);
    await eventually(() => Boolean(laneFor(305)));
    const summary = /^Home channel summary: tlsBytes=\d+, tlsCallbacks=\d+, tlsSpanMs=\d+, binaryBytes=\d+, binaryFrames=\d+, writtenBytes=\d+, writes=\d+, writeMs=\d+, queuePeakBytes=\d+, forwardMs=\d+$/;
    assert.ok(logs.some(message => summary.test(message)), 'No forwarded lane close summary was recorded');
    assert.ok(logs.every(message => summary.test(message) || /^Home channel event: (pooldeadline|poolfull|localwrite|remoteclose)$/.test(message)),
      'Diagnostics contained data beyond fixed event names');
  } finally {
    channel.stop();
    await settle();
    global.setTimeout = nativeTimeout;
    console.info = realInfo;
  }
  console.log('Home channel media reservation, FIFO, cancellation, deadlines and fixed diagnostics tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
