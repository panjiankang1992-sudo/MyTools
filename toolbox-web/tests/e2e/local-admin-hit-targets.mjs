// 管理页可点击元素命中区实测（设计第 176 行要求 44px）。
//
// **不点击**：只读 `getBoundingClientRect()`，所以不受"CDP 真实鼠标点击不稳定"影响。
// 用法：E2E_TARGET=http://127.0.0.1:4173 ADMIN_COOKIE='...' node tests/e2e/local-admin-hit-targets.mjs
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { connect, findChrome, launchChrome, sleep } from './cdp.mjs';

const target = (process.env.E2E_TARGET ?? '').replace(/\/$/, '');
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(target)) { console.error('拒绝：E2E_TARGET 必须是回环'); process.exit(2); }
const raw = process.env.ADMIN_COOKIE ?? '';
if (!raw.includes('=')) { console.error('缺少 ADMIN_COOKIE'); process.exit(2); }
const idx = raw.indexOf('=');
const OUT = '/Users/pankang/mycode/MyTools/docs/verification/toolbox-admin-completion-2026-10-07/evidence';
await mkdir(OUT, { recursive: true });

const MIN = 44;
const PAGES = [['list', '/#/admin/textbooks'], ['new', '/#/admin/textbooks/new'], ['editor', '/#/admin/textbooks/local-acceptance-2026-10-07/edit']];
const executablePath = findChrome();
if (!executablePath) { console.error('找不到 Chrome'); process.exit(2); }
const workDir = await mkdtemp(join(tmpdir(), 'tb-hit-'));
const port = Number(process.env.E2E_CDP_PORT ?? 9343);
let chrome;
const all = [];
try {
  chrome = await launchChrome({ executablePath, port, userDataDir: join(workDir, 'chrome') });
  const cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: raw.slice(0, idx), value: raw.slice(idx + 1), domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  for (const [label, path] of PAGES) {
    for (const [w, h] of [[375, 812], [1280, 900]]) {
      await cdp.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 768 });
      await cdp.send('Page.navigate', { url: `${target}${path}` });
      await sleep(1500);
      const res = await cdp.send('Runtime.evaluate', { returnByValue: true, expression: `(() => {
        const out = [];
        const nodes = document.querySelectorAll('button, a[href], [role="button"], input, select, textarea, .n-button, .n-base-selection');
        for (const el of nodes) {
          const r = el.getBoundingClientRect();
          const cs = getComputedStyle(el);
          if (cs.display === 'none' || cs.visibility === 'hidden' || r.width === 0 || r.height === 0) continue;
          const text = (el.getAttribute('aria-label') || el.textContent || el.tagName).trim().replace(/\\s+/g, ' ').slice(0, 24);
          out.push({ text, tag: el.tagName.toLowerCase(), w: Math.round(r.width), h: Math.round(r.height) });
        }
        return out;
      })()` });
      for (const item of res.result.value) all.push({ page: label, viewport: `${w}x${h}`, ...item });
      console.log(`${label} ${w}x${h}: ${res.result.value.length} 个可点击元素`);
    }
  }
  const small = all.filter(i => i.w < MIN || i.h < MIN);
  await writeFile(join(OUT, 'hit-targets.json'), `${JSON.stringify({ min: MIN, total: all.length, belowMin: small }, null, 2)}\n`);
  console.log(`\n共测 ${all.length} 个可点击元素；小于 ${MIN}px 的 ${small.length} 个：`);
  for (const s of small.slice(0, 25)) console.log(`  ${s.page} ${s.viewport} ${s.tag} "${s.text}" ${s.w}x${s.h}`);
} finally {
  if (chrome) chrome.kill?.();
  await rm(workDir, { recursive: true, force: true });
}
