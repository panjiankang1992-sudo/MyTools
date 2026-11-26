const assert = require('node:assert/strict');
const Module = require('node:module');
const path = require('node:path');
const { VehicleBlePolicy: policy } = require(path.join(path.dirname(process.argv[2]), 'VehicleBlePolicy.js'));
const { VehicleShortcutPolicy: shortcutPolicy } = require(path.join(path.dirname(process.argv[2]), 'VehicleShortcutPolicy.js'));

const originalLoad = Module._load;
const originalScroller = global.Scroller;
const originalResource = global.$r;
const originalNow = Date.now;
const testNow = 1700000000000;
let now = testNow;
let permission;
let requests = 0;
let clients = [];
let scrolls = [];

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, decline) => { resolve = accept; reject = decline; });
  return { promise, resolve, reject };
}

function emptySnapshot() {
  return {
    state: 'IDLE', scanning: false, connected: false, authenticated: false, devices: [],
    selectedDeviceId: '', services: [], standardInfo: policy.emptyInfo(), error: '', readErrorCount: 0
  };
}

class MockTransport {
  constructor(observer) { this.observer = observer; this.calls = []; clients.push(this); }
  snapshot() { return emptySnapshot(); }
  scan() { this.calls.push('scan'); }
  loadPaired() { this.calls.push('paired'); }
  connect(deviceId) { this.calls.push(`connect:${deviceId}`); return Promise.resolve(); }
  disconnect() { this.calls.push('disconnect'); }
  stopScan() { this.calls.push('stop'); }
  close() { this.calls.push('close'); }
}

global.Scroller = class Scroller { scrollTo(options) { scrolls.push(options); } };
global.$r = name => ({ id: name });
Date.now = () => now;
Module._load = function(name, parent, isMain) {
  if (name === '@kit.AbilityKit') {
    return { abilityAccessCtrl: { createAtManager: () => ({
      requestPermissionsFromUser: (_context, permissions) => {
        requests++;
        assert.deepEqual(permissions, ['ohos.permission.ACCESS_BLUETOOTH']);
        return permission.promise;
      }
    }) } };
  }
  if (name === './VehicleBleTransport') return { VehicleBleTransport: MockTransport };
  return originalLoad.call(this, name, parent, isMain);
};
const { VehiclePage } = require(process.argv[2]);
Module._load = originalLoad;

function makePage(shortcutRequest = '') {
  permission = deferred(); requests = 0; clients = []; scrolls = []; now = testNow;
  const page = new VehiclePage();
  page.getUIContext = () => ({ getHostContext: () => ({
    resourceManager: { getStringSync: key => key }
  }) });
  page.accountKey = 'test-owner';
  page.baseUrl = 'https://example.test';
  page.shortcutRequest = shortcutRequest;
  page.aboutToAppear();
  return page;
}

const selected = {
  id: '10:20:30:40:50:01', name: 'Yadea', paired: true, connectable: true,
  rssi: undefined, lastSeenAt: 0, yadeaCandidate: true
};
const action = (page, name) => name === 'connect' ? page.connect(selected) : page[name]();

function shortcut(issuedAt = now, nonce = '00000000-0000-4000-8000-000000000001') {
  return shortcutPolicy.create('unlock', issuedAt, nonce);
}

function assertNoNativeShortcutAction() {
  assert.equal(requests, 0, 'A navigation shortcut cannot request Bluetooth permission');
  assert.equal(clients.length, 0, 'A navigation shortcut cannot scan, connect, or write to a native client');
}

function suspend(page, kind) {
  if (kind === 'foreground') { page.foreground = false; page.foregroundChanged(); }
  else { page.active = false; page.visibilityChanged(); }
}

function resume(page, kind) {
  if (kind === 'foreground') { page.foreground = true; page.foregroundChanged(); }
  else { page.active = true; page.visibilityChanged(); }
}

async function main() {
  let passed = 0;
  for (const name of ['scan', 'paired', 'connect']) {
    // 使用实际 authorize()；在它已检查通过、调用方 await 恢复之前切换作用域。
    const page = makePage();
    assert.equal(clients.length, 0, 'Entering the page cannot implicitly create a BLE client');
    assert.equal(requests, 0, 'Entering the page cannot implicitly request permission');
    const pending = action(page, name);
    assert.equal(requests, 1);
    let releasedAfterAuthorization = false;
    const release = permission.promise.then(() => {
      assert.equal(page.permissionPending, false, 'Authorization finally must already have run');
      assert.equal(page.usable(), true, 'Scope release must invalidate revision even while the page stays usable');
      page.scopeChanged();
      releasedAfterAuthorization = true;
    });
    permission.resolve({ authResults: [0] });
    await pending;
    await release;
    assert.equal(releasedAfterAuthorization, true);
    assert.equal(clients.length, 0, 'A stale authorized continuation cannot recreate the released transport');
    assert.equal(page.transport, undefined);
    page.aboutToDisappear();
    passed++;
    console.log(`PASS ${name} rechecks page revision immediately before native work`);
  }

  for (const name of ['scan', 'paired', 'connect']) {
    const page = makePage();
    const pending = action(page, name);
    permission.resolve({ authResults: [0] });
    await pending;
    assert.equal(clients.length, 1);
    assert.deepEqual(clients[0].calls, [name === 'connect' ? `connect:${selected.id}` : name]);
    assert.equal(page.permissionPending, false);
    page.aboutToDisappear();
    assert.equal(clients[0].calls.at(-1), 'close');
    passed++;
    console.log(`PASS ${name} granted action reaches the real page transport dispatch`);
  }

  for (const exit of ['foreground', 'visibility', 'destroy']) {
    const page = makePage();
    const pending = page.scan();
    if (exit === 'foreground') { page.foreground = false; page.foregroundChanged(); }
    else if (exit === 'visibility') { page.active = false; page.visibilityChanged(); }
    else page.aboutToDisappear();
    permission.resolve({ authResults: [0] });
    await pending;
    assert.equal(clients.length, 0, 'Permission result cannot start Bluetooth after the page becomes inactive');
    assert.equal(page.permissionPending, false);
    page.aboutToDisappear();
    passed++;
    console.log(`PASS ${exit} invalidates in-flight permission results`);
  }

  {
    const page = makePage();
    const pending = page.scan();
    permission.resolve({ authResults: [-1] });
    await pending;
    assert.equal(clients.length, 0);
    assert.equal(page.notice, 'app.string.vehicle_permission_denied');
    assert.equal(page.permissionPending, false);
    page.aboutToDisappear();
    passed++;
    console.log('PASS permission denial never creates a native client');
  }

  {
    const page = makePage();
    const pending = page.scan();
    permission.reject(new Error('Private native error'));
    await pending;
    assert.equal(clients.length, 0);
    assert.equal(page.notice, 'app.string.vehicle_permission_failed');
    assert.equal(page.permissionPending, false);
    page.aboutToDisappear();
    passed++;
    console.log('PASS permission failures expose localized errors without SDK details');
  }

  {
    const page = makePage();
    const first = page.scan();
    const second = page.paired();
    assert.equal(requests, 1, 'Concurrent actions cannot show multiple permission requests');
    permission.resolve({ authResults: [0] });
    await Promise.all([first, second]);
    assert.deepEqual(clients[0].calls, ['scan']);
    const old = clients[0];
    page.scopeChanged();
    old.observer({ ...emptySnapshot(), state: 'CONNECTED', connected: true, selectedDeviceId: selected.id });
    assert.equal(page.view.connected, false, 'An old transport callback cannot repopulate another account');
    assert.equal(page.view.selectedDeviceId, '');
    assert.equal(old.calls.at(-1), 'close');
    page.aboutToDisappear();
    passed++;
    console.log('PASS permission de-duplication and old-account callback isolation');
  }

  for (const kind of ['foreground', 'visibility']) {
    const page = makePage();
    suspend(page, kind);
    const request = shortcut();
    page.shortcutRequest = request;
    page.shortcutChanged();
    assert.equal(page.actionTitle, '', 'Inactive pages must defer a fresh shortcut');
    assert.equal(page.unlockFocused, false);
    assert.equal(scrolls.length, 0);
    assertNoNativeShortcutAction();

    resume(page, kind);
    assert.equal(page.actionTitle, 'app.string.vehicle_home_unlock_title');
    assert.equal(page.actionMessage, 'app.string.vehicle_home_unlock_hint');
    assert.equal(page.unlockFocused, true);
    assert.equal(page.lastShortcutRequest, request);
    assert.equal(scrolls.length, 1, 'Resuming the page must display its pending shortcut exactly once');
    page.shortcutChanged();
    resume(page, kind);
    assert.equal(scrolls.length, 1, 'Repeated visibility events cannot redisplay a consumed shortcut');
    suspend(page, kind);
    resume(page, kind);
    assert.equal(page.actionTitle, '', 'A consumed shortcut cannot reopen after a later resume');
    assert.equal(scrolls.length, 1);
    assertNoNativeShortcutAction();
    page.aboutToDisappear();
    passed++;
    console.log(`PASS fresh shortcut deferred by ${kind} displays once on resume without native work`);
  }

  for (const kind of ['foreground', 'visibility']) {
    const page = makePage();
    suspend(page, kind);
    page.shortcutRequest = shortcut();
    page.shortcutChanged();
    now += shortcutPolicy.MAX_AGE_MS + 1;
    resume(page, kind);
    assert.equal(page.actionTitle, '', 'A shortcut that expired while hidden must not display on resume');
    assert.equal(page.unlockFocused, false);
    assert.equal(scrolls.length, 0);
    assertNoNativeShortcutAction();
    page.aboutToDisappear();
    passed++;
    console.log(`PASS shortcut expiration while ${kind} is inactive prevents display and native work`);
  }

  {
    const page = makePage();
    for (const request of [shortcut(now - shortcutPolicy.MAX_AGE_MS - 1), shortcut(now + 1), 'v1.unlock.invalid']) {
      page.shortcutRequest = request;
      page.shortcutChanged();
      assert.equal(page.actionTitle, '');
      assert.equal(page.unlockFocused, false);
      assert.equal(scrolls.length, 0);
      assertNoNativeShortcutAction();
    }
    page.aboutToDisappear();
    passed++;
    console.log('PASS already expired, future, or malformed shortcuts cannot display or scan');
  }

  {
    const page = makePage();
    const first = shortcut();
    page.shortcutRequest = first;
    page.shortcutChanged();
    assert.equal(scrolls.length, 1);
    page.actionTitle = '';
    page.actionMessage = '';
    page.unlockFocused = false;
    page.shortcutChanged();
    assert.equal(scrolls.length, 1);
    assert.equal(page.actionTitle, '', 'A repeated request must not reopen a dismissed action');
    assert.equal(page.unlockFocused, false);

    page.shortcutRequest = shortcut(now, '00000000-0000-4000-8000-000000000002');
    page.shortcutChanged();
    assert.equal(scrolls.length, 2, 'A distinct valid request remains usable after the first was consumed');
    assert.equal(page.actionTitle, 'app.string.vehicle_home_unlock_title');
    assertNoNativeShortcutAction();
    page.aboutToDisappear();
    passed++;
    console.log('PASS duplicate shortcut is ignored while a new valid request displays once');
  }

  {
    const request = shortcutPolicy.create('unlock', testNow, '00000000-0000-4000-8000-000000000003');
    const page = makePage(request);
    assert.equal(page.actionTitle, 'app.string.vehicle_home_unlock_title');
    assert.equal(page.lastShortcutRequest, request);
    assert.equal(scrolls.length, 1, 'A valid initial property must be consumed when the page mounts');
    assertNoNativeShortcutAction();
    page.aboutToDisappear();
    passed++;
    console.log('PASS a valid shortcut present on page mount only opens navigation');
  }

  console.log(`Vehicle page behavior tests passed (${passed} scenarios)`);
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
}).finally(() => {
  Module._load = originalLoad;
  global.Scroller = originalScroller;
  global.$r = originalResource;
  Date.now = originalNow;
});
