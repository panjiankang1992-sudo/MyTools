// 深色主题截图（合成点击切换，不依赖真实鼠标事件）。
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
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
const PAGES = [['list', '/#/admin/textbooks'], ['new', '/#/admin/textbooks/new'], ['editor', '/#/admin/textbooks/local-acceptance-2026-10-07/edit']];
const exe = findChrome(); if (!exe) { console.error('找不到 Chrome'); process.exit(2); }
const dir = await mkdtemp(join(tmpdir(), 'tb-dark-'));
const port = Number(process.env.E2E_CDP_PORT ?? 9345);
let chrome; const out = [];
try {
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: join(dir, 'chrome') });
  const cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: raw.slice(0, i), value: raw.slice(i + 1), domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  for (const [label, path] of PAGES) {
    await cdp.send('Page.navigate', { url: `${target}${path}` });
    await sleep(1600);
    const before = await cdp.send('Runtime.evaluate', { returnByValue: true, expression: "getComputedStyle(document.querySelector('.site-header')||document.body).backgroundColor" });
    const clicked = await cdp.send('Runtime.evaluate', { returnByValue: true, expression: `(() => {
      const b = [...document.querySelectorAll('button')].find(x => ((x.getAttribute('aria-label')||x.getAttribute('title')||x.textContent||'').match(/深色|浅色|暗色/));
      if (!b) return 'not-found';
      b.click(); return 'clicked';
    })()` });
    await sleep(1200);
    const after = await cdp.send('Runtime.evaluate', { returnByValue: true, expression: "getComputedStyle(document.querySelector('.site-header')||document.body).backgroundColor" });
    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
    const file = `dark-${label}-1280x900.png`;
    await writeFile(join(OUT, file), Buffer.from(shot.data, 'base64'));
    out.push({ page: label, file, click: clicked.result.value, before: before.result.value, after: after.result.value, changed: before.result.value !== after.result.value });
    console.log(`${label}: ${clicked.result.value} bg ${before.result.value} -> ${after.result.value} 变化=${before.result.value !== after.result.value}`);
  }
  await writeFile(join(OUT, 'dark-report.json'), `${JSON.stringify(out, null, 2)}\n`);
} finally { if (chrome) chrome.kill?.(); await sleep(800); await rm(dir, { recursive: true, force: true }).catch(() => {}); }
