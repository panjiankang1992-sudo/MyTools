const normalizerModule = require(process.argv[2]);
const normalizer = new normalizerModule.AuthResponseNormalizer();
const now = 1700000000000;
const token = 'a'.repeat(24) + '.' + 'b'.repeat(24) + '.' + 'c'.repeat(24);
const refresh = 'd'.repeat(24) + '.' + 'e'.repeat(24) + '.' + 'f'.repeat(24);

function validData() {
  return { userId: '123456789', username: 'reader', nickname: 'Reader', avatar: 'https://example.com/a.png',
    role: 'USER', accessToken: token, refreshToken: refresh, expiresIn: 900 };
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

const session = normalizer.normalizeLogin(validData(), now);
equal(session.expiresAt, now + 900000, 'Login expiry');
equal(session.username, 'reader', 'Login identity');
const refreshed = normalizer.normalizeRefresh({ accessToken: refresh, refreshToken: token, expiresIn: 1200 },
  session, now + 1000);
equal(refreshed.accessToken, refresh, 'Refresh access token');
equal(refreshed.refreshToken, token, 'Rotated refresh token');
equal(refreshed.username, session.username, 'Refresh identity preservation');
equal(session.accessToken, token, 'Refresh does not mutate old session');
const legacyRefreshed = normalizer.normalizeRefresh({ accessToken: refresh, expiresIn: 1200 }, session, now + 1000);
equal(legacyRefreshed.refreshToken, session.refreshToken, 'Legacy refresh token preservation');
equal(normalizer.normalizeStored(session, now).userId, session.userId, 'Stored session validation');
equal(normalizer.normalizeLogin(Object.assign(validData(), { avatar: 'data:image/png;base64,QUJDRA==' }), now).avatar,
  'data:image/png;base64,QUJDRA==', 'Bounded inline avatar');
equal(normalizer.normalizeLogin(Object.assign(validData(), { avatar: 'data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=' }), now).avatar,
  '', 'Server-generated SVG avatar safely falls back to account initial');

rejects(() => normalizer.normalizeLogin(Object.assign(validData(), { accessToken: 'Bearer bad' }), now),
  'Malformed token rejection');
rejects(() => normalizer.normalizeLogin(Object.assign(validData(), { expiresIn: 0 }), now),
  'Zero expiry rejection');
rejects(() => normalizer.normalizeLogin(Object.assign(validData(), { expiresIn: 400 * 24 * 60 * 60 }), now),
  'Excessive expiry rejection');
rejects(() => normalizer.normalizeLogin(Object.assign(validData(), { username: 'bad\nname' }), now),
  'Identity control rejection');
equal(normalizer.normalizeLogin(Object.assign(validData(), { avatar: 'javascript:alert(1)' }), now).avatar, '',
  'Unsafe avatar fallback');
equal(normalizer.normalizeLogin(Object.assign(validData(), { avatar: 123 }), now).avatar, '',
  'Non-string avatar fallback');
rejects(() => normalizer.normalizeLogin(Object.assign(validData(), { role: 'USER ADMIN' }), now),
  'Malformed role rejection');
rejects(() => normalizer.normalizeStored(Object.assign({}, session, { expiresAt: now + 367 * 24 * 60 * 60 * 1000 }), now),
  'Stored future expiry rejection');
rejects(() => normalizer.normalizeStored(Object.assign({}, session, { expiresAt: now - 367 * 24 * 60 * 60 * 1000 }), now),
  'Stored stale expiry rejection');

console.log('Auth response normalizer tests passed');
