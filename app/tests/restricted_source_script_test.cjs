const moduleUnderTest = require(process.argv[2]);
const runtime = new moduleUnderTest.RestrictedSourceScript();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
}

function rejected(action, message) {
  let failed = false;
  try { action(); } catch (_) { failed = true; }
  equal(failed, true, message);
}

let parsed = runtime.parse('$.name@js:return result.trim().toLowerCase();');
equal(parsed.selector, '$.name', 'Selector extraction');
equal(parsed.script, 'return result.trim().toLowerCase();', 'Script extraction');
equal(runtime.apply('$.name@js:return result.trim().toLowerCase();', '  HELLO  '), 'hello', 'Trim and lowercase');
equal(runtime.apply('$.name<js>return result.toUpperCase().concat("!")</js>', 'book'), 'BOOK!', 'Block marker');
equal(runtime.apply('$.name@js:return result.substring(1,4).replace("bc","XY")', 'abcde'), 'XYd',
  'Substring and literal replace');
equal(runtime.apply('$.name@js:return result.slice(-3).replaceAll("a","x")', 'baaa'), 'xxx',
  'Negative slice and replace all');
equal(runtime.apply('$.name@js:return result.concat("[ok]")', 'x'), 'x[ok]', 'Safe JSON literal characters');
equal(runtime.apply('$.name##foo##bar', 'foo foo'), 'bar bar', 'Global literal replacement');
equal(runtime.apply('$.name##^pre.*end$##ok', 'pre middle end'), 'ok', 'Anchored wildcard replacement');
equal(runtime.apply('$.name##a.c##x', 'a-c abc'), 'x x', 'Single character replacement');
equal(runtime.apply('$.name##\\.##,', 'a.b.c'), 'a,b,c', 'Escaped metacharacter replacement');
equal(runtime.apply('$.name##foo##bar@js:return result.toUpperCase()', 'foo'), 'BAR',
  'Replacement before restricted script');

rejected(() => runtime.parse('@js:return result'), 'Selector is mandatory');
rejected(() => runtime.parse('$.name@js:return eval(result)'), 'Global evaluation');
rejected(() => runtime.parse('$.name@js:return result.constructor()'), 'Prototype access');
rejected(() => runtime.parse('$.name@js:return result["trim"]()'), 'Dynamic property access');
rejected(() => runtime.parse('$.name@js:return result.replace(/a/g,"b")'), 'Regular expression argument');
rejected(() => runtime.parse('$.name@js:return result.substring(-1)'), 'Negative substring');
rejected(() => runtime.parse('$.name@js:return result.replace("","x")'), 'Empty replacement target');
rejected(() => runtime.parse('$.name@js:return result' + '.trim()'.repeat(17)), 'Call quota');
rejected(() => runtime.apply('$.name@js:return result', 'x'.repeat(5 * 1024 * 1024 + 1)), 'Input quota');
rejected(() => runtime.parse('$.name##replace'), 'Incomplete replacement rule');
rejected(() => runtime.parse('$.name##(a+)+##x'), 'Unsafe grouped quantifier');
rejected(() => runtime.parse('$.name##.*##x'), 'Empty wildcard match');
rejected(() => runtime.parse('$.name##a##$1'), 'Replacement delimiter ambiguity is rejected');
rejected(() => runtime.parse('javascript:alert(1)'), 'JavaScript URL remains blocked');

console.log('Restricted source script tests passed');
