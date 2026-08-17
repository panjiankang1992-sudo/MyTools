const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.MediaSeekPolicy();

if (policy.absolute(1234.6, 10000) !== 1235) throw new Error('Millisecond rounding');
if (policy.absolute(-50, 10000) !== 0) throw new Error('Lower clamp');
if (policy.absolute(20000, 10000) !== 10000) throw new Error('Duration clamp');
if (policy.absolute(Number.NaN, 10000) !== undefined) throw new Error('NaN rejection');
if (policy.absolute(Number.POSITIVE_INFINITY, 0) !== undefined) throw new Error('Infinity rejection');
if (policy.absolute(40000000000, 0) !== 31536000000) throw new Error('Unknown duration global cap');
if (policy.offset(5000, 10000, 12000) !== 12000) throw new Error('Positive offset clamp');
if (policy.offset(5000, -10000, 12000) !== 0) throw new Error('Negative offset clamp');
if (policy.offset(5000, 7200000, 10000000) !== 3605000) throw new Error('Single offset quota');
if (policy.offset(Number.NaN, 1000, 10000) !== undefined) throw new Error('Invalid current rejection');

console.log('Media seek policy tests passed');
