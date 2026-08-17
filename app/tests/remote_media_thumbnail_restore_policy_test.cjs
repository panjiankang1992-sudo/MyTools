const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteMediaThumbnailRestorePolicy();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

const current = [{ path: '/a', uri: 'file://current-a' }, { path: '/stale', uri: 'file://stale' }];
const snapshot = [{ path: '/a', uri: 'file://snapshot-a' }, { path: '/b', uri: 'file://snapshot-b' },
  { path: '/c', uri: 'file://missing-c' }];
const restored = policy.restore(['/a', '/b', '/c'], current, snapshot,
  uri => uri !== 'file://missing-c');
equal(restored, [{ path: '/a', uri: 'file://snapshot-a' }, { path: '/b', uri: 'file://snapshot-b' }],
  'Viewer snapshot restores valid gallery projections and replaces duplicate paths');
equal(policy.missing(['/a', '/b', '/c'], restored), ['/c'], 'Only missing projection is scheduled for reload');
equal(policy.restore(['/a'], [], [], () => true), [], 'Empty state stays empty');

console.log('Remote media thumbnail restore policy tests passed');
