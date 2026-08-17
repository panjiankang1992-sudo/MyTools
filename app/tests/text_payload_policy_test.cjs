const policyModule = require(process.argv[2]);
const policy = new policyModule.TextPayloadPolicy();

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${expected}, actual=${actual}`);
}

equal(policy.error('第一章\n正文内容\t继续'), '', 'Chinese text');
equal(policy.error('\uFEFFChapter 1\r\nReadable text'), '', 'BOM text');
equal(policy.error('   \r\n\t'), 'TXT中没有可显示的正文', 'Whitespace-only rejection');
equal(policy.error('\0\x01\x02\x03binary'), 'TXT疑似二进制文件', 'Binary control rejection');
equal(policy.error(`normal text${String.fromCharCode(1)}`), '', 'Isolated control compatibility');
equal(policy.error('<!DOCTYPE html><html><body>Gateway error</body></html>'),
  'TXT响应疑似网页或XML文档', 'HTML error page rejection');
equal(policy.error('\uFEFF  <?xml version="1.0"?><error>denied</error>'),
  'TXT响应疑似网页或XML文档', 'XML response rejection');
equal(policy.error('正文中可以出现 <html> 标签示例'), '', 'Inline tag compatibility');

console.log('Text payload policy tests passed');
