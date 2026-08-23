const normalizerModule = require(process.argv[2]);
const normalizer = new normalizerModule.ReaderSyncResponseNormalizer();
const syncA = `sha256:${'a'.repeat(64)}`;
const syncB = `sha256:${'b'.repeat(64)}`;
const keys = new Map([[syncA, 'local-a'], [syncB, 'local-b']]);

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejects(callback, message) {
  let rejected = false;
  try { callback(); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`${message}: expected rejection`);
}

function progress(bookId, revision, updatedAt, overrides = {}) {
  return Object.assign({ bookId, chapterTitle: '第一章', locator: 12, percentage: 20,
    clientUpdatedAt: updatedAt, deleted: false, revision }, overrides);
}

const progressValues = normalizer.progressList([
  progress(syncA, 1, 20), progress(syncA, 2, 10), progress(syncB, 1, 30),
  progress('bad', 9, 99), progress(syncB, 2, 40, { percentage: NaN })
], keys);
equal(progressValues.map(value => [value.bookId, value.revision, value.updatedAt]),
  [['local-a', 2, 10], ['local-b', 1, 30]], 'Progress validation and revision dedupe');
equal(normalizer.progressList({}, keys), [], 'Non-array progress list');
equal(normalizer.progressList([progress(syncA, 1, 20, { locator: 1000000001 })], keys), [],
  'Progress locator upper bound');
equal(normalizer.progressSave({ accepted: true, progress: progress(syncA, 3, 50) }, 'local-a').progress.bookId,
  'local-a', 'Progress save receipt');
rejects(() => normalizer.progressSave({ accepted: 'true', progress: progress(syncA, 3, 50) }, 'local-a'),
  'Progress accepted type');
rejects(() => normalizer.progressSave({ accepted: true }, 'local-a'), 'Accepted progress without authority');

function source(url, revision, updatedAt, overrides = {}) {
  return Object.assign({ sourceUrl: url, snapshotJson: '{"bookSourceName":"Test"}',
    clientUpdatedAt: updatedAt, deleted: false, revision }, overrides);
}
equal(normalizer.sourceList([
  source('https://one.example', 1, 10), source('https://one.example', 1, 20),
  source('https://bad.example', 1, 10, { snapshotJson: 'x'.repeat(524289) })
]).map(value => [value.sourceUrl, value.updatedAt]), [['https://one.example', 20]],
  'Source validation and timestamp dedupe');
equal(normalizer.sourceSave({ accepted: false, source: source('https://one.example', 2, 30) }).accepted,
  false, 'Rejected source receipt with authority');
rejects(() => normalizer.sourceSave({ accepted: true, source: [] }), 'Invalid source authority');

function marker(id, bookId, revision, updatedAt, overrides = {}) {
  return Object.assign({ markerId: id, kind: 'BOOKMARK', bookId, chapterTitle: '第一章', locator: 9,
    note: '', createdAt: 1, clientUpdatedAt: updatedAt, deleted: false, revision }, overrides);
}
equal(normalizer.markerList([
  marker('mark-1', syncA, 1, 10), marker('mark-1', syncA, 2, 20),
  marker('bad id', syncA, 3, 30), marker('mark-2', syncB, 1, 10, { kind: 'UNKNOWN' })
], keys).map(value => [value.id, value.revision]), [['mark-1', 2]],
  'Marker validation and revision dedupe');
equal(normalizer.markerList([marker('mark-high', syncA, 1, 10, { locator: 1000000001 })], keys), [],
  'Marker locator upper bound');
equal(normalizer.markerSave({ accepted: true, marker: marker('mark-1', syncA, 3, 30) }, 'local-a')
  .marker.bookId, 'local-a', 'Marker save receipt');
rejects(() => normalizer.markerSave({ accepted: true, marker: marker('mark-1', syncA, 3, 30,
  { note: 'x'.repeat(2001) }) }, 'local-a'), 'Marker note quota');

equal(normalizer.summary({ shelfRecords: 1, sourceRecords: 2, progressRecords: 3, markerRecords: 4 }),
  { shelfRecords: 1, sourceRecords: 2, progressRecords: 3, markerRecords: 4 }, 'Reader data summary');
equal(normalizer.deletedRecords({ deletedRecords: 10 }), 10, 'Reader delete receipt');
rejects(() => normalizer.summary({ shelfRecords: -1, sourceRecords: 2, progressRecords: 3, markerRecords: 4 }),
  'Negative summary count');
rejects(() => normalizer.deletedRecords({ deletedRecords: Infinity }), 'Infinite delete count');

const quotaInput = [];
for (let index = 0; index < 10020; index += 1) {
  quotaInput.push(source(`https://${index}.example`, 1, index));
}
equal(normalizer.sourceList(quotaInput).length, 5000, 'List scan and output quotas');

console.log('Reader sync response normalizer tests passed');
