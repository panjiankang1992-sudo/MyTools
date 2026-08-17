const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.ReaderTextScalePolicy();

if (policy.normalize(undefined) !== 1 || policy.normalize(Number.NaN) !== 1) {
  throw new Error('Invalid system scale must use the default');
}
if (policy.normalize(0.2) !== 0.8 || policy.normalize(9) !== 3.2) {
  throw new Error('System scale must be bounded');
}
if (policy.normalize(1.75) !== 1.75) throw new Error('Valid system scale must be preserved');
if (policy.changed(1, 1.005) || !policy.changed(1, 1.02)) {
  throw new Error('Scale change threshold');
}

console.log('Reader text scale policy tests passed');
