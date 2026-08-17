const assert = require('node:assert/strict');

module.exports = function run(SourceHttpRequestHeaderPolicy) {
  const policy = new SourceHttpRequestHeaderPolicy();
  assert.deepEqual(policy.parse(''), {});
  assert.deepEqual(policy.parse('{"User-Agent":"Reader","Content-Type":"text/plain"}'),
    { 'User-Agent': 'Reader', 'Content-Type': 'text/plain' });
  assert.throws(() => policy.parse('{'), /JSON对象/);
  assert.throws(() => policy.parse('[]'), /JSON对象/);
  assert.throws(() => policy.parse('{"Cookie":"secret"}'), /敏感/);
  assert.throws(() => policy.parse('{"Host":"evil.example"}'), /保留/);
  assert.throws(() => policy.parse('{"Content-Length":"1"}'), /保留/);
  assert.throws(() => policy.parse('{"Accept-Encoding":"gzip"}'), /保留/);
  assert.throws(() => policy.parse('{"X-Test":"a\\nb"}'), /值无效/);
  assert.throws(() => policy.parse(JSON.stringify({ 'X-Test': '中'.repeat(1366) })), /4096/);
  assert.throws(() => policy.parse('{"X-Test":{"nested":true}}'), /标量/);
  assert.throws(() => policy.parse('{"X-Test":"a","x-test":"b"}'), /重复/);

  const merged = policy.merge({ 'User-Agent': 'one', 'X-Test': 'base', Accept: 'bad' },
    { 'user-agent': 'two', 'X-Inline': 'yes' });
  assert.equal(merged['user-agent'], 'two');
  assert.equal(merged['User-Agent'], undefined);
  assert.equal(merged['X-Test'], 'base');
  assert.equal(merged['X-Inline'], 'yes');
  assert.equal(merged.Accept, 'application/json,text/plain,text/html,application/xml,application/xhtml+xml,*/*');
};
