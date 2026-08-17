const modulePath = process.argv[2];
if (!modulePath) throw new Error('compiled policy module path is required');
const { OpdsCredentialPolicy } = require(modulePath);
const policy = new OpdsCredentialPolicy();

const assertEqual = (actual, expected, label) => {
  if (actual !== expected) throw new Error(`${label}: expected ${expected}, got ${actual}`);
};
const assertRejects = (value) => {
  let rejected = false;
  try {
    policy.originOf(value);
  } catch (_) {
    rejected = true;
  }
  if (!rejected) throw new Error(`unsafe URL accepted: ${value}`);
};

assertEqual(policy.originOf(' HTTPS://Books.Example.com:8443/opds '),
  'https://books.example.com:8443', 'origin normalization');
assertEqual(policy.originOf('https://books.example.com/a'), policy.originOf('https://books.example.com/b'),
  'same origin');
assertRejects('http://books.example.com/opds');
assertRejects('https://user@books.example.com/opds');
assertRejects('https://books.example.com@evil.example/opds');
assertRejects('https://books.example.com\r\nAuthorization:x');
assertEqual(policy.encodeBase64(new TextEncoder().encode('Aladdin:open sesame')),
  'QWxhZGRpbjpvcGVuIHNlc2FtZQ==', 'basic base64');
let restored = policy.restore({ origin: 'https://books.example.com', username: ' user ', password: 'secret' });
assertEqual(restored.origin, 'https://books.example.com', 'restored origin');
assertEqual(restored.username, 'user', 'restored username normalization');
assertEqual(restored.password, 'secret', 'restored password');
assertEqual(policy.restore({ origin: 'https://books.example.com/path', username: 'user', password: 'secret' }),
  undefined, 'non-origin stored value rejection');
assertEqual(policy.restore({ origin: 'http://books.example.com', username: 'user', password: 'secret' }),
  undefined, 'plaintext restored origin rejection');
assertEqual(policy.restore({ origin: 'https://books.example.com', username: 'user\nname', password: 'secret' }),
  undefined, 'restored control character rejection');
assertEqual(policy.restore({ origin: 'https://books.example.com', username: 'user', password: { nested: true } }),
  undefined, 'restored type rejection');
assertEqual(policy.restore([]), undefined, 'restored array rejection');
console.log('OPDS credential policy tests passed');
