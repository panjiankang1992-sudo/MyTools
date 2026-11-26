#!/usr/bin/env node
/**
 * 「合成输入真的被 Vue 接收」的**小规模**验证（正式验收前的手法验证）。
 *
 * 背景：仓库 CDP 驱动的真实鼠标点击（`Input.dispatchMouseEvent`）送达不稳定，
 * 已被两条线独立报告。正式验收必须改用：
 *   1. 原生 setter + `input` 事件让 Vue 感知输入；
 *   2. 合成 `el.click()` 触发按钮；
 *   3. 轮询等待目标状态，而不是固定 sleep。
 *
 * 本脚本只做一件事：把编辑页第 1 行文本框改成一个**独特值**，然后断言
 *   - 页面上出现了这个独特值（Vue 确实渲染了新状态）；
 *   - 点击「预览草稿」后，**发送给后端的草稿载荷里**带着这个独特值
 *     （证明合成输入进了真实保存链路，而不只是 DOM 上看着变了）。
 *
 * 用法（**只允许回环**）：
 *   ADMIN_COOKIE='toolbox_admin_local=...' E2E_TARGET=http://127.0.0.1:4173 \
 *     node tests/e2e/local-admin-synthetic-input-smoke.mjs
 *
 * 退出码：0=断言通过；1=断言失败；2=目标不是回环或缺 Cookie。
 */

import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { connect, createProfileDir, evaluate, findChrome, launchChrome, removeProfileDir, stopChrome, sleep, waitFor } from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const cookie = process.env.ADMIN_COOKIE ?? '';
if (!cookie.includes('=')) {
  console.error('缺少 ADMIN_COOKIE（形如 toolbox_admin_local=...）。本次未执行（退出码 2）。');
  process.exit(2);
}
const [cookieName, ...cookieRest] = cookie.split('=');
const cookieValue = cookieRest.join('=');
const EDITION = process.env.E2E_EDITION ?? 'local-acceptance-2026-10-07';
const MARK = process.env.E2E_MARK ?? `烟${Date.now().toString(36).slice(-4)}`;

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const outDir = process.env.E2E_EVIDENCE_DIR
  ?? join(repoRoot, 'docs/verification/toolbox-admin-completion-2026-10-07/evidence/browser');
await mkdir(outDir, { recursive: true });

const results = [];
const check = (label, ok, detail = '') => {
  results.push({ label, ok: Boolean(ok), detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` —— ${detail}` : ''}`);
};

/** 在页面里设置 input/textarea 的值：必须走原生 setter 才会触发 Vue 的响应式。 */
const SET_NATIVE_VALUE = `
  (selector, value) => {
    const host = document.querySelector(selector);
    if (!host) return 'no-element';
    // Naive UI 的 NInput 把 aria-label 放在外层 div 上，真正的 <input>/<textarea> 在里面。
    const el = host.matches('input, textarea') ? host : host.querySelector('input, textarea');
    if (!el) return 'no-field';
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return el.value;
  }
`;

const exe = findChrome();
if (!exe) { console.error('找不到 Chrome。本次未执行（退出码 2）。'); process.exit(2); }
const port = Number(process.env.E2E_CDP_PORT ?? 9361);
let chrome;
let profileDir = null;
try {
  profileDir = await createProfileDir('tb-smoke-');
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: profileDir });
  const cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: cookieName, value: cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  /** 记录发往后端的草稿保存载荷，验证"合成输入进了真实链路"。 */
  const saves = [];
  cdp.on(message => {
    if (message.method === 'Network.requestWillBeSent') {
      const { url, method, postData } = message.params.request;
      if (method === 'PUT' || method === 'POST') {
        if (url.includes(`/admin/v1/textbooks/${EDITION}/draft`)) saves.push(postData ?? '');
      }
    }
  });

  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
const LEFT = '.n-input[aria-label="第 1 行汉字"]';
  const leftValue = `document.querySelector(${JSON.stringify(LEFT)})?.querySelector('textarea, input')?.value ?? null`;
  await waitFor(cdp, `${leftValue} !== null`, '编辑页文本框渲染');

  const before = await evaluate(cdp, `return ${leftValue};`);
  const unique = `${before ?? ''}${MARK}`;
  const applied = await evaluate(cdp, `return (${SET_NATIVE_VALUE})(${JSON.stringify(LEFT)}, ${JSON.stringify(unique)});`);
  const rendered = await waitFor(cdp, `${leftValue} === ${JSON.stringify(unique)}`, '文本框显示新值', 8000);
  console.log(`原始值=${JSON.stringify(before)} 目标值=${JSON.stringify(unique)} setter 返回=${JSON.stringify(applied)} 渲染=${rendered}`);
  // waitFor 返回的是表达式取值本身（这里是字符串），只要等于目标值即视为成立。
  check('原生 setter + input 事件让输入框（Vue 渲染）变成新值', rendered === unique, `实际 ${JSON.stringify(rendered)}`);

  // 让 2 秒防抖的自动保存真的发生，再断言载荷；这一步同时验证"编辑进入了保存链路"。
  try {
    await waitFor(cdp, `(() => { const t = document.body.innerText; return /已保存|有新改动待保存/.test(t); })()`, '保存状态出现', 10000);
  } catch { /* 状态文案缺失不致命，下一步的载荷断言才是关键 */ }
  await sleep(1500);

  const saved = saves.find(payload => payload.includes(MARK));
  console.log(`捕获到 ${saves.length} 次草稿保存请求`);
  // 收尾：把输入框改回原值，别把本地样册改脏（跑一次污染一次是不可接受的）。
  await evaluate(cdp, `(${SET_NATIVE_VALUE})(${JSON.stringify(LEFT)}, ${JSON.stringify(before ?? '')});`);
  const restoredValue = await evaluate(cdp, `return document.querySelector(${JSON.stringify(LEFT)})?.querySelector('textarea, input')?.value ?? null;`);
  const restoreDeadline = Date.now() + 20000;
  let restoredOnServer = false;
  while (Date.now() < restoreDeadline) {
    const current = await evaluate(cdp, `return (async () => { const response = await fetch('/admin/v1/textbooks/${EDITION}/draft', { credentials: 'same-origin' }); const body = await response.json(); return (body?.data?.draft?.entries ?? []).map(entry => entry.text + '|' + (entry.pinyin ?? '')).join(','); })();`);
    restoredOnServer = !String(current).includes(MARK);
    if (restoredOnServer) break;
    await sleep(600);
  }
  console.log(`收尾复原：输入框=${JSON.stringify(restoredValue)}（原值 ${JSON.stringify(before)}） 服务器草稿已无独特值=${restoredOnServer}`);
  check('后端确实收到了带独特值的草稿保存载荷（合成输入进了真实保存链路）', Boolean(saved),
    saved ? `载荷长度 ${saved.length}，含「${MARK}」` : `捕获 ${saves.length} 次保存，均不含「${MARK}」`);
  if (saved) {
    const body = JSON.parse(saved);
    const entry = (body.entries ?? []).find(item => String(item.text ?? '').includes(MARK));
    console.log(`载荷中命中条目：${JSON.stringify({ rowId: entry?.rowId, text: entry?.text, pinyin: entry?.pinyin })}`);
  }
  results.push({ label: '保存载荷原文（节选）', ok: true, detail: (saved ?? '').slice(0, 600) });
} catch (error) {
  check('脚本执行完成', false, error?.message ?? String(error));
} finally {
  await stopChrome(chrome, profileDir);
  await removeProfileDir(profileDir);
  await writeFile(join(outDir, 'synthetic-input-smoke.json'), `${JSON.stringify({ mark: MARK, results }, null, 2)}\n`);
}
console.log(`\n通过 ${results.filter(r => r.ok).length}/${results.filter(r => r.label !== '保存载荷原文（节选）').length}`);
process.exitCode = results.every(r => r.ok) ? 0 : 1;
