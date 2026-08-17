const moduleValue = require(process.argv[2]);
const policy = new moduleValue.ReaderViewportPolicy();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

assert(policy.normalize('360', 700) === undefined, 'String width rejected');
assert(policy.normalize(NaN, 700) === undefined, 'Non-finite width rejected');
assert(policy.normalize(0, 700) === undefined, 'Zero width rejected');
const minimum = policy.normalize(100, 200);
assert(minimum.width === 240 && minimum.height === 320, 'Minimum clamp');
const maximum = policy.normalize(9000, 9000);
assert(maximum.width === 2400 && maximum.height === 2400, 'Maximum clamp');
assert(!policy.changed({ width: 360, height: 700 }, { width: 361.9, height: 701.9 }), 'Jitter suppressed');
assert(policy.changed({ width: 360, height: 700 }, { width: 720, height: 400 }), 'Rotation detected');

console.log('Reader viewport policy tests passed');
