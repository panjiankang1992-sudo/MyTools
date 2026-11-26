const assert = require('node:assert/strict');
const { VehicleShortcutNavigation } = require(process.argv[2]);
const { VehicleShortcutPolicy: policy } = require(process.argv[3]);
const issuedAt = 1793689200000;
const oldRequest = policy.create('unlock', issuedAt, '69d39807-1cc9-4858-aada-6f88b776f2ba');
const newRequest = policy.create('unlock', issuedAt, '847ed3af-7ea7-4932-899e-47d084999845');
const originalNow = Date.now;
let now = issuedAt;
let passed = 0;
Date.now = () => now;
global.$r = key => key;

function fixture() {
  const page = new VehicleShortcutNavigation();
  const calls = [];
  Object.assign(page, {
    incomingVehicleShortcut: oldRequest, vehicleShortcutRequest: '', vehicleSessionReady: true,
    vehicleShortcutHandling: false, readerAnnotationVisible: false, bookDetailVisible: false,
    readerVisible: false, mediaViewerVisible: false, bookSourceManagementVisible: true,
    profileVisible: true, rssVisible: true, mediaVideoDetailVisible: false, authenticated: true,
    showLogin: false, currentIndex: 0, toolMode: 'home'
  });
  page.CloseBookDetail = () => { calls.push('book-detail'); page.bookDetailVisible = false; };
  page.CloseReader = () => { calls.push('reader'); page.readerVisible = false; };
  page.CloseRemoteMedia = async () => { calls.push('media'); page.mediaViewerVisible = false; };
  page.CloseChapterAdaptation = resume => { calls.push(['adaptation', resume]); };
  page.CloseMediaVideoDetail = () => { calls.push('media-detail'); page.mediaVideoDetailVisible = false; };
  page.ChangeMainTab = index => { calls.push(['tab', index]); page.currentIndex = index; };
  page.OpenLogin = () => { calls.push('login'); page.showLogin = true; };
  page.ShowReaderFeedback = message => { calls.push(['reader-feedback', message]); };
  page.PdfResource = resource => resource;
  return { page, calls };
}

function deferredMedia(page, calls) {
  let resolve;
  const pending = new Promise(done => { resolve = done; });
  page.mediaViewerVisible = true;
  page.CloseRemoteMedia = () => {
    calls.push('media-await');
    return pending.then(() => { page.mediaViewerVisible = false; });
  };
  return resolve;
}

async function check(name, action) {
  now = issuedAt;
  await action();
  passed++;
  console.log(`PASS ${name}`);
}

async function main() {
  await check('cold startup waits for session restoration without navigation or consumption', async () => {
    const { page, calls } = fixture();
    page.vehicleSessionReady = false;
    page.authenticated = false;
    await page.ResumeVehicleShortcut();
    assert.equal(page.incomingVehicleShortcut, oldRequest);
    assert.equal(page.vehicleShortcutRequest, '');
    assert.equal(page.currentIndex, 0);
    assert.equal(page.vehicleShortcutHandling, false);
    assert.deepEqual(calls, []);
  });

  await check('unauthenticated navigation opens login and retains pending request', async () => {
    const { page, calls } = fixture();
    page.authenticated = false;
    await page.ResumeVehicleShortcut();
    assert.equal(page.showLogin, true);
    assert.equal(page.toolMode, 'yadea-vehicle');
    assert.equal(page.currentIndex, 1);
    assert.equal(page.incomingVehicleShortcut, oldRequest);
    assert.equal(page.vehicleShortcutRequest, '');
    assert.equal(calls.filter(value => value === 'login').length, 1);
  });

  await check('successful login consumes the request and forwards its unique id to the vehicle page', async () => {
    const { page } = fixture();
    page.authenticated = false;
    await page.ResumeVehicleShortcut();
    page.authenticated = true;
    await page.ResumeVehicleShortcut();
    assert.equal(page.showLogin, false);
    assert.equal(page.incomingVehicleShortcut, '');
    assert.equal(page.vehicleShortcutRequest, oldRequest);
    assert.equal(page.currentIndex, 1);
    assert.equal(page.toolMode, 'yadea-vehicle');
    assert.equal(page.bookSourceManagementVisible, false);
    assert.equal(page.profileVisible, false);
    assert.equal(page.rssVisible, false);
  });

  await check('cancel and leave clear both pending and displayed shortcut requests', async () => {
    const { page } = fixture();
    page.vehicleShortcutRequest = newRequest;
    page.CancelVehicleShortcut();
    assert.equal(page.incomingVehicleShortcut, '');
    assert.equal(page.vehicleShortcutRequest, '');
    page.incomingVehicleShortcut = newRequest;
    page.vehicleShortcutRequest = oldRequest;
    page.toolMode = 'yadea-vehicle';
    page.LeaveVehiclePage();
    assert.equal(page.incomingVehicleShortcut, '');
    assert.equal(page.vehicleShortcutRequest, '');
    assert.equal(page.toolMode, 'home');
  });

  await check('protected reader close retains the shortcut until reader exit is allowed', async () => {
    const { page, calls } = fixture();
    page.readerVisible = true;
    page.CloseReader = () => { calls.push('protected-reader'); };
    await page.ResumeVehicleShortcut();
    assert.equal(page.readerVisible, true);
    assert.equal(page.incomingVehicleShortcut, oldRequest);
    assert.equal(page.vehicleShortcutRequest, '');
    assert.equal(page.toolMode, 'home');
    assert.equal(page.currentIndex, 0);
    assert.deepEqual(calls, ['protected-reader']);
  });

  await check('unfinished reader annotation remains open without discarding its navigation intent', async () => {
    const { page, calls } = fixture();
    page.readerAnnotationVisible = true;
    await page.ResumeVehicleShortcut();
    assert.equal(page.readerAnnotationVisible, true);
    assert.equal(page.incomingVehicleShortcut, oldRequest);
    assert.equal(page.vehicleShortcutRequest, '');
    assert.equal(page.currentIndex, 0);
    assert.equal(calls.length, 1);
    assert.equal(calls[0][0], 'reader-feedback');
  });

  await check('new shortcut during asynchronous media exit supersedes the older request', async () => {
    const { page, calls } = fixture();
    const finish = deferredMedia(page, calls);
    const displayed = [];
    let request = '';
    Object.defineProperty(page, 'vehicleShortcutRequest', {
      get: () => request,
      set: value => { request = value; displayed.push(value); }
    });
    const pending = page.ResumeVehicleShortcut();
    assert.equal(page.vehicleShortcutHandling, true);
    page.incomingVehicleShortcut = newRequest;
    page.HandleIncomingVehicleShortcut();
    finish();
    await pending;
    await Promise.resolve();
    assert.deepEqual(displayed, [newRequest]);
    assert.equal(page.incomingVehicleShortcut, '');
    assert.equal(page.vehicleShortcutRequest, newRequest);
    assert.equal(page.toolMode, 'yadea-vehicle');
  });

  await check('request expiring during asynchronous media exit is cleared without opening vehicle page', async () => {
    const { page, calls } = fixture();
    const finish = deferredMedia(page, calls);
    const pending = page.ResumeVehicleShortcut();
    now += policy.MAX_AGE_MS + 1;
    finish();
    await pending;
    assert.equal(page.incomingVehicleShortcut, '');
    assert.equal(page.vehicleShortcutRequest, '');
    assert.equal(page.toolMode, 'home');
    assert.equal(page.currentIndex, 0);
    assert.deepEqual(calls, ['media-await']);
  });

  await check('cancelling during asynchronous media exit cannot restore the cancelled request', async () => {
    const { page, calls } = fixture();
    const finish = deferredMedia(page, calls);
    const pending = page.ResumeVehicleShortcut();
    page.CancelVehicleShortcut();
    finish();
    await pending;
    assert.equal(page.incomingVehicleShortcut, '');
    assert.equal(page.vehicleShortcutRequest, '');
    assert.equal(page.toolMode, 'home');
    assert.equal(page.currentIndex, 0);
    assert.deepEqual(calls, ['media-await']);
  });

  await check('expired startup request is discarded even before session restoration', async () => {
    const { page, calls } = fixture();
    page.vehicleSessionReady = false;
    now += policy.MAX_AGE_MS + 1;
    await page.ResumeVehicleShortcut();
    assert.equal(page.incomingVehicleShortcut, '');
    assert.equal(page.vehicleShortcutRequest, '');
    assert.deepEqual(calls, []);
  });

  await check('navigation failure clears only its current request without producing a vehicle action', async () => {
    const { page } = fixture();
    page.ChangeMainTab = () => { throw new Error('TEST_NAVIGATION_FAILURE'); };
    await page.ResumeVehicleShortcut();
    assert.equal(page.incomingVehicleShortcut, '');
    assert.equal(page.vehicleShortcutRequest, '');
    assert.equal(page.currentIndex, 0);
    assert.equal(page.vehicleShortcutHandling, false);
  });

  console.log(`Vehicle shortcut Index navigation tests passed (${passed} scenarios)`);
}

main().finally(() => {
  Date.now = originalNow;
  delete global.$r;
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
