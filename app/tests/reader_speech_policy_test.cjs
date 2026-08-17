const moduleValue = require(process.argv[2]);
const policy = new moduleValue.ReaderSpeechPolicy();

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const text = policy.readableText([
  { kind: 'heading', text: '第一章', uri: '' },
  { kind: 'image', text: '不应朗读的图片说明', uri: 'file://image' },
  { kind: 'paragraph', text: '正文\u0000 内容。', uri: '' }
], 'fallback');
assert(text.includes('第一章') && text.includes('正文') && !text.includes('图片说明'), 'Readable block projection');
assert(!text.includes('\u0000'), 'Control character cleanup');
const chunks = policy.chunks('甲'.repeat(280) + '。' + '乙'.repeat(400) + '。');
assert(chunks.length === 2, 'Sentence-aware chunks');
assert(chunks.every(value => value.length > 0 && value.length <= 500), 'Chunk quota');
const surrogate = policy.chunks('文'.repeat(499) + '😀' + '尾');
assert(surrogate.join('') === '文'.repeat(499) + '😀尾', 'Surrogate pair preservation');
assert(policy.chunks(' ').length === 0, 'Empty text');
assert(policy.chunks('字'.repeat(300000)).join('').length <= 200000, 'Total text quota');

console.log('Reader speech policy tests passed');
