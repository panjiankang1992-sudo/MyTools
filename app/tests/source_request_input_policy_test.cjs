const assert = require('node:assert/strict');

module.exports = function run(SourceRequestInputPolicy) {
  const policy = new SourceRequestInputPolicy();
  assert.equal(policy.keyword('  三体  '), '三体');
  assert.equal(policy.keyword('a'.repeat(200)), 'a'.repeat(200));
  assert.equal(policy.keyword('😀'.repeat(100)), '😀'.repeat(100));
  assert.throws(() => policy.keyword('   '), /搜索词/);
  assert.throws(() => policy.keyword('a'.repeat(201)), /搜索词/);
  assert.throws(() => policy.keyword('中'.repeat(201)), /搜索词/);
  assert.throws(() => policy.keyword('book\nname'), /控制字符/);
  assert.equal(policy.page(1), 1);
  assert.equal(policy.page(100), 100);
  for (const value of [0, 101, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.throws(() => policy.page(value), /页码/);
  }
};
