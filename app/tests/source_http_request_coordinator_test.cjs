const assert = require('node:assert/strict');

module.exports = async function run(SourceHttpRequestCoordinator, DownloadCancellationToken) {
  const coordinator = new SourceHttpRequestCoordinator();
  const first = await coordinator.acquire('https://source.example');

  let secondResolved = false;
  const secondPromise = coordinator.acquire('https://source.example').then((lease) => {
    secondResolved = true;
    return lease;
  });
  await new Promise((resolve) => setTimeout(resolve, 60));
  assert.equal(secondResolved, false, '同一书源的第二个请求必须等待');

  const other = await coordinator.acquire('https://other.example');
  assert.equal(other.key, 'https://other.example', '不同书源必须能并行取得租约');
  coordinator.release(other);

  coordinator.release(first);
  const second = await secondPromise;
  assert.equal(secondResolved, true, '前一请求释放后等待请求必须继续');

  coordinator.release(first);
  const thirdToken = new DownloadCancellationToken();
  const waiting = coordinator.acquire('https://source.example', thirdToken);
  thirdToken.cancel();
  await assert.rejects(waiting, /已取消/, '取消信号必须立即终止租约等待');

  coordinator.release(second);
  const finalLease = await coordinator.acquire('https://source.example');
  coordinator.release(finalLease);
};
