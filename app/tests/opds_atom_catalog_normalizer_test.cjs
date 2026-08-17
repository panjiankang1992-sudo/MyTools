const moduleValue = require(process.argv[2]);
const normalizer = new moduleValue.OpdsAtomCatalogNormalizer();

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

function book(id, title, href, extraLinks = '') {
  return `<entry><id>${id}</id><title>${title}</title><author><name>Author &amp; Co</name></author>` +
    `<summary>Safe <b>summary</b></summary>` +
    `<link rel="http://opds-spec.org/acquisition" href="${href}" type="application/epub+zip"/>` +
    `${extraLinks}</entry>`;
}

const xml = `<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Library</title>` +
  book('book-1', 'Book One', '../files/book.epub', '<link rel="cover" href="../covers/book.jpg"/>') +
  book('book-1', 'Duplicate', '../files/duplicate.epub') +
  book('bad-http', 'Bad HTTP', 'http://internal/book.epub') +
  `<entry><id>bad-mime</id><title>Bad MIME</title><link rel="http://opds-spec.org/acquisition" ` +
  `href="../files/download" type="application/pdf-evil"/></entry>` +
  book('bad-title', 'x'.repeat(301), '../files/bad.epub') +
  `<entry><title>All books</title><link rel="subsection" href="../all.xml" type="application/atom+xml"/></entry>` +
  `<entry><title>Unsafe</title><link rel="subsection" href="//evil.example/opds" type="application/atom+xml"/></entry>` +
  `</feed>`;

equal(normalizer.normalize('https://books.example/catalog/featured.xml', xml), {
  title: 'Library',
  books: [{ id: 'book-1', title: 'Book One', author: 'Author & Co', summary: 'Safe summary',
    coverUrl: 'https://books.example/covers/book.jpg',
    acquisitionUrl: 'https://books.example/files/book.epub', format: 'epub' }],
  navigation: [{ title: 'All books', url: 'https://books.example/all.xml' }]
}, 'Atom projection, dedupe and item isolation');

equal(normalizer.normalize('https://books.example/opds.xml', '<feed><title></title></feed>'),
  { title: 'OPDS书库', books: [], navigation: [] }, 'Empty feed defaults');

const tooMany = [];
for (let index = 0; index < 2001; index += 1) tooMany.push(`<entry><title>${index}</title></entry>`);
rejects(() => normalizer.normalize('https://books.example/opds.xml', `<feed>${tooMany.join('')}</feed>`),
  'Atom scan quota');

const many = [];
for (let index = 0; index < 1100; index += 1) many.push(book(`id-${index}`, `Book ${index}`, `/book-${index}.epub`));
equal(normalizer.normalize('https://books.example/opds.xml', `<feed>${many.join('')}</feed>`).books.length,
  1000, 'Atom result quota');

const links = new Array(101).fill('<link rel="cover" href="/cover.jpg"/>').join('');
equal(normalizer.normalize('https://books.example/opds.xml', `<feed>${book('many-links', 'Many', '/book.epub', links)}</feed>`)
  .books.length, 0, 'Per-entry link quota');

console.log('OPDS Atom catalog normalizer tests passed');
