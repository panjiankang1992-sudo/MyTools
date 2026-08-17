const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteSubtitleNetworkPolicy();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${expected}, actual=${actual}`);
}

function rejects(callback, message) {
  let rejected = false;
  try { callback(); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`${message}: expected rejection`);
}

equal(policy.path('/movies/Movie.zh-CN.srt'), '/movies/Movie.zh-CN.srt', 'SRT path');
equal(policy.path('/movies/Movie.vtt'), '/movies/Movie.vtt', 'VTT path');
rejects(() => policy.path('https://evil.example/Movie.srt'), 'Absolute URL rejection');
rejects(() => policy.path('/movies/../Movie.srt'), 'Traversal rejection');
rejects(() => policy.path('/movies//Movie.srt'), 'Duplicate separator rejection');
rejects(() => policy.path('/movies/Movie.txt'), 'Unknown extension rejection');
rejects(() => policy.path('/movies/Movie.srt\nHeader: value'), 'Control character rejection');

equal(policy.headerError({ 'Content-Type': 'text/vtt; charset=utf-8', 'Content-Length': '1024' }), '',
  'VTT response headers');
equal(policy.headerError({ 'content-type': 'application/x-subrip', 'content-encoding': 'identity' }), '',
  'SRT identity response');
equal(policy.headerError({ Location: 'https://other.example/Movie.srt' }), '远程字幕不允许重定向',
  'Redirect rejection');
equal(policy.headerError({ 'Content-Length': '-1' }), '远程字幕长度无效或超过1 MB限制',
  'Negative length rejection');
equal(policy.headerError({ 'Content-Length': `${1024 * 1024 + 1}` }), '远程字幕长度无效或超过1 MB限制',
  'Length quota');
equal(policy.headerError({ 'Content-Type': 'text/html' }), '远程字幕响应类型无效',
  'HTML rejection');
equal(policy.headerError({ 'Content-Encoding': 'gzip' }), '远程字幕不接受压缩传输',
  'Compressed transfer rejection');

console.log('Remote subtitle network policy tests passed');
