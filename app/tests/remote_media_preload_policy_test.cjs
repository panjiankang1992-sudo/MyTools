const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.RemoteMediaPreloadPolicy();

const assertArray = (actual, expected, label) => {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${label}: ${JSON.stringify(actual)}`);
  }
};

assertArray(policy.neighborIndexes(30, 14), Array.from({length:12},(_,i)=>[15+i,13-i]).flat(), 'twelve each side, nearest next first');
assertArray(policy.neighborIndexes(5, 0), [1, 4, 2, 3], 'wrap and deduplicate');
assertArray(policy.neighborIndexes(5, 2, 2), [3, 1, 4, 0], 'custom radius');
assertArray(policy.neighborIndexes(30, 0, 12, false), Array.from({length:12},(_,i)=>i+1), 'Catalog start cannot wrap to temporary page end');
assertArray(policy.neighborIndexes(30, 28, 12, false), [29,...Array.from({length:12},(_,i)=>27-i)], 'Catalog end retains previous items without wrapping');
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
