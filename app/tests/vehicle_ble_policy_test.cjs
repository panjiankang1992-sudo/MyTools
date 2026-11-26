const assert = require('node:assert/strict');
const { VehicleBlePolicy: policy, VehicleBleEpoch } = require(process.argv[2]);

const uuid = value => policy.uuid(value);
const privateService = '0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc';
const bytes = value => new Uint8Array(Buffer.from(value, 'utf8'));

assert.equal(uuid(' 180A '), '0000180a-0000-1000-8000-00805f9b34fb');
assert.equal(uuid('0000180A'), uuid('180a'));
assert.equal(uuid(privateService.toUpperCase()), privateService);
for (const value of ['', 'invalid', '0000180a/2a29', '0000180a-0000-1000-8000-00805f9b34fg', undefined]) {
  assert.equal(uuid(value), '', 'Malformed service identifiers must not become read targets');
}

assert.equal(policy.deviceId('12:34:56:78:9A:BC'), true);
assert.equal(policy.deviceId('12:34:56:78:9A'), false);
assert.equal(policy.deviceId('../private'), false);
assert.equal(policy.maskedId('12:34:56:78:9A:BC'), '**:**:**:**:9A:BC');
assert.equal(policy.maskedId('invalid'), '');
assert.equal(policy.text('\u0000 A\nB\u202eC\u2069 '), 'ABC');
assert.equal(policy.text('x'.repeat(200)).length, 64);

// 名称与服务广播只影响候选提示，不具有鉴权或写入含义。
const serviceBytes = Buffer.from(privateService.replaceAll('-', ''), 'hex').reverse();
const advertisement = new Uint8Array([17, 7, ...serviceBytes]);
assert.equal(policy.yadeaCandidate('', advertisement), true);
assert.equal(policy.yadeaCandidate('YADEA Q9', new Uint8Array()), true);
assert.equal(policy.yadeaCandidate('Unrelated', new Uint8Array([2, 1, 6])), false);
assert.equal(policy.yadeaCandidate('', new Uint8Array([17, 7, ...serviceBytes.slice(0, 8)])), false);
assert.equal(policy.yadeaCandidate('', new Uint8Array([0, ...advertisement])), false);
assert.equal(policy.yadeaCandidate('', new Uint8Array(1651)), false);

function row(index, changes = {}) {
  return {
    id: `12:34:56:78:9A:${index.toString(16).padStart(2, '0')}`,
    name: `Device ${index}`, paired: false, connectable: true, rssi: -60,
    lastSeenAt: 1000, yadeaCandidate: false, ...changes
  };
}
let devices = [];
for (let index = 0; index < 100; index++) devices = policy.mergeDevice(devices, row(index));
assert.equal(devices.length, policy.MAX_DEVICES, 'Advertising flood must remain bounded');
const updated = policy.mergeDevice(devices, row(1, { name: '', paired: true, rssi: -50 }));
assert.equal(updated.length, policy.MAX_DEVICES);
assert.equal(updated.find(item => item.id === row(1).id).name, 'Device 1');
assert.equal(updated.find(item => item.id === row(1).id).paired, true);
const merged = policy.mergeDevice(updated, row(1, { rssi: NaN, paired: false }));
assert.equal(merged.find(item => item.id === row(1).id).rssi, -50);
assert.equal(merged.find(item => item.id === row(1).id).paired, true);
assert.equal(updated.find(item => item.id === row(1).id).rssi, -50, 'Merge must not mutate prior snapshots');
assert.equal(policy.mergeDevice([], row(0, { id: 'invalid' })).length, 0);
const replacement = policy.mergeDevice(devices, row(120, { name: 'Yadea', yadeaCandidate: true }));
assert.equal(replacement.length, policy.MAX_DEVICES);
assert.ok(replacement.some(item => item.id === row(120).id), 'Vehicle candidates must remain selectable in a crowded garage');
const pairedRows = devices.map(item => ({ ...item, paired: true }));
const preserved = policy.mergeDevice(pairedRows, row(120, { yadeaCandidate: true }));
assert.equal(preserved.length, policy.MAX_DEVICES);
assert.equal(preserved.every(item => item.paired), true, 'Advertising cannot evict already paired devices');

// 允许列表排除序列号和私有特征，也不能把标准特征挂到私有服务上读取。
const modelRead = policy.standardRead('180a', '2a24');
const batteryRead = policy.standardRead('180f', '2a19');
assert.equal(modelRead.field, 'model');
assert.equal(batteryRead.field, 'batteryLevel');
assert.equal(policy.standardRead('180a', '2a25'), undefined);
assert.equal(policy.standardRead(privateService, '2a24'), undefined);
assert.equal(policy.standardRead('180a', '2a19'), undefined);
assert.equal(policy.standardRead('180f', '2a24'), undefined);
assert.equal(policy.standardRead(privateService, '0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc'), undefined);

const info = policy.emptyInfo();
assert.equal(info.batteryLevel, undefined, 'No synthetic zero battery before a valid read');
assert.equal(info.observedAt, 0);
assert.equal(policy.applyStandardValue(info, batteryRead, new Uint8Array([0]), 1000), true);
assert.equal(info.batteryLevel, 0);
assert.equal(policy.applyStandardValue(info, batteryRead, new Uint8Array([100]), 2000), true);
assert.equal(info.batteryLevel, 100);
for (const invalid of [new Uint8Array(), new Uint8Array([101]), new Uint8Array([255]), new Uint8Array([50, 0])]) {
  assert.equal(policy.applyStandardValue(info, batteryRead, invalid, 3000), false);
  assert.equal(info.batteryLevel, 100);
  assert.equal(info.observedAt, 2000, 'Rejected data must not refresh the observation timestamp');
}
assert.equal(policy.applyStandardValue(info, modelRead, bytes('Q9'), 4000), true);
assert.equal(info.model, 'Q9');
for (const invalid of [new Uint8Array(), new Uint8Array([0xc3, 0x28]), new Uint8Array(193), bytes('\u0000\n')]) {
  assert.equal(policy.applyStandardValue(info, modelRead, invalid, 5000), false);
  assert.equal(info.model, 'Q9');
  assert.equal(info.observedAt, 4000);
}
assert.equal(policy.applyStandardValue(info, modelRead, bytes('Spoofed'), Infinity), false);
assert.equal(policy.applyStandardValue(info, { ...modelRead, field: 'firmware' }, bytes('Spoofed'), 5000), false);
assert.equal(info.model, 'Q9');

// 停止、切换和永久关闭必须让已经发出的异步结果失效。
const epoch = new VehicleBleEpoch();
const first = epoch.begin();
assert.equal(epoch.current(first), true);
epoch.invalidate();
assert.equal(epoch.current(first), false);
const second = epoch.begin();
assert.equal(epoch.current(second), true);
assert.equal(epoch.current(first), false);
epoch.close();
assert.equal(epoch.current(second), false);
assert.equal(epoch.current(epoch.begin()), false, 'Closed transports cannot revive old activities');

console.log('Vehicle BLE policy tests passed');
