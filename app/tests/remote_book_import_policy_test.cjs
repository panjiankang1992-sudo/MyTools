const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteBookImportPolicy();

const source = { id: '12', name: 'My WebDAV', type: 'webdav', url: '', isDefault: true, isActive: true };
function item(name, path, size, kind = 'other') {
  return { name, path, size, kind, contentType: 'application/octet-stream', lastModified: '' };
}
function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}
function rejects(callback, message) {
  let rejected = false;
  try { callback(); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`${message}: expected rejection`);
}

const candidate = policy.candidate(item('三体.EPUB', '/books/三体.EPUB', 1024), source);
equal([candidate.name, candidate.author, candidate.format, candidate.resourceUri, candidate.sourceId],
  ['三体', 'My WebDAV', 'epub', '/books/三体.EPUB', '12'], 'Remote book projection');
equal(candidate.identityMaterial, 'remote:v1\u000012\u0000/books/三体.EPUB', 'Stable identity material');
equal(policy.candidate(Object.assign(item('cached.epub', '/cached.epub', 2048),
  { bookFileHash: 'ABCDEF0123456789ABCDEF0123456789' }), source).cacheVersion,
  'abcdef0123456789abcdef0123456789', 'Validated cache version');
equal(policy.candidate(item('large.txt', '/large.txt', 500 * 1024 * 1024), source).format,
  'txt', 'TXT maximum accepted');
const tagged = policy.candidate(Object.assign(item('反派：我的母亲是大帝_tags_玄幻,穿越_user.txt',
  '/books/tagged.txt', 2048), { tags: [{ name: '完结', type: 'manual', confidence: 1 }] }), source);
equal([tagged.name, tagged.tags, tagged.size], ['反派：我的母亲是大帝', ['玄幻', '穿越', '完结'], 2048],
  'Transport metadata cleanup');

rejects(() => policy.candidate(item('folder.epub', '/folder', 0, 'directory'), source),
  'Directory rejection');
rejects(() => policy.candidate(item('empty.epub', '/empty.epub', 0), source), 'Empty file rejection');
rejects(() => policy.candidate(item('large.epub', '/large.epub', 100 * 1024 * 1024 + 1), source),
  'Binary quota rejection');
rejects(() => policy.candidate(item('large.txt', '/large.txt', 500 * 1024 * 1024 + 1), source),
  'TXT quota rejection');
rejects(() => policy.candidate(item('book.exe', '/book.exe', 1024), source), 'Unknown format rejection');
rejects(() => policy.candidate(item('comic.cbr', '/comic.cbr', 1024), source), 'Unavailable CBR rejection');
rejects(() => policy.candidate(item('book.epub', '/../book.epub', 1024), source), 'Traversal rejection');
rejects(() => policy.candidate(item('book.epub', '/book.epub', 1024), Object.assign({}, source, { id: '' })),
  'Missing source rejection');
rejects(() => policy.candidate(item(`${'x'.repeat(301)}.epub`, '/book.epub', 1024), source),
  'Shelf title quota rejection');

console.log('Remote book import policy tests passed');
