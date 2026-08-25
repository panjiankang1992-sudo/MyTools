const policyModule = require(process.argv[2]);
const policy = new policyModule.AuthorizedApiPolicy();

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

equal(policy.path('/api/files?path=%2Fbooks%2Fa.epub'), '/api/files?path=%2Fbooks%2Fa.epub',
  'API path with encoded query');
equal(policy.path('/api/app/v1/shelf'), '/api/app/v1/shelf', 'Basic API path');
rejects(() => policy.path('https://example.com/api/files'), 'Absolute URL rejection');
rejects(() => policy.path('/api/../secret'), 'Traversal rejection');
rejects(() => policy.path('/api//files'), 'Duplicate separator rejection');
rejects(() => policy.path('/api/files#fragment'), 'Fragment rejection');
rejects(() => policy.path('/api/files\nnext'), 'Control character rejection');
rejects(() => policy.path(`/api/${'x'.repeat(8190)}`), 'Path length quota');

policy.requestBody(undefined, 0);
policy.requestBody('{}', 2 * 1024 * 1024);
rejects(() => policy.requestBody('{}', 2 * 1024 * 1024 + 1), 'Request body quota');
rejects(() => policy.requestBody('{}', -1), 'Negative request size rejection');

equal(policy.envelope('{"code":"0000","message":"ok","data":{"id":1},"traceId":"trace-1"}', 70), {
  code: '0000', message: 'ok', data: { id: 1 }, traceId: 'trace-1'
}, 'Object envelope');
equal(policy.envelope('{"code":"0000","data":[1,2]}', 30), {
  code: '0000', message: '', data: [1, 2], traceId: undefined
}, 'Array data envelope');
equal(policy.envelope('{"code":"0000","data":null}', 29), {
  code: '0000', message: '', data: undefined, traceId: undefined
}, 'Null data normalization');
rejects(() => policy.envelope('[]', 2), 'Array envelope rejection');
rejects(() => policy.envelope('{"code":""}', 11), 'Empty code rejection');
equal(policy.envelope('{"code":"0000","message":"bad\\nmessage"}', 40), {
  code: '0000', message: 'bad message', data: undefined, traceId: undefined
}, 'Message control sanitization');
equal(policy.envelope('{"code":"0000","traceId":"bad trace"}', 38), {
  code: '0000', message: '', data: undefined, traceId: undefined
}, 'Invalid trace identifier omission');
rejects(() => policy.envelope('{"code":"0000"}', 0), 'Empty byte count rejection');
rejects(() => policy.envelope('{"code":"0000"}', 64 * 1024 * 1024 + 1), 'Response quota');

console.log('Authorized API policy tests passed');
