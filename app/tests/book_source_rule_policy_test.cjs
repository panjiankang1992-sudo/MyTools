const policyModule = require(process.argv[2]);
const policy = new policyModule.BookSourceRulePolicy();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
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

const jsonRules = {
  bookList: '$..books[?(@.enabled == true)][0:10]',
  name: "@['metadata']['name']"
};
equal(policy.jsonRule(jsonRules, 'bookList'), jsonRules.bookList, 'JSONPath must not use HTML validation');
equal(policy.jsonRule(jsonRules, 'name'), jsonRules.name, 'Quoted JSONPath must remain intact');
equal(policy.jsonRule(jsonRules, 'missing'), '', 'Missing rule');

const htmlRules = { bookList: 'article.book:not(.disabled)', name: '@xpath://a/text()' };
equal(policy.htmlRule(htmlRules, 'bookList'), htmlRules.bookList, 'Supported CSS rule');
equal(policy.htmlRule(htmlRules, 'name'), htmlRules.name, 'Supported XPath rule');
rejected(() => policy.htmlRule(jsonRules, 'bookList'), 'JSONPath must be rejected in HTML response path');
rejected(() => policy.jsonRule({ content: '@js:result' }, 'content'), 'JS marker rejection for JSON');
rejected(() => policy.htmlRule({ content: '<js>result</js>' }, 'content'), 'JS block rejection for HTML');
rejected(() => policy.jsonRule({ content: 'javascript:result' }, 'content'), 'JavaScript URL marker rejection');
equal(policy.jsonRule({ content: '$.content##foo##bar' }, 'content'), '$.content', 'Replacement selector extraction');
equal(policy.transform({ content: '$.content##foo##bar' }, 'content', 'foo'), 'bar', 'Replacement execution');
rejected(() => policy.jsonRule({ bookList: '$.books[*]##foo##bar' }, 'bookList'),
  'List replacement rejection');
equal(policy.jsonRule({ content: '$.content@js:return result.trim()' }, 'content'), '$.content',
  'Restricted script selector');
equal(policy.transform({ content: '$.content@js:return result.trim().toUpperCase()' }, 'content', ' hi '), 'HI',
  'Restricted scalar transformation');
equal(policy.htmlRule({ name: '.title<js>return result.trim()</js>' }, 'name'), '.title',
  'Restricted HTML selector');
rejected(() => policy.jsonRule({ bookList: '$.books@js:return result' }, 'bookList'),
  'List script rejection');

console.log('Book source rule policy tests passed');
