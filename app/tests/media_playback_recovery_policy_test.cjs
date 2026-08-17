const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.MediaPlaybackRecoveryPolicy();

if (policy.position(25000, 10000, 60000) !== 25000) throw new Error('Live position takes priority');
if (policy.position(0, 10000, 60000) !== 10000) throw new Error('Stored fallback');
if (policy.position(500, 10000, 60000) !== 10000) throw new Error('Ignore unstable initial progress');
if (policy.position(90000, 10000, 60000) !== 0) throw new Error('Completed position starts over');
if (policy.position(55000, 10000, 60000) !== 0) throw new Error('Near-end position starts over');
if (policy.position(Number.NaN, -1, Number.POSITIVE_INFINITY) !== 0) throw new Error('Invalid values');
if (policy.position(4000.5, 2000, 0) !== 2000) throw new Error('Only safe integers accepted');

console.log('Media playback recovery policy tests passed');
