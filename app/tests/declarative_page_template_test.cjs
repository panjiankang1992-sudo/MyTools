const templateModule = require(process.argv[2]);
const template = new templateModule.DeclarativePageTemplate();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${expected}, actual=${actual}`);
}

function rejected(value, page, message) {
  let failed = false;
  try { template.expand(value, page); } catch (_) { failed = true; }
  equal(failed, true, message);
}

equal(template.expand('/page/{{page}}', 3), '/page/3', 'Direct page');
equal(template.expand('/offset/{{ page - 1 }}', 1), '/offset/0', 'Zero based page');
equal(template.expand('/next/{{searchPage+2}}', 4), '/next/6', 'Search page addition');
equal(template.hasUnsupportedExpression('/page/{{ page - 1 }}'), false, 'Supported expression detection');
equal(template.hasUnsupportedExpression('/page/{{page > 1 ? page : 0}}'), true, 'Complex expression detection');
rejected('/page/{{page-2}}', 1, 'Negative result rejection');
rejected('/page/{{page+1001}}', 1, 'Offset quota rejection');
rejected('/page/{{page*2}}', 1, 'Multiplication rejection');
rejected('/page/{{unknown}}', 1, 'Unknown variable rejection');
rejected('/page/{{page', 1, 'Unclosed expression rejection');

console.log('Declarative page template tests passed');
