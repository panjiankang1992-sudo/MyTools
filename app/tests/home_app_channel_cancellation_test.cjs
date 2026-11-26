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
let firstMessage = 'READY';
let writeBarrier;
let ackBarrier;
const written = [];
const logs = [];
const realInfo = console.info;
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
    assert.equal(pending, undefined, 'Concurrent read on a local connection');
    return new Promise((resolve, reject) => readers.set(id, { resolve, reject }));
  },
  write: async (id, data) => {
    written.push({ id, data });
    if (writeBarrier) await writeBarrier.promise;
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
    this.readResets = 0;
    this.readSnapshots = 0;
    sockets.push(this);
  }

  on(event, callback) { this.events.set(event, callback); }
  resetReadStats() {
    this.readResets++;
    this.readBytes = 0;
    this.readCallbacks = 0;
  }
  readStats() {
    assert.equal(this.closed, false, 'Read statistics were collected after closing the wire');
    this.readSnapshots++;
    return { bytes: this.readBytes ?? 0, callbacks: this.readCallbacks ?? 0, spanMs: 17 };
  }

  async connect(url, options) {
    this.options = options;
    queueMicrotask(() => this.message(firstMessage));
    return true;
  }

  async send(data) {
    assert.equal(this.closed, false, 'A closed lane was reused');
    this.sent.push(data);
    if (data === 'ACK' && ackBarrier) await ackBarrier.promise;
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
  '@ohos.util': { default: { generateRandomUUID: () => key } },
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

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function summaryLines() {
  return logs.filter(value => value.startsWith('Home channel summary:'));
}

function assertHeaders(wire) {
  assert.equal(wire.options.header['X-MyTools-Channel'], '1');
  assert.equal(Object.hasOwn(wire.options.header, 'X-MyTools-Channel-Wakeup'), false,
    'The unsupported wakeup header was sent');
}

async function startChannel(channel) {
  await channel.start('https://home.example.test:8443', 'https://public.example.test', async () => 'fixture');
  const wire = sockets.at(-1);
  assertHeaders(wire);
  return wire;
}

async function openRequest(id, method = 'GET') {
  const bodyHeader = method === 'POST' ? 'Content-Length: 100000\r\n' : '';
  readers.set(id, {
    initial: bytes(method + ' /' + key + '/api/media HTTP/1.1\r\nHost: localhost\r\n' + bodyHeader + '\r\n')
  });
  accepted(id);
  await settle();
  assert.ok(readers.has(id), 'The request did not reach its forwarding read');
}

(async () => {
  const { HomeAppChannel } = load('HomeAppChannel');
  const channel = new HomeAppChannel();
  try {
    await startChannel(channel);

    // 取消首包尚未返回的 GET，以及只发送了请求头的上传，均须及时回收连接。
    for (const id of [1, 2, 3, 4, 5]) {
      await openRequest(id, id % 2 ? 'GET' : 'POST');
      const lane = sockets[sockets.length - 1];
      finishReader(id, pending => pending.resolve(new ArrayBuffer(0)));
      await settle();
      assert.equal(lane.closed, true, 'Cancellation retained a busy remote lane');
      assert.ok(ended.includes(id), 'Cancellation retained the local socket');
    }

    // 五次取消之后，新请求仍可立即建连，不必等待服务器或连接池超时。
    await openRequest(6);
    const reusable = sockets[sockets.length - 1];
    const beforeReuse = sockets.length;
    assert.equal(reusable.sent[0], 'BEGIN');

    // 正常 END 会拒绝尚未完成的本地 read，此时需要保留已经完成的远端连接。
    reusable.message(bytes('HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n'));
    reusable.message('END');
    await settle();
    assert.equal(reusable.closed, false, 'Response completion destroyed a reusable lane');
    assert.ok(ended.includes(6));
    assert.equal(reusable.sent.filter(value => value === 'ACK').length, 1);
    await openRequest(7);
    assert.equal(sockets.length, beforeReuse, 'The completed lane was not reused');
    delayedReadRejections.splice(0).forEach(reject => reject());
    await settle();
    assert.equal(reusable.closed, false);
    reusable.message('END');
    await settle();
    assert.equal(reusable.closed, false, 'An older pending read closed the reused lane');
    assert.equal(reusable.sent.filter(value => value === 'ACK').length, 2);

    const measured = await startChannel(channel);
    await openRequest(8);
    const measuredState = channel.lanes.find(lane => lane.wire === measured);
    const firstBody = bytes('HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}');
    const secondBody = bytes('HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n');
    const writesBeforeResponse = written.length;

    // 本地写入完成之前，FLUSH 不能确认尚未消费的业务字节。
    const blockedWrite = deferred();
    writeBarrier = blockedWrite;
    measured.message(firstBody);
    measured.message('FLUSH');
    await settle();
    assert.equal(measured.sent.filter(value => value === 'ACK').length, 0);
    assert.equal(written.length, writesBeforeResponse + 1);
    writeBarrier = undefined;
    blockedWrite.resolve();
    await settle();
    assert.equal(measured.sent.filter(value => value === 'ACK').length, 1);

    // END 的确认发送完成后，连接才能重新变为可复用状态。
    const blockedAck = deferred();
    ackBarrier = blockedAck;
    measured.message('END');
    await settle();
    assert.equal(measuredState.peer, -2);
    assert.ok(ended.includes(8));
    assert.equal(measured.sent.filter(value => value === 'ACK').length, 2);
    ackBarrier = undefined;
    blockedAck.resolve();
    await settle();
    assert.equal(measuredState.peer, -1);
    const socketsBeforeSecondResponse = sockets.length;
    await openRequest(9);
    assert.equal(sockets.length, socketsBeforeSecondResponse, 'The acknowledged lane was not reused');
    assert.equal(measured.sent.filter(value => value === 'BEGIN').length, 2);
    measured.message(secondBody);
    measured.message('FLUSH');
    measured.message('END');
    await settle();
    assert.equal(measured.closed, false);
    assert.equal(measuredState.peer, -1);
    assert.ok(ended.includes(9));
    assert.equal(measured.sent.filter(value => value === 'ACK').length, 4);
    assert.equal(written.length, writesBeforeResponse + 2);
    assert.deepEqual(written.slice(writesBeforeResponse), [
      { id: 8, data: firstBody }, { id: 9, data: secondBody }
    ]);

    // 同一条连接的两笔交换累计为十个数字字段，关闭时只输出一次。
    const summariesBeforeClose = summaryLines().length;
    channel.stop();
    await settle();
    assert.equal(measured.closed, true);
    const summaries = summaryLines();
    assert.equal(summaries.length, summariesBeforeClose + 1, 'Lane close did not emit exactly one summary');
    const summary = summaries.at(-1);
    assert.match(summary, /^Home channel summary: tlsBytes=\d+, tlsCallbacks=\d+, tlsSpanMs=\d+, binaryBytes=\d+, binaryFrames=\d+, writtenBytes=\d+, writes=\d+, writeMs=\d+, queuePeakBytes=\d+, forwardMs=\d+$/);
    const stats = Object.fromEntries(summary.substring('Home channel summary: '.length).split(', ').map(value => {
      const [name, count] = value.split('='); return [name, Number(count)];
    }));
    const bodyBytes = firstBody.byteLength + secondBody.byteLength;
    assert.equal(stats.tlsBytes, bodyBytes + 2 * ('FLUSH'.length + 'END'.length));
    assert.equal(stats.tlsCallbacks, 6);
    assert.equal(stats.tlsSpanMs, 17);
    assert.equal(stats.binaryBytes, bodyBytes);
    assert.equal(stats.binaryFrames, 2);
    assert.equal(stats.writtenBytes, bodyBytes);
    assert.equal(stats.writes, 2);
    assert.equal(stats.queuePeakBytes, Math.max(firstBody.byteLength, secondBody.byteLength));
    assert.ok(Object.values(stats).every(value => Number.isInteger(value) && value >= 0));
    assert.equal(measured.readResets, 1, 'A reused lane discarded its accumulated read statistics');
    assert.equal(measured.readSnapshots, 1);
    measuredState.close();
    measured.message('END');
    await settle();
    assert.equal(summaryLines().length, summaries.length,
      'Duplicate close or late input printed a second summary');
    assert.equal(measured.readSnapshots, 1, 'Duplicate close collected read statistics again');

    // PULSE 与其他未知文本遵循相同校验，空闲、转发中和 READY 前均须拒绝。
    let request = 10;
    for (const frame of ['PULSE', 'UNKNOWN']) {
      const idle = await startChannel(channel);
      const beforeIdle = summaryLines().length;
      idle.message(frame);
      await settle();
      assert.equal(idle.closed, true, frame + ' was accepted on an idle lane');
      assert.equal(summaryLines().length, beforeIdle, 'An idle lane printed a forwarding summary');
      assert.equal(idle.readSnapshots, 0);

      const active = await startChannel(channel);
      const peer = request++;
      await openRequest(peer);
      const beforeActive = summaryLines().length;
      const writesBeforeInvalid = written.length;
      active.message(frame);
      await settle();
      assert.equal(active.closed, true, frame + ' was accepted on an active lane');
      assert.ok(ended.includes(peer), 'An invalid frame retained the local socket');
      assert.equal(active.sent.filter(value => value === 'ACK').length, 0, 'An invalid frame was acknowledged');
      assert.equal(written.length, writesBeforeInvalid, 'An invalid frame reached the local response');
      assert.equal(summaryLines().length, beforeActive + 1, 'An invalid active lane did not close once');

      channel.stop();
      await settle();
      firstMessage = frame;
      const beforeHandshake = summaryLines().length;
      await assert.rejects(startChannel(channel));
      const rejected = sockets.at(-1);
      assertHeaders(rejected);
      assert.equal(rejected.closed, true, frame + ' before READY bypassed handshake validation');
      assert.equal(summaryLines().length, beforeHandshake, 'A failed handshake printed a forwarding summary');
      assert.equal(rejected.readSnapshots, 0);
      firstMessage = 'READY';
    }

    const idle = await startChannel(channel);
    const beforeIdleStop = summaryLines().length;
    channel.stop();
    await settle();
    assert.equal(summaryLines().length, beforeIdleStop,
      'An idle connection without forwarding printed a summary');
    assert.equal(idle.readSnapshots, 0);
  } finally {
    firstMessage = 'READY';
    writeBarrier = undefined;
    ackBarrier = undefined;
    channel.stop();
    await settle();
    console.info = realInfo;
  }
  console.log('Home app channel cancellation and completed-lane reuse tests passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
