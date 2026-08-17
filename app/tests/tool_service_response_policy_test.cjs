const marketModule = require(process.argv[2]);
const feedbackModule = require(process.argv[3]);
const market = new marketModule.AppMarketResponseNormalizer();
const feedback = new feedbackModule.FeedbackPolicy();

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

function item(id, overrides = {}) {
  return Object.assign({ id, name: `App ${id}`, type: 'tool', version: '1.0',
    contentPreview: '<b>safe</b> summary', status: 'ONLINE', userName: 'author',
    updateTime: '2026-08-12T00:00:00' }, overrides);
}

equal(market.page({ list: [item('1'), item('1', { name: 'duplicate' }), item('2'),
  item('bad', { name: 'x'.repeat(201) })], total: 2, page: 1, pageSize: 20 }, 1, 20), {
  items: [
    { id: '1', name: 'App 1', type: 'tool', version: '1.0', summary: 'safe summary',
      status: 'ONLINE', author: 'author', updatedAt: '2026-08-12T00:00:00' },
    { id: '2', name: 'App 2', type: 'tool', version: '1.0', summary: 'safe summary',
      status: 'ONLINE', author: 'author', updatedAt: '2026-08-12T00:00:00' }
  ], total: 2, page: 1, pageSize: 20
}, 'Market list projection and isolation');
rejects(() => market.page({ list: [], total: 0, page: 2, pageSize: 20 }, 1, 20), 'Page mismatch');
rejects(() => market.page({ list: [], total: '0', page: 1, pageSize: 20 }, 1, 20), 'String total');
rejects(() => market.page({ list: new Array(201).fill(item('1')), total: 201, page: 1, pageSize: 20 }, 1, 20),
  'List scan quota');

const detail = market.detail(Object.assign(item('3'), { content: '<script>ignored()</script><p>Description</p>',
  installCmd: 'rm -rf data', downloadUrl: 'https://evil.example/file', fileName: 'app.hap',
  fileSize: 1024, fileType: 'application/octet-stream' }));
equal([detail.content, detail.fileName, detail.fileSize], ['ignored() Description', 'app.hap', 1024],
  'Detail display projection');
if ('installCmd' in detail || 'downloadUrl' in detail) throw new Error('Executable fields leaked into detail');
rejects(() => market.detail(Object.assign(item('4'), { content: '', fileSize: NaN })), 'Invalid file size');

feedback.request('User', 'user@example.com', 'bug', 'Title', 'Line one\nLine two', 200);
rejects(() => feedback.request('x'.repeat(51), 'user@example.com', 'bug', 'Title', 'Body', 200),
  'Feedback username quota');
rejects(() => feedback.request('User', 'invalid', 'bug', 'Title', 'Body', 200), 'Feedback email validation');
rejects(() => feedback.request('User', 'user@example.com', 'bug', 'Title', 'Body', 16385),
  'Feedback body quota');
equal(feedback.receipt({ code: '0000', message: '', data: { feedbackId: 'feedback-123', status: 'OPEN' } }),
  { feedbackId: 'feedback-123', status: 'OPEN' }, 'Feedback receipt');
rejects(() => feedback.receipt({ code: '0000', message: '', data: { feedbackId: '../bad', status: 'OPEN' } }),
  'Feedback identifier validation');
rejects(() => feedback.receipt({ code: '0000', message: '', data: [] }), 'Feedback receipt shape');

console.log('Tool service response policy tests passed');
