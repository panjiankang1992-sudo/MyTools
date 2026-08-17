const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.MediaPlaybackHistoryPolicy();
const owner = `account:v2:${'a'.repeat(64)}`;

function item(overrides = {}) {
  const accountId = overrides.accountId || '12';
  const path = overrides.path || '/films/demo.mp4';
  return { owner, key: `${accountId.length}:${accountId}${path}`, accountId, name: 'Demo', path, kind: 'video',
    contentType: 'video/mp4', size: 42, lastModified: '2026-08-12T00:00:00Z', positionMs: 5000,
    durationMs: 10000, updatedAt: 10, ...overrides };
}

const normalized = policy.normalize([item(), item({ path: '/music/song.mp3', key: '2:12/music/song.mp3',
  kind: 'audio', contentType: 'audio/mpeg', updatedAt: 20 })]);
if (normalized.length !== 2 || normalized[0].kind !== 'audio') throw new Error('Valid history ordering');

for (const invalid of [
  item({ path: '/../secret', key: '2:12/../secret' }),
  item({ path: '/films//demo.mp4', key: '2:12/films//demo.mp4' }),
  item({ key: '2:99/films/demo.mp4' }),
  item({ kind: 'image' }),
  item({ accountId: '0', key: '1:0/films/demo.mp4' }),
  item({ positionMs: '5000' }),
  item({ name: 'bad\nname' })
]) {
  if (policy.normalize([invalid]).length !== 0) throw new Error(`Invalid history accepted: ${JSON.stringify(invalid)}`);
}

const duplicates = policy.normalize([item({ updatedAt: 1 }), item({ updatedAt: 2 })]);
if (duplicates.length !== 1 || duplicates[0].updatedAt !== 2) throw new Error('Newest duplicate must win');

const quota = [];
for (let index = 0; index < 230; index++) {
  const path = `/films/${index}.mp4`;
  quota.push(item({ path, key: `2:12${path}`, updatedAt: index + 1 }));
}
if (policy.normalize(quota).length !== 200) throw new Error('Per-account quota');

console.log('Media playback history policy tests passed');
