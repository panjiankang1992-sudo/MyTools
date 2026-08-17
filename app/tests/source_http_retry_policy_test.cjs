const assert = require('node:assert/strict');

module.exports = async function run(SourceHttpRetryPolicy, DownloadCancellationToken) {
  const policy = new SourceHttpRetryPolicy();
  assert.equal(policy.shouldRetry(true, 0), true);
  assert.equal(policy.shouldRetry(true, 0, 429), true);
  assert.equal(policy.shouldRetry(true, 0, 502), true);
  assert.equal(policy.shouldRetry(true, 0, 503), true);
  assert.equal(policy.shouldRetry(true, 0, 504), true);
  assert.equal(policy.shouldRetry(true, 0, 400), false);
  assert.equal(policy.shouldRetry(true, 0, 401), false);
  assert.equal(policy.shouldRetry(true, 0, 404), false);
  assert.equal(policy.shouldRetry(false, 0), false);
  assert.equal(policy.shouldRetry(false, 0, 503), false);
  assert.equal(policy.shouldRetry(true, 1), false);
  assert.equal(policy.shouldRetry(true, 1, 503), false);

  const token = new DownloadCancellationToken();
  const waiting = policy.wait(token);
  token.cancel();
  await assert.rejects(waiting, /已取消/);
};
