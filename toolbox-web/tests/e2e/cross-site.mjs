#!/usr/bin/env node
/**
 * A07：**浏览器级**跨站验证。
 *
 * HTTP 层已有 `OriginAllowlistTest`（真实 `Origin` 头 → `TOOLBOX_010`）。
 * 这里补的是"**浏览器会不会真的把凭据带过去**"——那是浏览器行为，只能真跑。
 *
 * 做法：在**另一个源**（同主机不同端口）上托管一个页面，让它向工具箱发一次跨站写请求。
 * 两个源端口不同即为跨源，浏览器会自动带上 `Origin: http://127.0.0.1:<另一个端口>`。
 *
 * 用法（需先启动 dev server 4173 与后端 23199）：
 *   node tests/e2e/cross-site.mjs
 * 退出码非 0 表示未通过。
 */
import { createServer } from 'node:http';
import { connect, findChrome, launchChrome, evaluate, sleep } from './cdp.mjs';

const TOOLBOX = process.env.CROSS_SITE_TARGET ?? 'http://127.0.0.1:4173';
const FOREIGN_PORT = Number(process.env.CROSS_SITE_PORT ?? 4199);

/** 外来源页面：发一次跨站写请求，并把结果放到 DOM 上供读取。 */
const PAGE = `<!doctype html><html lang="zh"><body><div id="out">running</div><script>
(async () => {
  const out = document.getElementById('out');
  try {
    const response = await fetch(${JSON.stringify(TOOLBOX)} + '/public/v1/events', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ events: [] })
    });
    out.textContent = 'status=' + response.status;
  } catch (error) {
    // CORS 被拒时 fetch 会抛错——这正是我们期望的结果。
    out.textContent = 'blocked=' + (error && error.name ? error.name : 'error');
  }
})();
</script></body></html>`;

const server = createServer((_request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  response.end(PAGE);
});
await new Promise(resolve => server.listen(FOREIGN_PORT, '127.0.0.1', resolve));

let chrome;
let cdp;
let failed = false;
try {
  chrome = await launchChrome({ executablePath: findChrome(), port: 9341, userDataDir: '/tmp/tb-cross-site' });
  cdp = await connect(9341);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.navigate', { url: `http://127.0.0.1:${FOREIGN_PORT}/` });
  await sleep(4000);

  const outcome = await evaluate(cdp, `return document.getElementById('out').textContent;`);
  // 两种可接受的结果：
  //   1) blocked=...  —— 浏览器按 CORS 拒绝（工具箱未允许该来源）
  //   2) status=403/401 —— 请求到了服务端但被来源校验/会话校验拒绝
  // 不可接受：任何 2xx（说明跨站写请求被接受了）。
  const accepted = /^status=2/.test(outcome);
  const blocked = /^blocked=/.test(outcome) || /^status=(401|403|429)/.test(outcome);
  if (!blocked || accepted) {
    failed = true;
    console.error(`FAIL  跨站写请求未被拒绝：${outcome}`);
  } else {
    console.log(`PASS  跨站写请求被拒绝（${outcome}）`);
  }
} finally {
  cdp?.close?.();
  chrome?.kill?.('SIGKILL');
  server.close();
}
process.exit(failed ? 1 : 0);
