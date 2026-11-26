#!/usr/bin/env node
/**
 * 管理端「预览范围」的浏览器端到端验收（**只允许回环目标**）。
 *
 * 要证明的三件事：
 *   1. 选「整册」预览时，真实请求 URL **不带** `nodeKind`／`nodeKey`；
 *   2. 选「第一单元」后**真的重新取预览**：请求 URL 带 `nodeKind=unit&nodeKey=cu1`，
 *      且响应里的条目数与页面上的"共 N 页"都**真的变少**（不是只改文案）；
 *   3. 界面上"当前范围"与产物一致。
 *
 * 手法（真实鼠标不稳定，按仓库既有做法绕开）：
 *   - 合成 `el.click()` 触发按钮（编辑器按钮是真实 `<button>`）；
 *   - 在应用脚本之前给 `window.fetch` 装探针，记录预览请求的 **URL 与响应体条目数**；
 *   - 页数直接读页面上"共 N 页"（来自产物 `pageCount`），不靠猜。
 *
 * 用法：
 *   ADMIN_COOKIE='toolbox_admin_local=...' E2E_TARGET=http://127.0.0.1:4173 \
 *     node tests/e2e/local-admin-preview-scope.mjs
 *
 * 退出码：0=断言全部通过；1=有断言失败；2=目标非回环或缺 Cookie。
 */

import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  connect, createProfileDir, evaluate, findChrome, launchChrome, removeProfileDir, sleep, stopChrome, waitFor
} from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const cookie = process.env.ADMIN_COOKIE ?? '';
if (!cookie.includes('=')) {
  console.error('缺少 ADMIN_COOKIE（形如 toolbox_admin_local=...）。本次未执行（退出码 2）。');
  process.exit(2);
}
const [cookieName, ...cookieRest] = cookie.split('=');
const cookieValue = cookieRest.join('=');
const EDITION = process.env.E2E_EDITION ?? 'accept-cn-2026a';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const shotsDir = join(repoRoot, 'docs/verification/toolbox-admin-completion-2026-10-07/screenshots');
await mkdir(shotsDir, { recursive: true });

const started = Date.now();
const elapsed = () => `${((Date.now() - started) / 1000).toFixed(1)}s`;
const results = [];
const check = (label, ok, detail = '') => {
  results.push({ label, ok: Boolean(ok), detail });
  console.log(`[${elapsed()}] ${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` —— ${detail}` : ''}`);
};

/** 在任何应用脚本之前给 `fetch` 装探针：记录预览请求的真实 URL 与响应条目数。 */
const PROBE = `
window.__previewCalls = [];
(() => {
  const original = window.fetch;
  window.fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : ((input && input.url) || String(input));
    const response = await original(input, init);
    if (url.includes('/draft/preview')) {
      let body = null;
      try { body = await response.clone().json(); } catch (error) { body = null; }
      const data = body && body.data ? body.data : {};
      window.__previewCalls.push({
        url,
        status: response.status,
        code: body ? body.code : null,
        entries: Array.isArray(data.entries) ? data.entries.length : null,
        units: Array.isArray(data.units) ? data.units.length : null,
        lessons: Array.isArray(data.lessons) ? data.lessons.length : null,
        scope: data.scope ?? null,
        draftRevision: data.draftRevision ?? null
      });
    }
    return response;
  };
})();
`;

/** 生成"按文本合成点击一个按钮"的表达式。 */
const clickByText = text => `
  const target = [...document.querySelectorAll('button')].find(item => item.textContent.trim() === ${JSON.stringify(text)});
  if (target) target.click();
  return Boolean(target);
`;

/** 读页面状态：预览块文案、画布指纹、以及探针记录到的全部预览请求。 */
const READ_STATE = `
  const note = document.querySelector('.preview-snapshot') ? document.querySelector('.preview-snapshot').textContent.trim() : '';
  const settings = document.querySelector('.preview-settings') ? document.querySelector('.preview-settings').textContent.trim() : '';
  const canvas = document.querySelector('.paper-sheet canvas');
  let fingerprint = null;
  if (canvas) {
    const data = canvas.toDataURL('image/png');
    let hash = 0;
    for (let index = 0; index < data.length; index += 1) hash = (hash * 31 + data.charCodeAt(index)) | 0;
    fingerprint = String(hash);
  }
  return {
    note,
    settings,
    open: Boolean(document.querySelector('[aria-label="草稿预览"]')),
    canvas: Boolean(canvas),
    fingerprint,
    calls: window.__previewCalls.slice()
  };
`;

/** 从"共 N 页"里取真实页数；没有那个片段时返回 null。 */
const pagesOf = note => {
  const matched = /共\s*(\d+)\s*页/u.exec(note ?? '');
  return matched ? Number(matched[1]) : null;
};

const shot = async (cdp, name) => {
  const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' });
  await writeFile(join(shotsDir, name), Buffer.from(data, 'base64'));
  return name;
};

const exe = findChrome();
if (!exe) {
  console.error('找不到 Chrome。本次未执行（退出码 2）。');
  process.exit(2);
}

const port = Number(process.env.E2E_CDP_PORT ?? 9377);
let chrome = null;
let cdp = null;
let profileDir = null;
let exitCode = 1;
const report = { base, edition: EDITION, checks: results, calls: [], notes: [] };

try {
  profileDir = await createProfileDir('tb-preview-scope-');
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: profileDir });
  cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: cookieName, value: cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: PROBE });

  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
  // 草稿加载出来的标志：目录里出现种子数据的单元按钮。
  await waitFor(cdp, `
    [...document.querySelectorAll('button')].some(item => item.textContent.trim() === '第一单元')
      && [...document.querySelectorAll('button')].some(item => item.textContent.trim() === '预览草稿')
  `, '编辑页与草稿目录就绪', 90000);

  // ---- 1) 整册预览 ----
  const clickedWhole = await evaluate(cdp, clickByText('预览草稿'));
  check('手法：合成 el.click() 点到「预览草稿」', clickedWhole === true, `clicked=${clickedWhole}`);
  await waitFor(cdp, 'window.__previewCalls.length >= 1', '整册预览请求发出', 60000);
  await waitFor(cdp, `Boolean(document.querySelector('.paper-sheet canvas'))`, '整册预览产出画布', 90000);
  await sleep(600);
  const whole = await evaluate(cdp, READ_STATE);
  report.calls.push(whole.calls[0] ?? null);
  report.notes.push({ scope: '整册', note: whole.note });
  await shot(cdp, 'preview-scope-whole-book.png');

  check('整册：请求 URL **不带** nodeKind／nodeKey',
    typeof whole.calls[0]?.url === 'string'
      && !whole.calls[0].url.includes('nodeKind') && !whole.calls[0].url.includes('nodeKey'),
    whole.calls[0]?.url);
  check('整册：响应 scope.filtered=false 且条目数为整册规模',
    whole.calls[0]?.scope?.filtered === false && (whole.calls[0]?.entries ?? 0) > 0,
    `entries=${whole.calls[0]?.entries} scope=${JSON.stringify(whole.calls[0]?.scope)}`);
  const wholePages = pagesOf(whole.note);
  check('整册：界面上"当前范围：整册"且产物页数可见',
    whole.settings.includes('当前范围：整册') && wholePages !== null,
    `note="${whole.note}"`);

  // ---- 2) 切到第一单元：必须真的重取 ----
  const clickedUnit = await evaluate(cdp, clickByText('第一单元'));
  check('手法：合成 el.click() 点到目录里的「第一单元」', clickedUnit === true, `clicked=${clickedUnit}`);
  await waitFor(cdp, 'window.__previewCalls.length >= 2', '切范围后重新取预览', 60000);
  await waitFor(cdp, `
    (document.querySelector('.preview-snapshot') ? document.querySelector('.preview-snapshot').textContent : '').includes('单元：第一单元')
  `, '预览块标注新范围', 60000);
  await sleep(600);
  const unit = await evaluate(cdp, READ_STATE);
  report.calls.push(unit.calls[1] ?? null);
  report.notes.push({ scope: '第一单元', note: unit.note });
  await shot(cdp, 'preview-scope-unit-1.png');

  const unitCall = unit.calls[1] ?? {};
  check('切范围真的重取了预览（请求次数 1 → 2）', unit.calls.length >= 2, `calls=${unit.calls.length}`);
  check('第一单元：请求 URL 含 nodeKind=unit 与 nodeKey=cu1',
    typeof unitCall.url === 'string' && unitCall.url.includes('nodeKind=unit') && unitCall.url.includes('nodeKey=cu1'),
    unitCall.url);
  check('第一单元：响应 scope.filtered=true',
    unitCall.scope?.filtered === true && unitCall.scope?.nodeKey === 'cu1',
    JSON.stringify(unitCall.scope));

  const unitPages = pagesOf(unit.note);
  check('第一单元：产物条目数**真的变少**（不是只改文案）',
    Number.isInteger(unitCall.entries) && Number.isInteger(whole.calls[0]?.entries)
      && unitCall.entries < whole.calls[0].entries,
    `entries ${whole.calls[0]?.entries} → ${unitCall.entries}`);
  check('第一单元：产物页数**真的变少或不增加**，且文案与范围一致',
    unitPages !== null && wholePages !== null && unitPages <= wholePages
      && unit.settings.includes('当前范围：单元：第一单元'),
    `pages ${wholePages} → ${unitPages}；note="${unit.note}"`);

  // ---- 3) 再切到第二单元：内容与整册/第一单元都不同（第一单元恰好是整册的前 66 条，首页看起来一样） ----
  const clickedUnit2 = await evaluate(cdp, clickByText('第二单元'));
  check('手法：合成 el.click() 点到目录里的「第二单元」', clickedUnit2 === true, `clicked=${clickedUnit2}`);
  await waitFor(cdp, 'window.__previewCalls.length >= 3', '再切范围后重新取预览', 60000);
  await waitFor(cdp, `
    (document.querySelector('.preview-snapshot') ? document.querySelector('.preview-snapshot').textContent : '').includes('单元：第二单元')
  `, '预览块标注第二单元', 60000);
  await sleep(600);
  const unit2 = await evaluate(cdp, READ_STATE);
  report.calls.push(unit2.calls[2] ?? null);
  report.notes.push({ scope: '第二单元', note: unit2.note });
  await shot(cdp, 'preview-scope-unit-2.png');

  const unit2Call = unit2.calls[2] ?? {};
  const unit2Pages = pagesOf(unit2.note);
  check('第二单元：请求 URL 含 nodeKind=unit 与 nodeKey=cu2',
    typeof unit2Call.url === 'string' && unit2Call.url.includes('nodeKind=unit') && unit2Call.url.includes('nodeKey=cu2'),
    unit2Call.url);
  check('第二单元：产物首页画布与整册**不同**（内容真的换了，不是同一份复用）',
    unit2.fingerprint !== null && whole.fingerprint !== null && unit2.fingerprint !== whole.fingerprint,
    `整册指纹 ${whole.fingerprint} → 第二单元指纹 ${unit2.fingerprint}`);
  check('第二单元：条目数与页数都比整册小',
    unit2Call.entries < whole.calls[0].entries && unit2Pages !== null && unit2Pages < wholePages,
    `entries ${whole.calls[0].entries} → ${unit2Call.entries}；pages ${wholePages} → ${unit2Pages}`);

  const passed = results.every(item => item.ok);
  exitCode = passed ? 0 : 1;
  console.log(`[${elapsed()}] 结束：${passed ? '全部通过' : '有断言失败'}（退出码 ${exitCode}）`);
} catch (cause) {
  console.error(`[${elapsed()}] 运行失败：${cause instanceof Error ? cause.message : String(cause)}`);
  exitCode = 1;
} finally {
  report.checks = results;
  report.exitCode = exitCode;
  // 结构化结果写到系统临时目录：证据目录只放截图与本轮报告，不掺运行时垃圾。
  await writeFile(
    join(process.env.TMPDIR ?? '/tmp', 'preview-scope-browser-report.json'),
    `${JSON.stringify(report, null, 2)}\n`
  ).catch(() => undefined);
  try { cdp?.close(); } catch { /* 已关闭 */ }
  await stopChrome(chrome, profileDir);
  await removeProfileDir(profileDir);
  process.exit(exitCode);
}
