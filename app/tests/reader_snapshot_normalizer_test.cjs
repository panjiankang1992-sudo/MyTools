const moduleUnderTest = require(process.argv[2]);
const normalizer = new moduleUnderTest.ReaderSnapshotNormalizer();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const baseBook = {
  id: 'book-1', name: 'Book', author: 'Author', coverUrl: '', origin: 'source', format: 'txt',
  resourceUri: 'https://books.example.com/book.txt', sourceId: 'https://books.example.com', progress: 2
};
const books = normalizer.normalizeBooks([
  baseBook,
  Object.assign({}, baseBook, { name: 'Latest', progress: 100,
    tags: ['history', 'history', '\u0000bad', 'x'.repeat(41), 'finished'] }),
  Object.assign({}, baseBook, { id: 'bad', origin: 'forged' }),
  null,
  { id: 'missing-fields' }
]);
assert(books.length === 1 && books[0].name === 'Latest', 'books should deduplicate and isolate invalid records');
assert(books[0].progress === 100, 'percentage progress should restore without ratio clamping');
assert(books[0].tags.length === 2 && books[0].tags[0] === 'history' && books[0].tags[1] === 'finished',
  'book tags should deduplicate and isolate invalid values');

const progress = normalizer.normalizeProgress([
  { bookId: 'book-1', chapterTitle: 'Chapter', locator: Infinity, percentage: -3, updatedAt: NaN, revision: -1 },
  { bookId: 'book-2', chapterTitle: '\u0000bad', locator: 1, percentage: 0.5, updatedAt: 1 },
  { bookId: 'book-3', chapterTitle: 'Finished', locator: 1, percentage: 100, updatedAt: 2, revision: 3 }
]);
assert(progress.length === 2, 'invalid progress item should be isolated');
assert(progress[0].locator === 0 && progress[0].percentage === 0 && progress[0].revision === 0,
  'non-finite and negative progress values should normalize');
assert(progress[1].percentage === 100, 'completed percentage should survive snapshot restore');

const bookmark = { id: 'marker-1', bookId: 'book-1', chapterTitle: 'Chapter', locator: 3,
  note: 'line 1\nline 2', createdAt: 1, updatedAt: 2, revision: 3 };
assert(normalizer.normalizeBookmarks([bookmark]).length === 1, 'multiline marker note should restore');
assert(normalizer.normalizeBookmarks([Object.assign({}, bookmark, { id: '../bad' })]).length === 0,
  'invalid marker id should be rejected');
assert(normalizer.normalizeMarkerTombstones([Object.assign({}, bookmark, { kind: 'OTHER' })]).length === 0,
  'unknown marker tombstone kind should be rejected');

const settings = normalizer.normalizeSettings({ fontFamily: 'invalid', fontSize: Infinity, lineHeight: 99,
  paragraphSpacing: 99, brightness: -1,
  orientation: 'invalid', theme: 'invalid', pageTurnMode: 'invalid', comicDirection: 'invalid', comicPageMode: 'invalid',
  comicFitMode: 'invalid', comicScale: 99, comicPreload: 99 });
assert(settings.fontSize === 19, 'non-finite setting should use default');
assert(settings.lineHeight === 2.4 && settings.brightness === 0.35, 'numeric settings should clamp');
assert(settings.paragraphSpacing === 32, 'paragraph spacing should clamp');
assert(settings.theme === 'paper' && settings.pageTurnMode === 'slide', 'unknown enums should use defaults');
assert(settings.orientation === 'system', 'unknown orientation should use default');
assert(settings.fontFamily === 'serif', 'unknown font should use default');
assert(settings.comicScale === 3 && settings.comicPreload === 5, 'comic settings should clamp');
assert(normalizer.normalizeSettings({ lineHeight: 1.5 }).paragraphSpacing === 14,
  'legacy settings should migrate default paragraph spacing');
assert(normalizer.normalizeSettings({ orientation: 'landscape' }).orientation === 'landscape',
  'valid orientation should restore');
assert(normalizer.normalizeSettings({ fontFamily: 'serif' }).fontFamily === 'serif', 'valid font should restore');

const many = new Array(10020).fill(null).map((_, index) => Object.assign({}, bookmark, { id: `m-${index}` }));
assert(normalizer.normalizeBookmarks(many).length === 10000, 'marker count should be bounded');

console.log('Reader snapshot normalizer tests passed');
