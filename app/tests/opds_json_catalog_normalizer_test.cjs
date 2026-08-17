const moduleValue = require(process.argv[2]);
const normalizer = new moduleValue.OpdsJsonCatalogNormalizer();

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

function publication(id, title, href, overrides = {}) {
  return Object.assign({ metadata: { identifier: id, title, author: [{ name: 'Author A' }, { name: 'Author B' }],
    description: 'Summary' }, links: [{ rel: ['http://opds-spec.org/acquisition'], href,
      type: 'application/epub+zip' }], images: [{ rel: 'cover', href: '../covers/book.jpg' }] }, overrides);
}

const catalog = normalizer.normalize('https://books.example/catalog/featured.json', JSON.stringify({
  metadata: { title: 'Featured' },
  publications: [
    publication('book-1', 'Book One', '../files/book.epub'),
    publication('book-1', 'Duplicate', '../files/duplicate.epub'),
    publication('bad-scheme', 'Bad', 'http://internal/book.epub'),
    publication('bad-mime', 'Bad MIME', '../files/download', { links: [{ rel: 'http://opds-spec.org/acquisition',
      href: '../files/download', type: 'application/pdf-evil' }] }),
    publication('bad-title', 'x'.repeat(301), '../files/bad.epub'),
    { metadata: { title: 'No links' }, links: 'invalid' }
  ],
  navigation: [
    { title: 'All books', href: '../all.json' },
    { title: 'Duplicate', href: '../all.json' },
    { title: 'Unsafe', href: '//evil.example/catalog' },
    { title: 'Traversal beyond origin', href: '../../../../escape.json' }
  ]
}));
equal(catalog, {
  title: 'Featured',
  books: [{ id: 'book-1', title: 'Book One', author: 'Author A、Author B', summary: 'Summary',
    coverUrl: 'https://books.example/covers/book.jpg',
    acquisitionUrl: 'https://books.example/files/book.epub', format: 'epub' }],
  navigation: [{ title: 'All books', url: 'https://books.example/all.json' }]
}, 'OPDS 2 projection, relative URLs and item isolation');

equal(normalizer.normalize('https://books.example/opds.json', JSON.stringify({ metadata: {}, publications: [] })),
  { title: 'OPDS书库', books: [], navigation: [] }, 'Optional collections and title');
rejects(() => normalizer.normalize('https://books.example/opds.json', '[]'), 'Root object validation');
rejects(() => normalizer.normalize('https://books.example/opds.json', '{"publications":{}}'),
  'Publication list shape');
rejects(() => normalizer.normalize('https://books.example/opds.json', JSON.stringify({
  publications: new Array(2001).fill(publication('x', 'X', '/x.epub'))
})), 'Publication scan quota');

const many = [];
for (let index = 0; index < 1100; index += 1) many.push(publication(`id-${index}`, `Book ${index}`, `/book-${index}.epub`));
equal(normalizer.normalize('https://books.example/opds.json', JSON.stringify({ publications: many })).books.length,
  1000, 'Publication result quota');

console.log('OPDS JSON catalog normalizer tests passed');
