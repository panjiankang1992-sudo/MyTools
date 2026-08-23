const importerModule = require(process.argv[2]);
const importer = new importerModule.BookSourceImporter();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function source(index, overrides = {}) {
  return Object.assign({
    bookSourceUrl: `https://books.example.com/${index}`,
    bookSourceName: `Source ${index}`,
    searchUrl: '/search?q={{key}}',
    exploreUrl: '/explore?page={{page}}',
    ruleSearch: { bookList: '@xpath://div', name: '@xpath:.//a/text()', bookUrl: '@xpath:.//a/@href' },
    ruleExplore: { bookList: '$.books', name: '$.name', bookUrl: '$.url' },
    ruleBookInfo: {},
    ruleToc: {},
    ruleContent: {}
  }, overrides);
}

let result = importer.importJson(JSON.stringify([source(1), source(2)]));
assert(result.sources.length === 2 && result.rejectedCount === 0, 'valid sources should import');
assert(result.sources[0].ruleExplore.bookList === '$.books', 'explore rules should import');

result = importer.importJson(JSON.stringify([source(1), source(2, { bookSourceUrl: source(1).bookSourceUrl })]));
assert(result.sources.length === 1, 'duplicate source URL should merge');

result = importer.importJson(JSON.stringify([source(1, { ruleSearch: { bookList: 'x'.repeat(16 * 1024 + 1) } })]));
assert(result.sources.length === 1, 'Legado search rule larger than 16 KB should import within source quota');

result = importer.importJson(JSON.stringify([source(1, { ruleSearch: { bookList: 'x'.repeat(64 * 1024 + 1) } })]));
assert(result.sources.length === 0 && result.rejectedCount === 1, 'oversized rule should reject source');

const tooManyRules = {};
for (let index = 0; index < 65; index++) tooManyRules[`field${index}`] = 'value';
result = importer.importJson(JSON.stringify([source(1, { ruleSearch: tooManyRules })]));
assert(result.sources.length === 0 && result.rejectedCount === 1, 'too many rule fields should reject source');

const largeTocRules = {};
for (let index = 0; index < 4; index++) largeTocRules[`field${index}`] = 'x'.repeat(60 * 1024);
result = importer.importJson(JSON.stringify([source(1, { ruleToc: largeTocRules })]));
assert(result.sources.length === 1, 'large Legado rule object should import within source quota');

result = importer.importJson(JSON.stringify([source(1, { ruleSearch: { bookList: { nested: 'not allowed' } } })]));
assert(result.sources.length === 0 && result.rejectedCount === 1, 'nested rule object should reject source');

result = importer.importJson(JSON.stringify([source(1, { ruleExplore: { bookList: 'x'.repeat(16 * 1024 + 1) } })]));
assert(result.sources.length === 1, 'Legado rule values larger than 16 KB should import within source quota');

result = importer.importJson(JSON.stringify([source(1, { ruleExplore: { bookList: 'x'.repeat(64 * 1024 + 1) } })]));
assert(result.sources.length === 0 && result.rejectedCount === 1, 'oversized explore rule should reject source');

const manySources = [];
for (let index = 0; index < 505; index++) manySources.push(source(index));
result = importer.importJson(JSON.stringify(manySources));
assert(result.sources.length === 500 && result.rejectedCount === 5, 'source count quota should apply');

result = importer.importJson(JSON.stringify([source(1, { ruleContent: { content: '@js:return result' } })]));
assert(result.sources.length === 1 && result.scriptedCount === 1, 'script source should be marked, not executed');

const yckceoSource = source(7730, {
  bookSourceUrl: 'https://www.xiaoxiaoshuo8.com',
  bookSourceName: '千白羽轻小说交流站',
  searchUrl: 'https://m.xiaoxiaoshuo8.com/s.php,{"body":"keyword={{key}}&t=1","method":"POST","webView":true}',
  exploreUrl: '[{"title":"全部小说","url":"https://www.xiaoxiaoshuo8.com/xiaoshuodaquan/"}]',
  ruleSearch: {
    bookList: 'div.hot_sale',
    name: 'p.title@text',
    bookUrl: 'a@href',
    coverUrl: '@js:result'
  },
  ruleBookInfo: { name: 'div#info > h1@text', wordCount: '<js>result = java.ajax(url)</js>' },
  ruleToc: { chapterList: 'div#list > dl > dd[9:]', chapterName: 'a@text', chapterUrl: 'a@href' },
  ruleContent: { content: '#content@html' }
});
result = importer.importJson(JSON.stringify([yckceoSource]));
assert(result.sources.length === 1 && result.scriptedCount === 1,
  'YckCeo Legado source should import while script fields remain restricted');
assert(result.sources[0].ruleSearch.name === 'p.title@text' && result.sources[0].enabledExplore === true,
  'YckCeo declarative search and explore fields should remain available');

result = importer.importJson(JSON.stringify([source(1, { header: JSON.stringify({ Cookie: 'session=secret' }) })]));
assert(result.sources.length === 0 && result.rejectedCount === 1, 'sensitive header should not enter preferences');

result = importer.importJson(JSON.stringify([source(1, { header: JSON.stringify({ Accept: 'text/html' }) })]));
assert(result.sources.length === 1, 'ordinary bounded header should import');

result = importer.importJson(JSON.stringify([source(1, {
  header: '"Accept": "text/html"\u200b'
})]));
assert(result.sources.length === 1 && JSON.parse(result.sources[0].header).Accept === 'text/html',
  'header fragment and zero-width suffix should normalize');

result = importer.importJson(JSON.stringify([source(1, {
  header: JSON.stringify({ visitor_id: 'anonymous' })
})]));
assert(result.sources.length === 1, 'HTTP token extension header should import');

result = importer.importJson(JSON.stringify([source(1, { bookSourceType: 4 })]));
assert(result.sources.length === 1 && result.sources[0].bookSourceType === 4, 'extended source type should import');

for (const invalidType of [-1, 1.5, 5, Number.MAX_SAFE_INTEGER]) {
  result = importer.importJson(JSON.stringify([source(1, { bookSourceType: invalidType })]));
  assert(result.sources.length === 0 && result.rejectedCount === 1, 'invalid source type should reject');
}

result = importer.importJson(JSON.stringify([source(1, {
  searchUrl: '/search?q={{key}},{"method":"GET","headers":{"Authorization":"Bearer secret"}}'
})]));
assert(result.sources.length === 0 && result.rejectedCount === 1,
  'inline sensitive header should not enter persisted search URL');

result = importer.importJson(JSON.stringify([source(1, { bookSourceUrl: 'https://user:secret@books.example.com' })]));
assert(result.sources.length === 0, 'credential URL should reject');

console.log('Book source importer quota tests passed');
