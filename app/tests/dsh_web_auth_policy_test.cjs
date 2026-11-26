const assert = require('node:assert/strict');
const { DshWebAuthPolicy } = require(process.argv[2]);
const token = 'a'.repeat(43);
const valid = `https://dsh-app.yuyutian.top/_mytools/connect?ticket=${token}`;
assert.equal(DshWebAuthPolicy.connectionUrl(valid), valid);
for (const input of ['', token, valid.replace('https:', 'http:'), valid.replace('.top/', '.top.evil.test/'),
  `${valid}&redirect=https://evil.test`, `${valid}#fragment`, `https://evil.test/?ticket=${token}`,
  `https://dsh-app.yuyutian.top/?token=${token}`, `${valid}\n`, valid.replace(token, 'short')]) {
  assert.equal(DshWebAuthPolicy.connectionUrl(input), '');
}
console.log('PASS: DSH one-time ticket URL restrictions');

for (const input of ['https://platform.deepseek.com/oauth/authorize?state=sample&theme=light',
  'https://platform.deepseek.com/', 'https://docs.deepseek.com/en/']) {
  assert.equal(DshWebAuthPolicy.externalUrl(input), input);
}
for (const input of ['javascript:alert(1)', 'file:///etc/passwd', 'http://platform.deepseek.com/',
  'https://user:password@platform.deepseek.com/', 'https://platform.deepseek.com:8443/',
  'https://platform.deepseek.com/\n', 'https://platform.deepseek.com\\@evil.test/', 'about:blank']) {
  assert.equal(DshWebAuthPolicy.externalUrl(input), '');
}
assert.equal(DshWebAuthPolicy.trustedPage('https://dsh-app.yuyutian.top/'), true);
assert.equal(DshWebAuthPolicy.trustedPage(valid), false);
assert.equal(DshWebAuthPolicy.trustedPage('https://dsh-app.yuyutian.top.evil.test/'), false);
console.log('PASS: DSH popup destination and source restrictions');

const lan = 'http://192.168.1.7:23201';
assert.equal(DshWebAuthPolicy.routedConnectionUrl(valid, lan), `${lan}/_mytools/connect?ticket=${token}`);
assert.equal(DshWebAuthPolicy.routedConnectionUrl(valid, 'https://mytools.yuyutian.top'), valid);
assert.equal(DshWebAuthPolicy.routedConnectionUrl(valid, 'http://8.8.8.8:23201'), '');
assert.equal(DshWebAuthPolicy.routedConnectionUrl(valid, 'http://192.168.1.7:23110'), '');
assert.equal(DshWebAuthPolicy.routedConnectionUrl(valid + '&next=evil', lan), '');
assert.equal(DshWebAuthPolicy.trustedPage(`${lan}/`, lan), true);
assert.equal(DshWebAuthPolicy.trustedPage(`${lan}/chat`, lan), true);
assert.equal(DshWebAuthPolicy.trustedPage(`${lan}/`), false);
assert.equal(DshWebAuthPolicy.trustedPage(`${lan}/`, 'http://192.168.1.8:23201'), false);
for (const path of ['/_mytools/connect', '/internal/dsh-web', '/api/app/v1/dsh', '/\\evil']) {
  assert.equal(DshWebAuthPolicy.trustedPage(lan + path, lan), false);
}
console.log('PASS: DSH verified LAN origin routing and isolation');

const direct = 'https://direct6.example.test';
assert.equal(DshWebAuthPolicy.routedConnectionUrl(valid, direct, true), `${direct}/_mytools/connect?ticket=${token}`);
assert.equal(DshWebAuthPolicy.trustedPage(`${direct}/chat`, direct), true);
assert.equal(DshWebAuthPolicy.trustedPage(`${direct}.evil.test/chat`, direct), false);
assert.equal(DshWebAuthPolicy.routedConnectionUrl(valid, 'https://127.0.0.1', true), '');

const directPort = 'https://direct6.example.test:8443';
assert.equal(DshWebAuthPolicy.routedConnectionUrl(valid, directPort, true), `${directPort}/_mytools/connect?ticket=${token}`);
assert.equal(DshWebAuthPolicy.trustedPage(`${directPort}/chat`, directPort), true);
assert.equal(DshWebAuthPolicy.trustedPage('https://direct6.example.test/chat', directPort), false);
console.log('PASS: IPv6 HTTPS alternate port preserves strict origin isolation');

const businessPublic = 'https://mytools.yuyutian.top';
const publicDsh = 'https://dsh-app.yuyutian.top';
assert.equal(DshWebAuthPolicy.webOrigin(businessPublic, businessPublic, true, false), publicDsh);
assert.equal(DshWebAuthPolicy.webOrigin(lan, businessPublic, true, false), publicDsh);
assert.equal(DshWebAuthPolicy.webOrigin(lan + '/', businessPublic, false, false), lan);
assert.equal(DshWebAuthPolicy.webOrigin(directPort, businessPublic, true, false), directPort);
assert.equal(DshWebAuthPolicy.webOrigin(directPort, businessPublic, false, true), publicDsh);
assert.equal(DshWebAuthPolicy.webOrigin('http://8.8.8.8:23201', businessPublic, false, false), '');
assert.equal(DshWebAuthPolicy.webOrigin('https://127.0.0.1', businessPublic, true, false), '');
console.log('PASS: WebView origin planning preserves trusted route and HTTPS preference rules');
