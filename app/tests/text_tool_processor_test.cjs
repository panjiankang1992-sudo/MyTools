const processorModule = require(process.argv[2]);
const processor = new processorModule.TextToolProcessor();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

assert(processor.process('{"value":1}', 'json').includes('\n  "value": 1\n'), 'JSON should format');
assert(processor.process('a b/中文', 'urlEncode') === 'a%20b%2F%E4%B8%AD%E6%96%87', 'URL should encode');
assert(processor.process('a%20b%2F%E4%B8%AD%E6%96%87', 'urlDecode') === 'a b/中文', 'URL should decode');
assert(processor.process('%ZZ', 'urlDecode') === '输入不是有效的URL编码文本', 'bad URL input should fail');
assert(processor.process('0', 'timestampToDate') === '1970-01-01T00:00:00.000Z', 'seconds should convert');
assert(processor.process('100000000000', 'timestampToDate') === '1973-03-03T09:46:40.000Z',
  'large timestamp should be treated as milliseconds');
assert(processor.process('not-a-number', 'timestampToDate') === '请输入有效的秒或毫秒时间戳',
  'bad timestamp should fail');
assert(processor.process('1970-01-01T00:00:01.000Z', 'dateToTimestamp') === '秒：1\n毫秒：1000',
  'ISO date should convert');
assert(processor.process('b\na\nb', 'unique') === 'b\na', 'lines should deduplicate');
assert(processor.process('b\na', 'sort') === 'a\nb', 'lines should sort');
assert(processor.process('a\n\n b', 'compact') === 'a\n b', 'blank lines should compact');
assert(processor.process('', 'unknown') === '不支持的处理操作', 'unknown operation should reject');

console.log('Text tool processor tests passed');
