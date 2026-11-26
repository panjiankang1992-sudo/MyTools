const assert = require('node:assert/strict');
const { DownloadReplyPolicy: policy } = require(process.argv[2]);
assert.equal(policy.hasSavedFiles('FAILED', ['storage://archive/file']), true);
assert.equal(policy.hasSavedFiles('FAILED', ['', 'storage://archive/file']), true);
assert.equal(policy.hasSavedFiles('FAILED', []), false);
assert.equal(policy.hasSavedFiles('FAILED', ['', '  ', undefined]), false);
for (const status of ['RUNNING', 'SUCCEEDED', 'CANCELLED', 'CANCELLING']) {
  assert.equal(policy.hasSavedFiles(status, ['storage://archive/file']), false);
}
console.log('PASS: failed tasks with saved files, partial results, empty results and other lifecycle states');
