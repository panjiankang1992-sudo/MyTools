const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const sourceRoot = path.resolve(__dirname, '../entry/src/main/ets');
const typescript = '/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript';
const fixturePath = path.join(__dirname, 'business_endpoint_transport_test.cjs');
const fixtureSource = fs.readFileSync(fixturePath, 'utf8');
const fixtureEnd = fixtureSource.indexOf('const cases = [];');
assert.ok(fixtureEnd > 0, 'Shared business fixture initialization boundary is missing');
const withoutGuard = process.argv.includes('--without-restore-guard');

function loadFixture() {
  let mutationCount = 0;
  const fixtureFs = Object.create(fs);
  fixtureFs.readFileSync = (filename, ...options) => {
    const source = fs.readFileSync(filename, ...options);
    if (!withoutGuard || !String(filename).endsWith('/features/auth/AuthSessionManager.ets')) return source;
    // 仅内存移除修复，验证同一行为用例确实可以检出旧竞态；不修改任何业务文件。
    return source.replace(/(\} catch \(caught\) \{\n)(?:[ \t]*\/\/[^\n]*\n)*[ \t]*this\.assertAuthenticationCurrent\(revision\);\n/,
      (_match, catchStart) => { mutationCount++; return catchStart; });
  };
  const fixtureRequire = dependency => dependency === 'node:fs' ? fixtureFs : require(dependency);
  const expose = `
const authApi = loadSource(path.join(sourceRoot, 'features/auth/AuthApi.ets'));
return { AuthSessionManager, AuthRejectedError: authApi.AuthRejectedError, FakeSessionStore,
  storeState, requests, responses, manager, session, envelope, publicBase, accessToken, rotatedToken };
`;
  // 直接复用已有原生网络及安全存储 mock，实际认证实现仍由原 fixture 编译真实 ETS。
  const result = new Function('require', '__dirname', 'process', 'module',
    fixtureSource.substring(0, fixtureEnd) + expose)(fixtureRequire, __dirname,
    { argv: ['', '', sourceRoot, typescript] }, module);
  if (withoutGuard) assert.equal(mutationCount, 1, 'Old-code countercheck did not remove exactly one restore guard');
  return result;
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

async function runRace(operation, completion) {
  const fixture = loadFixture();
  const auth = await fixture.manager();
  const oldOrigin = 'https://older-fixture.example.test';
  const newOrigin = 'https://newer-fixture.example.test';
  const oldLoad = deferred();
  let invalidations = 0;
  auth.onSessionInvalidated = () => invalidations++;
  const load = fixture.FakeSessionStore.prototype.load;
  fixture.FakeSessionStore.prototype.load = async function(base) {
    if (base === oldOrigin) return oldLoad.promise;
    return load.call(this, base);
  };

  // 旧恢复停在存储读取；新认证操作必须先完整提交，之后才释放旧恢复结果。
  const oldWork = auth.restore(oldOrigin).then(
    value => ({ fulfilled: true, value }),
    error => ({ fulfilled: false, error }));
  if (operation === 'restore') {
    fixture.storeState.seed = { ...fixture.session(), userId: '8', username: 'new-fixture',
      accessToken: fixture.rotatedToken, refreshToken: 's'.repeat(48) };
    await auth.restore(newOrigin);
  } else if (operation === 'login') {
    fixture.responses.push({ url: newOrigin + '/api/app/v1/identity/login', method: 'POST',
      body: fixture.envelope({ userId: '8', username: 'new-fixture', nickname: '', role: 'USER',
        accessToken: fixture.rotatedToken, refreshToken: 's'.repeat(48), expiresIn: 3600 }) });
    await auth.login(newOrigin, { account: 'new-fixture', password: 'fixture-password', deviceName: 'fixture-device' });
  } else {
    fixture.responses.push({ url: fixture.publicBase + '/api/app/v1/identity/logout', method: 'POST' });
    await auth.logout();
  }
  const committed = { session: auth.session, origin: auth.publicBusinessBaseUrl(),
    active: auth.activeBusinessBaseUrl(), revision: auth.sessionRevision(),
    stored: structuredClone(fixture.storeState.seed), saves: fixture.storeState.saves.length,
    clears: fixture.storeState.clears };
  if (operation === 'logout') {
    assert.equal(committed.session, undefined);
    assert.equal(committed.stored, undefined);
  } else {
    assert.ok(committed.session !== undefined);
    assert.ok(committed.origin === newOrigin, 'New authentication did not commit before the stale result');
  }

  if (completion === 'success') oldLoad.resolve(fixture.session());
  else if (completion === 'storage-error') oldLoad.reject(Error('Fixture storage unavailable'));
  else oldLoad.reject(new fixture.AuthRejectedError('Fixture old session rejected'));
  const stale = await oldWork;
  assert.equal(stale.fulfilled, false, 'An obsolete restore resolved and rebound its old session');
  assert.match(stale.error.message, /认证操作已被新的请求替代/,
    'An obsolete restore entered the ordinary fallback or rejected-session cleanup');
  assert.ok(auth.session === committed.session, 'Old restore replaced the newer committed in-memory session');
  assert.ok(auth.publicBusinessBaseUrl() === committed.origin, 'Old restore rebound the API to its old public origin');
  assert.ok(auth.activeBusinessBaseUrl() === committed.active, 'Old restore reset the newer business endpoint');
  assert.equal(auth.sessionRevision(), committed.revision);
  assert.deepEqual(fixture.storeState.seed, committed.stored, 'Old restore changed the new secure-storage contents');
  assert.equal(fixture.storeState.saves.length, committed.saves, 'Old restore wrote obsolete credentials');
  assert.equal(fixture.storeState.clears, committed.clears, 'Old AuthRejected cleared the new secure-storage session');
  assert.equal(invalidations, 0, 'Old AuthRejected invalidated the newly committed account');
  assert.equal(fixture.responses.length, 0);
}

(async () => {
  let cases = 0;
  for (const operation of ['restore', 'login', 'logout']) {
    for (const completion of ['success', 'storage-error', 'auth-rejected']) {
      try { await runRace(operation, completion); }
      catch (error) {
        console.error('Failed restore revision test: newer=' + operation + ', oldLoad=' + completion);
        throw error;
      }
      cases++;
    }
  }
  console.log(`Auth restore scope revision behavior tests passed (${cases} cases)`);
})().catch(error => { console.error(error); process.exitCode = 1; });
