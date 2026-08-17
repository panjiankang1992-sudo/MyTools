const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteAudioQueuePolicy();

function item(index, kind = 'audio') {
  return { name: `Track ${index}`, path: `/track-${index}`, kind, contentType: 'audio/mpeg',
    size: 100, lastModified: '' };
}

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

equal(policy.queue([item(1), item(2, 'image'), item(3)]).map(value => value.path),
  ['/track-1', '/track-3'], 'Audio filtering');
equal(policy.queue(new Array(10020).fill(null).map((_, index) => item(index))).length,
  10000, 'Queue quota');
equal(policy.nextIndex(4, 3, 1), 0, 'Forward wrap');
equal(policy.nextIndex(4, 0, -1), 3, 'Backward wrap');
equal(policy.nextIndex(0, 0, 1), -1, 'Empty queue rejection');
equal(policy.nextIndex(4, 4, 1), -1, 'Invalid index rejection');
equal(policy.nextIndex(4, 0, NaN), -1, 'Invalid direction rejection');
const largeQueue = new Array(300).fill(null).map((_, index) => item(index));
equal(policy.visibleWindow(largeQueue, '/track-150').length, 101, 'Visible window quota');
equal(policy.visibleWindow(largeQueue, '/track-150')[50].path, '/track-150', 'Visible window center');
equal(policy.visibleWindow(largeQueue, '/missing')[0].path, '/track-0', 'Missing current fallback');

console.log('Remote audio queue policy tests passed');
