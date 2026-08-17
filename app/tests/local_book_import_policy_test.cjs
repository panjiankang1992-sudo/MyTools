const policyModule = require(process.argv[2]);
const policy = new policyModule.LocalBookImportPolicy();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

const books = policy.normalize([
  'file://docs/storage/Users/currentUser/Download/%E4%B8%89%E4%BD%93.EPUB',
  'file://docs/storage/Users/currentUser/Download/comic.cbz',
  'file://docs/storage/Users/currentUser/Download/album.zip',
  'file://docs/storage/Users/currentUser/Download/legacy.cbr',
  'file://docs/storage/Users/currentUser/Download/%E4%B8%89%E4%BD%93.EPUB',
  'https://example.com/not-local.txt',
  'file://docs/storage/Users/currentUser/Download/unsupported.docx'
]);
equal(books.map(value => [value.name, value.format]), [['三体', 'epub'], ['comic', 'cbz'], ['album', 'cbz']],
  'Format, decoding, order and deduplication');
equal(books[0].origin, 'local', 'Local origin');
equal(books[0].id, `local:${books[0].resourceUri}`, 'Stable local id');
equal(policy.normalize(new Array(60).fill(null).map((_, index) =>
  `file://docs/storage/Users/currentUser/Download/book-${index}.txt`)).length, 50, 'Selection quota');
equal(policy.normalize([
  'file://docs/storage/Users/currentUser/Download/%2e%2e%2fsecret.txt',
  'file://docs/storage/Users/currentUser/Download/bad%00.txt',
  'file://docs/storage/Users/currentUser/Download/.epub',
  `file://docs/storage/Users/currentUser/Download/${'x'.repeat(501)}.txt`,
  'content://provider/book.txt',
  'file://docs/storage/Users/currentUser/Download/good.mobi#fragment'
]).length, 0, 'Unsafe selection isolation');

console.log('Local book import policy tests passed');
