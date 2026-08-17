const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.MediaOperationRevisionPolicy();

if (!policy.current(3, 3, true)) throw new Error('Current player event');
if (policy.current(2, 3, true)) throw new Error('Stale revision rejection');
if (policy.current(3, 3, false)) throw new Error('Replaced instance rejection');
if (policy.current(0, 0, true) || policy.current(Number.NaN, Number.NaN, true)) {
  throw new Error('Invalid revision rejection');
}

console.log('Media operation revision policy tests passed');
