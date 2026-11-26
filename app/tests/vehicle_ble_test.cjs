const assert = require('node:assert/strict');
const Module = require('node:module');
const path = require('node:path');
const { VehicleBlePolicy: policy } = require(path.join(path.dirname(process.argv[2]), 'VehicleBlePolicy.js'));

const originalLoad = Module._load;
const originalSetTimeout = global.setTimeout;
const originalClearTimeout = global.clearTimeout;
const originalNow = Date.now;
const nativeProxy = {};
let activeKit = nativeProxy;

// 测试使用虚拟时钟推进回调，不等待真实蓝牙或真实超时。
class Clock {
  constructor() { this.now = 1700000000000; this.sequence = 0; this.timers = new Map(); }
  install() {
    global.setTimeout = (callback, delay = 0) => {
      const id = ++this.sequence;
      this.timers.set(id, { callback, due: this.now + Number(delay) });
      return id;
    };
    global.clearTimeout = id => { this.timers.delete(id); };
    Date.now = () => this.now;
  }
  async advance(milliseconds) {
    const target = this.now + milliseconds;
    for (;;) {
      const entries = [...this.timers.entries()].filter(([, value]) => value.due <= target)
        .sort((first, second) => first[1].due - second[1].due);
      if (entries.length === 0) break;
      const [id, timer] = entries[0];
      this.now = timer.due;
      this.timers.delete(id);
      timer.callback();
      await drain();
    }
    this.now = target;
    await drain();
  }
}

class Emitter {
  constructor() { this.listeners = new Map(); }
  on(name, callback) {
    this.listeners.set(name, [...(this.listeners.get(name) || []), callback]);
  }
  off(name, callback) {
    if (callback === undefined) this.listeners.delete(name);
    else this.listeners.set(name, this.callbacks(name).filter(value => value !== callback));
  }
  callbacks(name) { return [...(this.listeners.get(name) || [])]; }
  emit(name, value) { this.callbacks(name).forEach(callback => callback(value)); }
  count() { return [...this.listeners.values()].reduce((total, rows) => total + rows.length, 0); }
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, decline) => { resolve = accept; reject = decline; });
  return { promise, resolve, reject };
}

async function drain() {
  for (let index = 0; index < 60; index++) await Promise.resolve();
}

function track(promise) {
  const result = { done: false, error: undefined, value: undefined };
  Promise.resolve(promise).then(value => { result.done = true; result.value = value; }, error => {
    result.done = true; result.error = error;
  });
  return result;
}

const id = index => `10:20:30:40:50:${index.toString(16).padStart(2, '0')}`;
const nativeService = '0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc';
const nativeControl = '0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc';

function characteristic(serviceUuid, characteristicUuid, readable = true) {
  return {
    serviceUuid: policy.uuid(serviceUuid), characteristicUuid: policy.uuid(characteristicUuid),
    characteristicValue: new ArrayBuffer(0), descriptors: [],
    properties: { read: readable, write: true, writeNoResponse: false, notify: true, indicate: false }
  };
}

function service(serviceUuid, characteristics) {
  return { serviceUuid: policy.uuid(serviceUuid), isPrimary: true, characteristics, includeServices: [] };
}

function scanResult(deviceId, changes = {}) {
  return { deviceId, rssi: -60, data: new ArrayBuffer(0), connectable: true, deviceName: 'Yadea Q9', ...changes };
}

class Gatt extends Emitter {
  constructor(deviceId, kit, options) {
    super(); this.deviceId = deviceId; this.kit = kit; this.options = options;
    this.connectCount = 0; this.disconnectCount = 0; this.closeCount = 0; this.serviceCount = 0;
    this.reads = []; this.writes = []; this.notifications = [];
  }
  connect() {
    this.connectCount++;
    if (this.options.connectError) throw this.options.connectError;
    if (this.options.synchronousConnection) this.connected();
  }
  disconnect() { this.disconnectCount++; }
  close() { this.closeCount++; }
  getServices() {
    this.serviceCount++;
    if (this.options.serviceError) return Promise.reject(this.options.serviceError);
    return this.options.servicesPromise || Promise.resolve(this.options.services || []);
  }
  readCharacteristicValue(target) {
    this.reads.push(target);
    if (this.options.readPromise) return this.options.readPromise;
    if (this.options.readError) return Promise.reject(this.options.readError);
    const value = this.options.values?.get(target.characteristicUuid) || new Uint8Array([75]);
    return Promise.resolve({ ...target, characteristicValue: value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength) });
  }
  writeCharacteristicValue(...args) { this.writes.push(args); throw new Error('Unexpected characteristic write'); }
  writeDescriptorValue(...args) { this.writes.push(args); throw new Error('Unexpected descriptor write'); }
  setCharacteristicChangeNotification(...args) { this.notifications.push(args); throw new Error('Unexpected private subscription'); }
  connected() { this.emit('BLEConnectionStateChange', { deviceId: this.deviceId, state: 2 }); }
  disconnected() { this.emit('BLEConnectionStateChange', { deviceId: this.deviceId, state: 0 }); }
}

function makeKit() {
  const bleEvents = new Emitter();
  const accessEvents = new Emitter();
  const kit = {
    state: 2, scans: 0, stops: 0, pairs: [], gatts: [], queue: [], scanRequests: [], bleEvents, accessEvents,
    access: {
      BluetoothState: { STATE_OFF: 0, STATE_TURNING_ON: 1, STATE_ON: 2, STATE_TURNING_OFF: 3, STATE_BLE_ON: 5 },
      getState: () => kit.state,
      on: (name, callback) => accessEvents.on(name, callback),
      off: (name, callback) => accessEvents.off(name, callback)
    },
    constant: { ProfileConnectionState: { STATE_DISCONNECTED: 0, STATE_CONNECTING: 1, STATE_CONNECTED: 2, STATE_DISCONNECTING: 3 } },
    connection: {
      getPairedDevices: () => [...kit.pairs],
      getRemoteDeviceName: deviceId => `Device ${deviceId.slice(-2)}`
    },
    ble: {
      ScanDuty: { SCAN_MODE_LOW_POWER: 0, SCAN_MODE_BALANCED: 1, SCAN_MODE_LOW_LATENCY: 2 },
      MatchMode: { MATCH_MODE_AGGRESSIVE: 1, MATCH_MODE_STICKY: 2 },
      on: (name, callback) => bleEvents.on(name, callback),
      off: (name, callback) => bleEvents.off(name, callback),
      startBLEScan: (filters, options) => {
        kit.scanRequests.push({ filters, options });
        // 原生解析允许 null 表示无过滤，但拒绝长度为零的过滤器数组。
        if (filters !== null && (!Array.isArray(filters) || filters.length === 0)) {
          throw Object.assign(new Error('Invalid scan filter parameters'), { code: 401 });
        }
        kit.scans++;
      },
      stopBLEScan: () => { kit.stops++; },
      createGattClientDevice: deviceId => {
        const gatt = new Gatt(deviceId, kit, kit.queue.shift() || {});
        kit.gatts.push(gatt);
        return gatt;
      }
    }
  };
  return kit;
}

Module._load = function(name, parent, isMain) {
  if (name === '@kit.ConnectivityKit') return activeKit;
  if (name === '@kit.BasicServicesKit') return {};
  return originalLoad.call(this, name, parent, isMain);
};
const { VehicleBleTransport } = require(process.argv[2]);
Module._load = originalLoad;

// SDK 模块在首次加载时保留导出对象，因此通过稳定代理向当前用例转发。
async function run(name, body) {
  const clock = new Clock();
  clock.install();
  const kit = makeKit();
  Object.keys(nativeProxy).forEach(key => delete nativeProxy[key]);
  Object.assign(nativeProxy, kit);
  const events = [];
  const transport = new VehicleBleTransport(snapshot => events.push(snapshot));
  try {
    await body({ clock, kit, transport, events });
    assert.equal(kit.gatts.reduce((total, gatt) => total + gatt.writes.length, 0), 0,
      'Connection verification must never write characteristics or descriptors');
    assert.equal(kit.gatts.reduce((total, gatt) => total + gatt.notifications.length, 0), 0,
      'Connection verification must never subscribe to unknown private notifications');
    assert.equal(transport.snapshot().authenticated, false, 'BLE connection cannot grant vehicle authentication');
    console.log(`PASS ${name}`);
  } finally {
    transport.close();
    assert.equal(kit.bleEvents.count(), 0, 'Dispose must detach scan callbacks');
    assert.equal(kit.accessEvents.count(), 0, 'Dispose must detach adapter callbacks');
    assert.equal(clock.timers.size, 0, 'Dispose must clear operation deadlines');
    kit.gatts.forEach(gatt => assert.equal(gatt.count(), 0, 'Dispose must detach GATT callbacks'));
    global.setTimeout = originalSetTimeout;
    global.clearTimeout = originalClearTimeout;
    Date.now = originalNow;
  }
}

function discover(transport, kit, deviceId = id(1)) {
  transport.scan();
  kit.bleEvents.emit('BLEDeviceFind', [scanResult(deviceId)]);
  transport.stopScan();
  assert.equal(transport.snapshot().devices.some(device => device.id === deviceId), true);
}

async function ready(transport, kit, deviceId = id(1), options = {}) {
  discover(transport, kit, deviceId);
  kit.queue.push(options);
  const task = track(transport.connect(deviceId));
  const gatt = kit.gatts.at(-1);
  assert.ok(gatt, 'Selected discovered device must create a GATT client');
  gatt.connected();
  await drain();
  assert.equal(task.done, true, 'Successful discovery should settle without real time');
  assert.equal(task.error, undefined);
  return gatt;
}

async function main() {
  await run('native scan filter contract accepts unfiltered scans without an empty filter array', async ({ kit, transport }) => {
    const contract = makeKit();
    const options = { interval: 0, dutyMode: contract.ble.ScanDuty.SCAN_MODE_LOW_POWER,
      matchMode: contract.ble.MatchMode.MATCH_MODE_AGGRESSIVE };
    assert.throws(() => contract.ble.startBLEScan([], options), error => error.code === 401,
      'The native stub must reproduce rejection of an empty filter array');
    assert.doesNotThrow(() => contract.ble.startBLEScan(null, options));
    assert.doesNotThrow(() => contract.ble.startBLEScan([{}], options));
    assert.equal(contract.scans, 2);

    transport.scan();
    assert.equal(kit.scans, 1, 'Production scan must reach an accepted native call');
    assert.equal(transport.snapshot().scanning, true);
    assert.equal(transport.snapshot().error, '');
    assert.equal(kit.scanRequests.length, 1);
    const request = kit.scanRequests[0];
    assert.ok(request.filters === null || Array.isArray(request.filters) && request.filters.length > 0,
      'Unfiltered discovery cannot use an empty array on the native API');
  });

  await run('bounded scan stops at its deadline and ignores late advertisements', async ({ clock, kit, transport }) => {
    transport.scan();
    const late = kit.bleEvents.callbacks('BLEDeviceFind')[0];
    assert.equal(kit.scans, 1);
    for (let index = 0; index < 100; index++) kit.bleEvents.emit('BLEDeviceFind', [scanResult(id(index))]);
    assert.equal(transport.snapshot().devices.length, policy.MAX_DEVICES);
    await clock.advance(policy.SCAN_DURATION_MS + 1);
    assert.equal(transport.snapshot().scanning, false);
    assert.ok(kit.stops >= 1);
    const snapshot = transport.snapshot();
    late([scanResult(id(1), { rssi: -10 })]);
    assert.deepEqual(transport.snapshot(), snapshot, 'Expired scan events cannot alter devices');
  });

  await run('manual stop and permanent close invalidate existing scan callbacks', async ({ kit, transport, events }) => {
    transport.scan();
    const late = kit.bleEvents.callbacks('BLEDeviceFind')[0];
    transport.stopScan();
    const snapshot = transport.snapshot();
    late([scanResult(id(1))]);
    assert.deepEqual(transport.snapshot(), snapshot);
    transport.close();
    const count = events.length;
    late([scanResult(id(2))]);
    transport.scan();
    assert.equal(kit.scans, 1, 'Disposed transport cannot start scanning again');
    assert.equal(events.length, count, 'Disposed transport cannot notify a stale page');
  });

  await run('paired list stays bounded and does not imply authentication', async ({ kit, transport }) => {
    kit.pairs = Array.from({ length: 100 }, (_, index) => id(index));
    transport.loadPaired();
    await drain();
    const snapshot = transport.snapshot();
    assert.equal(snapshot.devices.length, policy.MAX_DEVICES);
    assert.equal(snapshot.devices.every(device => device.paired), true);
    assert.equal(snapshot.connected, false);
    assert.equal(kit.gatts.length, 0);
  });

  await run('paired refresh replaces stale records after system unpairing', async ({ kit, transport }) => {
    kit.pairs = [id(1), id(2)];
    transport.loadPaired();
    assert.equal(transport.snapshot().devices.length, 2);
    kit.pairs = [id(2)];
    transport.loadPaired();
    assert.deepEqual(transport.snapshot().devices.map(device => device.id), [id(2)]);
    kit.pairs = [];
    transport.loadPaired();
    assert.equal(transport.snapshot().devices.length, 0, 'An empty system snapshot cannot preserve old paired devices');
    const task = track(transport.connect(id(1)));
    await drain();
    assert.equal(task.done, true);
    assert.equal(kit.gatts.length, 0, 'Removed paired addresses cannot be used as selectable devices');
  });

  await run('repeated scan requests do not start competing native scans', async ({ kit, transport }) => {
    transport.scan();
    transport.scan();
    transport.loadPaired();
    assert.equal(kit.scans, 1);
    assert.equal(transport.snapshot().scanning, true);
    assert.equal(transport.snapshot().error, 'VEHICLE_BLE_BUSY');
  });

  await run('disabled adapter cannot scan or connect', async ({ kit, transport }) => {
    kit.state = 0;
    transport.scan();
    await drain();
    assert.equal(transport.snapshot().scanning, false);
    assert.equal(kit.scans, 0);
    assert.ok(transport.snapshot().error);
    track(transport.connect(id(1)));
    await drain();
    assert.equal(kit.gatts.length, 0);
  });

  await run('unselected addresses do not create native clients', async ({ kit, transport }) => {
    const task = track(transport.connect(id(1)));
    await drain();
    assert.equal(task.done, true);
    assert.equal(kit.gatts.length, 0);
    assert.equal(transport.snapshot().connected, false);
  });

  await run('discovered standard read whitelist excludes private values and serial numbers', async ({ kit, transport }) => {
    const standards = service('180a', [characteristic('180a', '2a24'), characteristic('180a', '2a25'), characteristic('180a', '2a29', false)]);
    const battery = service('180f', [characteristic('180f', '2a19')]);
    const privateData = service(nativeService, [characteristic(nativeService, nativeControl)]);
    const values = new Map([[policy.uuid('2a24'), new Uint8Array(Buffer.from('Q9'))], [policy.uuid('2a19'), new Uint8Array([84])]]);
    const gatt = await ready(transport, kit, id(1), { services: [standards, battery, privateData], values });
    assert.deepEqual(gatt.reads.map(item => item.characteristicUuid).sort(), [policy.uuid('2a19'), policy.uuid('2a24')].sort());
    const snapshot = transport.snapshot();
    assert.equal(snapshot.connected, true);
    assert.equal(snapshot.standardInfo.model, 'Q9');
    assert.equal(snapshot.standardInfo.batteryLevel, 84);
    assert.ok(snapshot.standardInfo.observedAt > 0);
    snapshot.standardInfo.model = 'Spoofed';
    snapshot.devices.length = 0;
    snapshot.services[0].characteristics.length = 0;
    assert.equal(transport.snapshot().standardInfo.model, 'Q9', 'Public snapshots must be copies');
    assert.equal(transport.snapshot().devices.length, 1);
    assert.ok(transport.snapshot().services[0].characteristics.length > 0);
  });

  await run('malformed standard values remain unknown', async ({ kit, transport }) => {
    const services = [service('180f', [characteristic('180f', '2a19')])];
    await ready(transport, kit, id(1), { services, values: new Map([[policy.uuid('2a19'), new Uint8Array([255])]]) });
    assert.equal(transport.snapshot().standardInfo.batteryLevel, undefined);
    assert.equal(transport.snapshot().standardInfo.observedAt, 0);
    assert.ok(transport.snapshot().readErrorCount > 0);
  });

  await run('duplicate connection events do not restart completed service discovery', async ({ kit, transport }) => {
    const gatt = await ready(transport, kit);
    const snapshot = transport.snapshot();
    assert.equal(snapshot.state, 'CONNECTED');
    gatt.connected();
    await drain();
    assert.equal(gatt.serviceCount, 1);
    assert.deepEqual(transport.snapshot(), snapshot);
  });

  await run('synchronous native connection events cannot be lost before the waiter is ready', async ({ kit, transport }) => {
    discover(transport, kit);
    kit.queue.push({ synchronousConnection: true });
    const task = track(transport.connect(id(1)));
    await drain();
    assert.equal(task.done, true);
    assert.equal(transport.snapshot().state, 'CONNECTED');
    assert.equal(kit.gatts[0].serviceCount, 1);
  });

  await run('native failures expose stable codes without raw SDK messages', async ({ kit, transport }) => {
    discover(transport, kit);
    kit.queue.push({ connectError: { code: 201, message: 'Private native diagnostic' } });
    const task = track(transport.connect(id(1)));
    await drain();
    assert.equal(task.done, true);
    assert.equal(transport.snapshot().error, 'VEHICLE_BLE_PERMISSION');
    assert.equal(transport.snapshot().connected, false);
    assert.ok(kit.gatts[0].closeCount > 0);
  });

  await run('connection deadline disposes the client and ignores late connection events', async ({ clock, kit, transport }) => {
    discover(transport, kit);
    const task = track(transport.connect(id(1)));
    const gatt = kit.gatts[0];
    const late = gatt.callbacks('BLEConnectionStateChange')[0];
    await clock.advance(policy.CONNECT_TIMEOUT_MS + 1);
    assert.equal(task.done, true);
    assert.equal(transport.snapshot().connected, false);
    assert.equal(transport.snapshot().error, 'VEHICLE_BLE_CONNECT_TIMEOUT');
    assert.ok(gatt.closeCount > 0);
    const snapshot = transport.snapshot();
    late({ deviceId: id(1), state: 2 });
    await drain();
    assert.deepEqual(transport.snapshot(), snapshot);
    assert.equal(gatt.serviceCount, 0);
  });

  await run('service discovery deadline cannot adopt a late service response', async ({ clock, kit, transport }) => {
    discover(transport, kit);
    const pending = deferred();
    kit.queue.push({ servicesPromise: pending.promise });
    const task = track(transport.connect(id(1)));
    kit.gatts[0].connected();
    await drain();
    await clock.advance(policy.DISCOVERY_TIMEOUT_MS + 1);
    assert.equal(task.done, true);
    assert.equal(transport.snapshot().connected, false);
    assert.equal(transport.snapshot().error, 'VEHICLE_BLE_DISCOVERY_TIMEOUT');
    const snapshot = transport.snapshot();
    pending.resolve([service('180f', [characteristic('180f', '2a19')])]);
    await drain();
    assert.deepEqual(transport.snapshot(), snapshot);
    assert.equal(kit.gatts[0].reads.length, 0);
  });

  await run('read timeout settles and never races another GATT read', async ({ clock, kit, transport }) => {
    discover(transport, kit);
    const pending = deferred();
    const services = [service('180a', [characteristic('180a', '2a24'), characteristic('180a', '2a26')])];
    kit.queue.push({ services, readPromise: pending.promise });
    const task = track(transport.connect(id(1)));
    const gatt = kit.gatts[0];
    gatt.connected();
    await drain();
    assert.equal(gatt.reads.length, 1);
    await clock.advance(policy.READ_TIMEOUT_MS + 1);
    assert.equal(task.done, true);
    assert.equal(transport.snapshot().error, 'VEHICLE_BLE_READ_TIMEOUT');
    assert.equal(gatt.reads.length, 1, 'A timed out native operation must not overlap another read');
    const snapshot = transport.snapshot();
    pending.resolve({ ...gatt.reads[0], characteristicValue: new Uint8Array(Buffer.from('Late')).buffer });
    await drain();
    assert.deepEqual(transport.snapshot(), snapshot, 'Late read cannot refresh stale state');
  });

  await run('disconnect clears all data and late reads cannot populate another vehicle', async ({ kit, transport }) => {
    discover(transport, kit);
    const pending = deferred();
    kit.queue.push({ services: [service('180f', [characteristic('180f', '2a19')])], readPromise: pending.promise });
    track(transport.connect(id(1)));
    kit.gatts[0].connected();
    await drain();
    assert.equal(kit.gatts[0].reads.length, 1);
    transport.disconnect();
    assert.equal(transport.snapshot().connected, false);
    assert.equal(transport.snapshot().services.length, 0);
    assert.equal(transport.snapshot().standardInfo.batteryLevel, undefined);
    await ready(transport, kit, id(2));
    const snapshot = transport.snapshot();
    pending.resolve({ ...kit.gatts[0].reads[0], characteristicValue: new Uint8Array([99]).buffer });
    await drain();
    assert.deepEqual(transport.snapshot(), snapshot);
    assert.equal(transport.snapshot().selectedDeviceId, id(2));
    assert.equal(transport.snapshot().standardInfo.batteryLevel, undefined);
  });

  await run('native disconnection clears stale services and standard info', async ({ kit, transport }) => {
    const gatt = await ready(transport, kit, id(1), {
      services: [service('180f', [characteristic('180f', '2a19')])]
    });
    assert.equal(transport.snapshot().standardInfo.batteryLevel, 75);
    gatt.disconnected();
    await drain();
    assert.equal(transport.snapshot().connected, false);
    assert.equal(transport.snapshot().services.length, 0);
    assert.equal(transport.snapshot().standardInfo.batteryLevel, undefined);
  });

  await run('adapter shutdown cancels active work and clears vehicle data', async ({ kit, transport }) => {
    await ready(transport, kit, id(1), { services: [service('180f', [characteristic('180f', '2a19')])] });
    kit.state = 0;
    kit.accessEvents.emit('stateChange', 0);
    await drain();
    assert.equal(transport.snapshot().connected, false);
    assert.equal(transport.snapshot().services.length, 0);
    assert.equal(transport.snapshot().standardInfo.batteryLevel, undefined);
    assert.equal(transport.snapshot().error, 'VEHICLE_BLE_OFF');
  });

  await run('close during service discovery settles waiting work and ignores the response', async ({ kit, transport, events }) => {
    discover(transport, kit);
    const pending = deferred();
    kit.queue.push({ servicesPromise: pending.promise });
    const task = track(transport.connect(id(1)));
    kit.gatts[0].connected();
    await drain();
    transport.close();
    await drain();
    assert.equal(task.done, true);
    const count = events.length;
    pending.resolve([service('180f', [characteristic('180f', '2a19')])]);
    await drain();
    assert.equal(events.length, count);
    assert.equal(transport.snapshot().state, 'CLOSED');
    assert.equal(kit.gatts[0].reads.length, 0);
  });

  console.log('Vehicle BLE transport tests passed');
}

main().catch(error => {
  global.setTimeout = originalSetTimeout;
  global.clearTimeout = originalClearTimeout;
  Date.now = originalNow;
  Module._load = originalLoad;
  console.error(error);
  process.exitCode = 1;
});
