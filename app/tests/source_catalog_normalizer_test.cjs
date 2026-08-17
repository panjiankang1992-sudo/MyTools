const normalizerModule = require(process.argv[2]);
const normalizer = new normalizerModule.SourceCatalogNormalizer();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

const base = 'https://books.example/catalog/index.html';
let chapters = normalizer.normalize(base, [
  { title: ' First ', url: './chapter/1' },
  { title: 'Duplicate', url: 'chapter/1' },
  { title: '', url: '../chapter/2' },
  { title: 'Empty', url: '   ' },
  { title: 'POST variant', url: './chapter/1,{"method":"POST","body":"page=2"}' }
]);
equal(chapters, [
  { title: 'First', requestUrl: 'https://books.example/catalog/chapter/1' },
  { title: '第2章', requestUrl: 'https://books.example/chapter/2' },
  {
    title: 'POST variant',
    requestUrl: 'https://books.example/catalog/chapter/1,{"method":"POST","body":"page=2"}'
  }
], 'Stable chapter normalization and deduplication');

const many = [];
for (let index = 0; index < 10050; index++) many.push({ title: `Chapter ${index}`, url: `/chapter/${index}` });
chapters = normalizer.normalize(base, many);
equal(chapters.length, 10000, 'Unique chapter limit');
equal(chapters[9999].title, 'Chapter 9999', 'Stable limit order');

console.log('Source catalog normalizer tests passed');
