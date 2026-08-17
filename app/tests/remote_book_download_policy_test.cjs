const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteBookDownloadPolicy();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${expected}, actual=${actual}`);
}

function rejects(callback, message) {
  let rejected = false;
  try { callback(); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`${message}: expected rejection`);
}

equal(policy.url('https://books.example/files/book.epub?ticket=abc'),
  'https://books.example/files/book.epub?ticket=abc', 'HTTPS acquisition URL');
equal(policy.url('http://public-books.example/book.epub'), 'http://public-books.example/book.epub',
  'Public HTTP source compatibility');
rejects(() => policy.url('https://user:pass@books.example/book.epub'), 'User info rejection');
rejects(() => policy.url('https://books.example/book.epub#chapter'), 'Fragment rejection');
rejects(() => policy.url('https://books.example/book.epub\nHeader'), 'Control character rejection');

equal(JSON.stringify(policy.requestHeaders({ authorization: 'Bearer token', Cookie: 'sid=value',
  'X-API-Key': 'secret' })), JSON.stringify({ 'Accept-Encoding': 'identity', Authorization: 'Bearer token',
  Cookie: 'sid=value', 'X-API-Key': 'secret' }), 'Credential header normalization');
equal(JSON.stringify(policy.requestHeaders()), JSON.stringify({ 'Accept-Encoding': 'identity' }),
  'Identity transfer request header');
rejects(() => policy.requestHeaders({ Host: 'evil.example' }), 'Host override rejection');
rejects(() => policy.requestHeaders({ Range: 'bytes=0-100' }), 'Range override rejection');
rejects(() => policy.requestHeaders({ 'Accept-Encoding': 'gzip' }), 'Encoding override rejection');
rejects(() => policy.requestHeaders({ Authorization: 'Bearer ok\r\nX-Evil: value' }), 'Header injection rejection');
rejects(() => policy.requestHeaders({ Authorization: 'a', authorization: 'b' }), 'Duplicate normalized header');

equal(policy.headerError({ 'Content-Type': 'text/plain; charset=utf-8', 'Content-Length': '100' }, 1024, 'text'),
  '', 'Text headers');
equal(policy.headerError({ 'Content-Type': 'application/epub+zip' }, 1024, 'archive'), '', 'EPUB MIME');
equal(policy.headerError({ 'Content-Type': 'application/vnd.comicbook+zip' }, 1024, 'archive'), '', 'CBZ MIME');
equal(policy.headerError({ 'Content-Type': 'application/pdf' }, 1024, 'pdf'), '', 'PDF MIME');
equal(policy.headerError({ 'Content-Type': 'application/x-mobipocket-ebook' }, 1024, 'mobi'), '', 'MOBI MIME');
equal(policy.headerError({ 'Content-Type': 'application/octet-stream' }, 1024, 'mobi'), '', 'Generic binary MIME');
equal(policy.headerError({ Location: 'https://other.example/book.epub' }, 1024, 'archive'),
  '远程图书重定向已阻止，请使用最终地址', 'Redirect rejection');
equal(policy.headerError({ 'Content-Length': '-1' }, 1024, 'archive'),
  '远程图书长度无效或超过格式限制', 'Negative length rejection');
equal(policy.headerError({ 'Content-Length': '1025' }, 1024, 'archive'),
  '远程图书长度无效或超过格式限制', 'Length quota');
equal(policy.headerError({ 'Content-Encoding': 'gzip' }, 1024, 'archive'),
  '远程图书不接受压缩传输', 'Compressed transfer rejection');
equal(policy.headerError({ 'Content-Type': 'text/html' }, 1024, 'archive'),
  '远程图书响应类型与格式不符', 'HTML rejection');
equal(policy.headerError({ 'Content-Type': 'application/pdf' }, 1024, 'archive'),
  '远程图书响应类型与格式不符', 'MIME and target mismatch');
equal(JSON.stringify(policy.headers({ 'Content-Length': '100' }, 1024, 'text')),
  JSON.stringify({ error: '', contentLength: 100 }), 'Declared content length');
equal(JSON.stringify(policy.headers({}, 1024, 'text')), JSON.stringify({ error: '' }),
  'Chunked response without content length');
equal(policy.headerError({ 'Content-Length': '100, 100' }, 1024, 'text'),
  '远程图书关键响应头重复或存在歧义', 'Combined length rejection');
equal(policy.headerError({ 'Content-Type': ['application/pdf', 'text/html'] }, 1024, 'pdf'),
  '远程图书关键响应头重复或存在歧义', 'Array type rejection');
equal(policy.headerError({ 'Content-Type': 'application/pdf', 'content-type': 'application/pdf' }, 1024, 'pdf'),
  '远程图书关键响应头重复或存在歧义', 'Case-insensitive duplicate rejection');
equal(policy.headerError({ 'Content-Encoding': 'identity, gzip' }, 1024, 'pdf'),
  '远程图书关键响应头重复或存在歧义', 'Combined encoding rejection');
equal(policy.headerError({ 'Transfer-Encoding': 'chunked', 'Content-Length': '100' }, 1024, 'text'),
  '远程图书传输编码与内容长度冲突', 'TE and CL conflict rejection');
equal(policy.headerError({ 'Transfer-Encoding': 'gzip' }, 1024, 'text'),
  '远程图书传输编码无效', 'Unsupported transfer encoding');
equal(policy.headerError({ 'Transfer-Encoding': 'chunked' }, 1024, 'text'), '', 'Chunked transfer');
equal(policy.headerError({ 'Transfer-Encoding': ['chunked', 'identity'] }, 1024, 'text'),
  '远程图书关键响应头重复或存在歧义', 'Array transfer encoding rejection');
equal(policy.headerError({ Location: ['https://one.example', 'https://two.example'] }, 1024, 'text'),
  '远程图书重定向已阻止，请使用最终地址', 'Ambiguous redirect rejection');
const manyHeaders = {};
for (let index = 0; index < 65; index++) manyHeaders[`X-Test-${index}`] = 'ok';
equal(policy.headerError(manyHeaders, 1024, 'text'), '远程图书响应头数量超过限制', 'Header count quota');
equal(policy.headerError({ 'X-Test': 'a'.repeat(8193) }, 1024, 'text'),
  '远程图书响应头大小超过限制', 'Header value quota');
equal(policy.headerError({ 'Bad Header': 'value' }, 1024, 'text'),
  '远程图书响应头格式无效', 'Header name rejection');
equal(policy.headerError({ 'Content-Type': ' Application/PDF ; charset="utf-8" ' }, 1024, 'pdf'), '',
  'Normalized MIME and quoted parameter');
equal(policy.headerError({ 'Content-Type': 'application/pdf;' }, 1024, 'pdf'),
  '远程图书响应类型格式无效', 'Empty MIME parameter rejection');
equal(policy.headerError({ 'Content-Type': 'application/pdf; charset=utf-8; CHARSET=gbk' }, 1024, 'pdf'),
  '远程图书响应类型格式无效', 'Duplicate MIME parameter rejection');
equal(policy.headerError({ 'Content-Type': 'application/pdf; charset' }, 1024, 'pdf'),
  '远程图书响应类型格式无效', 'Malformed MIME parameter rejection');

equal(policy.payloadError(Uint8Array.from([0x25, 0x50, 0x44, 0x46, 0x2D]), 'pdf'), '', 'PDF signature');
equal(policy.payloadError(Uint8Array.from([0x3C, 0x68, 0x74, 0x6D, 0x6C]), 'pdf'),
  'PDF文件签名无效', 'Spoofed PDF rejection');
equal(policy.payloadError(Uint8Array.from([0x50, 0x4B, 0x03, 0x04]), 'archive'), '', 'ZIP signature');
equal(policy.payloadError(Uint8Array.from([0x50, 0x4B, 0x05, 0x06]), 'archive'),
  '图书容器签名无效', 'Empty ZIP rejection');
const mobi = new Uint8Array(68);
mobi.set(Buffer.from('BOOKMOBI'), 60);
equal(policy.payloadError(mobi, 'mobi'), '', 'MOBI signature');
mobi[67] = 0;
equal(policy.payloadError(mobi, 'mobi'), 'MOBI文件签名无效', 'Spoofed MOBI rejection');
equal(policy.payloadError(Uint8Array.from([0]), 'text'), '', 'Text has no container signature');

console.log('Remote book download policy tests passed');
