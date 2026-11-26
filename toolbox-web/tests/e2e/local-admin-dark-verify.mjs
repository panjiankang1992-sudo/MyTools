// 深色主题的可判定核验。
//
// 前两次失败都在"测量方法"上（探针选到透明的 body 背景；按钮定位与返回值混淆），
// 所以这次同时读**多个必然随主题变化的探针**，任一变化即算切换成功，
// 并且**把全部读数原样打印**，让结论可被独立判断。
import { mkdtemp, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { connect, findChrome, launchChrome, sleep } from './cdp.mjs';

const target = (process.env.E2E_TARGET ?? '').replace(/\/$/, '');
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(target)) { console.error('拒绝：E2E_TARGET 必须是回环'); process.exit(2); }
const raw = process.env.ADMIN_COOKIE ?? '';
if (!raw.includes('=')) { console.error('缺少 ADMIN_COOKIE'); process.exit(2); }
const i = raw.indexOf('=');
const OUT = '/Users/pankang/mycode/MyTools/docs/verification/toolbox-admin-completion-2026-10-07/screenshots';
await mkdir(OUT, { recursive: true });

/** 一组"必然随主题变化"的探针。 */
const PROBE = `(() => {
  const cs = getComputedStyle(document.documentElement);
  const read = (n) => (cs.getPropertyValue(n) || '').trim();
  return {
    nColor: read('--n-color'),
    nTextColor: read('--n-text-color'),
    nBodyColor: read('--n-body-color'),
    htmlClass: document.documentElement.className,
    bodyBg: getComputedStyle(document.body).backgroundColor,
    headerBg: getComputedStyle(document.querySelector('.site-header') || document.body).backgroundColor
  };
})()`;

const exe = findChrome();
if (!exe) { console.error('找不到 Chrome'); process.exit(2); }
const dir = await mkdtemp(join(tmpdir(), 'tb-dark2-'));
const port = Number(process.env.E2E_CDP_PORT ?? 9347);
let chrome;
try {
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: join(dir, 'chrome') });
  const cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: raw.slice(0, i), value: raw.slice(i + 1), domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  // 五个尺寸都验一遍（不只是 1280）—— 深色若只在某个断点生效，这里就能抓到。
  const SIZES = [[320, 720], [375, 812], [768, 1024], [1280, 900], [1440, 900]];
  const summary = [];
  for (const [w, h] of SIZES) {
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 768 });
  await cdp.send('Page.navigate', { url: `${target}/#/admin/textbooks` });
  await sleep(2000);

  const before = (await cdp.send('Runtime.evaluate', { returnByValue: true, expression: PROBE })).result.value;
  // 用 **合成 click**（绕开不稳定的真实鼠标），并**先确认找到了按钮**。
  const found = await cdp.send('Runtime.evaluate', { returnByValue: true, expression: `(() => {
    const b = [...document.querySelectorAll('button')].find(x => /深色|浅色/.test(x.getAttribute('aria-label') || ''));
    if (!b) return null;
    const label = b.getAttribute('aria-label');
    b.click();
    return label;
  })()` });
  await sleep(1500);
  const after = (await cdp.send('Runtime.evaluate', { returnByValue: true, expression: PROBE })).result.value;
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
  const changed = Object.keys(before).filter(k => before[k] !== after[k]);
  const ok = changed.length > 0;
  summary.push({ size: `${w}x${h}`, changed, ok, headerBg: `${before.headerBg} -> ${after.headerBg}`, button: found.result.value });
  console.log(`[${w}x${h}] 按钮=${JSON.stringify(found.result.value)} 变化探针=${JSON.stringify(changed)} headerBg ${before.headerBg} -> ${after.headerBg} ${ok ? 'OK' : 'FAIL'}`);
  await writeFile(join(OUT, `dark-textbooks-${w}x${h}.png`), Buffer.from(shot.data, 'base64'));
  }
  const failed = summary.filter(x => !x.ok);
  console.log(`\n五个尺寸：${summary.length - failed.length}/${summary.length} 切换成功`);
  if (failed.length) console.log('失败尺寸:', JSON.stringify(failed));
  await writeFile(join(OUT, '..', 'evidence', 'dark-verify-all-sizes.json'), `${JSON.stringify(summary, null, 2)}\n`);
  process.exitCode = failed.length === 0 ? 0 : 1;
} finally {
  if (chrome) chrome.kill?.();
  await sleep(600);
  await rm(dir, { recursive: true, force: true }).catch(() => {});
}
