const assert = require('node:assert/strict');
const { LogoutRiskPolicy } = require(process.argv[2]);
const policy = new LogoutRiskPolicy();

assert.equal(policy.pendingCount({ pendingProgress: 2, pendingMarkers: 3, pendingShelfBooks: 4,
  pendingBookSources: 5, syncActive: false }), 14);
assert.equal(policy.requiresConfirmation({ pendingProgress: 0, pendingMarkers: 0, pendingShelfBooks: 0,
  pendingBookSources: 0, syncActive: false }), false);
assert.equal(policy.requiresConfirmation({ pendingProgress: 1, pendingMarkers: 0, pendingShelfBooks: 0,
  pendingBookSources: 0, syncActive: false }), true);
assert.equal(policy.requiresConfirmation({ pendingProgress: 0, pendingMarkers: 0, pendingShelfBooks: 0,
  pendingBookSources: 0, syncActive: true }), true);
assert.equal(policy.pendingCount({ pendingProgress: -1, pendingMarkers: 1.5, pendingShelfBooks: Number.NaN,
  pendingBookSources: 2000000, syncActive: false }), 1000000);
console.log('Logout risk policy tests passed');
