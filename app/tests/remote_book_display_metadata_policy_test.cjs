const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteBookDisplayMetadataPolicy();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

equal(policy.metadata('三体.EPUB'), { title: '三体', tags: [] }, 'Extension cleanup');
equal(policy.metadata('长安的荔枝_tags_历史,文学_user.txt'),
  { title: '长安的荔枝', tags: ['历史', '文学'] }, 'Transport metadata cleanup');
equal(policy.metadata('book_name_tags_fantasy|completed_user.epub', [
  { name: 'Fantasy', type: 'manual', confidence: 1 },
  { name: '精选', type: 'ai', confidence: 0.9 }
]), { title: 'book name', tags: ['fantasy', 'completed', '精选'] }, 'Tag merge and deduplication');

console.log('Remote book display metadata policy tests passed');
