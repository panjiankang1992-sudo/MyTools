const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.RemoteMediaPickerSelectionPolicy();
const raw = ['all', 'image', 'video', 'audio'];
const display = ['全部媒体', '图片', '视频', '音频'];

if (policy.resolve('视频', 0, raw, display) !== 'video') {
  throw new Error('Visible picker value must win over stale index');
}
if (policy.resolve('', 2, raw, display) !== 'video') {
  throw new Error('Index fallback');
}
if (policy.resolve(['音频'], [0], raw, display) !== 'audio') {
  throw new Error('Array callback value');
}
if (policy.resolve('未知', 99, raw, display) !== '') {
  throw new Error('Invalid selection');
}

console.log('Remote media picker selection policy tests passed');
