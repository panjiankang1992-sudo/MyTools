const evaluatorModule = require(process.argv[2]);
const evaluator = new evaluatorModule.DeclarativeJsonPath();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejected(rule, message) {
  rejectedWithRoot({ data: [{ name: 'A' }] }, rule, message);
}

function rejectedWithRoot(root, rule, message) {
  let failed = false;
  try {
    evaluator.evaluate(root, rule);
  } catch (_) {
    failed = true;
  }
  equal(failed, true, message);
}

const payload = {
  data: {
    books: [
      { name: 'Alpha', chapters: [{ title: 'A1' }, { title: 'A2' }] },
      { name: 'Beta', chapters: [{ title: 'B1' }], enabled: true, score: 2,
        meta: { 'book-type': 'novel', state: { visible: true } } },
      { name: 'Draft', chapters: [], enabled: false, score: 0, note: null,
        meta: { 'book-type': 'draft', state: { visible: false } } }
    ],
    'latest-items': [{ id: 7 }]
  }
};

equal(evaluator.evaluate(payload, '$.data.books'), payload.data.books, 'Dot property path');
equal(evaluator.evaluate(payload, '$["data"]["books"][1].name'), 'Beta', 'Quoted property and array index');
equal(evaluator.evaluate(payload, '$.data.books[0,2].name'), ['Alpha', 'Draft'], 'Array index union');
equal(evaluator.evaluate(payload, '$.data.books[0,-1].name'), ['Alpha', 'Draft'], 'Negative array index union');
equal(evaluator.evaluate(payload, '$.data.books[:2].name'), ['Alpha', 'Beta'], 'Open-start array slice');
equal(evaluator.evaluate(payload, '$.data.books[1:].name'), ['Beta', 'Draft'], 'Open-end array slice');
equal(evaluator.evaluate(payload, '$.data.books[1:2].name'), 'Beta', 'Bounded array slice');
equal(evaluator.evaluate(payload, '$.data.books[:].name'), ['Alpha', 'Beta', 'Draft'], 'Fully open bounded slice');
equal(evaluator.evaluate(payload, '$.data.books[-1].name'), 'Draft', 'Negative array index');
equal(evaluator.evaluate(payload, '$.data.books[-2:].name'), ['Beta', 'Draft'], 'Negative slice start');
equal(evaluator.evaluate(payload, '$.data.books[:-1].name'), ['Alpha', 'Beta'], 'Negative slice end');
equal(evaluator.evaluate(payload, '$.data.books[::-1].name'), ['Draft', 'Beta', 'Alpha'], 'Reverse full slice');
equal(evaluator.evaluate(payload, '$.data.books[2:0:-1].name'), ['Draft', 'Beta'], 'Bounded reverse slice');
equal(evaluator.evaluate(payload, '$.data.books[0:3:2].name'), ['Alpha', 'Draft'], 'Forward stepped slice');
equal(evaluator.evaluate(payload, '$.data.books[8:10].name'), undefined, 'Slice beyond array length');
equal(evaluator.evaluate({ primary: 1, fallback: 2 }, "$['primary','fallback']"), [1, 2],
  'Quoted property union');
equal(evaluator.evaluate({ 'name,full': 'Alpha', 'ns:item': 7 }, "$['name,full']"), 'Alpha',
  'Comma inside quoted property');
equal(evaluator.evaluate({ 'name,full': 'Alpha', 'ns:item': 7 }, '$.ns:item'), 7,
  'Colon inside dot property');
equal(evaluator.evaluate(payload, '$.data.books[*].name'), ['Alpha', 'Beta', 'Draft'], 'Array wildcard projection');
equal(evaluator.evaluate(payload, '$.data.books[*].chapters[0].title'), ['A1', 'B1'],
  'Wildcard followed by indexed child');
equal(evaluator.evaluate(payload, '$..name'), ['Alpha', 'Beta', 'Draft'],
  'Recursive property descent');
equal(evaluator.evaluate(payload, '$..title'), ['A1', 'A2', 'B1'],
  'Recursive property descent through arrays');
equal(evaluator.evaluate(payload, '$..chapters[0].title'), ['A1', 'B1'],
  'Recursive property followed by index and property');
equal(evaluator.evaluate(payload, '$..chapters[:1].title'), ['A1', 'B1'],
  'Recursive property followed by slice and property');
const recursiveWildcardRoot = { a: { x: 1 }, b: [2, { y: 3 }] };
equal(evaluator.evaluate(recursiveWildcardRoot, '$..*'),
  [recursiveWildcardRoot.a, recursiveWildcardRoot.b, 1, 2, recursiveWildcardRoot.b[1], 3],
  'Recursive wildcard descent');
equal(evaluator.evaluate(payload, '$.data.books[?(@.enabled)].name'), ['Beta', 'Draft'],
  'Property existence filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@.enabled == true)].name'), 'Beta',
  'Boolean equality filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@.score != 0)].name'), 'Beta',
  'Numeric inequality filter');
equal(evaluator.evaluate(payload, "$.data.books[?(@.name == 'Alpha')].name"), 'Alpha',
  'String equality filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@.name =~ /ph/)].name'), 'Alpha',
  'Safe regex substring filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@.name =~ /^beta$/i)].name'), 'Beta',
  'Safe regex anchors and case-insensitive flag');
equal(evaluator.evaluate(payload, '$.data.books[?(@.name =~ /^A.*a$/)].name'), 'Alpha',
  'Safe regex deterministic wildcard');
equal(evaluator.evaluate({ rows: [{ value: 'v1.2' }, { value: 'v112' }] },
  '$.rows[?(@.value =~ /^v1\\.2$/)].value'), 'v1.2', 'Safe regex escaped metacharacter');
equal(evaluator.evaluate({ books: [{ name: 'Alpha,Beta' }, { name: 'urn:book' }] },
  "$.books[?(@.name == 'Alpha,Beta' || @.name == 'urn:book')].name"), ['Alpha,Beta', 'urn:book'],
  'Comma and colon inside filter literals');
equal(evaluator.evaluate(payload, '$.data.books[?(@.note == null)].name'), 'Draft',
  'Null equality filter');
equal(evaluator.evaluate(payload, "$.data.books[?(@.meta['book-type'] == 'novel')].name"), 'Beta',
  'Single quoted bracket property filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@["meta"]["book-type"] != "draft")].name'), 'Beta',
  'Double quoted bracket property filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@.meta.state.visible == true)].name'), 'Beta',
  'Nested dot property filter');
equal(evaluator.evaluate(payload, "$.data.books[?(@.chapters[0].title == 'A1')].name"), 'Alpha',
  'Nested array index filter path');
equal(evaluator.evaluate(payload, "$.data.books[?(@.chapters[-1].title == 'A2')].name"), 'Alpha',
  'Negative array index filter path');
equal(evaluator.evaluate(payload, "$.data.books[?(@.chapters[-1:].title == 'A2')].name"), 'Alpha',
  'Negative slice filter path projection');
equal(evaluator.evaluate(payload, "$.data.books[?(@.chapters[:1].title =~ /^B1$/)].name"), 'Beta',
  'Slice projection with safe regex');
equal(evaluator.evaluate(payload, '$.data.books[?(@.chapters[:1])].name'), ['Alpha', 'Beta'],
  'Slice existence filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@.chapters.length() > 1)].name'), 'Alpha',
  'Terminal array length function');
equal(evaluator.evaluate(payload, '$.data.books[?(@.name.length() >= 5)].name'), ['Alpha', 'Draft'],
  'Terminal string length function');
equal(evaluator.evaluate({ rows: [[1], [], 'abc', { length: 9 }] }, '$.rows[?(@.length() > 0)]'), [[1], 'abc'],
  'Length function only accepts arrays and strings');
equal(evaluator.evaluate(payload, "$.data.books[?(@.chapters[::-1].title == 'A1')].name"), 'Alpha',
  'Reverse slice filter path projection');
equal(evaluator.evaluate(payload, "$.data.books[?(@['chapters'][0]['title'] == 'B1')].name"), 'Beta',
  'Quoted properties around array index filter path');
equal(evaluator.evaluate({ rows: [[{ enabled: true }], [{ enabled: false }], []] },
  '$.rows[?(@[0].enabled == true)]'), [{ enabled: true }],
  'Array index immediately after current node');
equal(evaluator.evaluate({ rows: [[0], [false], []] }, '$.rows[?(@[0])]'), [[0], [false]],
  'Array index existence ending in bracket');
equal(evaluator.evaluate(payload,
  "$.data.books[?(@.enabled == true && @.meta['book-type'] == 'novel' && @.score != 0)].name"), 'Beta',
  'Bounded conjunction filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@.score >= 1 && @.score < 3)].name'), 'Beta',
  'Numeric range conjunction filter');
equal(evaluator.evaluate(payload, '$.data.books[?(@.score <= 0)].name'), 'Draft',
  'Numeric less-than-or-equal filter');
equal(evaluator.evaluate(payload, "$.data.books[?(@.name == 'Alpha' || @.score >= 2)].name"), ['Alpha', 'Beta'],
  'Bounded disjunction filter');
equal(evaluator.evaluate(payload,
  "$.data.books[?((@.enabled == true && @.score >= 2) || (@.enabled == false && @.score == 0))].name"),
  ['Beta', 'Draft'], 'Grouped disjunction of conjunctions');
equal(evaluator.evaluate(payload,
  "$.data.books[?((@.name == 'Alpha' || @.name == 'Beta') && @.score >= 2)].name"), 'Beta',
  'Grouped disjunction combined with conjunction');
equal(evaluator.evaluate(payload,
  "$.data.books[?(@.name == 'Alpha' || @.enabled == true && @.score == 2)].name"), ['Alpha', 'Beta'],
  'Conjunction precedence over disjunction');
equal(evaluator.evaluate(payload, '$.data.books[?(!@.enabled)].name'), 'Alpha',
  'Negated property existence filter');
equal(evaluator.evaluate(payload, '$.data.books[?(!(@.enabled == true))].name'), ['Alpha', 'Draft'],
  'Negated grouped comparison');
equal(evaluator.evaluate(payload, '$.data.books[?(!!(@.enabled == true))].name'), 'Beta',
  'Double negation filter');
equal(evaluator.evaluate(payload, '$.data.books[?(!(@.enabled == true) && @.score == 0)].name'), 'Draft',
  'Negation precedence over conjunction');
equal(evaluator.evaluate({ books: [{ name: 'Rock && Roll', enabled: true }] },
  "$.books[?(@.name == 'Rock && Roll' && @.enabled)].name"), 'Rock && Roll',
  'Conjunction marker inside quoted value');
equal(evaluator.evaluate({ books: [{ name: 'Rock || Roll' }] },
  "$.books[?(@.name == 'Rock || Roll')].name"), 'Rock || Roll',
  'Disjunction marker inside quoted value');
equal(evaluator.evaluate(payload, '$.data.latest-items[0].id'), 7, 'Hyphenated property');
equal(evaluator.evaluate(payload, '$.data.books[9].name'), undefined, 'Out of range index');
rejected("$..['name']", 'Quoted recursive descent target rejection');
rejected('$...', 'Empty recursive descent target rejection');
rejected("$.data.books[?(@.name > 'Alpha')]", 'String range comparison rejection');
rejected('$.data.books[?(@.score > true)]', 'Boolean range comparison rejection');
rejected('$.data.books[?(@.score > 1e3)]', 'Exponent range comparison rejection');
rejected('$.data.books[?(@.name =~ /A+/)]', 'Regex quantifier rejection');
rejected('$.data.books[?(@.name =~ /(A)/)]', 'Regex group rejection');
rejected('$.data.books[?(@.name =~ /A|B/)]', 'Regex alternation rejection');
rejected('$.data.books[?(@.name =~ /A/g)]', 'Unsupported regex flag rejection');
rejected('$.data.books[?(@.name =~ /\\d/)]', 'Unsafe regex escape rejection');
rejected('$.data.books[?(@.name =~ //)]', 'Empty regex rejection');
rejected('$.data.books[?(@.chapters[0:1:0])]', 'Filter zero slice step rejection');
rejected('$.data.books[?(@.chapters[0:1000001])]', 'Filter slice boundary quota rejection');
rejected('$.data.books[?(@.chapters.length().value > 0)]', 'Length function must be terminal');
rejected('$.data.books[?(@.chapters.length(1) > 0)]', 'Length function argument rejection');
rejected('$.data.books[?(@.chapters.size() > 0)]', 'Unknown filter function rejection');
rejected('$.data.books[?(@.chapters[1000001])]', 'Filter array index quota rejection');
rejected("$.data.books[?(@.a.b.c.d.e == 'novel')]", 'Filter path depth rejection');
rejected("$.data.books[?(@.chapters[0].title.value.extra == 'A1')]", 'Mixed filter path depth rejection');
rejected("$.data.books[?(@[''] == 'novel')]", 'Empty bracket filter key rejection');
rejected('$.data.books[?(@.a && @.b && @.c && @.d && @.e && @.f && @.g && @.h && @.i)]',
  'Boolean condition quota rejection');
rejected('$.data.books[?(@.a || @.b || @.c || @.d || @.e || @.f || @.g || @.h || @.i)]',
  'Disjunction condition quota rejection');
rejected('$.data.books[?((((((@.enabled))))))]', 'Boolean group depth rejection');
rejected('$.data.books[?(@.enabled ||)]', 'Empty disjunction branch rejection');
rejected('$.data.books[?(|| @.enabled)]', 'Leading disjunction branch rejection');
rejected('$.data.books[?((@.enabled || @.score)]', 'Unbalanced group rejection');
rejected('$.data.books[?(!)]', 'Empty unary negation rejection');
rejected('$.data.books[?(!(!(!(!(!(@.enabled))))))]', 'Unary negation depth rejection');
equal(evaluator.evaluate(payload, '$.data.books[2:1]'), undefined, 'Empty forward slice');
rejected('$.data.books[0:3:0]', 'Zero slice step rejection');
rejected('$.data.books[0:3:1000001]', 'Slice step quota rejection');
rejected('$.data.books[0:10001]', 'Slice result quota rejection');
rejected('$.data.books[0:1000001]', 'Slice boundary quota rejection');
rejected('$.data.books[-1000001]', 'Negative index quota rejection');
rejected('$.data.books[0,]', 'Trailing union item rejection');
rejected('$.data.books[,0]', 'Leading union item rejection');
rejected('$.data.books[0,-1000001]', 'Negative union index quota rejection');
rejected('$.data.books[0,name]', 'Unquoted union property rejection');
rejected('$.data.books[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16]', 'Union selector quota rejection');
rejectedWithRoot({ data: new Array(20001).fill({ enabled: true }) }, '$.data[?(@.enabled == true)]',
  'Filter candidate quota');
rejectedWithRoot({ data: new Array(1000).fill({ name: 'x'.repeat(4096) }) }, '$.data[?(@.name =~ /x/)]',
  'Safe regex aggregate work quota');
rejectedWithRoot({ data: new Array(21).fill({ values: new Array(10000).fill('x') }) },
  "$.data[?(@.values[:] == 'missing')]", 'Filter slice aggregate work quota');
let deepRecursiveRoot = { leaf: true };
for (let depth = 0; depth < 17; depth++) deepRecursiveRoot = { child: deepRecursiveRoot };
rejectedWithRoot(deepRecursiveRoot, '$..leaf', 'Recursive descent depth quota');
rejectedWithRoot(new Array(20001).fill(0).map((_, index) => ({ value: index })), '$..missing',
  'Recursive descent candidate quota');
rejectedWithRoot(new Array(10001).fill(0).map((_, index) => ({ name: `${index}` })), '$..name',
  'Recursive descent result quota');
rejectedWithRoot(new Array(10001).fill(0), '$[:]', 'Open slice runtime result quota');

console.log('Declarative JSONPath tests passed');
