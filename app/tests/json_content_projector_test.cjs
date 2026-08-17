const projectorModule = require(process.argv[2]);
const projector = new projectorModule.JsonContentProjector();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
}

function rejected(value, message) {
  let failed = false;
  try {
    projector.multiline(value);
  } catch (_) {
    failed = true;
  }
  equal(failed, true, message);
}

equal(projector.multiline('  single chapter  '), 'single chapter', 'Single string');
equal(projector.multiline(42), '42', 'Single number');
equal(projector.multiline([' first ', '', null, 'second', 3, true]), 'first\nsecond\n3\ntrue',
  'Bounded scalar array paragraphs');
equal(projector.multiline(undefined), '', 'Missing result');
rejected({ text: 'nested' }, 'Object result rejection');
rejected(['valid', { text: 'nested' }], 'Nested array object rejection');
rejected(new Array(10001).fill('line'), 'Segment quota rejection');
rejected(['x'.repeat(5 * 1024 * 1024 + 1)], 'Character quota rejection');

console.log('JSON content projector tests passed');
