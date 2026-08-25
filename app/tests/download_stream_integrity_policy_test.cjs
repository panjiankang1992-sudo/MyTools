const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.DownloadStreamIntegrityPolicy();
const jpeg = [0xFF, 0xD8, 0xFF];

if (policy.error(200, 10, 10, '', '', []) !== '') throw new Error('Valid stream');
if (policy.error(206, 10, undefined, '', '', []) !== '') throw new Error('Chunked successful stream');
if (policy.error(401, 10, 10, '', '', []) !== 'HTTP 401') throw new Error('HTTP failure');
if (policy.error(200, 0, 0, '', '', []) !== '远程文件为空') throw new Error('Empty stream');
if (policy.error(200, 9, 10, '', '', []) !== '远程文件长度与响应声明不一致') {
  throw new Error('Truncated stream');
}
if (policy.error(200, 11, 10, '', '', []) !== '远程文件长度与响应声明不一致') {
  throw new Error('Oversized stream');
}
if (policy.error(200, 3, 3, 'image/jpeg', 'text/html', jpeg) !== '缩略图响应类型无效') {
  throw new Error('Content type');
}
if (policy.error(200, 3, 3, 'image/jpeg', 'image/jpeg', [1, 2, 3]) !== '缩略图内容签名无效') {
  throw new Error('JPEG signature');
}
if (policy.error(200, 3, 3, 'image/jpeg', 'image/jpeg', jpeg) !== '') throw new Error('Valid JPEG');
if (policy.error(200, 3, 3, 'image/*', 'image/jpg', jpeg) !== '') throw new Error('Legacy JPEG MIME alias');
if (policy.error(200, 3, 3, 'image/*', 'image/pjpeg', jpeg) !== '') throw new Error('Progressive JPEG MIME alias');

console.log('Download stream integrity policy tests passed');
