#!/usr/bin/env node
/**
 * 管理端浏览器验收驱动（**只允许回环目标**）。
 *
 * 覆盖两块审查要求：
 * - 任务 A：四题型草稿预览 —— 真实生成 PDF、记录实际页数、真实下载、打印预览窗口、
 *   "页面未全部准备完成不打印"、"窗口关闭即取消"、"先保存再预览"的修订一致性、范围标注。
 * - 任务 B：五尺寸响应式、深色主题、键盘路径。
 *
 * 设计原则：
 * 1. 只断言**可观察结果**（真实 PDF 字节被 pdf-lib 解析出的页数、真实落盘的下载文件、
 *    打印窗口的真实 DOM 状态），不把"点到了按钮"当成通过；
 * 2. 目标非回环直接退出码 2，绝不触碰生产；
 * 3. 截图全部来自真实 Chrome（CDP `Page.captureScreenshot`）。
 *
 * 用法：
 *   E2E_TARGET=http://127.0.0.1:4173 node tests/e2e/browser-acceptance.mjs
 *   E2E_ONLY=widths,dark E2E_TARGET=http://127.0.0.1:4173 node tests/e2e/browser-acceptance.mjs
 */

import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PDFDocument } from 'pdf-lib';
import {
  centerOf, clickPoint, connect, createProbe, evaluate, findChrome, launchChrome,
  sleep, waitFor
} from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const port = Number(process.env.E2E_CDP_PORT ?? 9455);
const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const evidenceDir = process.env.E2E_EVIDENCE_DIR
  ?? join(repoRoot, 'docs/verification/toolbox-admin-completion-2026-10-07');
const shotsDir = join(evidenceDir, 'screenshots');
const pdfsDir = join(evidenceDir, 'pdfs');
const logsDir = join(evidenceDir, 'evidence', 'browser');
const only = (process.env.E2E_ONLY ?? '').split(',').map(item => item.trim()).filter(Boolean);
const wants = section => only.length === 0 || only.includes(section);

/** 语文样册（语文两种题型）。 */
const CHINESE_EDITION = 'accept-cn-2026a';
/** 英语样册（英语两种题型）。 */
const ENGLISH_EDITION = 'accept-en-2026a';

/** 四种题型：教材、题型取值、界面名称。 */
const MODE_MATRIX = [
  { edition: CHINESE_EDITION, mode: 'chinese-dictation', label: '汉字字帖' },
  { edition: CHINESE_EDITION, mode: 'chinese-pinyin', label: '拼音字帖' },
  { edition: ENGLISH_EDITION, mode: 'english-dictation', label: '英语默写' },
  { edition: ENGLISH_EDITION, mode: 'english-meaning', label: '中文释义练习' }
];

const results = [];
const transcript = [];

/** 同时打印并留档。 */
function log(line = '') {
  transcript.push(line);
  console.log(line);
}

/** 记录一条断言。 */
function check(name, ok, detail = '') {
  results.push({ name, ok: Boolean(ok), detail });
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
  return Boolean(ok);
}

/**
 * 记录一条**环境**观测（不计入产品通过率与退出码）。
 *
 * 用于"验收期间源码被并发修改"这类与产品行为无关、但会影响证据有效性的条件：
 * 必须如实写出来，但不能把它算成产品失败。
 */
function checkEnv(name, ok, detail = '') {
  results.push({ name, ok: Boolean(ok), env: true, detail });
  log(`${ok ? 'ENV-OK' : 'ENV-WARN'}  ${name}${detail ? ` — ${detail}` : ''}`);
  return Boolean(ok);
}

/** 章节包装：单节异常不中止其余章节，但如实记录。 */
async function section(id, title, body) {
  if (!wants(id)) {
    log(`\n===== 跳过章节 ${id}（E2E_ONLY 未包含）=====`);
    return;
  }
  log(`\n===== 章节 ${id}：${title} =====`);
  try {
    await body();
  } catch (cause) {
    check(`章节 ${id} 完整执行`, false, `异常中止：${cause?.message ?? cause}`);
    log(String(cause?.stack ?? cause));
  }
}

const sleepSafe = ms => sleep(ms);

/* ------------------------------------------------------------------ 基础工具 */

/** 截图到 evidence/screenshots。 */
async function shot(cdp, name, options = {}) {
  const params = { format: 'png' };
  if (options.full) params.captureBeyondViewport = true;
  const { data } = await cdp.send('Page.captureScreenshot', params, options.sessionId);
  const buffer = Buffer.from(data, 'base64');
  const file = join(shotsDir, `${name}.png`);
  await writeFile(file, buffer);
  log(`  截图 ${file}（${buffer.byteLength} 字节）`);
  return file;
}

/** 设定精确视口宽度。 */
async function setViewport(cdp, width, height = 900) {
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile: false
  });
  await sleepSafe(400);
}

/** 清除视口覆盖，恢复窗口尺寸。 */
async function clearViewport(cdp) {
  await cdp.send('Emulation.clearDeviceMetricsOverride');
  await sleepSafe(300);
}

/** 导航并在条件不成立时重试（hash 路由切换可能不重建组件）。 */
async function navigateUntil(cdp, url, condition, label, attempts = 3, timeoutMs = 60000) {
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    await cdp.send('Page.navigate', { url });
    const ok = await waitFor(cdp, condition, label, timeoutMs).then(() => true).catch(() => false);
    if (ok) return true;
    if (attempt < attempts) await sleepSafe(2500);
  }
  return false;
}

/** 等待某个布尔表达式为真，返回布尔值而不抛错。 */
async function tryWait(cdp, expression, label, timeoutMs = 60000) {
  return await waitFor(cdp, expression, label, timeoutMs).then(() => true).catch(() => false);
}

/** 用真实鼠标点击某个 CSS 选择器命中的元素。 */
async function clickSelector(cdp, expression) {
  const point = await centerOf(cdp, expression);
  if (!point) return false;
  await clickPoint(cdp, point);
  return true;
}

/**
 * 取"已经静止"的元素中心点。
 *
 * Naive UI 的弹层有 ~0.3s 的缩放／淡入动画，动画期间元素的 rect 一直在变，
 * 先测量再派发鼠标事件会点到已经移开的位置（表现为"点了但没反应"）。
 * 这里连续两次测量一致才认为元素已静止。
 */
async function stableCenterOf(cdp, expression) {
  let previous = null;
  for (let attempt = 0; attempt < 14; attempt += 1) {
    const point = await centerOf(cdp, expression).catch(() => null);
    if (point && previous && Math.abs(point.x - previous.x) < 2 && Math.abs(point.y - previous.y) < 2) {
      return point;
    }
    previous = point;
    await sleepSafe(180);
  }
  return previous;
}

/** 点击一个已静止的元素。 */
async function clickStable(cdp, expression) {
  const point = await stableCenterOf(cdp, expression);
  if (!point) return false;
  await clickPoint(cdp, point);
  return true;
}

/** 反复点击直到条件成立（应对动画与异步渲染）。 */
async function clickUntil(cdp, expression, predicate, label, attempts = 6) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    await clickStable(cdp, expression);
    await sleepSafe(900);
    const ok = await evaluate(cdp, `return Boolean(${predicate});`).catch(() => false);
    if (ok) return { ok: true, attempts: attempt + 1 };
  }
  return { ok: false, attempts };
}

/** 找到文本包含给定片段的按钮并点击（真实鼠标，元素静止后点击）。 */
async function clickButtonByText(cdp, text, scope = 'document') {
  // 作用域表达式必须整体加括号：调用方会传 `document.querySelector('.x') ?? document`。
  const expression = `[...( ${scope} ).querySelectorAll('.n-button, button, a')]
    .filter(el => el.offsetParent !== null)
    .find(el => (el.textContent ?? '').replace(/\\s+/g, '').includes(${JSON.stringify(text.replace(/\s+/g, ''))}))`;
  const found = await evaluate(cdp, `return Boolean(${expression});`);
  if (!found) return false;
  return await clickStable(cdp, expression);
}

/** 文本定位按钮的表达式（供 clickUntil 复用）。 */
function buttonByTextExpression(text, scope = 'document') {
  return `[...( ${scope} ).querySelectorAll('.n-button, button, a')]
    .filter(el => el.offsetParent !== null)
    .find(el => (el.textContent ?? '').replace(/\\s+/g, '').includes(${JSON.stringify(text.replace(/\s+/g, ''))}))`;
}

/** 把焦点轨迹压成一行可读文本。 */
function formatTrail(trail) {
  return trail.map(item => (item ? `${item.tag}${item.label ? `「${item.label}」` : ''}` : 'null')).join(' → ');
}

/** 派发一个按键（rawKeyDown + keyUp）。 */
async function press(cdp, key, code, vk) {
  const common = { key, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk };
  await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...common });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', ...common });
  await sleepSafe(160);
}

const pressTab = cdp => press(cdp, 'Tab', 'Tab', 9);
const pressEnter = cdp => press(cdp, 'Enter', 'Enter', 13);
const pressEscape = cdp => press(cdp, 'Escape', 'Escape', 27);
const pressArrowDown = cdp => press(cdp, 'ArrowDown', 'ArrowDown', 40);

/** Shift+Tab：反向遍历焦点。 */
async function pressShiftTab(cdp) {
  const common = { key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9, modifiers: 8 };
  await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...common });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', ...common });
  await sleepSafe(160);
}

/** 键盘文本输入（CDP 文本输入通道，等价于键盘/输入法输入）。 */
async function typeText(cdp, text) {
  await cdp.send('Input.insertText', { text });
  await sleepSafe(300);
}

/** 当前焦点元素的可读描述。 */
const ACTIVE_DESCRIPTOR = `
  const el = document.activeElement;
  if (!el) return null;
  const raw = (el.getAttribute('aria-label') || el.getAttribute('placeholder')
    || (el.innerText || '').replace(/\\s+/g, ' ').trim() || el.value || '').toString();
  return {
    tag: el.tagName,
    cls: (el.className ?? '').toString().split(' ').filter(Boolean).slice(0, 3).join('.'),
    label: raw.replace(/\\s+/g, ' ').trim().slice(0, 48),
    disabled: Boolean(el.disabled)
  };
`;

/** 按 Tab（或 Shift+Tab）直到焦点满足条件。 */
async function tabUntil(cdp, predicate, label, maxSteps = 80, options = {}) {
  const trail = [];
  for (let index = 0; index < maxSteps; index += 1) {
    if (options.back) await pressShiftTab(cdp);
    else await pressTab(cdp);
    const active = await evaluate(cdp, ACTIVE_DESCRIPTOR);
    trail.push(active);
    const matched = await evaluate(cdp, `
      const el = document.activeElement;
      return Boolean(el && (${predicate}));
    `);
    if (matched) return { trail, active, steps: index + 1 };
  }
  return { trail, active: null, steps: maxSteps };
}

/**
 * 计算前端源码的指纹（相对路径 + 大小 + mtime）。
 *
 * 为什么需要它：本仓库的 `toolbox-web/` 是**未跟踪目录**，验收期间可能有其它工作线
 * 正在改它的源码；Vite dev 的 HMR 会因此在验收中途重建组件，把"预览区块"整块换掉，
 * 产生与产品无关的失败。每次验收前后比对这个指纹，才能判断结果是否对应同一个快照。
 *
 * @param root 源码根目录
 * @returns 文件数与摘要
 */
async function sourceFingerprint(root) {
  const files = [];
  const walk = async directory => {
    for (const entry of await readdir(directory, { withFileTypes: true }).catch(() => [])) {
      const full = join(directory, entry.name);
      if (entry.isDirectory()) await walk(full);
      else if (entry.isFile()) files.push(full);
    }
  };
  await walk(root);
  const hash = createHash('sha256');
  const stamps = {};
  for (const file of files.sort()) {
    const info = await stat(file);
    hash.update(`${file}:${info.size}:${info.mtimeMs}\n`);
    stamps[file] = `${info.size}@${Math.round(info.mtimeMs)}`;
  }
  return { count: files.length, digest: hash.digest('hex').slice(0, 16), stamps };
}

/* ------------------------------------------------------------ 管理会话与数据 */

/** 通过界面登录本地假身份管理员（真实点击 + 真实输入）。 */
async function loginViaUi(cdp, account = 'local-admin', password = 'local-admin') {
  const ready = await tryWait(cdp, `!!document.querySelector('.admin-entry')`, '首页身份入口', 60000);
  if (!ready) throw new Error('首页未渲染出 .admin-entry');
  const entryText = await evaluate(cdp, `return document.querySelector('.admin-entry')?.innerText?.trim() ?? '';`);
  if (!entryText.includes('管理员登录')) {
    log(`  首页身份入口已经是「${entryText}」，无需重复登录。`);
    return true;
  }
  await clickStable(cdp, `document.querySelector('.admin-entry')`);
  const modalOpen = await tryWait(cdp, `!!document.querySelector('.n-modal [aria-label="用户名或邮箱"]')`, '登录弹窗', 20000);
  if (!modalOpen) {
    const notices = await evaluate(cdp, `return document.body.innerText.replace(/\\s+/g, ' ').slice(0, 200);`);
    throw new Error(`点击「管理员登录」后弹窗未出现；页面文本=「${notices}」`);
  }
  // 等弹窗的进入动画结束：动画期间的 rect 会移动，点击会落空。
  await sleepSafe(1500);
  await clickStable(cdp, `document.querySelector('.n-modal [aria-label="用户名或邮箱"]')`);
  await typeText(cdp, account);
  await clickStable(cdp, `document.querySelector('.n-modal [aria-label="密码"]')`);
  await typeText(cdp, password);
  const fields = await evaluate(cdp, `
    return {
      account: document.querySelector('.n-modal [aria-label="用户名或邮箱"]')?.value ?? null,
      password: document.querySelector('.n-modal [aria-label="密码"]')?.value ?? null
    };
  `);
  if (fields.account !== account || fields.password !== password) {
    throw new Error(`登录表单未收到键盘输入：${JSON.stringify(fields)}`);
  }
  const submit = await clickUntil(
    cdp,
    buttonByTextExpression('登录', `document.querySelector('.n-modal') ?? document`),
    `!!document.querySelector('.admin-name')`,
    '登录提交',
    6
  );
  if (!submit.ok) {
    const error = await evaluate(cdp, `
      return {
        loginError: document.querySelector('.login-error')?.innerText?.trim() ?? '',
        modalOpen: !!document.querySelector('.n-modal'),
        fields: {
          account: document.querySelector('.n-modal [aria-label="用户名或邮箱"]')?.value ?? null,
          password: document.querySelector('.n-modal [aria-label="密码"]')?.value ?? null
        }
      };
    `);
    throw new Error(`登录未成功（尝试 ${submit.attempts} 次）；界面状态=${JSON.stringify(error)}`);
  }
  log(`  已登录（第 ${submit.attempts} 次点击提交成功）：${await evaluate(cdp, `return document.querySelector('.admin-name')?.innerText?.trim() ?? '';`)}`);
  return true;
}

/** 在页面里 GET 管理接口（同源 fetch，自动带会话 Cookie）。 */
async function adminGet(cdp, path) {
  return await evaluate(cdp, `
    const response = await fetch(${JSON.stringify(path)}, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    const text = await response.text();
    let body = null;
    try { body = JSON.parse(text); } catch { /* 非 JSON */ }
    return { status: response.status, body };
  `);
}

/** 打开教材编辑页并等待编辑器与修订号就绪。 */
async function openEditor(cdp, editionId) {
  const url = `${base}/#/admin/textbooks/${editionId}/edit`;
  // 先离开站点：hash 路由下同址导航不会重建组件，上一次的预览产物与题型会残留，
  // 于是"第二种题型"实际会打印到第一种题型的旧产物上。
  await cdp.send('Page.navigate', { url: 'about:blank' });
  await sleepSafe(600);
  const ok = await navigateUntil(
    cdp, url,
    `!!document.querySelector('.admin-editor') && /修订\\s*\\d+/.test(document.querySelector('.workspace-head')?.innerText ?? '')`,
    `${editionId} 编辑器`
  );
  if (!ok) throw new Error(`编辑页 ${url} 未就绪`);
  await sleepSafe(1200);
  return await evaluate(cdp, `
    const head = document.querySelector('.workspace-head')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '';
    const match = /修订\\s*(\\d+)/.exec(head);
    return { head, revision: match ? Number(match[1]) : null,
      subject: document.querySelector('[aria-label="学科"]')?.innerText?.trim() ?? '',
      entryCount: document.querySelector('.workspace-head .n-tag')?.innerText?.trim() ?? '' };
  `);
}

/* ---------------------------------------------------------------- 预览与打印 */

/** 选择预览题型（真实鼠标操作 NSelect；动画期间点击会落空，因此自己实现重试与诊断）。 */
async function selectPreviewMode(cdp, label) {
  const selectionExpression = `document.querySelector('.preview-settings .n-select')?.querySelector('.n-base-selection')`;
  const optionExpression = `[...document.querySelectorAll('.n-base-select-option')]
    .find(o => o.textContent.trim() === ${JSON.stringify(label)})`;
  let lastOptions = [];
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const applied = await evaluate(cdp, `
      return (document.querySelector('.preview-settings')?.innerText ?? '').includes(${JSON.stringify(`题型：${label}`)});
    `);
    if (applied) return { opened: true, applied: true, attempts: attempt, options: lastOptions };
    await clickStable(cdp, selectionExpression);
    await sleepSafe(900);
    lastOptions = await evaluate(cdp, `
      return [...document.querySelectorAll('.n-base-select-option')].map(o => o.textContent.trim());
    `);
    const optionPoint = await stableCenterOf(cdp, optionExpression);
    if (optionPoint) {
      await clickPoint(cdp, optionPoint);
    } else {
      // 鼠标路径落空时的兜底：对已渲染的选项派发合成 click。
      await evaluate(cdp, `
        ${optionExpression}?.click();
        return true;
      `);
    }
    await sleepSafe(1200);
  }
  const applied = await evaluate(cdp, `
    return (document.querySelector('.preview-settings')?.innerText ?? '').includes(${JSON.stringify(`题型：${label}`)});
  `);
  return { opened: lastOptions.length > 0, applied, attempts: 8, options: lastOptions };
}

/** 触发草稿预览并等待画布出现。 */
async function generatePreview(cdp) {
  // 已打开的预览区块必须关掉再重开：否则"预览区块已存在"会让等待立即通过，
  // 读到的是上一次题型／范围的旧产物。
  const alreadyOpen = await evaluate(cdp, `return Boolean(document.querySelector('.preview-block'));`);
  if (alreadyOpen) {
    await clickStable(cdp, buttonByTextExpression('关闭预览', `document.querySelector('.preview-block')`));
    await tryWait(cdp, `!document.querySelector('.preview-block')`, '关闭上一次预览', 15000);
    await sleepSafe(600);
  }
  const clicked = await clickUntil(
    cdp,
    buttonByTextExpression('预览草稿'),
    `!!document.querySelector('.preview-block')`,
    '预览草稿',
    4
  );
  if (!clicked.ok) {
    const notice = await evaluate(cdp, `
      return {
        alerts: [...document.querySelectorAll('.editor-alert')].map(el => el.innerText.replace(/\\s+/g, ' ').trim()).join(' | '),
        notice: document.querySelector('.notice')?.innerText ?? null
      };
    `);
    throw new Error(`点击「预览草稿」后没有出现预览区块；页面提示=${JSON.stringify(notice)}`);
  }
  const painted = await tryWait(cdp, `
    (() => {
      const canvas = document.querySelector('.preview-block canvas');
      // 300×150 是 canvas 的默认尺寸，说明还没有被真正绘制过。
      return Boolean(canvas) && canvas.width > 400 && canvas.height > 400
        && !document.querySelector('.preview-block .paper-overlay');
    })()
  `, '预览画布完成绘制', 120000);
  const snapshot = await evaluate(cdp, `
    const block = document.querySelector('.preview-block');
    const canvas = block?.querySelector('canvas');
    const overlay = block?.querySelector('.paper-overlay');
    return {
      painted: Boolean(canvas) && canvas.width > 400 && canvas.height > 400 && !overlay,
      canvas: canvas ? { width: canvas.width, height: canvas.height } : null,
      overlay: overlay ? (overlay.className + '::' + (overlay.innerText ?? '').replace(/\\s+/g, ' ').slice(0, 80)) : null,
      snapshotText: block?.querySelector('.preview-snapshot')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '',
      scopeNotice: block ? (document.querySelector('.scope-limit')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '') : '',
      settings: document.querySelector('.preview-settings')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '',
      headButtons: [...(block?.querySelectorAll('.preview-head .n-button') ?? [])].map(b => b.innerText.trim())
    };
  `);
  if (!painted) log(`  警告：预览画布未在超时内完成绘制（overlay=${snapshot.overlay}）`);
  return snapshot;
}

/** 给 window.open 装上捕获器与打印计数探针。 */
async function armPrintCapture(cdp) {
  return await evaluate(cdp, `
    window.__printCalls = 0;
    window.__openCalls = 0;
    window.__openResult = 'none';
    window.__printTimeline = [];
    window.__earlyPrintProbe = { attempted: false };
    if (!window.__origOpen) window.__origOpen = window.open.bind(window);
    window.open = (...args) => {
      window.__openCalls += 1;
      const opened = window.__origOpen(...args);
      window.__openResult = opened ? 'window' : 'null';
      if (!opened) return opened;
      // 打印调用探针：requestPrint 会调用 target.print()，这里只计数，不真的弹系统打印。
      try { opened.print = () => { window.__printCalls += 1; }; } catch { /* 跨窗口不可写 */ }
      const timeline = window.__printTimeline;
      const probe = window.__earlyPrintProbe;
      // 关键点：观察器在 window.open 返回的**同一同步栈**里注册。
      // 工具栏是随后同步写入的，页图是异步逐页追加的，因此"未就绪但工具栏已在"
      // 这个瞬间必然会被观察到——不必和渲染速度赛跑。
      const sample = () => {
        let snapshot;
        try {
          const doc = opened.document;
          const button = doc.querySelector('.print-actions button.primary');
          snapshot = {
            t: Math.round(performance.now()),
            toolbar: doc.querySelectorAll('.print-actions').length,
            ready: doc.body.classList.contains('print-ready'),
            images: doc.querySelectorAll('img.page').length,
            disabled: button ? Boolean(button.disabled) : null
          };
        } catch (cause) {
          snapshot = { error: String(cause) };
        }
        timeline.push(snapshot);
        // 第一次看到"工具栏已出现、尚未就绪"时，强行启用被禁用的打印按钮并点击。
        // 这验证的是 requestPrint 内部的 ready 守卫，而不只是 disabled 属性。
        if (!probe.attempted && snapshot.toolbar === 1 && snapshot.ready === false && snapshot.disabled === true) {
          probe.attempted = true;
          probe.readyAtAttempt = snapshot.ready;
          probe.disabledAtAttempt = snapshot.disabled;
          probe.imagesAtAttempt = snapshot.images;
          probe.printCallsBefore = window.__printCalls;
          try {
            const button = opened.document.querySelector('.print-actions button.primary');
            button.disabled = false;
            button.click();
            probe.clickedAt = Math.round(performance.now());
          } catch (cause) {
            probe.error = String(cause);
          }
          // 关键：如果守卫失效，这次点击会让 __printCalls 变成 1。
        }
        return snapshot;
      };
      try {
        const observer = new MutationObserver(sample);
        observer.observe(opened.document.documentElement, {
          childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'disabled']
        });
        window.__printObserver = observer;
        sample();
      } catch (cause) {
        window.__patchError = String(cause);
      }
      return opened;
    };
    return true;
  `);
}

/** 列出当前所有 page target 的 id。 */
async function pageTargetIds(cdp) {
  const { targetInfos } = await cdp.send('Target.getTargets');
  return targetInfos.filter(info => info.type === 'page').map(info => info.targetId);
}

/** 在指定的 target 会话里求值。 */
async function evaluateOn(cdp, sessionId, expression) {
  const result = await cdp.send('Runtime.evaluate', {
    expression: `(async () => { ${expression} })()`,
    awaitPromise: true,
    returnByValue: true,
    userGesture: true
  }, sessionId);
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  }
  return result.result.value;
}

/**
 * 点击「打印预览」并附加到打印窗口的独立 target。
 *
 * 为什么必须用 target 会话而不是主页面的 `window.__printWindow`：
 * `createPrintPreview` 会执行 `target.opener = null`，此后主页面读不到那个窗口的
 * document（不是同源问题，而是 opener 断链），而 CDP 的 target 会话不受影响。
 *
 * @returns {{sessionId: string, targetId: string} | null}
 */
async function openPrintWindow(cdp) {
  await armPrintCapture(cdp);
  const expression = buttonByTextExpression('打印预览', `document.querySelector('.preview-block') ?? document`);
  const before = await pageTargetIds(cdp);
  let popup = null;
  for (let attempt = 0; attempt < 5 && !popup; attempt += 1) {
    await clickStable(cdp, expression);
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline && !popup) {
      const { targetInfos } = await cdp.send('Target.getTargets');
      const fresh = targetInfos.find(info => info.type === 'page'
        && !before.includes(info.targetId)
        && !info.url.includes('127.0.0.1'));
      if (fresh) {
        const attached = await cdp.send('Target.attachToTarget', { targetId: fresh.targetId, flatten: true })
          .catch(() => null);
        if (attached?.sessionId) {
          await cdp.send('Page.enable', {}, attached.sessionId).catch(() => undefined);
          await cdp.send('Runtime.enable', {}, attached.sessionId).catch(() => undefined);
          popup = { sessionId: attached.sessionId, targetId: fresh.targetId };
          break;
        }
      }
      await sleepSafe(400);
    }
    if (!popup) await sleepSafe(1200);
  }
  if (!popup) {
    const diagnostic = await evaluate(cdp, `
      const button = ${expression};
      return {
        buttonFound: Boolean(button),
        buttonDisabled: button ? Boolean(button.disabled) : null,
        buttonRect: button ? (() => { const r = button.getBoundingClientRect(); return { w: Math.round(r.width), h: Math.round(r.height) }; })() : null,
        artifactPresent: Boolean(document.querySelector('.preview-block canvas')),
        openCalls: window.__openCalls,
        openResult: window.__openResult,
        previewOverlay: document.querySelector('.preview-block .paper-overlay')
          ? (document.querySelector('.preview-block .paper-overlay').innerText ?? '').replace(/\s+/g, ' ').slice(0, 120) : null
      };
    `);
    log(`  打印窗口未出现的诊断：${JSON.stringify(diagnostic)}`);
    return null;
  }
  // 工具栏可能在附加之后才写入，等它出现。
  const toolbar = await tryWait(cdp, `
    return Boolean(document.querySelector('.print-actions'));
  `, '打印窗口工具栏', 20000).catch(() => false);
  if (!toolbar) {
    await waitForSession(cdp, popup.sessionId, `!!document.querySelector('.print-actions')`, '打印窗口工具栏', 20000)
      .catch(() => undefined);
  }
  return popup;
}

/** 在指定会话里等待条件成立，返回布尔值而不抛错。 */
async function waitForSessionReady(cdp, sessionId, expression, label, timeoutMs = 60000) {
  return await waitForSession(cdp, sessionId, expression, label, timeoutMs).then(() => true).catch(() => false);
}

/** 关闭除主页面之外的所有 page target（打印窗口）。 */
async function closeExtraPageTargets(cdp) {
  const { targetInfos } = await cdp.send('Target.getTargets');
  for (const info of targetInfos) {
    if (info.type !== 'page') continue;
    if (info.url.includes('127.0.0.1')) continue;
    await cdp.send('Target.closeTarget', { targetId: info.targetId }).catch(() => undefined);
  }
  await sleepSafe(600);
}

/** 在指定会话里等待条件成立。 */
async function waitForSession(cdp, sessionId, expression, label, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await evaluateOn(cdp, sessionId, `return ${expression};`).catch(() => undefined);
    if (last) return last;
    await sleepSafe(400);
  }
  throw new Error(`等待「${label}」超时（最后一次取值 ${JSON.stringify(last)}）`);
}

/**
 * 打开打印预览并在**逐页渲染途中**关闭窗口，验证"关闭即取消"。
 *
 * 同样是靠 `window.open` 返回瞬间注册的观察器：一看到"工具栏已在、尚未就绪"
 * 就安排关闭，因此不依赖渲染速度。
 *
 * @returns 取消探针结果
 */
async function openPrintWindowClosedMidRender(cdp) {
  const before = await pageTargetIds(cdp);
  await evaluate(cdp, `
    window.__cancelProbe = { closedAt: null, samplesAtClose: null, lastSample: null, blobHref: null };
    if (!window.__origOpen) window.__origOpen = window.open.bind(window);
    window.open = (...args) => {
      const opened = window.__origOpen(...args);
      if (!opened) return opened;
      let fired = false;
      const sample = () => {
        try {
          const doc = opened.document;
          const toolbar = doc.querySelectorAll('.print-actions').length;
          const ready = doc.body.classList.contains('print-ready');
          const images = doc.querySelectorAll('img.page').length;
          const link = doc.querySelector('a[download]');
          if (link && link.href.startsWith('blob:')) window.__cancelProbe.blobHref = link.href;
          window.__cancelProbe.lastSample = { images, ready, t: Math.round(performance.now()) };
          if (!fired && toolbar === 1 && !ready) {
            fired = true;
            window.__cancelProbe.samplesAtClose = { images, ready };
            setTimeout(() => {
              try { opened.close(); } catch { /* 已关闭 */ }
              window.__cancelProbe.closedAt = Math.round(performance.now());
            }, 120);
          }
        } catch (cause) {
          window.__cancelProbe.error = String(cause);
        }
      };
      const observer = new MutationObserver(sample);
      observer.observe(opened.document.documentElement, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'disabled']
      });
      sample();
      return opened;
    };
    return true;
  `);
  await clickStable(cdp, buttonByTextExpression('打印预览', `document.querySelector('.preview-block') ?? document`));
  const cancelled = await tryWait(cdp, `window.__cancelProbe.closedAt !== null`, '逐页渲染途中关闭打印窗口', 30000);
  await sleepSafe(3000);
  const probe = await evaluate(cdp, `
    return { probe: window.__cancelProbe, printCalls: window.__printCalls, timelineStopped: true };
  `);
  const after = await pageTargetIds(cdp);
  return { cancelled, ...probe, extraTargets: after.filter(id => !before.includes(id)).length };
}

/** 读取打印窗口状态（在打印窗口自己的 target 会话里读）。 */
async function readPrintState(cdp, sessionId, mainCdp) {
  const state = await evaluateOn(cdp, sessionId, `
    const doc = document;
    const link = doc.querySelector('a[download]');
    const printButton = doc.querySelector('.print-actions button.primary');
    return {
      ready: doc.body.classList.contains('print-ready'),
      printDisabled: printButton ? Boolean(printButton.disabled) : null,
      summary: doc.querySelector('.page-summary')?.textContent?.trim() ?? '',
      pageImages: doc.querySelectorAll('img.page').length,
      decodedImages: [...doc.querySelectorAll('img.page')].filter(i => i.complete && i.naturalWidth > 0).length,
      statusText: doc.querySelector('.print-status')?.textContent?.trim() ?? '',
      buttons: [...doc.querySelectorAll('.print-actions button, .print-actions a')].map(b => b.textContent.trim()),
      downloadName: link?.getAttribute('download') ?? '',
      blobHref: link?.href ?? '',
      title: doc.title
    };
  `);
  state.openCalls = await evaluate(mainCdp, `return window.__openCalls;`).catch(() => null);
  state.printCalls = await evaluate(mainCdp, `return window.__printCalls;`).catch(() => null);
  return state;
}

/** 在打印窗口会话里点击工具栏的打印按钮，并回到主页面读打印计数。 */
async function clickPrintInWindow(cdp, sessionId, mainCdp) {
  await evaluateOn(cdp, sessionId, `document.querySelector('.print-actions button.primary').click(); return true;`);
  await sleepSafe(600);
  return await evaluate(mainCdp, `return window.__printCalls;`);
}

/** 从打印窗口的下载链接取回真实 PDF 字节（blob 地址由主页面创建，主页面可读）。 */
async function fetchPrintPdfBase64(cdp, sessionId, mainCdp) {
  const href = await evaluateOn(cdp, sessionId, `
    const link = document.querySelector('a[download]');
    return link ? link.href : null;
  `).catch(() => null);
  if (!href) return { ok: false, reason: '打印窗口没有下载链接' };
  const fetched = await evaluate(mainCdp, `
    try {
      const response = await fetch(${JSON.stringify(href)});
      if (!response.ok) return { ok: false, reason: 'HTTP ' + response.status };
      const buffer = await response.arrayBuffer();
      const bytes = new Uint8Array(buffer);
      let binary = '';
      const chunk = 0x8000;
      for (let index = 0; index < bytes.length; index += chunk) {
        binary += String.fromCharCode.apply(null, bytes.subarray(index, index + chunk));
      }
      return { ok: true, base64: btoa(binary), bytes: bytes.length, href: ${JSON.stringify(href)} };
    } catch (cause) {
      return { ok: false, reason: String(cause && cause.message ? cause.message : cause) };
    }
  `);
  return fetched;
}

/** 关闭打印窗口的 target。 */
async function closePrintWindow(cdp, popup, mainCdp) {
  if (!popup) return { closed: true };
  await cdp.send('Target.closeTarget', { targetId: popup.targetId }).catch(() => undefined);
  await sleepSafe(1200);
  const stillThere = await pageTargetIds(cdp);
  return { closed: !stillThere.includes(popup.targetId), openCalls: null };
}

/** 用 pdf-lib 解析真实字节。 */
async function inspectPdfBytes(buffer) {
  const document = await PDFDocument.load(buffer);
  const { width, height } = document.getPage(0).getSize();
  return {
    pages: document.getPageCount(),
    widthMm: Number((width * 25.4 / 72).toFixed(1)),
    heightMm: Number((height * 25.4 / 72).toFixed(1))
  };
}

/** 等待下载目录出现新文件。 */
async function waitForDownload(directory, before, timeoutMs = 45000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const now = (await readdir(directory).catch(() => [])).filter(name => !name.endsWith('.crdownload'));
    const fresh = now.filter(name => !before.includes(name));
    if (fresh.length) return fresh.sort().pop();
    await sleepSafe(500);
  }
  return null;
}

/* ------------------------------------------------------------------ 任务 A */

/** 一种题型的完整链路。 */
async function runMode(cdp, downloads, spec, index) {
  const tag = `${index + 1}-${spec.mode}`;
  log(`\n--- 题型 ${spec.mode}（${spec.label}，教材 ${spec.edition}）---`);
  const editor = await openEditor(cdp, spec.edition);
  log(`  编辑器：${editor.head}；条目数=${editor.entryCount}`);

  // 题型选择：必须真的切到目标题型（默认是学科的第一种）。
  const selection = await selectPreviewMode(cdp, spec.label);
  check(`A/${spec.mode}：侧面板可选择题型「${spec.label}」`, selection.applied,
    `下拉选项=${JSON.stringify(selection.options)} 尝试=${selection.attempts} 生效=${selection.applied}`);
  if (!selection.applied) return null;

  await shot(cdp, `A-editor-${tag}`);

  const preview = await generatePreview(cdp);
  check(`A/${spec.mode}：草稿预览完成绘制`, preview.painted,
    `canvas=${JSON.stringify(preview.canvas)} 遮挡=${preview.overlay ?? '无'} 按钮=${preview.headButtons.join('/')}`);
  check(`A/${spec.mode}：预览标注本次快照（修订号与范围）`,
    /修订\s*\d+/.test(preview.snapshotText),
    `快照标注=「${preview.snapshotText}」 设置栏=「${preview.settings}」`);
  await shot(cdp, `A-preview-${tag}`, { full: false });

  // 打印预览窗口 → 反查真实页数、真实 PDF 字节、真实下载。
  const popup = await openPrintWindow(cdp);
  check(`A/${spec.mode}：可打开打印预览窗口（独立 target）`, Boolean(popup),
    popup ? `target=${popup.targetId.slice(0, 8)}` : '窗口未出现');
  if (!popup) return { spec, preview, pages: null };

  const early = await readPrintState(cdp, popup.sessionId, cdp);
  const earlySummaryPages = Number((/(\d+)\s*页/.exec(early.summary) ?? [])[1] ?? 0);
  log(`  打印窗口初始状态（附加后读取）：就绪=${early.ready} 打印按钮禁用=${early.printDisabled} 页图=${early.pageImages}/${earlySummaryPages} 状态=「${early.statusText}」`);

  const ready = await waitForSessionReady(cdp, popup.sessionId,
    `document.body.classList.contains('print-ready')`, '打印页全部准备完成', 120000);
  const state = await readPrintState(cdp, popup.sessionId, cdp);
  const summaryPages = Number((/(\d+)\s*页/.exec(state.summary) ?? [])[1] ?? 0);
  check(`A/${spec.mode}：全部页面准备完成后才就绪（print-ready + 打印按钮启用）`,
    ready && state.ready === true && state.printDisabled === false,
    `就绪=${state.ready} 打印按钮禁用=${state.printDisabled} 页图=${state.pageImages}/${summaryPages} 已解码=${state.decodedImages} 页数文案=「${state.summary}」`);
  check(`A/${spec.mode}：打印窗口逐页渲染出可打印纸张`,
    state.pageImages === summaryPages && state.decodedImages === summaryPages && summaryPages >= 1,
    `页图=${state.pageImages} 已解码=${state.decodedImages} 摘要页数=${summaryPages}`);
  await shot(cdp, `A-print-ready-${tag}`, { sessionId: popup.sessionId });

  // 门禁断言：观察器在"工具栏已出现、页图未齐"的瞬间强行启用按钮并点击。
  const gate = await evaluate(cdp, `
    return {
      probe: window.__earlyPrintProbe,
      timelineLength: window.__printTimeline.length,
      printCalls: window.__printCalls,
      first: window.__printTimeline[0] ?? null,
      notReadySamples: window.__printTimeline.filter(sample => sample.ready === false).length,
      maxImagesWhileNotReady: Math.max(0, ...window.__printTimeline.filter(sample => sample.ready === false).map(sample => sample.images ?? 0))
    };
  `);
  check(`A/${spec.mode}：页面未全部准备完成时点打印不触发打印`,
    gate.probe.attempted === true && gate.printCalls === 0,
    `未就绪时强行启用按钮并点击：尝试=${gate.probe.attempted} 当时页图=${gate.probe.imagesAtAttempt}/${summaryPages} 就绪=${gate.probe.readyAtAttempt} 禁用=${gate.probe.disabledAtAttempt}；`
    + `该次点击后 print 调用次数=${gate.printCalls}（未就绪采样 ${gate.notReadySamples} 次，未就绪时最多已渲染 ${gate.maxImagesWhileNotReady} 页）`);
  if (gate.probe.attempted) {
    await shot(cdp, `A-print-rendering-${tag}`, { sessionId: popup.sessionId });
  }

  // 就绪后点击打印，验证调用链确实走到 target.print()。
  const printCalls = await clickPrintInWindow(cdp, popup.sessionId, cdp);
  check(`A/${spec.mode}：就绪后点击打印确实发起打印`, printCalls === 1,
    `print 调用次数=${printCalls}`);

  // 真实 PDF 字节（同一份文件）落盘并用 pdf-lib 解析页数。
  const fetched = await fetchPrintPdfBase64(cdp, popup.sessionId, cdp);
  let parsed = null;
  let pdfFile = null;
  if (fetched.ok) {
    const buffer = Buffer.from(fetched.base64, 'base64');
    pdfFile = join(pdfsDir, `${spec.mode}.pdf`);
    await writeFile(pdfFile, buffer);
    try {
      parsed = await inspectPdfBytes(buffer);
    } catch (cause) {
      log(`  pdf-lib 解析失败：${cause.message}`);
    }
    // 页数已知后重命名，让文件名体现题型与页数。
    if (parsed) {
      const renamed = join(pdfsDir, `${spec.mode}-${parsed.pages}pages.pdf`);
      await writeFile(renamed, buffer);
      pdfFile = renamed;
    }
    check(`A/${spec.mode}：打印窗口的 PDF 是真实文件且可被解析`,
      buffer.subarray(0, 5).toString('latin1') === '%PDF-' && parsed !== null,
      `${pdfFile} ${buffer.byteLength} 字节 页数=${parsed?.pages} 尺寸=${parsed?.widthMm}×${parsed?.heightMm}mm`);
    check(`A/${spec.mode}：PDF 页数与打印窗口页数一致`,
      parsed !== null && parsed.pages === summaryPages,
      `PDF=${parsed?.pages} 打印窗口=${summaryPages}`);
  } else {
    check(`A/${spec.mode}：打印窗口的 PDF 是真实文件且可被解析`, false, `取字节失败：${fetched.reason}`);
    check(`A/${spec.mode}：PDF 页数与打印窗口页数一致`, false, '没有 PDF 字节');
  }

  // 真实点击「导出 PDF」并确认文件落盘（用户可见的下载路径）。
  const before = (await readdir(downloads).catch(() => [])).filter(name => !name.endsWith('.crdownload'));
  await evaluateOn(cdp, popup.sessionId, `
    const link = document.querySelector('a[download]');
    if (link) link.click();
    return true;
  `).catch(cause => log(`  点击下载链接失败：${cause.message}`));
  const downloaded = await waitForDownload(downloads, before, 45000);
  if (downloaded) {
    const { readFile } = await import('node:fs/promises');
    const buffer = await readFile(join(downloads, downloaded));
    const info = buffer.subarray(0, 5).toString('latin1') === '%PDF-'
      ? await inspectPdfBytes(buffer).catch(() => null)
      : null;
    check(`A/${spec.mode}：下载按钮产出真实 PDF 文件`,
      info !== null,
      `${downloaded} ${buffer.byteLength} 字节 页数=${info?.pages}`);
  } else {
    check(`A/${spec.mode}：下载按钮产出真实 PDF 文件`, false, '点击后 45 秒内下载目录没有新文件');
  }

  // 关闭窗口即取消：blob 地址被回收（主页面再 fetch 同一地址必须失败）+ target 消失。
  const blobHref = state.blobHref;
  await closePrintWindow(cdp, popup, cdp);
  const revoked = await evaluate(cdp, `
    try {
      const response = await fetch(${JSON.stringify(blobHref)});
      return { fetched: true, ok: response.ok };
    } catch (cause) {
      return { fetched: false, reason: String(cause && cause.message ? cause.message : cause) };
    }
  `);
  const closedState = await evaluate(cdp, `
    return { openCalls: window.__openCalls, printCalls: window.__printCalls };
  `);
  check(`A/${spec.mode}：关闭打印预览窗口后专用 PDF 地址被回收`,
    revoked.fetched === false,
    `关闭后 fetch(blob) => ${JSON.stringify(revoked)}`);
  log(`  关闭后页面探针：${JSON.stringify(closedState)}`);

  // 逐页渲染途中关闭：页图不得再增长，地址同样被回收。
  const midRender = await openPrintWindowClosedMidRender(cdp);
  const midBlob = midRender.probe.blobHref;
  const midRevoked = midBlob
    ? await evaluate(cdp, `
      try { const response = await fetch(${JSON.stringify(midBlob)}); return { fetched: true, ok: response.ok }; }
      catch (cause) { return { fetched: false, reason: String(cause && cause.message ? cause.message : cause) }; }
    `)
    : { fetched: null, reason: '没有取到 blob 地址' };
  check(`A/${spec.mode}：逐页渲染途中关闭窗口即取消（页图停止增长）`,
    midRender.cancelled === true
      && midRender.probe.samplesAtClose?.ready === false
      && (midRender.probe.lastSample?.images ?? 99) < summaryPages
      && midRender.extraTargets === 0,
    `渲染途中状态=${JSON.stringify(midRender.probe.samplesAtClose)}；关闭后最终页图=${midRender.probe.lastSample?.images}（完整应为 ${summaryPages}）`
    + `；残留 target=${midRender.extraTargets}`);
  check(`A/${spec.mode}：逐页渲染途中关闭后 PDF 地址同样被回收`,
    midRevoked.fetched === false,
    `关闭后 fetch(blob) => ${JSON.stringify(midRevoked)}`);

  // 主预览不受打印窗口关闭影响（重试，避免恰好落在重绘中途）。
  let previewAlive = false;
  for (let attempt = 0; attempt < 12 && !previewAlive; attempt += 1) {
    previewAlive = await evaluate(cdp, `
      const canvas = document.querySelector('.preview-block canvas');
      return Boolean(canvas) && canvas.width > 400 && canvas.height > 400;
    `).catch(() => false);
    if (!previewAlive) await sleepSafe(600);
  }
  check(`A/${spec.mode}：关闭打印窗口不影响主预览`, previewAlive, `主预览画布仍在=${previewAlive}`);

  return { spec, preview, state, summaryPages, parsed, pdfFile, download: downloaded ?? null };
}

/* ------------------------------------------------------------------ 任务 B */

/** 测量页面响应式与命中区。 */
const MEASURE_PAGE = `
  const doc = document.documentElement;
  const buttons = [...document.querySelectorAll('.n-button, button')]
    .filter(el => el.offsetParent !== null)
    .map(el => {
      const rect = el.getBoundingClientRect();
      return {
        text: (el.innerText || el.getAttribute('aria-label') || '').replace(/\\s+/g, ' ').trim().slice(0, 24),
        w: Math.round(rect.width), h: Math.round(rect.height)
      };
    });
  const small = buttons.filter(b => b.h < 44 || b.w < 44);
  return {
    innerWidth: window.innerWidth,
    scrollWidth: doc.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth,
    overflowX: doc.scrollWidth - window.innerWidth,
    buttonCount: buttons.length,
    smallButtons: small.slice(0, 12),
    smallButtonCount: small.length,
    outlinePane: (() => {
      const pane = document.querySelector('.outline-pane');
      if (!pane) return null;
      const rect = pane.getBoundingClientRect();
      const style = getComputedStyle(pane);
      return { display: style.display, width: Math.round(rect.width), height: Math.round(rect.height), top: Math.round(rect.top), visible: rect.width > 0 && rect.height > 0 };
    })(),
    drawerButtonVisible: Boolean([...document.querySelectorAll('.n-button')].find(b => (b.innerText ?? '').includes('选择单元'))),
    headerButtons: [...document.querySelectorAll('.editor-header .n-button')].map(b => {
      const rect = b.getBoundingClientRect();
      return { text: b.innerText.trim(), h: Math.round(rect.height), w: Math.round(rect.width), inViewport: rect.top >= 0 && rect.top < window.innerHeight };
    }),
    mobileMenuVisible: Boolean(document.querySelector('.mobile-menu')?.offsetParent)
  };
`;

/** 深色主题下统计对比度不足的文本（自包含 IIFE，直接作为表达式求值）。 */
const CONTRAST_PROBE = `(() => {
  const parse = value => {
    const match = /rgba?\\(([^)]+)\\)/.exec(value ?? '');
    if (!match) return null;
    const parts = match[1].split(',').map(item => Number(item.trim()));
    return { r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1 };
  };
  const luminance = color => {
    const channel = value => {
      const v = value / 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
  };
  const background = element => {
    let node = element;
    while (node && node !== document.documentElement) {
      const color = parse(getComputedStyle(node).backgroundColor);
      if (color && color.a > 0.5) return color;
      node = node.parentElement;
    }
    const root = parse(getComputedStyle(document.body).backgroundColor);
    return root && root.a > 0 ? root : { r: 255, g: 255, b: 255, a: 1 };
  };
  const ratio = (a, b) => {
    const l1 = luminance(a); const l2 = luminance(b);
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  };
  const rows = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const seen = new Set();
  while (walker.nextNode()) {
    const text = (walker.currentNode.textContent ?? '').replace(/\\s+/g, ' ').trim();
    if (text.length < 2) continue;
    const element = walker.currentNode.parentElement;
    if (!element || !element.offsetParent) continue;
    const style = getComputedStyle(element);
    if (style.visibility === 'hidden' || Number(style.opacity) < 0.3) continue;
    const key = element.tagName + '|' + text.slice(0, 20);
    if (seen.has(key)) continue;
    seen.add(key);
    const color = parse(style.color);
    if (!color) continue;
    const bg = background(element);
    const value = ratio(color, bg);
    const size = Number.parseFloat(style.fontSize);
    const bold = Number(style.fontWeight) >= 600;
    const large = size >= 24 || (size >= 18.66 && bold);
    const threshold = large ? 3 : 4.5;
    if (value < threshold) rows.push({ text: text.slice(0, 32), ratio: Number(value.toFixed(2)), threshold, color: style.color, bg: 'rgb(' + [bg.r, bg.g, bg.b].join(',') + ')', size });
  }
  return { failing: rows.slice(0, 20), failingCount: rows.length };
})()`;

/* ------------------------------------------------------------------- 主流程 */

const workDir = await mkdtemp(join(tmpdir(), 'toolbox-admin-acceptance-'));
const downloads = join(workDir, 'downloads');

let chrome;
let cdp;
let exitCode = 1;
const started = new Date().toISOString();

await mkdir(shotsDir, { recursive: true });
await mkdir(pdfsDir, { recursive: true });
await mkdir(logsDir, { recursive: true });
await mkdir(downloads, { recursive: true });

const executablePath = findChrome();
if (!executablePath) {
  console.error('未找到 Chrome/Chromium，管理端浏览器验收未执行（可设置 CHROME_PATH）。退出码 2。');
  process.exit(2);
}

const sourceBefore = await sourceFingerprint(join(repoRoot, 'toolbox-web', 'src'));

log(`证据目录：${evidenceDir}`);
log(`目标：${base}`);
log(`浏览器：${executablePath}`);
log(`临时 profile：${join(workDir, 'chrome')}`);
log(`开始时间：${started}`);

try {
  chrome = await launchChrome({ executablePath, port, userDataDir: join(workDir, 'chrome') });
  cdp = await connect(port);
  const probe = createProbe(cdp);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.enable');
  await cdp.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: downloads });

  // 生产泄漏哨兵：出现非回环主机的请求就记为失败。
  const foreignRequests = [];
  cdp.on(message => {
    if (message.method === 'Network.requestWillBeSent') {
      const url = message.params?.request?.url ?? '';
      if (/^https?:/i.test(url) && !/^http:\/\/(127\.0\.0\.1|localhost|\[::1\])(:|\/)/i.test(url)) {
        foreignRequests.push(url);
      }
    }
  });

  await cdp.send('Page.navigate', { url: `${base}/#/` });
  await tryWait(cdp, `document.readyState === 'complete' && !!document.querySelector('.admin-entry')`, '首页就绪', 60000);
  await loginViaUi(cdp);

  /* ---------------- 任务 A：四题型 ---------------- */
  await section('modes', '任务 A：四题型草稿预览 / PDF / 打印预览', async () => {
    const summary = [];
    for (const [index, spec] of MODE_MATRIX.entries()) {
      const outcome = await runMode(cdp, downloads, spec, index);
      if (outcome) summary.push(outcome);
    }
    for (const item of summary) {
      check(`A/汇总：${item.spec.mode} 至少 3 页`,
        (item.parsed?.pages ?? item.summaryPages ?? 0) >= 3,
        `PDF 页数=${item.parsed?.pages} 打印窗口页数=${item.summaryPages} 文件=${item.pdfFile ?? '（无）'}`);
    }
    const names = summary.map(item => item.spec.mode);
    check('A/汇总：四种题型各生成一次', names.length === 4 && new Set(names).size === 4, names.join(', '));
    // 打印窗口全部关闭并还原 window.open，避免影响后续章节。
    await closeExtraPageTargets(cdp);
    await evaluate(cdp, `
      if (window.__origOpen) window.open = window.__origOpen;
      return true;
    `).catch(() => undefined);
  });

  /* ---------------- 任务 A2：编辑快照与范围 ---------------- */
  await section('snapshot', '任务 A：当前编辑快照（先保存再预览）与范围标注', async () => {
    const before = await adminGet(cdp, `/admin/v1/textbooks/${CHINESE_EDITION}/draft`);
    const revisionBefore = before.body?.data?.revision ?? null;
    const entriesBefore = before.body?.data?.draft?.entries?.length ?? null;
    check('A/快照：可读到预览前的服务器草稿修订', Number.isInteger(revisionBefore),
      `读回 HTTP ${before.status} revision=${revisionBefore} 条目=${entriesBefore}`);

    await openEditor(cdp, CHINESE_EDITION);
    const selection = await selectPreviewMode(cdp, '汉字字帖');
    check('A/快照：切到「汉字字帖」题型', selection.applied, `生效=${selection.applied}`);

    // **改内容但不点保存**：批量粘贴追加两条，随后直接点「预览草稿」。
    const pasted = await evaluate(cdp, `
      const area = document.querySelector('[aria-label="批量粘贴"] textarea, textarea[aria-label="批量粘贴"]');
      if (!area) return { ok: false, reason: '找不到批量粘贴输入框' };
      area.focus();
      return { ok: true };
    `);
    if (!pasted.ok) {
      check('A/快照：可以制造未保存的编辑内容', false, pasted.reason);
      return;
    }
    await typeText(cdp, '海洋\n山川');
    const parsed = await clickButtonByText(cdp, '解析并追加', `document.querySelector('.outline-pane') ?? document`);
    await sleepSafe(800);
    const dirty = await evaluate(cdp, `
      const head = document.querySelector('.workspace-head')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '';
      return { head, rows: document.querySelectorAll('.pair-row').length };
    `);
    check('A/快照：追加了未保存的内容', parsed, `点击解析并追加=${parsed} 工作区=「${dirty.head}」`);

    // 立刻预览（不点保存）→ 编辑器应先 flush 保存再预览。
    const preview = await generatePreview(cdp);
    const after = await adminGet(cdp, `/admin/v1/textbooks/${CHINESE_EDITION}/draft`);
    const revisionAfter = after.body?.data?.revision ?? null;
    const entriesAfter = after.body?.data?.draft?.entries?.length ?? null;
    const shown = Number((/修订\s*(\d+)/.exec(preview.snapshotText) ?? [])[1] ?? NaN);
    check('A/快照：未点保存直接预览时先 flush 保存（服务器修订号前进）',
      Number.isInteger(revisionAfter) && Number.isInteger(revisionBefore) && revisionAfter > revisionBefore,
      `预览前 revision=${revisionBefore} → 预览后 revision=${revisionAfter}，条目 ${entriesBefore} → ${entriesAfter}`);
    check('A/快照：预览标注的修订号与服务器当前修订一致',
      shown === revisionAfter,
      `界面标注「${preview.snapshotText}」解析修订=${shown} 服务器修订=${revisionAfter}`);
    const payload = await adminGet(cdp, `/admin/v1/textbooks/${CHINESE_EDITION}/draft/preview`);
    const payloadEntries = payload.body?.data?.entries?.length ?? null;
    check('A/快照：预览载荷来自刚保存的草稿（含新增条目）',
      payloadEntries === entriesAfter && payloadEntries > 0,
      `预览接口条目=${payloadEntries} 草稿条目=${entriesAfter} HTTP ${payload.status}`);
    await shot(cdp, 'A-snapshot-flushed-save');

    // 先记下「整册」范围的实际页数（当前预览就是整册）。
    const wholePopup = await openPrintWindow(cdp);
    let wholePages = null;
    if (wholePopup) {
      await waitForSessionReady(cdp, wholePopup.sessionId,
        `document.body.classList.contains('print-ready')`, '整册预览全部页就绪', 120000);
      const wholeSummary = await evaluateOn(cdp, wholePopup.sessionId,
        `return document.querySelector('.page-summary')?.textContent?.trim() ?? '';`).catch(() => '');
      wholePages = Number((/(\d+)\s*页/.exec(wholeSummary) ?? [])[1] ?? 0);
      await closePrintWindow(cdp, wholePopup, cdp);
    }

    // 选择范围：点「第一单元」，确认只做标注、仍按全册生成。
    const scopeSelected = await clickButtonByText(cdp, '第一单元', `document.querySelector('.outline-pane') ?? document`);
    await sleepSafe(600);
    const scopeInfo = await evaluate(cdp, `
      const settings = document.querySelector('.preview-settings')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '';
      const notice = document.querySelector('.scope-limit')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '';
      return { settings, notice };
    `);
    check('A/范围：选中单元后界面标注范围并声明仍按全册',
      scopeSelected && scopeInfo.settings.includes('第一单元') && scopeInfo.notice.includes('整册'),
      `设置栏=「${scopeInfo.settings}」 限制提示=「${scopeInfo.notice}」`);
    await shot(cdp, 'A-scope-unit-selected');

    const scopeRun = await generatePreview(cdp);
    const scopePopup = await openPrintWindow(cdp);
    let unitPages = null;
    let unitSummary = '';
    if (scopePopup) {
      await waitForSessionReady(cdp, scopePopup.sessionId,
        `document.body.classList.contains('print-ready')`, '范围预览全部页就绪', 120000);
      unitSummary = await evaluateOn(cdp, scopePopup.sessionId,
        `return document.querySelector('.page-summary')?.textContent?.trim() ?? '';`).catch(() => '');
      unitPages = Number((/(\d+)\s*页/.exec(unitSummary) ?? [])[1] ?? 0);
      await closePrintWindow(cdp, scopePopup, cdp);
    }
    const scopeText = await evaluate(cdp, `
      return document.querySelector('.preview-snapshot')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '';
    `);
    check('A/范围：单元范围的标注进入预览快照',
      scopeText.includes('范围') && scopeText.includes('第一单元'),
      `预览快照=「${scopeText}」`);
    check('A/范围：明确记录后端预览仍按全册（未做过滤）',
      (scopeRun.scopeNotice ?? '').includes('整册') && wholePages !== null && unitPages === wholePages,
      `界面限制提示=「${scopeRun.scopeNotice}」；整册页数=${wholePages} 单元范围页数=${unitPages}（相同即证明未过滤）`);
    log(`  范围对比：整册=${wholePages} 页，选「第一单元」后=${unitPages} 页（摘要「${unitSummary}」）。`);
  });

  /* ---------------- 任务 B1：五尺寸 ---------------- */
  await section('widths', '任务 B：320/375/768/1280/1440 五尺寸', async () => {
    const widths = [320, 375, 768, 1280, 1440];
    const pages = [
      {
        id: 'list',
        url: `${base}/#/admin/textbooks`,
        ready: `!!document.querySelector('.admin-list')`
      },
      {
        id: 'new',
        url: `${base}/#/admin/textbooks/new`,
        ready: `!!document.querySelector('.admin-editor')`
      },
      {
        id: 'edit',
        url: `${base}/#/admin/textbooks/${CHINESE_EDITION}/edit`,
        ready: `!!document.querySelector('.admin-editor') && !!document.querySelector('.preview-settings')`
      }
    ];
    for (const width of widths) {
      for (const page of pages) {
        await setViewport(cdp, width, 900);
        const ok = await navigateUntil(cdp, page.url, page.ready, `${page.id}@${width}`);
        if (!ok) {
          check(`B/尺寸：${page.id}@${width} 页面就绪`, false, '多次导航后仍未渲染');
          continue;
        }
        await sleepSafe(900);
        const metrics = await evaluate(cdp, MEASURE_PAGE);
        await shot(cdp, `B1-${page.id}-${width}w-light`);
        check(`B/尺寸：${page.id}@${width} 无横向溢出`,
          metrics.overflowX <= 1,
          `scrollWidth=${metrics.scrollWidth} innerWidth=${metrics.innerWidth} 溢出=${metrics.overflowX}px`);
        log(`  ${page.id}@${width}：按钮 ${metrics.buttonCount} 个，小于 44px 的 ${metrics.smallButtonCount} 个`
          + `${metrics.smallButtonCount ? `（示例 ${metrics.smallButtons.map(b => `${b.text}:${b.w}×${b.h}`).join(' ')}）` : ''}`);
        if (page.id === 'edit') {
          log(`  目录面板@${width}：${JSON.stringify(metrics.outlinePane)}；「选择单元」按钮=${metrics.drawerButtonVisible}；移动端菜单按钮=${metrics.mobileMenuVisible}`);
          log(`  操作栏按钮@${width}：${JSON.stringify(metrics.headerButtons)}`);
          if (width <= 375) {
            await evaluate(cdp, `
              const pane = document.querySelector('.outline-pane');
              pane?.scrollIntoView({ block: 'start' });
              window.scrollBy(0, -120);
              return true;
            `);
            await sleepSafe(500);
            await shot(cdp, `B1-${page.id}-${width}w-outline`);
          }
        }
      }
    }
    await clearViewport(cdp);
  });

  /* ---------------- 任务 B2：深色主题 ---------------- */
  await section('dark', '任务 B：深色主题', async () => {
    await clearViewport(cdp);
    const toggled = await evaluate(cdp, `
      const button = document.querySelector('[aria-label="切换深色模式"]');
      if (!button) return false;
      button.click();
      return true;
    `);
    const dark = await tryWait(cdp, `!!document.querySelector('.toolbox-app.is-dark')`, '深色主题生效', 15000);
    check('B/深色：可切换到深色主题', toggled && dark, `找到切换按钮=${toggled} 生效=${dark}`);
    if (!dark) return;
    await sleepSafe(800);

    const targets = [
      { id: 'list', url: `${base}/#/admin/textbooks`, ready: `!!document.querySelector('.admin-list')` },
      { id: 'new', url: `${base}/#/admin/textbooks/new`, ready: `!!document.querySelector('.admin-editor')` },
      { id: 'edit', url: `${base}/#/admin/textbooks/${CHINESE_EDITION}/edit`, ready: `!!document.querySelector('.admin-editor') && !!document.querySelector('.preview-settings')` }
    ];
    for (const target of targets) {
      await setViewport(cdp, 1280, 900);
      const ok = await navigateUntil(cdp, target.url, target.ready, `深色 ${target.id}`);
      if (!ok) {
        check(`B/深色：${target.id} 页面就绪`, false, '多次导航后仍未渲染');
        continue;
      }
      await sleepSafe(900);
      if (target.id === 'edit') {
        await selectPreviewMode(cdp, '汉字字帖');
        await generatePreview(cdp).catch(cause => log(`  深色预览生成失败：${cause.message}`));
        await sleepSafe(600);
      }
      const contrast = await evaluate(cdp, `return ${CONTRAST_PROBE};`);
      await shot(cdp, `B2-dark-${target.id}-1280w`);
      check(`B/深色：${target.id} 无对比度不足的可见文本`,
        contrast.failingCount === 0,
        contrast.failingCount === 0 ? '全部可见文本达到 WCAG AA 阈值' : `不足 ${contrast.failingCount} 处：${contrast.failing.slice(0, 5).map(item => `「${item.text}」${item.ratio}:1(需${item.threshold})`).join(' ')}`);
    }

    const back = await evaluate(cdp, `
      const button = document.querySelector('[aria-label="切换浅色模式"]');
      if (!button) return false;
      button.click();
      return true;
    `);
    const light = await tryWait(cdp, `!document.querySelector('.toolbox-app.is-dark')`, '恢复浅色主题', 15000);
    check('B/深色：可切回浅色主题', back && light, `找到切换按钮=${back} 生效=${light}`);
  });

  /* ---------------- 任务 B3：键盘路径 ---------------- */
  await section('keyboard', '任务 B：纯键盘路径（含 Escape）', async () => {
    await clearViewport(cdp);
    // 从匿名状态开始：清空服务端会话后**整页重载**，让路由守卫用干净状态做首次判定。
    // （只做 SPA 内跳转的话，Pinia 里仍缓存着 admin 状态，守卫不会重新验证。）
    const logoutStatus = await evaluate(cdp, `
      const response = await fetch('/admin/v1/auth/logout', { method: 'POST', credentials: 'same-origin' });
      return response.status;
    `);
    log(`  键盘路径起点：清空管理会话 HTTP ${logoutStatus}`);
    await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks` });
    await sleepSafe(1200);
    await cdp.send('Page.reload', { ignoreCache: false });
    await tryWait(cdp, `document.readyState === 'complete' && !!document.querySelector('.admin-entry')`, '未授权直达被挡回首页', 60000);
    const route = await evaluate(cdp, `return location.hash;`);
    check('B/键盘：未授权直达管理页被守卫挡回并带上 redirect',
      route.startsWith('#/?redirect=') || route.includes('redirect'),
      `落地路由=${route}`);

    // 键盘：Tab 到「管理员登录」并回车打开弹窗。
    const foundEntry = await tabUntil(cdp,
      `el.classList.contains('admin-entry') && (el.innerText ?? '').includes('管理员登录')`,
      '管理员登录入口', 60);
    check('B/键盘：Tab 可达「管理员登录」入口', Boolean(foundEntry.active),
      `用了 ${foundEntry.steps} 次 Tab，焦点=${JSON.stringify(foundEntry.active)}`);
    const trailLog = foundEntry.trail.map(item => item ? `${item.tag}/${item.label}` : 'null');
    log(`  首页 Tab 顺序（末 20 个）：${trailLog.slice(-20).join(' → ')}`);
    if (!foundEntry.active) return;
    await pressEnter(cdp);
    const modalOpen = await tryWait(cdp, `!!document.querySelector('.n-modal [aria-label="用户名或邮箱"]')`, '登录弹窗打开', 20000);
    check('B/键盘：回车可打开登录弹窗', modalOpen, `打开=${modalOpen}`);
    if (!modalOpen) return;

    // Escape 关闭弹窗，并确认焦点回到触发入口。
    await pressEscape(cdp);
    const modalClosed = await tryWait(cdp, `!document.querySelector('.n-modal')`, 'Escape 关闭弹窗', 10000);
    const focusBack = await evaluate(cdp, ACTIVE_DESCRIPTOR);
    check('B/键盘：Escape 可关闭登录弹窗', modalClosed, `关闭=${modalClosed}`);
    check('B/键盘：关闭弹窗后焦点回到触发入口',
      Boolean(focusBack) && (focusBack.label ?? '').includes('管理员登录'),
      `焦点=${JSON.stringify(focusBack)}`);

    // 重新打开并用键盘登录。
    await pressEnter(cdp);
    await tryWait(cdp, `!!document.querySelector('.n-modal [aria-label="用户名或邮箱"]')`, '登录弹窗再次打开', 20000);
    const accountFocus = await tabUntil(cdp, `el.getAttribute('aria-label') === '用户名或邮箱'`, '账号输入框', 20);
    check('B/键盘：Tab 可达账号输入框', Boolean(accountFocus.active), `焦点=${JSON.stringify(accountFocus.active)}`);
    await typeText(cdp, 'local-admin');
    const passwordFocus = await tabUntil(cdp, `el.getAttribute('aria-label') === '密码'`, '密码输入框', 20);
    check('B/键盘：Tab 可达密码输入框', Boolean(passwordFocus.active), `焦点=${JSON.stringify(passwordFocus.active)}`);
    await typeText(cdp, 'local-admin');
    const loginButton = await tabUntil(cdp,
      `el.tagName === 'BUTTON' && (el.innerText ?? '').trim() === '登录'`, '登录按钮', 20);
    check('B/键盘：Tab 可达「登录」按钮', Boolean(loginButton.active), `焦点=${JSON.stringify(loginButton.active)}`);
    await pressEnter(cdp);
    const adminIn = await tryWait(cdp, `!!document.querySelector('.admin-name')`, '键盘登录成功', 30000);
    check('B/键盘：回车提交登录成功', adminIn, `登录=${adminIn}`);
    if (!adminIn) return;
    const landed = await tryWait(cdp, `!!document.querySelector('.admin-list')`, '登录后回到被挡下的管理页', 30000);
    check('B/键盘：登录后回到 redirect 指定的管理页', landed,
      `路由=${await evaluate(cdp, `return location.hash;`)}`);

    // 进入编辑：Tab 到「编辑」按钮并回车。
    const editFocus = await tabUntil(cdp,
      `el.tagName === 'BUTTON' && (el.innerText ?? '').includes('编辑')`, '列表里的编辑按钮', 80);
    check('B/键盘：Tab 可达列表中的「编辑」按钮', Boolean(editFocus.active),
      `用了 ${editFocus.steps} 次 Tab，焦点=${JSON.stringify(editFocus.active)}`);
    if (!editFocus.active) return;
    await pressEnter(cdp);
    const editorOpen = await tryWait(cdp,
      `!!document.querySelector('.admin-editor') && !!document.querySelector('.preview-settings')`,
      '编辑页打开', 45000);
    check('B/键盘：回车进入编辑页', editorOpen, `打开=${editorOpen}`);
    if (!editorOpen) return;

    // 选择题型：Tab 到「预览题型」下拉，用键盘展开并选择第二种题型。
    const modeFocus = await tabUntil(cdp,
      `el.closest('.preview-settings .n-select') !== null`,
      '预览题型下拉', 120);
    check('B/键盘：Tab 可达「预览题型」下拉', Boolean(modeFocus.active),
      `用了 ${modeFocus.steps} 次 Tab，焦点=${JSON.stringify(modeFocus.active)}`);
    log(`  编辑页 Tab 顺序（到题型下拉为止）：${formatTrail(modeFocus.trail)}`);
    if (modeFocus.active) {
      const before = await evaluate(cdp, `return document.querySelector('.preview-settings')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '';`);
      await pressEnter(cdp);
      await sleepSafe(600);
      await pressArrowDown(cdp);
      await pressEnter(cdp);
      await sleepSafe(800);
      const after = await evaluate(cdp, `
        return {
          settings: document.querySelector('.preview-settings')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '',
          menuOpen: Boolean(document.querySelector('.n-base-select-option'))
        };
      `);
      if (after.menuOpen) {
        await pressEscape(cdp);
        await sleepSafe(300);
      }
      const changed = after.settings !== before;
      check('B/键盘：可用键盘切换预览题型', changed,
        `切换前=「${before}」 切换后=「${after.settings}」`);
    }

    // 触发预览：「预览草稿」在 DOM 里位于题型下拉**之前**，因此用 Shift+Tab 反向回到它。
    const previewFocus = await tabUntil(cdp,
      `el.tagName === 'BUTTON' && (el.innerText ?? '').includes('预览草稿')`, '预览草稿按钮', 30, { back: true });
    check('B/键盘：Shift+Tab 反向可达「预览草稿」按钮', Boolean(previewFocus.active),
      `用了 ${previewFocus.steps} 次 Shift+Tab，焦点=${JSON.stringify(previewFocus.active)}`);
    if (previewFocus.active) {
      await pressEnter(cdp);
      const previewShown = await tryWait(cdp,
        `!!document.querySelector('.preview-block canvas')`, '键盘触发预览', 120000);
      check('B/键盘：回车可触发草稿预览', previewShown, `预览出现=${previewShown}`);
      await shot(cdp, 'B3-keyboard-preview');
    }

    // 窄屏抽屉：Escape 关闭。
    await setViewport(cdp, 375, 800);
    await sleepSafe(800);
    const drawerOpened = await evaluate(cdp, `
      const button = document.querySelector('.mobile-menu');
      if (!button) return false;
      button.click();
      return true;
    `);
    const drawerVisible = drawerOpened
      ? await tryWait(cdp, `!!document.querySelector('.n-drawer')`, '窄屏目录抽屉', 15000)
      : false;
    check('B/键盘：窄屏可打开目录抽屉', drawerOpened && drawerVisible,
      `菜单按钮=${drawerOpened} 抽屉=${drawerVisible}`);
    if (drawerVisible) {
      await shot(cdp, 'B3-narrow-drawer');
      await pressEscape(cdp);
      const closed = await tryWait(cdp, `!document.querySelector('.n-drawer') || document.querySelector('.n-drawer')?.offsetParent === null`, 'Escape 关闭抽屉', 15000);
      check('B/键盘：Escape 可关闭目录抽屉', closed, `关闭=${closed}`);
    }
    await clearViewport(cdp);
  });

  /* ---------------- 生产泄漏与页面错误 ---------------- */
  log('\n===== 生产泄漏哨兵与页面错误 =====');
  check('边界：整个验收过程未向非回环主机发起请求',
    foreignRequests.length === 0,
    foreignRequests.length ? `出现 ${foreignRequests.length} 条：${foreignRequests.slice(0, 5).join(' ')}` : '全部请求都在 127.0.0.1');
  const badResponses = probe.badResponses.filter(item => !/\/admin\/v1\/auth\/login$/.test(item));
  check('边界：没有非预期的 4xx/5xx 静态或接口响应',
    badResponses.length === 0,
    badResponses.length ? badResponses.slice(0, 6).join(' | ') : '无');
  log(`  控制台错误 ${probe.consoleErrors.length} 条：${probe.consoleErrors.slice(0, 4).join(' | ')}`);
  log(`  未捕获异常 ${probe.pageErrors.length} 条：${probe.pageErrors.slice(0, 4).join(' | ')}`);
  log(`  失败请求 ${probe.failedRequests.length} 条：${probe.failedRequests.slice(0, 4).join(' | ')}`);

  const sourceAfter = await sourceFingerprint(join(repoRoot, 'toolbox-web', 'src'));
  const changedFiles = Object.keys(sourceAfter.stamps)
    .filter(file => sourceBefore.stamps[file] !== sourceAfter.stamps[file]);
  checkEnv('环境：验收期间前端源码未发生变化（Vite HMR 不重建组件）',
    sourceBefore.digest === sourceAfter.digest,
    sourceBefore.digest === sourceAfter.digest
      ? `指纹一致 ${sourceBefore.digest}（${sourceBefore.count} 个文件）`
      : `指纹变化 ${sourceBefore.digest} → ${sourceAfter.digest}；变化文件 ${changedFiles.length} 个：${changedFiles.slice(0, 6).join(', ')}`);

  const expectedBlobFailures = probe.failedRequests.filter(item => /ERR_FILE_NOT_FOUND/.test(item));
  const unexpectedFailures = probe.failedRequests.filter(item => !/ERR_FILE_NOT_FOUND/.test(item));
  check('边界：失败请求仅来自「关闭打印窗口后回收 blob 地址」的预期失败',
    unexpectedFailures.length === 0,
    `预期（blob 已回收）${expectedBlobFailures.length} 条，非预期 ${unexpectedFailures.length} 条${unexpectedFailures.length ? `：${unexpectedFailures.slice(0, 4).join(' | ')}` : ''}`);

  const product = results.filter(item => !item.env);
  const failed = product.filter(item => !item.ok);
  const envWarnings = results.filter(item => item.env && !item.ok);
  log(`\n===== 浏览器验收结果（产品项）：${product.length - failed.length}/${product.length} 通过 =====`);
  if (failed.length) {
    log('未通过项：');
    for (const item of failed) log(`  - ${item.name}：${item.detail}`);
  }
  if (envWarnings.length) {
    log('环境告警（不计入通过率，但影响证据有效性）：');
    for (const item of envWarnings) log(`  - ${item.name}：${item.detail}`);
  }
  exitCode = failed.length ? 1 : 0;
} catch (cause) {
  log(`\n浏览器验收异常中止：${cause?.message ?? cause}`);
  log(String(cause?.stack ?? ''));
  exitCode = 1;
} finally {
  try { cdp?.close(); } catch { /* 已断开 */ }
  try { chrome?.kill('SIGKILL'); } catch { /* 已退出 */ }
  await sleepSafe(400);
  const payload = {
    target: base,
    started,
    finished: new Date().toISOString(),
    exitCode,
    totals: {
      checks: results.length,
      product: results.filter(item => !item.env).length,
      failed: results.filter(item => !item.ok && !item.env).length,
      envWarnings: results.filter(item => item.env && !item.ok).length
    },
    results,
    transcript
  };
  await writeFile(join(logsDir, 'acceptance-latest.json'), JSON.stringify(payload, null, 2)).catch(() => undefined);
  await rm(workDir, { recursive: true, force: true });
}
process.exit(exitCode);
