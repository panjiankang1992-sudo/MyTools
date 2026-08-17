const moduleValue = require(process.argv[2]);
const policy = new moduleValue.ReaderTextLocatorPolicy();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const start = policy.encode(0);
const middle = policy.encode(0.5);
const end = policy.encode(1);
assert(start === 500000000, 'Version base');
assert(middle === 750000000, 'Fraction encoding');
assert(end === 1000000000, 'Maximum encoding');
assert(policy.decode(middle, 2000).versioned && policy.decode(middle, 2000).fraction === 0.5,
  'Versioned decode');
const legacy = policy.decode(600, 2400);
assert(!legacy.versioned && legacy.legacyPixels === 600 && legacy.fraction === 0.25,
  'Legacy pixel migration');
assert(policy.decode(-1, 100).legacyPixels === 0, 'Negative locator clamp');
assert(policy.encode(Number.NaN) === 500000000, 'Invalid fraction fallback');

console.log('Reader text locator policy tests passed');
