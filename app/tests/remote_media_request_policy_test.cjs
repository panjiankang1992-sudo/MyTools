const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteMediaRequestPolicy();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${expected}, actual=${actual}`);
}

function rejects(callback, message) {
  let rejected = false;
  try { callback(); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`${message}: expected rejection`);
}

equal(policy.accountId('12345678901234567890'), '12345678901234567890', 'Maximum snowflake identifier');
rejects(() => policy.accountId('1&accountId=2'), 'Query injection account');
rejects(() => policy.accountId('0'), 'Zero account');
rejects(() => policy.accountId(' 12 '), 'Whitespace account');

equal(policy.path('/Movies/Film.mp4', false), '/Movies/Film.mp4', 'Remote file path');
equal(policy.path('/'), '/', 'Allowed root');
rejects(() => policy.path('/', false), 'Forbidden root mutation');
rejects(() => policy.path('/Movies/../secret'), 'Traversal path');
rejects(() => policy.path('/Movies//Film.mp4'), 'Duplicate separator path');
rejects(() => policy.path('/Movies/'), 'Trailing separator path');
rejects(() => policy.path('/Movies\nHeader'), 'Control character path');

equal(policy.name(' New Folder '), 'New Folder', 'Trimmed remote name');
rejects(() => policy.name('../file'), 'Slash in name');
rejects(() => policy.name('..'), 'Traversal name');
rejects(() => policy.name('x'.repeat(256)), 'Name quota');

equal(policy.fileUri('file://docs/storage/Downloads/file.epub'), 'file://docs/storage/Downloads/file.epub',
  'System picker URI');
rejects(() => policy.fileUri('https://example.com/file'), 'Remote URI rejection');
rejects(() => policy.fileUri('file://bad\nuri'), 'URI control character');

equal(policy.ticket('abcdef0123456789abcdef0123456789'), 'abcdef0123456789abcdef0123456789', 'Playback ticket');
rejects(() => policy.ticket('ABCDEF0123456789ABCDEF0123456789'), 'Uppercase ticket');
rejects(() => policy.ticket('../abcdef0123456789abcdef0123456789'), 'Ticket path injection');

console.log('Remote media request policy tests passed');
