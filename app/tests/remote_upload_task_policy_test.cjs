const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteUploadTaskPolicy();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

equal(policy.headers({ ':status': '200', 'Content-Type': 'application/json;charset=UTF-8',
  'Content-Length': '128' }), { status: 200, error: '' }, 'Successful upload headers');
equal(policy.headers({ statusCode: 401, 'content-type': 'application/problem+json' }),
  { status: 401, error: '' }, 'Authentication rejection headers');
equal(policy.headers({ ':status': 302, Location: 'https://other.example/upload' }),
  { status: 302, error: '远程上传不允许重定向' }, 'Redirect rejection');
equal(policy.headers({ ':status': 'invalid' }), { status: 0, error: '远程上传响应状态无效' },
  'Invalid status rejection');
equal(policy.headers({ status: 200, responseCode: 201 }),
  { status: 0, error: '远程上传响应状态冲突' }, 'Conflicting status rejection');
equal(policy.headers({ 'Content-Length': '-1' }),
  { status: 0, error: '远程上传响应长度无效或超过1 MB限制' }, 'Negative length rejection');
equal(policy.headers({ 'Content-Type': 'text/html' }),
  { status: 0, error: '远程上传响应类型无效' }, 'HTML rejection');
equal(policy.headers({ 'Content-Encoding': 'gzip' }),
  { status: 0, error: '远程上传不接受压缩响应' }, 'Compressed response rejection');

equal(policy.progress(100, 1000, 50), { uploadedBytes: 100, totalBytes: 1000 }, 'Monotonic upload progress');
equal(policy.progress(40, 1000, 50), undefined, 'Regressive progress rejection');
equal(policy.progress(1001, 1000, 50), undefined, 'Progress overflow rejection');
equal(policy.progress(NaN, 1000, 50), undefined, 'Non-finite progress rejection');
equal(policy.progress(1, 2 * 1024 * 1024 * 1024 + 1, 0), undefined, 'Upload size quota');

console.log('Remote upload task policy tests passed');
