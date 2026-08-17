const moduleUnderTest = require(process.argv[2]);
const token = new moduleUnderTest.DownloadCancellationToken();
let calls = 0;
token.bind(() => calls += 1);
if (token.isCancelled()) throw new Error('Fresh token must be active');
token.cancel();
token.cancel();
if (!token.isCancelled() || calls !== 1) throw new Error('Cancellation must be monotonic and idempotent');

const late = new moduleUnderTest.DownloadCancellationToken();
late.cancel();
late.bind(() => calls += 1);
if (calls !== 2) throw new Error('Late binding must abort immediately');
late.unbind();

const concurrent = new moduleUnderTest.DownloadCancellationToken();
let firstCalls = 0;
let secondCalls = 0;
const first = () => firstCalls += 1;
const second = () => secondCalls += 1;
concurrent.bind(first);
concurrent.bind(second);
concurrent.unbind(first);
concurrent.cancel();
if (firstCalls !== 0 || secondCalls !== 1) {
  throw new Error('Unbinding one request must preserve other concurrent cancellation handlers');
}

console.log('Download cancellation token tests passed');
