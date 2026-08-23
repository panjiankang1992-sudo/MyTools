const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.ReaderTextMetadataPolicy();

const plain = policy.sanitize('第一章\n正文');
if (plain.content !== '第一章\n正文' || plain.originalFilename !== '') {
  throw new Error('Plain text must remain unchanged');
}

const marked = policy.sanitize('[Original filename] book--hash.txt\n\uFEFF第一章\n正文');
if (marked.content !== '第一章\n正文' || marked.originalFilename !== 'book--hash.txt') {
  throw new Error('Original filename metadata must be removed from reader content');
}

const embedded = policy.sanitize('正文\n[Original filename] should-stay.txt');
if (embedded.content !== '正文\n[Original filename] should-stay.txt') {
  throw new Error('Only the first metadata line may be removed');
}

const tooLong = `[Original filename] ${'a'.repeat(2100)}\n正文`;
if (policy.sanitize(tooLong).content !== tooLong) {
  throw new Error('Oversized untrusted metadata lines must remain content');
}

console.log('Reader text metadata policy tests passed');
