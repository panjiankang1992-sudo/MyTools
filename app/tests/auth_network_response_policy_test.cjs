const policyModule = require(process.argv[2]);
const policy = new policyModule.AuthNetworkResponsePolicy();

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

equal(policy.headerError({ 'Content-Type': 'application/json;charset=UTF-8', 'Content-Length': '128' }, 1024 * 1024),
  '', 'JSON response headers');
equal(policy.headerError({ 'content-type': 'application/vnd.spring-boot.actuator.v3+json' }, 1024 * 1024),
  '', 'Actuator vendor JSON');
equal(policy.headerError({ Location: 'https://other.example/login' }, 1024 * 1024),
  '认证接口不允许重定向', 'Redirect rejection');
equal(policy.headerError({ 'Content-Length': '-1' }, 1024 * 1024),
  '认证响应长度无效或超过1 MB限制', 'Negative length rejection');
equal(policy.headerError({ 'Content-Type': 'text/html' }, 1024 * 1024),
  '认证响应类型无效', 'HTML response rejection');

equal(policy.envelope('{"code":"0000","message":"ok","data":{"id":"1"}}', 48),
  { code: '0000', message: 'ok', data: { id: '1' } }, 'Auth envelope');
rejects(() => policy.envelope('[]', 2), 'Array envelope rejection');
rejects(() => policy.envelope('{"code":"0000","data":[]}', 25), 'Array data rejection');
rejects(() => policy.envelope('{"code":"0000"}', 1024 * 1024 + 1), 'UTF-8 response quota');
policy.requestBody(16 * 1024);
rejects(() => policy.requestBody(16 * 1024 + 1), 'Auth request quota');

console.log('Auth network response policy tests passed');
