const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.RemoteMediaPreloadPolicy();

const assertArray = (actual, expected, label) => {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${label}: ${JSON.stringify(actual)}`);
  }
};

assertArray(policy.neighborIndexes(12, 5), [4, 6, 3, 7, 2, 8, 1, 9, 0, 10], 'five each side');
assertArray(policy.neighborIndexes(5, 0), [4, 1, 3, 2], 'wrap and deduplicate all available items');
assertArray(policy.neighborIndexes(5, 2, 2), [1, 3, 0, 4], 'custom radius');
assertArray(policy.neighborIndexes(2, 0), [1], 'deduplicate two item sequence');
assertArray(policy.neighborIndexes(1, 0), [], 'skip single item sequence');
assertArray(policy.neighborIndexes(5, -1), [], 'reject invalid current index');
assertArray(policy.neighborIndexes(5, 0, 0), [], 'reject invalid radius');

const now = 1_700_000_000_000;
if (!policy.reusable('file:///cache/image.jpg', 0, now)) throw new Error('local cached image');
if (!policy.reusable('https://example.test/ticket', now + 31_000, now)) throw new Error('fresh ticket');
if (policy.reusable('https://example.test/ticket', now + 30_000, now)) throw new Error('ticket safety margin');
if (policy.reusable('https://example.test/ticket', Number.NaN, now)) throw new Error('invalid expiry');

console.log('Remote media preload policy tests passed');
