const configModule = require(process.argv[2]);
const parser = new configModule.DeclarativeRequestConfig();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejected(config, message) {
  let failed = false;
  try {
    parser.parse(JSON.stringify(config));
  } catch (_) {
    failed = true;
  }
  equal(failed, true, message);
}

let result = parser.parse(JSON.stringify({ method: 'POST', body: 'key=Alpha%20Book&page=1' }));
equal(result.body, 'key=Alpha%20Book&page=1', 'Raw form body must not be JSON quoted');
equal(result.headers['Content-Type'], 'application/x-www-form-urlencoded;charset=UTF-8',
  'Raw body form content type');

result = parser.parse(JSON.stringify({ method: 'POST', body: { keyword: 'Alpha', page: 1 } }));
equal(result.body, '{"keyword":"Alpha","page":1}', 'Object body JSON serialization');
equal(result.headers['Content-Type'], 'application/json', 'Object body JSON content type');

result = parser.parse(JSON.stringify({ method: 'GET', headers: { Accept: 'application/json', 'X-Client': 'MyTools' } }));
equal(result.headers, { Accept: 'application/json', 'X-Client': 'MyTools' }, 'Ordinary inline headers');
result = parser.parse(JSON.stringify({ method: 'POST', body: 'raw', headers: { 'content-type': 'text/plain' } }));
equal(result.headers, { 'Content-Type': 'text/plain' }, 'Explicit content type canonicalization');
result = parser.parse(JSON.stringify({ method: 'POST', body: '中'.repeat(Math.floor(64 * 1024 / 3)) }));
equal(result.body.length, Math.floor(64 * 1024 / 3), 'UTF-8 body boundary accepted');

rejected({ method: 'PUT' }, 'Unsupported method rejection');
rejected({ method: 'GET', body: 'hidden' }, 'GET body rejection');
rejected({ method: 'POST', body: 'a'.repeat(64 * 1024 + 1) }, 'ASCII body quota rejection');
rejected({ method: 'POST', body: '中'.repeat(Math.floor(64 * 1024 / 3) + 1) }, 'UTF-8 body quota rejection');
rejected({ method: 'POST', body: 'raw', headers: { 'Content-Type': 'application/octet-stream' } },
  'Binary content type rejection');
rejected({ method: 'POST', body: 'raw', headers: { 'Content-Type': 'multipart/form-data' } },
  'Multipart content type rejection');
rejected({ method: 'GET', headers: { Authorization: 'Bearer secret' } }, 'Authorization rejection');
rejected({ method: 'GET', headers: { Cookie: 'sid=secret' } }, 'Cookie rejection');
rejected({ method: 'GET', headers: { 'X-Test': 'safe\r\ninjected: true' } }, 'CRLF rejection');
rejected({ method: 'GET', headers: { 'Invalid Header': 'value' } }, 'Invalid header name rejection');
rejected({ method: 'GET', headers: { 'X-Test': { nested: true } } }, 'Structured header value rejection');

let oversizedConfigRejected = false;
try {
  parser.parse(`{"method":"POST","body":"${'a'.repeat(128 * 1024)}"}`);
} catch (_) {
  oversizedConfigRejected = true;
}
equal(oversizedConfigRejected, true, 'Configuration byte quota rejection');

console.log('Declarative request config tests passed');
