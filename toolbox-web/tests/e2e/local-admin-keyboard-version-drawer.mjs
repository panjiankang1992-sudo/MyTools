#!/usr/bin/env node
/**
 * 版本记录抽屉的**纯键盘路径**验收（设计 3.6 第 166 行 + 第 176 行）。
 *
 * 只做一件事：进入管理编辑页 → 打开版本记录抽屉 → 在抽屉里用 Tab 移动 → Escape 关闭，
 * 并把**实测**的 Tab 顺序、焦点是否真的进入抽屉、Escape 是否真的关得掉、关闭后焦点
 * 是否回到触发按钮，原样记录下来。
 *
 * 为什么必须**只**用 CDP 的 `Input.dispatchKeyEvent`：本仓库两条线都报告过
 * `Input.dispatchMouseEvent`（真实鼠标）**送达不稳定**——同一坐标有时渲染进程收不到 click。
 * 键盘事件不受该问题影响，因此这条路径本身就是"绕开该问题"的验收手段。
 *
 * 用法（**只允许回环**）：
 *   ADMIN_COOKIE='toolbox_admin_local=xxx' \
 *     E2E_TARGET=http://127.0.0.1:4173 \
 *     node tests/e2e/local-admin-keyboard-version-drawer.mjs
 *
 * 退出码：0=全部断言通过；1=有断言失败（**真缺陷**，脚本会原样打印）；2=目标不是回环。
 */

import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { connect, createProbe, evaluate, findChrome, launchChrome, sleep } from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const cookie = process.env.ADMIN_COOKIE ?? '';
if (!cookie.includes('=')) {
  console.error('缺少 ADMIN_COOKIE（形如 toolbox_admin_local=...）。本次未执行（退出码 2）。');
  process.exit(2);
}
const [cookieName, ...cookieRest] = cookie.split('=');
const cookieValue = cookieRest.join('=');

/** 有历史版本的样册（本地隔离库；3 个版本）。 */
const EDITION = process.env.E2E_EDITION ?? 'local-acceptance-2026-10-07';
const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const evidenceDir = process.env.E2E_EVIDENCE_DIR
  ?? join(repoRoot, 'docs/verification/toolbox-admin-completion-2026-10-07');
const shotsDir = join(evidenceDir, 'screenshots');
const logsDir = join(evidenceDir, 'evidence', 'browser');

const results = [];
const transcript = [];
const check = (name, ok, detail) => {
  results.push({ name, ok: Boolean(ok), detail: detail ?? '' });
  const line = `${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` —— ${detail}` : ''}`;
  console.log(line);
  transcript.push(line);
};
const log = message => {
  console.log(message);
  transcript.push(message);
};

/** 当前焦点元素的可读描述。 */
const ACTIVE = `
  const el = document.activeElement;
  if (!el) return null;
  const raw = (el.getAttribute('aria-label') || el.getAttribute('placeholder')
    || el.getAttribute('data-testid') || el.getAttribute('data-version')
    || (el.innerText || '').replace(/\\s+/g, ' ').trim() || el.value || '').toString();
  const box = el.getBoundingClientRect();
  const style = getComputedStyle(el);
  return {
    tag: el.tagName,
    cls: (el.className ?? '').toString().split(' ').filter(Boolean).slice(0, 3).join('.'),
    label: raw.replace(/\\s+/g, ' ').trim().slice(0, 60),
    ariaHidden: el.getAttribute('aria-hidden'),
    tabIndex: el.tabIndex,
    visible: box.width > 0 && box.height > 0 && style.visibility !== 'hidden'
      && Number(style.opacity) > 0,
    inDialog: Boolean(el.closest('[role="dialog"]')),
    inDrawerTestId: Boolean(el.closest('[data-testid="version-drawer"]'))
  };
`;

const focusNow = () => evaluate(cdp, ACTIVE).catch(() => null);

/** 派发一个按键。Tab 用 rawKeyDown 才会触发浏览器默认的焦点移动。 */
async function dispatch(events) {
  for (const event of events) await cdp.send('Input.dispatchKeyEvent', event);
  await sleep(220);
}

const pressTab = () => dispatch([
  { type: 'rawKeyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9 },
  { type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9 }
]);

const pressShiftTab = () => dispatch([
  { type: 'rawKeyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9, modifiers: 8 },
  { type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9, modifiers: 8 }
]);

/**
 * 回车。
 *
 * **配方必须是 `keyDown(text='\r')` + `keyUp`，不能加 `char`、也不能只用 `rawKeyDown`。**
 * 这一条是本轮实测踩出来的（同一台无头 Chrome、同一个按钮）：
 * - `rawKeyDown` + `keyUp`：页面上 `keydown`/`keyup` 都收到，但**浏览器不激活按钮**，抽屉不开；
 * - `rawKeyDown` + `char` + `keyUp`：会**多触发**（实测 3 次 click），
 *   而抽屉在打开后焦点已移入抽屉，多余的那次激活会落到关闭按钮上——
 *   现象是"回车打开又立刻关掉"，看起来像产品缺陷，其实是驱动发错了事件 ✗；
 * - `keyDown(text)` + `keyUp`：正好一次 click，抽屉打开 ✓。
 */
const pressEnter = () => dispatch([
  { type: 'keyDown', key: 'Enter', code: 'Enter', text: '\r', unmodifiedText: '\r', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 },
  { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 }
]);

// **必须用 `keyDown` 而不是 `rawKeyDown`**：与 Enter 同一个坑 ——
// `rawKeyDown + keyUp` 不激活/不触发目标处理器，表现为"事件发了但什么都没发生"，
// 极易被误判成产品缺陷。第 137 轮独立复跑就是在这里出现 16/17（Escape 关闭=false），
// 而同一份实现在另一次运行里是 17/17 —— 差别就在这个配方上。
const pressEscape = () => dispatch([
  { type: 'keyDown', key: 'Escape', code: 'Escape', text: '\u001b',
    windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 },
  { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 }
]);

/** 焦点是否落在"真的可交互"的元素上（不是 body、不是隐藏哨兵）。 */
function isInteractive(node) {
  if (!node) return false;
  if (node.tag === 'BODY' || node.tag === 'HTML') return false;
  if (node.ariaHidden === 'true') return false;
  if (!node.visible) return false;
  return true;
}

/** 触发按钮的表达式（编辑页头部那个「版本记录」）。 */
const TRIGGER = `[...document.querySelectorAll('.editor-header button')]
  .find(el => (el.textContent ?? '').trim() === '版本记录')`;

let cdp;
const chromeState = {};

const main = async () => {
  await mkdir(shotsDir, { recursive: true });
  await mkdir(logsDir, { recursive: true });
  const executablePath = findChrome();
  if (!executablePath) {
    console.error('找不到 Chrome（可用 CHROME_PATH 指定）。本次未执行（退出码 2）。');
    process.exit(2);
  }
  const workDir = await mkdtemp(join(tmpdir(), 'tb-keyboard-'));
  chromeState.workDir = workDir;
  const port = Number(process.env.E2E_CDP_PORT ?? 9342);
  chromeState.child = await launchChrome({ executablePath, port, userDataDir: join(workDir, 'chrome') });
  cdp = await connect(port);
  const probe = createProbe(cdp);
  await cdp.send('Network.enable');
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.setCookie', {
    name: cookieName, value: cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true
  });

  // 1) 直达编辑页（**只做注入会话 + 导航**；本脚本关心的键盘路径从编辑页开始）。
  // 显式设一次桌面视口：不设时无头窗口的布局宽度可能与窗口尺寸不一致，
  // 右侧抽屉会被截掉一半（第一版截图就是这样）。
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1440, height: 900, deviceScaleFactor: 1, mobile: false
  });
  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
  await sleep(2500);
  const editorReady = await evaluate(cdp,
    `return !!document.querySelector('.admin-editor') && !!document.querySelector('.editor-header');`);
  check('进入管理编辑页（注入会话 Cookie 后直达）', editorReady, `编辑页渲染=${editorReady}`);
  if (!editorReady) throw new Error('编辑页未渲染，无法继续键盘路径');

  const historyLoaded = await evaluate(cdp,
    `return document.body.innerText.includes('修订') || document.querySelector('.workspace-head') !== null;`);
  check('编辑页已加载该册草稿（头部出现修订信息）', historyLoaded, `加载=${historyLoaded}`);

  // 2) 把焦点放到触发按钮上（Tab 顺序里它的位置见下面的实测记录）。
  const focused = await evaluate(cdp, `
    const button = ${TRIGGER};
    if (!button) return false;
    button.focus();
    return document.activeElement === button;
  `);
  check('可以把焦点放到「版本记录」按钮上（触发入口本身可聚焦）', focused, `聚焦=${focused}`);
  if (!focused) throw new Error('触发按钮不可聚焦');

  // 2b) 键位落点：记录触发按钮**在真实 Tab 顺序中的位置**（从上一个可交互元素走到它）。
  const backOne = await (async () => {
    await pressShiftTab();
    return await focusNow();
  })();
  await pressTab();
  const forwardAgain = await focusNow();
  log(`  实测：Shift+Tab 从触发按钮退到 ${backOne?.tag}/${backOne?.label}；再 Tab 回到 ${forwardAgain?.tag}/${forwardAgain?.label}`);

  // 3) 用键盘打开抽屉（Enter）。
  // 先装一个临时探针：记录触发按钮收到的真实事件，便于在"没打开"时区分
  // "按键没送到"与"送到了但没激活"——上一版脚本就是靠它定位到 `rawKeyDown` 的问题。
  await evaluate(cdp, `
    const button = ${TRIGGER};
    window.__triggerEvents = [];
    ['keydown', 'keyup', 'click'].forEach(type =>
      button.addEventListener(type, event => window.__triggerEvents.push(type + ':' + (event.key || '-'))));
    button.focus();
    return document.activeElement === button;
  `);
  await pressEnter();
  await sleep(900);
  const triggerEvents = await evaluate(cdp, `return { events: window.__triggerEvents ?? [], hash: location.hash };`);
  log(`  触发按钮收到的事件：${JSON.stringify(triggerEvents.events)}`);
  const drawerOpen = await evaluate(cdp, `return !!document.querySelector('[data-testid="version-drawer"]');`);
  check('回车可打开版本记录抽屉', drawerOpen,
    `打开=${drawerOpen} 事件=${JSON.stringify(triggerEvents.events)}`);
  if (!drawerOpen) {
    // 回车打不开就是**真缺陷**：不再往下编造"抽屉里 Tab 顺序"之类的结论，
    // 直接收尾并在报告里记为未通过。这里不退回合成 click 掩盖它。
    throw new Error(`回车未打开版本记录抽屉（触发按钮事件=${JSON.stringify(triggerEvents.events)}）`);
  }

  const rendered = await evaluate(cdp, `
    const drawer = document.querySelector('[data-testid="version-drawer"]');
    return {
      text: (drawer?.innerText ?? '').replace(/\\s+/g, ' ').trim().slice(0, 400),
      rows: drawer ? drawer.querySelectorAll('[data-version]').length : 0,
      versions: drawer ? [...drawer.querySelectorAll('[data-version]')].map(el => el.getAttribute('data-version')) : [],
      downloads: drawer ? [...drawer.querySelectorAll('[data-download-version]')].map(el => el.getAttribute('href')) : [],
      snapshots: drawer ? [...drawer.querySelectorAll('[data-snapshot-toggle]')].map(el => el.getAttribute('data-snapshot-toggle')) : [],
      error: drawer ? drawer.querySelector('[data-testid="version-error"]')?.innerText ?? null : null,
      empty: Boolean(drawer?.querySelector('[data-testid="version-empty"]'))
    };
  `);
  check('抽屉列出了该册历史版本', rendered.rows > 0,
    `版本行=${rendered.rows} 版本号=${JSON.stringify(rendered.versions)}`);
  log(`  抽屉文本：${rendered.text}`);
  if (rendered.error) log(`  ⚠ 抽屉报错：${rendered.error}`);
  log(`  下载链接：${JSON.stringify(rendered.downloads)}`);
  log(`  有来源快照的版本：${JSON.stringify(rendered.snapshots)}`);

  await shot('version-drawer-keyboard-open');

  // 3b) 该版本原始字节是否真的取得到。
  // 只断言"有 href"太弱：链接可能 404。这里在**页面同源会话**里真取一次并自算 SHA-256。
  //
  // **注意别拿错了哈希**：抽屉里的 `SHA-256` 是**来源快照**的哈希 ✗，
  // 而 `X-Content-Sha256` 是**正文原始字节**的哈希 ✓，两者本来就不同。
  // 第一版我拿快照哈希去比正文，于是"实测不一致"——错的是断言，不是产品。
  const bytesCheck = await evaluate(cdp, `
    const link = document.querySelector('[data-download-version]');
    const href = link?.getAttribute('href');
    if (!href) return { ok: false, reason: '没有下载链接' };
    const response = await fetch(href, { credentials: 'same-origin' });
    const buffer = await response.arrayBuffer();
    const digest = await crypto.subtle.digest('SHA-256', buffer);
    const hex = [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('');
    return {
      ok: response.ok,
      status: response.status,
      href,
      version: link.getAttribute('data-download-version'),
      byteLength: buffer.byteLength,
      header: response.headers.get('X-Content-Sha256'),
      headerVersion: response.headers.get('X-Content-Version'),
      sha256: hex,
      contentType: response.headers.get('Content-Type')
    };
  `);
  check('该版本原始字节可下载（200 + octet-stream），字节与自算 SHA-256 同 `X-Content-Sha256` 一致',
    Boolean(bytesCheck.ok) && bytesCheck.status === 200
      && bytesCheck.sha256 === bytesCheck.header
      && bytesCheck.headerVersion === bytesCheck.version,
    `${JSON.stringify(bytesCheck)}`);

  // 4) 打开后焦点是否进入抽屉？
  const afterOpen = await focusNow();
  const focusEntered = Boolean(afterOpen) && (afterOpen.inDialog || afterOpen.inDrawerTestId);
  check('打开后焦点进入抽屉（在 role="dialog" 或抽屉内容里）', focusEntered,
    `焦点=${JSON.stringify(afterOpen)}`);

  // 5) 在抽屉里用 Tab 移动，记录顺序；统计是否出现焦点陷阱／不可达控件。
  const tabTrail = [];
  for (let index = 0; index < 24; index += 1) {
    await pressTab();
    const node = await focusNow();
    tabTrail.push(node);
    if (node && node.tag === 'BODY') break;
  }
  const trailText = tabTrail.map((node, index) =>
    `${index + 1}.${node ? `${node.tag}${node.label ? `「${node.label}」` : ''}${node.inDrawerTestId ? '' : node.inDialog ? '(对话框内/抽屉外)' : '(抽屉外)'}` : 'null'}`)
    .join(' → ');
  log(`  抽屉内 Tab 顺序（实测）：${trailText}`);
  const unreachable = tabTrail.filter(node => !isInteractive(node));
  check('Tab 在抽屉内依次落在可交互元素上（不是 body、不是隐藏哨兵）',
    unreachable.length === 0,
    `异常落点=${unreachable.length}${unreachable.length ? `：${JSON.stringify(unreachable)}` : ''}`);
  const stayedInDialog = tabTrail.every(node => node && node.inDialog);
  check('Tab 不会离开抽屉（焦点陷阱生效，未落到页面背景）', stayedInDialog,
    `离开抽屉的落点=${tabTrail.filter(node => !node?.inDialog).length}`);

  // 6) Escape 是否能关掉抽屉。
  await pressEscape();
  // **等待条件**而不是固定 sleep：固定等待在慢一点的环境里就会读成"没关上"，
  // 那是测量方法的缺陷，不是产品的。这里最多等 5 秒，超时才算失败。
  let drawerClosed = false;
  for (let i = 0; i < 50; i += 1) {
    drawerClosed = await evaluate(cdp, `return !document.querySelector('[data-testid="version-drawer"]');`);
    if (drawerClosed) break;
    await sleep(100);
  }
  check('Escape 可关闭版本记录抽屉', drawerClosed, `关闭=${drawerClosed}`);
  await shot('version-drawer-keyboard-after-escape');

  // 7) 关闭后焦点是否回到触发按钮。
  const afterClose = await focusNow();
  const backToTrigger = await evaluate(cdp, `return document.activeElement === ${TRIGGER};`);
  check('关闭后焦点回到「版本记录」触发按钮（设计第 176 行）', backToTrigger,
    `焦点=${JSON.stringify(afterClose)} activeElement 是触发按钮=${backToTrigger}`);

  // 8) 复开一次，验证再次可用（不是一次性）。
  await pressEnter();
  await sleep(900);
  const reopened = await evaluate(cdp, `return !!document.querySelector('[data-testid="version-drawer"]');`);
  check('关闭后可再次用键盘打开（Escape 没有把入口弄坏）', reopened, `再次打开=${reopened}`);

  // 9) 抽屉内的"查看来源快照"用键盘可达且可展开。
  if (rendered.snapshots.length) {
    const found = await (async () => {
      const trail = [];
      for (let index = 0; index < 24; index += 1) {
        const node = await focusNow();
        trail.push(node);
        if (node && node.label && node.label.includes('查看来源快照')) return { node, steps: index, trail };
        await pressTab();
      }
      return { node: null, steps: trail.length, trail };
    })();
    check('Tab 可达「查看来源快照」按钮', Boolean(found.node),
      `用了 ${found.steps} 次 Tab；焦点=${JSON.stringify(found.node)}`);
    if (found.node) {
      await pressEnter();
      await sleep(1200);
      const snapshotShown = await evaluate(cdp, `
        const pre = document.querySelector('[data-snapshot-json]');
        return { shown: Boolean(pre), head: (pre?.textContent ?? '').slice(0, 160) };
      `);
      check('回车可展开该版本来源快照 JSON', snapshotShown.shown, `展开=${snapshotShown.shown} 头=${JSON.stringify(snapshotShown.head)}`);
      await shot('version-drawer-keyboard-snapshot');
    }
  }

  // 10) 只读检查：抽屉内不得出现写操作控件。
  const controls = await evaluate(cdp, `
    const drawer = document.querySelector('[data-testid="version-drawer"]');
    if (!drawer) return [];
    return [...drawer.querySelectorAll('button, a')].map(el => (el.textContent ?? '').trim()).filter(Boolean);
  `);
  const writable = controls.filter(label => /恢复|回滚|下架|删除|覆盖/.test(label));
  check('抽屉是只读的（控件里没有恢复／回滚／下架／删除／覆盖）', writable.length === 0,
    `写操作控件=${JSON.stringify(writable)} 全部控件=${JSON.stringify(controls)}`);

  // 11) 控制台／网络异常。
  check('键盘路径期间无渲染进程异常', probe.pageErrors.length === 0,
    `pageErrors=${JSON.stringify(probe.pageErrors.slice(0, 3))}`);
  const bad = probe.badResponses.filter(item => !item.includes('/bytes'));
  check('键盘路径期间无异常响应（4xx/5xx）', bad.length === 0,
    `badResponses=${JSON.stringify(bad.slice(0, 5))}`);

  await writeFile(join(logsDir, 'keyboard-version-drawer.json'), `${JSON.stringify({
    target: base,
    edition: EDITION,
    results,
    drawn: rendered,
    tabTrail,
    unexpected: unreachable,
    consoleErrors: probe.consoleErrors,
    pageErrors: probe.pageErrors,
    failedRequests: probe.failedRequests,
    badResponses: probe.badResponses,
    transcript
  }, null, 2)}\n`);
  log(`证据写入 ${join(logsDir, 'keyboard-version-drawer.json')}`);
};

/** 截图（不做任何点击）。 */
async function shot(name) {
  const data = (await cdp.send('Page.captureScreenshot', { format: 'png' })).data;
  const file = join(shotsDir, `${name}.png`);
  await writeFile(file, Buffer.from(data, 'base64'));
  log(`  截图 ${file}`);
}

try {
  await main();
} catch (cause) {
  check('脚本执行完成', false, `异常：${cause?.message ?? cause}`);
  console.error(cause);
} finally {
  chromeState.child?.kill?.();
  if (chromeState.workDir) {
    // Chrome 退出后仍在写 profile：重试几次再放弃，别让清理失败盖掉真正的断言结果。
    for (let attempt = 0; attempt < 5; attempt += 1) {
      try {
        await rm(chromeState.workDir, { recursive: true, force: true });
        break;
      } catch {
        await sleep(300);
      }
    }
  }
}

const failed = results.filter(item => !item.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
process.exit(failed.length ? 1 : 0);
