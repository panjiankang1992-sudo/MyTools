const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const { VehicleShortcutPolicy: policy } = require(process.argv[2]);
const appDir = process.argv[3];
const entryPath = process.argv[4];
const { VehicleShortcutRoutePolicy: route } = require(process.argv[5]);
const now = 1793689200000;
const nonce = '69d39807-1cc9-4858-aada-6f88b776f2ba';
const nextNonce = '847ed3af-7ea7-4932-899e-47d084999845';

const first = policy.create('unlock', now, nonce);
assert.ok(first);
assert.deepEqual(policy.parse(first, now), { id: first, action: 'unlock', issuedAt: now });
assert.equal(policy.isCurrent(first, now + policy.MAX_AGE_MS), true);
assert.equal(policy.isCurrent(first, now + policy.MAX_AGE_MS + 1), false);
assert.equal(policy.isCurrent(first, now - 1), false);
assert.notEqual(policy.create('unlock', now, nextNonce), first);
assert.notEqual(policy.create('unlock', now + 1, nonce), first);

for (const action of [undefined, null, false, 1, {}, [], 'lock', 'UNLOCK', ' unlock', 'unlock\n']) {
  assert.equal(policy.create(action, now, nonce), '');
}
for (const issuedAt of [-1, 0, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
  assert.equal(policy.create('unlock', issuedAt, nonce), '');
}
for (const invalid of ['', 'nonce', nonce.toUpperCase(), nonce + '\n', '../' + nonce]) {
  assert.equal(policy.create('unlock', now, invalid), '');
}
for (const request of [undefined, null, {}, '', 'unlock', first + '\n', first.replace('unlock', 'lock'),
  first.replace('v1.', 'v2.'), first.replace(String(now), '0' + now), 'x'.repeat(1000)]) {
  assert.equal(policy.parse(request, now), undefined);
}
for (const invalidNow of [-1, 0, NaN, Infinity, now + 0.5]) {
  assert.equal(policy.parse(first, invalidNow), undefined);
}

assert.equal(route.resolve(first, now, false, false), 'WAIT');
assert.equal(route.resolve(first, now, false, true), 'WAIT');
assert.equal(route.resolve(first, now, true, false), 'LOGIN');
assert.equal(route.resolve(first, now, true, true), 'OPEN');
assert.equal(route.resolve(first, now + policy.MAX_AGE_MS, true, true), 'OPEN');
assert.equal(route.resolve(first, now + policy.MAX_AGE_MS + 1, true, true), 'IGNORE');
assert.equal(route.resolve(first, now + policy.MAX_AGE_MS + 1, false, false), 'IGNORE');
assert.equal(route.resolve(first, now - 1, true, true), 'IGNORE');
assert.equal(route.resolve('', now, true, true), 'IGNORE');
const repeated = policy.create('unlock', now, nextNonce);
assert.notEqual(repeated, first);
assert.equal(route.resolve(repeated, now, true, true), 'OPEN');
assert.equal(route.resolve(first, now, true, false), 'LOGIN');
assert.equal(route.resolve(first, now + 1000, true, true), 'OPEN');
console.log('Vehicle shortcut route cold-start, login resume, TTL and repeated request tests passed');

const profile = JSON.parse(fs.readFileSync(path.join(appDir, 'entry/src/main/resources/base/profile/shortcuts.json'), 'utf8'));
assert.equal(profile.shortcuts.length, 1);
const shortcut = profile.shortcuts[0];
assert.equal(shortcut.shortcutId, 'vehicle-unlock');
assert.equal(shortcut.label, '$string:vehicle_shortcut_unlock');
assert.equal(shortcut.icon, '$media:vehicle_unlock');
assert.equal(shortcut.visible, true);
assert.deepEqual(shortcut.wants, [{ bundleName: 'com.yuyutian.mytools', moduleName: 'entry', abilityName: 'EntryAbility',
  parameters: { vehicleShortcutAction: 'unlock' } }]);
const moduleProfile = JSON.parse(fs.readFileSync(path.join(appDir, 'entry/src/main/module.json5'), 'utf8'));
const ability = moduleProfile.module.abilities.find(value => value.name === 'EntryAbility');
assert.ok(ability.metadata.some(value => value.name === 'ohos.ability.shortcuts' && value.resource === '$profile:shortcuts'));
console.log('Vehicle shortcut policy and static profile contract tests passed');

// 只观察真实入口发布的导航请求，不加载设备原生环境或访问云端服务。
const storage = new Map();
const originalLoad = Module._load;
const originalNow = Date.now;
let nextUuid = nonce;
let failUuid = false;
global.AppStorage = { setOrCreate: (key, value) => storage.set(key, value) };
Module._load = function(request, parent, isMain) {
  if (request === '@ohos.app.ability.UIAbility') return { default: class FakeAbility {} };
  if (request === '@kit.ArkTS') return { util: { generateRandomUUID: () => {
    if (failUuid) throw new Error('TEST_UUID_FAILURE');
    return nextUuid;
  } } };
  if (request === '../theme/AppFonts') return { AppFonts: { register: () => {} } };
  return originalLoad.call(this, request, parent, isMain);
};
Date.now = () => now;
try {
  const EntryAbility = require(entryPath).default;
  const entry = new EntryAbility();
  entry.onCreate({ parameters: { vehicleShortcutAction: 'unlock' } });
  const cold = storage.get(policy.STORAGE_KEY);
  assert.equal(cold, first);
  nextUuid = nextNonce;
  entry.onNewWant({ parameters: { vehicleShortcutAction: 'unlock' } });
  const warm = storage.get(policy.STORAGE_KEY);
  assert.ok(warm && warm !== cold);
  assert.equal(policy.parse(warm, now).action, 'unlock');
  for (const bad of [{}, { parameters: { vehicleShortcutAction: 'lock' } },
    { parameters: { vehicleShortcutAction: true } }, { parameters: { vehicleShortcutAction: { value: 'unlock' } } }]) {
    entry.onNewWant(bad);
    assert.equal(storage.get(policy.STORAGE_KEY), warm);
  }
  storage.set(policy.STORAGE_KEY, warm);
  entry.onNewWant({ uri: 'file:///documents/test.zip', parameters: { vehicleShortcutAction: 'unlock' } });
  assert.equal(storage.get('incomingArchiveUri'), 'file:///documents/test.zip');
  assert.equal(storage.get(policy.STORAGE_KEY), '');
  entry.onNewWant({ uri: 'https://example.invalid', parameters: { vehicleShortcutAction: 'unlock' } });
  assert.equal(storage.get(policy.STORAGE_KEY), '');
  entry.onNewWant({ parameters: { vehicleShortcutAction: 'unlock', vehicleId: 'untrusted-device', command: 'POWER_ON' } });
  assert.deepEqual(policy.parse(storage.get(policy.STORAGE_KEY), now), {
    id: policy.create('unlock', now, nextNonce), action: 'unlock', issuedAt: now
  });
  assert.equal(storage.get('incomingArchiveUri'), '');
  storage.set('incomingArchiveUri', 'file:///documents/pending.zip');
  entry.onNewWant({ parameters: { vehicleShortcutAction: 'lock' } });
  assert.equal(storage.get('incomingArchiveUri'), 'file:///documents/pending.zip');
  storage.clear();
  storage.set('incomingArchiveUri', 'file:///documents/pending.zip');
  failUuid = true;
  assert.doesNotThrow(() => entry.onCreate({ parameters: { vehicleShortcutAction: 'unlock' } }));
  assert.equal(storage.has(policy.STORAGE_KEY), false);
  assert.equal(storage.get('incomingArchiveUri'), 'file:///documents/pending.zip');
  console.log('Vehicle shortcut EntryAbility cold/warm, archive priority and invalid Want tests passed');
} finally {
  Module._load = originalLoad;
  Date.now = originalNow;
  delete global.AppStorage;
}
