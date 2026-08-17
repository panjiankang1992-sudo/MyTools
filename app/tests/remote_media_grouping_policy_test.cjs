const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.RemoteMediaGroupingPolicy();

const item = (path, directoryName, kind = 'image') => ({
  name: path.split('/').pop(), path, directoryName, kind, contentType: '', size: 1, lastModified: ''
});

const groups = policy.groups([
  item('/local/1', '旅行'),
  item('/local/2', '20260813'),
  item('/local/3', '20260814'),
  item('/local/4', '旅行'),
  item('/local/5', undefined),
  item('/folder', '忽略', 'directory')
]);

const names = groups.map(group => group.directoryName);
if (JSON.stringify(names) !== JSON.stringify(['20260814', '20260813', '旅行', '根目录'])) {
  throw new Error(`Directory order: ${JSON.stringify(names)}`);
}
if (groups.find(group => group.directoryName === '旅行').items.length !== 2) {
  throw new Error('Directory aggregation');
}
if (policy.groups([item('/local/6', 'album/20260814'), item('/local/7', 'album/20251231')])[0].directoryName !==
  '20260814') throw new Error('Nested date directory order');

const datedItem = item('/local/8', '202608/20260815/album');
datedItem.lastModified = '2026-08-15T09:00:00';
if (policy.groups([datedItem])[0].directoryName !== '20260815') {
  throw new Error('Friendly date grouping');
}
if (policy.directoryNames([datedItem])[0] !== '202608/20260815/album') {
  throw new Error('Directory filter keeps raw directory');
}

const now = new Date(2026, 7, 14, 12, 0, 0).getTime();
if (policy.displayName('20260814', now) !== '今天 · 8月14日') throw new Error('Today display label');
if (policy.displayName('20260813', now) !== '昨天 · 8月13日') throw new Error('Yesterday display label');
if (policy.displayName('album/20260812', now) !== 'album · 2026年8月12日') throw new Error('Nested date label');
if (policy.displayName('202608', now) !== '2026年8月') throw new Error('Month display label');
if (policy.displayName('20261340', now) !== '20261340') throw new Error('Invalid date label');
if (policy.displayName('旅行', now) !== '旅行') throw new Error('Plain directory label');

console.log('Remote media grouping policy tests passed');
