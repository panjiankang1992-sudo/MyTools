const normalizerModule = require(process.argv[2]);
const normalizer = new normalizerModule.ShelfSyncResponseNormalizer();

function remote(id = 'remote:sha256:' + 'a'.repeat(64), overrides = {}) {
  return Object.assign({ bookId: id, name: 'Book', author: 'Library', origin: 'remote', format: 'epub',
    resourceUri: '/books/book.epub', sourceId: '12', remoteCoverUrl: '', clientUpdatedAt: 100,
    deleted: false, revision: 1 }, overrides);
}
function source(id = 'source:' + 'b'.repeat(64), overrides = {}) {
  return Object.assign({ bookId: id, name: 'Web Book', author: 'Author', origin: 'source', format: 'txt',
    resourceUri: 'https://books.example.com/book', sourceId: 'https://books.example.com/source',
    remoteCoverUrl: 'https://books.example.com/cover.jpg', clientUpdatedAt: 100,
    deleted: false, revision: 1 }, overrides);
}
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

equal(normalizer.list([remote(), source()]).map(value => value.origin), ['remote', 'source'],
  'Remote and source projection');
equal(normalizer.list([remote('same', { revision: 1 }), remote('same', { revision: 2, name: 'New' })])[0].name,
  'New', 'Latest revision wins');
equal(normalizer.list([remote('same', { revision: 2, clientUpdatedAt: 100 }),
  remote('same', { revision: 2, clientUpdatedAt: 200, name: 'Later' })])[0].name,
  'Later', 'Latest timestamp tie break');
equal(normalizer.list([remote(), remote('local:bad'), remote('bad', { sourceId: '../1' }), null]).length,
  1, 'Damaged item isolation');
equal(normalizer.list(new Array(6000).fill(null).map((_, index) => remote(`book-${index}`))).length,
  5000, 'Shelf quota');
equal(normalizer.localItem({ id: 'remote:ok', name: 'Book', author: 'Library', origin: 'remote', format: 'epub',
  resourceUri: '/book.epub', sourceId: '1', remoteCoverUrl: '', updatedAt: 1, deleted: false, revision: 0 }).id,
  'remote:ok', 'Local request validation');
equal(normalizer.saveResult({ accepted: true, book: remote() }).accepted, true, 'Accepted save response');
equal(normalizer.saveResult({ accepted: false, book: null }).book, undefined, 'Missing conflict record allowed');

rejects(() => normalizer.list({}), 'Non-array list rejection');
rejects(() => normalizer.localItem({ id: 'local:file', name: 'Book', author: 'Me', origin: 'remote', format: 'txt',
  resourceUri: '/book.txt', sourceId: '1', remoteCoverUrl: '', updatedAt: 1, deleted: false, revision: 0 }),
  'Local book upload rejection');
rejects(() => normalizer.localItem({ id: 'remote:bad', name: 'Book', author: 'Me', origin: 'remote', format: 'txt',
  resourceUri: '/../book.txt', sourceId: '1', remoteCoverUrl: '', updatedAt: 1, deleted: false, revision: 0 }),
  'Remote traversal rejection');
rejects(() => normalizer.saveResult({ accepted: true, book: null }), 'Accepted response requires book');
rejects(() => normalizer.saveResult({ accepted: 'true', book: remote() }), 'Accepted type rejection');

console.log('Shelf sync response normalizer tests passed');
