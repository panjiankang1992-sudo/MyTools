// 管理页五尺寸 + 深色截图（本地回环专用）。
//
// **为什么不点击**：两条线都报告过"CDP 真实鼠标点击不稳定"（同一坐标有时渲染进程收不到）。
// 截图不需要交互，所以这里走"注入会话 Cookie + 设定视口 + 捕获"这条路，绕开该问题。
//
// 用法：E2E_TARGET=http://127.0.0.1:4173 ADMIN_COOKIE='toolbox_admin_local=xxx' node tests/e2e/local-admin-screenshots.mjs
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { connect, findChrome, launchChrome, sleep } from './cdp.mjs';

const target = (process.env.E2E_TARGET ?? '').replace(/\/$/, '');
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(target)) {
  console.error(`拒绝运行：E2E_TARGET 必须是回环地址，实际为 "${target}"`);
  process.exit(2);
}
const cookie = process.env.ADMIN_COOKIE ?? '';
if (!cookie.includes('=')) { console.error('缺少 ADMIN_COOKIE'); process.exit(2); }
const [cookieName, ...cookieRest] = cookie.split('=');
const cookieValue = cookieRest.join('=');

const OUT = '/Users/pankang/mycode/MyTools/docs/verification/toolbox-admin-completion-2026-10-07/screenshots';
const SIZES = [[320, 720], [375, 812], [768, 1024], [1280, 900], [1440, 900]];
const PAGES = [['list', '/#/admin/textbooks'], ['new', '/#/admin/textbooks/new'], ['editor', '/#/admin/textbooks/local-acceptance-2026-10-07/edit']];

await mkdir(OUT, { recursive: true });
const executablePath = findChrome();
if (!executablePath) { console.error('找不到 Chrome'); process.exit(2); }
const workDir = await mkdtemp(join(tmpdir(), 'tb-shots-'));
const port = Number(process.env.E2E_CDP_PORT ?? 9341);
let chrome;
const written = [];
try {
  chrome = await launchChrome({ executablePath, port, userDataDir: join(workDir, 'chrome') });
  const cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: cookieName, value: cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  for (const [theme, emulate] of [['light', 'light'], ['dark', 'dark']]) {
    for (const [label, path] of PAGES) {
      for (const [w, h] of SIZES) {
        if (theme === 'dark' && w !== 1280) continue; // 深色只在 1280 取一份，避免截图爆炸
        await cdp.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 768 });
        await cdp.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: emulate }] });
        await cdp.send('Page.navigate', { url: `${target}${path}` });
        await sleep(1600);
        const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
        const file = `${theme}-${label}-${w}x${h}.png`;
        await writeFile(join(OUT, file), Buffer.from(shot.data, 'base64'));
        const overflow = await cdp.send('Runtime.evaluate', {
          expression: 'document.documentElement.scrollWidth - document.documentElement.clientWidth',
          returnByValue: true
        });
        written.push({ theme, page: label, size: `${w}x${h}`, file, overflowPx: overflow.result.value });
        console.log(`${theme} ${label} ${w}x${h} -> ${file} (横向溢出 ${overflow.result.value}px)`);
      }
    }
  }
  await writeFile(join(OUT, 'report.json'), `${JSON.stringify(written, null, 2)}\n`);
  console.log(`共 ${written.length} 张`);
} finally {
  if (chrome) chrome.kill?.();
  await rm(workDir, { recursive: true, force: true });
}
