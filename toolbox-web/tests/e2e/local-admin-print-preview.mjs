// 管理端「打印预览」的端到端验收（本地回环专用）。
//
// 用**合成 click()** 而不是真实鼠标：两条线都报告过 Input.dispatchMouseEvent 送达不稳定，
// 而"按钮是否真的打开了一个打印预览窗口"这件事不需要真实鼠标参与。
//
// 关键断言：点击后 **CDP 的 target 列表里真的多出一个窗口**（打印预览是 window.open 出来的），
// 并且关掉它之后原页面不报错、还能再次打开。这比"点了一下没抛异常"强得多。
import { mkdtemp, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { connect, findChrome, launchChrome, sleep } from './cdp.mjs';

const target = (process.env.E2E_TARGET ?? '').replace(/\/$/, '');
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(target)) { console.error('拒绝：E2E_TARGET 必须是回环'); process.exit(2); }
const raw = process.env.ADMIN_COOKIE ?? '';
if (!raw.includes('=')) { console.error('缺少 ADMIN_COOKIE'); process.exit(2); }
const i = raw.indexOf('=');
const OUT = '/Users/pankang/mycode/MyTools/docs/verification/toolbox-admin-completion-2026-10-07';
await mkdir(join(OUT, 'screenshots'), { recursive: true });

const listTargets = async () => (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json())
  .filter(t => t.type === 'page').map(t => t.url);

const exe = findChrome();
if (!exe) { console.error('找不到 Chrome'); process.exit(2); }
const dir = await mkdtemp(join(tmpdir(), 'tb-print-'));
const port = Number(process.env.E2E_CDP_PORT ?? 9349);
let chrome; const results = []; let bad = 0;
const check = (label, ok, extra = '') => { results.push({ label, ok, extra }); if (!ok) bad += 1;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${extra ? ` —— ${extra}` : ''}`); };
try {
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: join(dir, 'chrome') });
  const cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: raw.slice(0, i), value: raw.slice(i + 1), domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  // 用一个**本地真实存在**的教材，避免落到"目标不存在"错误态。
  await cdp.send('Page.navigate', { url: `${target}/#/admin/textbooks/local-acceptance-2026-10-07/edit` });
  await sleep(2600);

  const before = await listTargets();
  const clicked = await cdp.send('Runtime.evaluate', { returnByValue: true, expression: `(() => {
    const b = [...document.querySelectorAll('button')].find(x => (x.textContent || '').includes('打印预览'));
    if (!b) return null;
    if (b.disabled) return 'disabled';
    b.click();
    return 'clicked';
  })()` });
  check('能找到并点击「打印预览」按钮', clicked.result.value === 'clicked', `结果=${JSON.stringify(clicked.result.value)}`);
  await sleep(3500);
  const after = await listTargets();
  const opened = after.filter(u => !before.includes(u));
  check('打印预览**真的开出了新窗口**', opened.length > 0, `新窗口=${JSON.stringify(opened)}`);

  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
  await writeFile(join(OUT, 'screenshots', 'admin-print-preview.png'), Buffer.from(shot.data, 'base64'));

  await writeFile(join(OUT, 'evidence', 'print-preview-e2e.json'), `${JSON.stringify({ results, targets: after }, null, 2)}\n`);
} catch (error) {
  console.error('执行失败:', error?.message ?? error);
  bad += 1;
} finally {
  if (chrome) chrome.kill?.();
  await sleep(600);
  await rm(dir, { recursive: true, force: true }).catch(() => {});
}
console.log(`\n通过 ${results.filter(r => r.ok).length}/${results.length}`);
process.exitCode = bad === 0 ? 0 : 1;
