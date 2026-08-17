const policyModule = require(process.argv[2]);
const policy = new policyModule.ArchiveToolPolicy();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function expectError(action, expected) {
  try {
    action();
    throw new Error(`expected error: ${expected}`);
  } catch (error) {
    if (error.message !== expected) throw error;
  }
}

policy.validateSelectionCount(1);
policy.validateSelectionCount(20);
expectError(() => policy.validateSelectionCount(0), '请选择1至20个普通文件');
expectError(() => policy.validateSelectionCount(21), '请选择1至20个普通文件');
assert(policy.addFileSize(1, 0) === 1, 'minimum file should pass');
assert(policy.addFileSize(100 * 1024 * 1024, 0) === 100 * 1024 * 1024, 'maximum file should pass');
expectError(() => policy.addFileSize(0, 0), '单个文件必须在1字节至100 MB之间');
expectError(() => policy.addFileSize(100 * 1024 * 1024 + 1, 0), '单个文件必须在1字节至100 MB之间');
expectError(() => policy.addFileSize(1, 500 * 1024 * 1024), '所选文件总大小不能超过500 MB');
assert(policy.safeFileName('file:///docs/report%202026.txt', 0) === '1-report 2026.txt', 'URI should decode');
assert(policy.safeFileName('file:///docs/a%2Fb%3Ac.txt', 1) === '2-a_b_c.txt', 'path characters should sanitize');
assert(policy.safeFileName('file:///docs/%ZZ', 2) === '3-%ZZ', 'invalid encoding should remain bounded');
assert(policy.safeFileName('file:///docs/', 3) === '4-file', 'empty filename should fall back');

console.log('Archive tool policy tests passed');
