const normalizerModule = require(process.argv[2]);
const normalizer = new normalizerModule.BookSourceSnapshotNormalizer();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const legacy = {
  bookSourceUrl: 'https://books.example.com',
  bookSourceName: 'Legacy',
  searchUrl: '/search?q={{key}}',
  exploreUrl: '/rank/{{page}}',
  ruleSearch: {},
  ruleBookInfo: {},
  ruleToc: {},
  ruleContent: {},
  healthStatus: 'available',
  healthLatencyMs: 320.8,
  healthSuccessRate: 5,
  healthMessage: 'x'.repeat(300),
  healthHistory: new Array(25).fill({ status: 'available', latencyMs: 20, checkedAt: 10 })
};
const restored = normalizer.normalize([legacy]);
assert(restored.length === 1, 'legacy source should restore');
assert(Object.keys(restored[0].ruleExplore).length === 0, 'missing explore rules should migrate to empty record');
assert(restored[0].healthLatencyMs === 320, 'health latency should normalize to integer');
assert(restored[0].healthSuccessRate === 1, 'health success rate should clamp');
assert(restored[0].healthMessage.length === 200, 'health message should be bounded');
assert(restored[0].healthHistory.length === 20, 'health history should be bounded');

const unsafe = Object.assign({}, legacy, { bookSourceUrl: 'https://user:secret@books.example.com' });
const nested = Object.assign({}, legacy, { bookSourceUrl: 'https://nested.example.com',
  ruleSearch: { bookList: { invalid: true } } });
assert(normalizer.normalize([unsafe, nested, legacy]).length === 1,
  'invalid persisted sources should be rejected independently');
assert(normalizer.normalize(new Array(501).fill(legacy)).length === 1,
  'source count and URL deduplication should remain bounded');

console.log('Book source snapshot normalizer tests passed');
