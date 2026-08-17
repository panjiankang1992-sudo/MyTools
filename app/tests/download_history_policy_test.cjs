const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.DownloadHistoryPolicy();
const owner = `account:v2:${'a'.repeat(64)}`;
const owner2 = `account:v2:${'b'.repeat(64)}`;
const item = (id, overrides = {}) => Object.assign({ owner, id, name: `file-${id}`, status: 'completed',
  receivedBytes: 12, totalBytes: 12, updatedAt: 100 }, overrides);

const normalized = policy.normalize([item('old', { updatedAt: 1 }), item('new', { updatedAt: 2 }),
  item('new', { updatedAt: 3 })]);
if (normalized.map(value => value.id).join(',') !== 'new,old') throw new Error('Stable newest-first deduplication');
if (policy.normalize([item('run', { status: 'running' })], true)[0].status !== 'interrupted') {
  throw new Error('Running task must become interrupted after process restoration');
}
if (policy.normalize([item('run', { status: 'running' })], false)[0].status !== 'running') {
  throw new Error('Live normalization must preserve running state');
}
if (policy.normalize([item('cancel', { status: 'cancelled', receivedBytes: 4 })])[0].status !== 'cancelled') {
  throw new Error('Cancelled task state must be preserved');
}

const invalid = [item('fraction', { receivedBytes: 1.5 }), item('overflow', { receivedBytes: 13 }),
  item('bad-owner', { owner: 'guest' }), item('bad-id\n'), item('bad-status', { status: 'queued' }),
  item('bad-time', { updatedAt: Number.MAX_VALUE })];
if (policy.normalize(invalid).length !== 0) throw new Error('Invalid persisted values must be rejected');

const many = [];
for (let index = 0; index < 60; index += 1) many.push(item(`a-${index}`, { updatedAt: index + 1 }));
for (let index = 0; index < 60; index += 1) many.push(item(`b-${index}`, { owner: owner2, updatedAt: index + 1 }));
const bounded = policy.normalize(many);
if (bounded.length !== 100 || bounded.filter(value => value.owner === owner).length !== 50 ||
  bounded.filter(value => value.owner === owner2).length !== 50) throw new Error('Per-owner quota');

const owners = [];
for (let index = 0; index < 12; index += 1) {
  owners.push(item(`owner-${index}`, { owner: `account:v2:${index.toString(16).padStart(64, '0')}`,
    updatedAt: index + 1 }));
}
if (policy.normalize(owners).length !== 10) throw new Error('Owner quota');
if (policy.normalize(new Array(10001).fill(item('x'))).length !== 0) throw new Error('Input scan quota');

console.log('Download history policy tests passed');
