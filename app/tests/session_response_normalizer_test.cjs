const normalizerModule = require(process.argv[2]);
const normalizer = new normalizerModule.SessionResponseNormalizer();

function session(id, overrides = {}) {
  return Object.assign({ id: `${id}`, tokenName: `Device ${id}`, tokenPrefix: 'eyJhbGci', status: 'ACTIVE',
    createdTime: '2026-08-12T06:00:00', expireTime: '2026-08-12T07:00:00' }, overrides);
}

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejects(callback, message) {
  let rejected = false;
  try { callback(); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`${message}: expected rejection`);
}

equal(normalizer.parseEnvelope('{"code":"0000","message":"ok","data":{"id":"12"}}').code,
  '0000', 'Envelope parsing');
equal(normalizer.currentId({ id: '12' }), '12', 'Current id');
const sessions = normalizer.sessions({ list: [session(2), session(1), session(2),
  session(3, { status: 'BROKEN' }), null] }, '1');
equal(sessions.map(value => value.id), ['1', '2'], 'Current priority, deduplication and isolation');
equal(sessions[0].current, true, 'Current marker');
equal(normalizer.sessions({ list: new Array(300).fill(null).map((_, index) => session(index + 1)) }, '250').length,
  200, 'Retained session quota');
equal(normalizer.sessions({ list: new Array(1100).fill(null).map((_, index) => session(index + 1)) }, '999')[0].id,
  '999', 'Scanned session quota keeps current');
equal(normalizer.revokeId('123e4567-e89b-42d3-a456-426614174000'),
  '123e4567-e89b-42d3-a456-426614174000', 'Revoke id validation');

rejects(() => normalizer.parseEnvelope(''), 'Empty envelope rejection');
rejects(() => normalizer.parseEnvelope('x'.repeat(1024 * 1024 + 1)), 'Oversize envelope rejection');
rejects(() => normalizer.currentId({ id: '../1' }), 'Unsafe current id rejection');
rejects(() => normalizer.sessions({ list: {} }, '1'), 'Non-array list rejection');
rejects(() => normalizer.revokeId('0'), 'Non-UUID revoke id rejection');
rejects(() => normalizer.revokeId('1/others'), 'Path injection rejection');

console.log('Session response normalizer tests passed');
