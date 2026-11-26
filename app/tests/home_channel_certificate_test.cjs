const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { Module } = require('node:module');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');

const source = path.resolve(__dirname, '../entry/src/main/ets/features/connectivity/HomeChannelCertificate.ets');
const compiled = new Module(source, module);
compiled.require = name => {
  if (name === '@ohos.net.socket') return { default: {} };
  assert.equal(name, '@ohos.security.cert');
  return { default: {
    EncodingFormat: { FORMAT_DER: 0 },
    createX509Cert: async raw => ({ getEncoded: async () => raw })
  } };
};
compiled._compile(ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText, source);
const { HomeChannelCertificate } = compiled.exports;

function der(tag, payload) {
  const body = Array.isArray(payload) && payload.some(value => Buffer.isBuffer(value)) ?
    Buffer.concat(payload) : Buffer.from(payload);
  const length = body.length < 128 ? [body.length] : body.length < 256 ? [129, body.length] :
    [130, body.length >> 8, body.length & 255];
  return Buffer.concat([Buffer.from([tag, ...length]), body]);
}

function dns(value) { return der(0x82, Buffer.from(value)); }
function ip(value) { return der(0x87, Buffer.from(value)); }
function san(names) { return der(0x30, [der(6, [0x55, 0x1d, 0x11]), der(4, der(0x30, names))]); }
function leaf(extensions) {
  return der(0x30, der(0x30, [der(2, [1]), der(0xa3, der(0x30, extensions))]));
}

(async () => {
  const valid = leaf([san([dns('home.example.test'), dns('*.example.test'), ip([10, 0, 2, 2])])]);
  for (const host of ['home.example.test', 'HOME.EXAMPLE.TEST', 'one.example.test', '10.0.2.2']) {
    assert.equal(HomeChannelCertificate.matches(valid, host), true, host);
    await HomeChannelCertificate.verify({ data: valid, encodingFormat: 0 }, host);
  }
  for (const host of ['example.test', 'two.one.example.test', 'home.example.test.evil', 'other.test',
    '10.0.2.3', '010.0.2.2', '10.0.2.2.evil', 'home.example.test\0evil', 'home.example.test\n',
    'https://home.example.test', '[::1]', '', '-bad.example.test', 'bad..example.test']) {
    assert.equal(HomeChannelCertificate.matches(valid, host), false, host);
  }
  for (const bad of [
    leaf([]),
    leaf([san([dns('*.test')])]),
    leaf([san([dns('h*.example.test')])]),
    leaf([san([dns('*.*.example.test')])]),
    leaf([san([dns('home.example.test\0.evil')])]),
    leaf([san([dns('home.example.test')]), san([dns('home.example.test')])]),
    leaf([san([ip([10, 0, 2])])]),
    leaf([san([der(0x86, Buffer.from('home.example.test'))])]),
    leaf([san([dns('10.0.2.2')])]),
    Buffer.concat([valid, Buffer.from([0])]),
    Buffer.from([0x30, 0x80, 0, 0]),
    Buffer.from([0x30, 0x81, 1, 0]),
    Buffer.alloc(65537)
  ]) {
    assert.equal(HomeChannelCertificate.matches(bad, 'home.example.test'), false);
    await assert.rejects(HomeChannelCertificate.verify({ data: bad, encodingFormat: 0 }, 'home.example.test'),
      /Channel certificate hostname rejected/);
  }
  assert.equal(HomeChannelCertificate.matches(leaf([san([dns('10.0.2.2')])]), '10.0.2.2'), false,
    'An IP reference must match iPAddress SAN, never dNSName');
  for (let length = 0; length < valid.length; length++) {
    assert.equal(HomeChannelCertificate.matches(valid.subarray(0, length), 'home.example.test'), false,
      'A truncated DER certificate was accepted');
  }

  // 系统格式来自真实 OpenSSL 叶证书，确保 DER 遍历也适用于完整 TBS 和其他扩展。
  const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'home-channel-cert-'));
  try {
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
      '-subj', '/CN=ignored.example.test', '-addext',
      'subjectAltName=DNS:home.example.test,DNS:*.example.test,IP:10.0.2.2,IP:127.0.0.1',
      '-keyout', path.join(fixture, 'fixture.key'), '-out', path.join(fixture, 'fixture.pem')], { stdio: 'ignore' });
    const actual = execFileSync('openssl', ['x509', '-in', path.join(fixture, 'fixture.pem'), '-outform', 'DER']);
    for (const host of ['home.example.test', 'one.example.test', '10.0.2.2', '127.0.0.1']) {
      assert.equal(HomeChannelCertificate.matches(actual, host), true, host);
    }
    for (const host of ['ignored.example.test.evil', 'ignored.example.test.other', '10.0.2.3']) {
      assert.equal(HomeChannelCertificate.matches(actual, host), false, host);
    }
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
      '-subj', '/CN=home.example.test', '-keyout', path.join(fixture, 'fixture.key'),
      '-out', path.join(fixture, 'fixture.pem')], { stdio: 'ignore' });
    const cnOnly = execFileSync('openssl', ['x509', '-in', path.join(fixture, 'fixture.pem'), '-outform', 'DER']);
    assert.equal(HomeChannelCertificate.matches(cnOnly, 'home.example.test'), false, 'Missing SAN fell back to CN');
  } finally {
    fs.rmSync(fixture, { recursive: true, force: true });
  }
  console.log('Channel certificate DNS, wildcard, typed IP SAN, malformed DER and real certificate tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
