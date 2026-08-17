const projectorModule = require(process.argv[2]);
const projector = new projectorModule.SourceComicPageProjector();
const base = 'https://comic.example/books/7/chapter/2.html';

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejected(value, message) {
  let failed = false;
  try {
    projector.project(base, value);
  } catch (_) {
    failed = true;
  }
  equal(failed, true, message);
}

equal(projector.project(base, '../images/1.jpg'), ['https://comic.example/books/7/images/1.jpg'],
  'Relative comic page');
equal(projector.project(base, ['/images/1.jpg#page', '/images/1.jpg', '//cdn.example/2.webp']),
  ['https://comic.example/images/1.jpg', 'https://cdn.example/2.webp'], 'Stable dedupe and fragment removal');
equal(projector.project(base, ' /1.jpg\n\n/2.jpg\r\n/3.jpg '),
  ['https://comic.example/1.jpg', 'https://comic.example/2.jpg', 'https://comic.example/3.jpg'],
  'Newline-separated page list');
equal(projector.project(base, undefined), [], 'Missing comic page result');
rejected([1], 'Non-string page rejection');
rejected(['javascript:alert(1)'], 'Script URL rejection');
rejected(['file:///data/page.jpg'], 'Local file URL rejection');
rejected(['/page.jpg,{"headers":{"Referer":"x"}}'], 'Inline request config rejection');
rejected(new Array(501).fill(0).map((_, index) => `/pages/${index}.jpg`), 'Comic page quota rejection');

console.log('Source comic page projector tests passed');
