const parserModule = require(process.argv[2]);
const parser = new parserModule.BookSourceHtmlParser();

function equal(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejected(action, message) {
  let failed = false;
  try {
    action();
  } catch (_) {
    failed = true;
  }
  equal(failed, true, message);
}

const html = `
<html><body><section id="catalog">
  <div class="book featured" data-kind="novel"><a class="title" href="/a">Alpha</a></div>
  <div class="book" data-kind="comic"><a class="title extra" href="/b">Beta</a></div>
</section></body></html>`;

const books = parser.selectList(html, "@xpath://div[contains(@class,'book')]");
equal(books.length, 2, 'XPath class substring list');
equal(parser.selectText(html, "@xpath://div[@data-kind='novel']/a[contains(@class,'title')]/text()"),
  'Alpha', 'XPath text extraction');
equal(parser.selectText(html, "@xpath://div[@data-kind='comic']/a/@href"), '/b',
  'XPath attribute extraction');
equal(parser.selectText(html, '@xpath://div[2]/a/text()'), 'Beta', 'XPath one-based position');
equal(parser.selectText(html, '@xpath://div[last()]/a/text()'), 'Beta', 'XPath last position');
equal(parser.selectText(html, '@xpath://div[last()-1]/a/text()'), 'Alpha', 'XPath last offset position');
equal(parser.selectText(html, '@xpath://div[position()=2]/a/text()'), 'Beta', 'XPath explicit position');
equal(JSON.stringify(parser.selectTexts(html, '@xpath://div[position()<=1]/a/text()')), JSON.stringify(['Alpha']),
  'XPath position less-than-or-equal range');
equal(JSON.stringify(parser.selectTexts(html, '@xpath://div[position()>1]/a/text()')), JSON.stringify(['Beta']),
  'XPath position greater-than range');
equal(JSON.stringify(parser.selectTexts(html, '@xpath://div[position()>=1]/a/text()')),
  JSON.stringify(['Alpha', 'Beta']), 'XPath position greater-than-or-equal lower boundary');
equal(JSON.stringify(parser.selectTexts(html, '@xpath://div[position()>0]/a/text()')),
  JSON.stringify(['Alpha', 'Beta']), 'XPath position greater than zero boundary');
equal(parser.selectText(html, "@xpath://div[contains(text(),'Alpha')]/a/text()"), 'Alpha',
  'XPath text contains');
equal(parser.selectText(html, "@xpath://div[starts-with(@data-kind,'nov')]/a/text()"), 'Alpha',
  'XPath attribute prefix');
equal(parser.selectText(html, "@xpath://div[ends-with(@data-kind,'mic')]/a/text()"), 'Beta',
  'XPath attribute suffix');
equal(parser.selectText('<div class="name">  Alpha   Reader </div>',
  "@xpath://div[normalize-space(text())='Alpha Reader']/text()"), 'Alpha   Reader',
  'XPath normalized text equality');
equal(parser.selectText(html, 'section#catalog div.book:nth-child(2) a@text'), 'Beta',
  'CSS nth-child position');
equal(JSON.stringify(parser.selectTexts(html, 'section#catalog div.book a@text')), JSON.stringify(['Alpha', 'Beta']),
  'CSS multi-value text extraction');
equal(JSON.stringify(parser.selectTexts(html, 'section#catalog div.book a@href')), JSON.stringify(['/a', '/b']),
  'CSS multi-value attribute extraction');
equal(JSON.stringify(parser.selectTexts('<i>A</i><i>B</i><i>C</i>', 'i:lt(2)@text')),
  JSON.stringify(['A', 'B']), 'CSS bounded less-than position');
equal(JSON.stringify(parser.selectTexts('<i>A</i><i>B</i><i>C</i>', 'i:gt(0)@text')),
  JSON.stringify(['B', 'C']), 'CSS bounded greater-than position');
equal(JSON.stringify(parser.selectTexts('<i>A</i><i>B</i><i>C</i>', 'i:lt(-1)@text')),
  JSON.stringify(['A', 'B']), 'CSS negative less-than position');
equal(JSON.stringify(parser.selectTexts('<i>A</i><i>B</i><i>C</i>', 'i:gt(-1)@text')),
  JSON.stringify([]), 'CSS negative greater-than position remains end-relative');
rejected(() => parser.selectTexts(new Array(10001).fill('<p>line</p>').join(''), 'p@text'),
  'HTML multi-value result quota');

const compatibilityCorpus = `
<main id="results">
  <article class="result book" data-type="comic" data-state="disabled">
    <a class="book-name" data-role="detail" href="/wrong">Wrong edition</a>
  </article>
  <article class="result book" data-type="novel" data-state="enabled">
    <a class="book-name primary" data-role="detail" href="/right">Right Book</a>
  </article>
</main>`;
equal(parser.selectText(compatibilityCorpus,
  "@xpath://article[contains(@class,'book')][@data-type='novel'][@data-state='enabled']/a[@data-role='detail']/text()"),
  'Right Book', 'XPath multiple predicates must all match');
equal(parser.selectText(compatibilityCorpus,
  "@xpath://article[contains(@class,'book') and @data-type='novel' and @data-state='enabled']/a[@data-role='detail']/text()"),
  'Right Book', 'XPath top-level and predicate must combine all filters');
equal(parser.selectText(compatibilityCorpus,
  "@xpath://article[(@data-type='novel' or @data-type='comic')][@data-state='enabled']/a/text()"),
  '', 'Parenthesized XPath disjunction remains outside supported grammar');
equal(parser.selectText(compatibilityCorpus,
  "@xpath://article[@data-type='novel' or @data-type='comic'][@data-state='enabled']/a/text()"),
  'Right Book', 'XPath top-level or predicate combines with following predicate');
equal(JSON.stringify(parser.selectTexts(compatibilityCorpus,
  "@xpath://article[@data-type='novel' or @data-type='comic']/a/text()")),
  JSON.stringify(['Wrong edition', 'Right Book']), 'XPath top-level or returns bounded alternatives');
equal(parser.selectText('<div data-label="rock and roll" data-state="enabled">Match</div>',
  "@xpath://div[@data-label='rock and roll' and @data-state='enabled']/text()"),
  'Match', 'XPath and inside quoted value must not split predicate');
equal(parser.selectText(compatibilityCorpus,
  'article.result[data-type="novel"][data-state="enabled"] a[data-role="detail"]@href'),
  '/right', 'CSS multiple attributes must all match');
equal(parser.selectText(compatibilityCorpus,
  'article.result[data-type="novel"][data-state="disabled"] a@text'),
  '', 'CSS multiple attributes reject partial match');
equal(parser.selectText('<input data-empty="" value="fallback">', 'input[data-empty=""]@value'),
  'fallback', 'CSS empty quoted attribute equality');
equal(parser.selectText(compatibilityCorpus,
  'article.result:not(.disabled):not([data-state="disabled"]) a@text'),
  'Right Book', 'CSS bounded simple negations');
equal(parser.selectText(compatibilityCorpus, '.result:not(article) a@text'), '',
  'CSS tag negation uses actual matched tag');
equal(parser.selectText(compatibilityCorpus,
  "@xpath://article[not(@data-state='disabled')]/a/text()"),
  'Right Book', 'XPath negated attribute equality');
equal(parser.selectText('<div class="item">Allowed</div><div class="item" hidden>Hidden</div>',
  '@xpath://div[not(@hidden)]/text()'), 'Allowed', 'XPath negated attribute existence');
equal(parser.supportsRule('@xpath://div/ancestor::section'), false, 'XPath axis rejection');
equal(parser.supportsRule('@xpath://div/following-sibling::div'), false, 'XPath sibling axis rejection');
equal(parser.supportsRule('@xpath://div[substring(text(),1,2)="Al"]'), false,
  'XPath unsupported nested function rejection');
equal(parser.supportsRule("@xpath://div[@data-kind='novel' or @data-kind='comic']"), true,
  'XPath top-level or predicate support');
equal(parser.supportsRule("@xpath://div[@a or @b or @c or @d or @e or @f or @g or @h or @i]"), false,
  'XPath disjunction quota rejection');
equal(parser.supportsRule("@xpath://div[position()=1 and @data-kind='novel']"), false,
  'XPath position and attribute conjunction rejection');
equal(parser.supportsRule("@xpath://div[@a and @b and @c and @d and @e and @f and @g and @h and @i]"), false,
  'XPath conjunction quota rejection');
equal(parser.supportsRule('div:not(.disabled)'), true, 'Simple CSS negation support');
equal(parser.supportsRule('div:not(.a):not(.b):not(.c):not(.d):not(.e)'), false,
  'CSS negation quota rejection');
equal(parser.supportsRule('div:not(.disabled, .draft)'), false, 'Complex CSS negation rejection');
equal(parser.selectText('<div class="disabled">Wrong</div>', 'div:not(.disabled, .draft)@text'), '',
  'Invalid CSS negation must not broaden selection');
equal(parser.supportsRule('div:not(:contains(test))'), false, 'Nested CSS negation rejection');
equal(parser.supportsRule('div:lt(1000001)'), false, 'CSS position quota rejection');
equal(parser.supportsRule('@xpath://div[position()<1000002]'), false, 'XPath position quota rejection');
equal(parser.supportsRule('div[data-kind!="novel"]'), false, 'Unsupported CSS operator rejection');
equal(parser.selectText('<main><p>Wildcard</p></main>', '@xpath://*/text()'), 'Wildcard',
  'XPath wildcard element support');

console.log('Book source HTML parser XPath tests passed');
