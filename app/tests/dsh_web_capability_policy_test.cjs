const assert = require('node:assert/strict');
const { DshWebCapabilityPolicy: policy } = require(process.argv[2]);
const origin = 'https://dsh-app.yuyutian.top';
const page = origin + '/';
const audio = ['TYPE_AUDIO_CAPTURE'];
assert.equal(policy.microphone(origin, page, '', audio), true);
for (const request of [origin + '/', 'https://evil.test', origin + '.evil.test']) {
  assert.equal(policy.microphone(request, page, '', audio), false);
}
for (const resources of [[], ['TYPE_VIDEO_CAPTURE'], [...audio, 'TYPE_VIDEO_CAPTURE']]) {
  assert.equal(policy.microphone(origin, page, '', resources), false);
}
const lan = 'http://192.168.1.7:23201';
assert.equal(policy.microphone(lan, lan + '/', lan, audio), false);
assert.equal(policy.microphone(origin, origin + '/_mytools/connect', '', audio), false);
for (const url of [origin + '/api/export/file.zip', 'blob:' + origin + '/test-id']) {
  assert.equal(policy.download(url, page, ''), true);
}
assert.equal(policy.download(lan + '/api/export', lan + '/', lan), true);
assert.equal(policy.internalNavigation(lan + '/api/export', lan + '/', lan), true);
assert.equal(policy.internalNavigation(lan + '/_mytools/connect?ticket=' + 'a'.repeat(43), 'about:blank', lan), true);
assert.equal(policy.internalNavigation(lan + '/_mytools/connect?ticket=bad', 'about:blank', lan), false);
assert.equal(policy.internalNavigation('https://docs.deepseek.com/', page, ''), false);
assert.equal(policy.internalNavigation('blob:' + origin + '/id', page, ''), true);
for (const url of ['https://evil.test/file', 'blob:https://evil.test/id', 'file:///data/file',
  origin + '.evil.test/file', origin + '/_mytools/connect', origin + '/internal/status',
  'https://user@dsh-app.yuyutian.top/file', origin + '/foo\\bar', 'data:text/plain,test']) {
  assert.equal(policy.download(url, page, ''), false);
}
assert.equal(policy.download(origin + '/api/export', 'https://evil.test/', ''), false);
assert.equal(policy.filename('../example\\file.zip'), '_example_file.zip');
assert.equal(policy.filename('...'), 'dsh-download.bin');
assert.equal(policy.filename('a'.repeat(300)).length, 160);
console.log('PASS: DSH microphone isolation, authenticated downloads, and safe file names');
