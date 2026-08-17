const resolverModule = require(process.argv[2]);
const resolver = new resolverModule.SourceUrlResolver();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${expected}, actual=${actual}`);
}

function rejected(value, message) {
  let failed = false;
  try {
    resolver.resolve('https://books.example/catalog/section/index.html?old=1#top', value);
  } catch (_) {
    failed = true;
  }
  equal(failed, true, message);
}

function requestRejected(value, message) {
  let failed = false;
  try {
    resolver.resolveRequest('https://books.example/catalog/index.html', value);
  } catch (_) {
    failed = true;
  }
  equal(failed, true, message);
}

const base = 'https://books.example/catalog/section/index.html?old=1#top';
equal(resolver.resolve(base, '../chapter/1.html'), 'https://books.example/catalog/chapter/1.html',
  'Parent path resolution');
equal(resolver.resolve(base, './chapter/2.html?mode=full#page'),
  'https://books.example/catalog/section/chapter/2.html?mode=full#page', 'Current path resolution');
equal(resolver.resolve(base, '/covers/a.jpg'), 'https://books.example/covers/a.jpg', 'Root relative resolution');
equal(resolver.resolve(base, '?page=2'), 'https://books.example/catalog/section/index.html?page=2',
  'Query reference resolution');
equal(resolver.resolve(base, '#chapter-2'),
  'https://books.example/catalog/section/index.html?old=1#chapter-2', 'Fragment reference resolution');
equal(resolver.resolve(base, '//cdn.example/cover.jpg'), 'https://cdn.example/cover.jpg',
  'Protocol relative resolution');
equal(resolver.resolve(base, 'https://other.example/book'), 'https://other.example/book', 'Absolute URL preservation');
equal(resolver.resolve(base, '../../../book'), 'https://books.example/book', 'Path traversal normalization at root');
equal(resolver.resolveRequest(base,
  '../chapter,{' + '"method":"POST","body":"next=../2?mode=full#part"}'),
  'https://books.example/catalog/chapter,{' + '"method":"POST","body":"next=../2?mode=full#part"}',
  'Inline request config preservation');
rejected('javascript:alert(1)', 'Non HTTP scheme rejection');
rejected('..\\private', 'Backslash rejection');
requestRejected('/chapter,{"body":"' + 'x'.repeat(64 * 1024) + '"}', 'Oversized inline config rejection');

console.log('Source URL resolver tests passed');
