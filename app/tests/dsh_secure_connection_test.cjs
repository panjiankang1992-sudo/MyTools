const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript');
const policy = require(process.argv[3]);
let ticket = 'https://dsh-app.yuyutian.top/_mytools/connect?ticket=' + 'a'.repeat(43);
let pendingTicket;
const ticketRequests = [];
const compiled = ts.transpileModule(fs.readFileSync(process.argv[2], 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText;
const output = { exports: {} };
vm.runInNewContext(compiled, { exports: output.exports, require: name => {
  if (name === './DshWebAuthPolicy') return policy;
  if (name === './DshResponseNormalizer') return { DshResponseNormalizer: class {} };
  if (name === '../../shared/network/AuthorizedApiClient') return { AuthorizedApiClient: class {
    constructor(base, auth, fallback, timeout, pinned) { this.route = { base, pinned }; }
    async post(path, cancellation) {
      assert.equal(path, '/api/app/v1/dsh/web-ticket');
      ticketRequests.push({ ...this.route, cancellation });
      if (pendingTicket) await pendingTicket;
      return { data: { url: ticket } };
    }
  } };
  throw new Error('Unexpected import ' + name);
} });
(async () => {
  const lan = 'http://192.168.1.7:23201';
  const businessPublic = 'https://mytools.yuyutian.top';
  const auth = { activeBusinessBaseUrl: () => lan, publicBusinessBaseUrl: () => businessPublic };
  const api = new output.exports.DshApi(lan, auth);
  assert.equal(await api.webTicket(true), ticket);
  assert.equal(await api.webTicket(false), ticket.replace('https://dsh-app.yuyutian.top', lan));
  assert.equal(auth.activeBusinessBaseUrl(), lan);
  const direct = 'https://direct6.example.test:8443';
  let active = direct;
  const directAuth = { activeBusinessBaseUrl: () => active, publicBusinessBaseUrl: () => businessPublic };
  const directApi = new output.exports.DshApi(direct, directAuth);
  assert.equal(await directApi.webTicket(true), ticket.replace('https://dsh-app.yuyutian.top', direct));
  const cancellation = {};
  assert.equal(await directApi.webTicket(true, true, cancellation), ticket);
  assert.deepEqual(ticketRequests.at(-1), { base: businessPublic, pinned: true, cancellation });
  assert.equal(directAuth.activeBusinessBaseUrl(), direct);

  let finishTicket;
  pendingTicket = new Promise(resolve => { finishTicket = resolve; });
  const directRequest = directApi.webTicket(true);
  active = businessPublic;
  finishTicket();
  assert.equal(await directRequest, ticket.replace('https://dsh-app.yuyutian.top', direct));

  pendingTicket = new Promise(resolve => { finishTicket = resolve; });
  const publicRequest = directApi.webTicket(true);
  active = direct;
  finishTicket();
  assert.equal(await publicRequest, ticket);
  pendingTicket = undefined;

  ticket = 'https://evil.test/_mytools/connect?ticket=' + 'a'.repeat(43);
  await assert.rejects(api.webTicket(true), /Invalid DSH ticket URL/);
  console.log('PASS: HTTPS preference, verified LAN, stable WebView origin during ticket exchange, invalid ticket rejection');
})().catch(error => { console.error(error); process.exitCode = 1; });
