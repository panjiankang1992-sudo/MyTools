const modulePath = process.argv[2];
if (!modulePath) throw new Error('compiled policy module path is required');
const { BookSourceCredentialPolicy } = require(modulePath);
const policy = new BookSourceCredentialPolicy();

const assertEqual = (actual, expected, label) => {
  if (actual !== expected) throw new Error(`${label}: expected ${expected}, got ${actual}`);
};
const assertRejects = (action, label) => {
  let rejected = false;
  try {
    action();
  } catch (_) {
    rejected = true;
  }
  if (!rejected) throw new Error(`${label} should reject`);
};

assertEqual(policy.originOf(' HTTPS://Books.Example.com:8443/path '),
  'https://books.example.com:8443', 'origin normalization');
assertEqual(policy.normalizeHeaderName('cookie'), 'Cookie', 'cookie normalization');
assertEqual(policy.normalizeHeaderName('X-API-Key'), 'X-API-Key', 'api key normalization');
assertEqual(policy.normalizeValue(' Bearer token '), 'Bearer token', 'value normalization');
assertRejects(() => policy.originOf('http://books.example.com'), 'plaintext source');
assertRejects(() => policy.originOf('https://user@books.example.com'), 'credential URL');
assertRejects(() => policy.normalizeHeaderName('Proxy-Authorization'), 'unsupported header');
assertRejects(() => policy.normalizeValue('token\r\nX-Evil: yes'), 'header injection');
let restored = policy.restore([
  { sourceUrl: 'https://books.example.com/api', origin: 'https://books.example.com', headerName: 'cookie', value: 'a=1' },
  { sourceUrl: 'https://books.example.com/api', origin: 'https://books.example.com', headerName: 'X-API-Key', value: 'new' },
  { sourceUrl: 'http://plain.example.com', origin: 'http://plain.example.com', headerName: 'Cookie', value: 'bad' },
  { sourceUrl: 'https://other.example/path', origin: 'https://evil.example', headerName: 'Cookie', value: 'bad' },
  { sourceUrl: 'https://bad.example', origin: 'https://bad.example', headerName: 'Host', value: 'bad' },
  { sourceUrl: 'https://broken.example', origin: 'https://broken.example', headerName: 'Cookie', value: { nested: true } },
  null
]);
assertEqual(restored.length, 1, 'damaged records isolated');
assertEqual(restored[0].headerName, 'X-API-Key', 'latest duplicate retained');
assertEqual(restored[0].value, 'new', 'restored value normalized');
assertEqual(policy.restore({}).length, 0, 'non-array payload rejected');
const many = Array.from({ length: 80 }, (_, index) => ({
  sourceUrl: `https://source${index}.example/path`, origin: `https://source${index}.example`,
  headerName: 'Cookie', value: `sid=${index}`
}));
restored = policy.restore(many);
assertEqual(restored.length, 64, 'restored record quota');
assertEqual(restored[0].sourceUrl, 'https://source16.example/path', 'latest records retained');
console.log('Book source credential policy tests passed');
