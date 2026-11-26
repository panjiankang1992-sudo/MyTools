#!/usr/bin/env node
/**
 * 任务 2：管理端「打印预览」的浏览器端到端验收（就绪门 + 窗口关闭即取消 + 不调系统打印）。
 *
 * 要证明的三件事：
 *   1. 点「打印预览」**真的开出了一个独立打印窗口**（CDP 里多一个 page target）；
 *   2. 窗口里"全部页图准备完成才允许打印"——`print-ready` 与打印按钮解禁（就绪门 ✓）；
 *   3. **关掉窗口即取消**：窗口消失、且全程 `window.print` **一次都没被调用**
 *      （既没有自动打印，也没在未就绪时打印）。
 *
 * 手法（真实鼠标 `Input.dispatchMouseEvent` 送达不稳定，刻意绕开）：
 *   - 合成 `el.click()` 触发两个按钮；
 *   - 用 CDP 的 `Target.getTargets` / `Target.attachToTarget` 观察打印窗口这个**独立 target**；
 *   - 用 `Page.addScriptToEvaluateOnNewDocument` 在**应用脚本之前**给 `window.open` 装探针，
 *     记录它被调用了几次、返回了什么，并把 `window.print` 包成计数器
 *     （主页面与同源打印窗口共用同一个 JS 全局，因此能读到）。
 *
 * 每一步都有超时；脚本整体在 3 分钟内结束或失败。
 *
 * 用法（**只允许回环**）：
 *   ADMIN_COOKIE='toolbox_admin_local=...' E2E_TARGET=http://127.0.0.1:4173 \
 *     node tests/e2e/local-admin-print-preview-e2e.mjs
 *
 * 反向验证（把"关闭即取消"改坏后必须失败）：
 *   E2E_EXPECT=reverse ...
 *
 * 退出码：0=断言全部通过；1=有断言失败（反向验证时期望为 1）；2=目标非回环/缺 Cookie。
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
const REVERSE = process.env.E2E_EXPECT === 'reverse';
const MODE = REVERSE ? '反向验证（预期失败）' : '正式验收';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const evidenceDir = process.env.E2E_EVIDENCE_DIR
  ?? join(repoRoot, 'docs/verification/toolbox-admin-completion-2026-10-07');
const shotsDir = join(evidenceDir, 'screenshots');
const logsDir = join(evidenceDir, 'evidence/browser');
await mkdir(shotsDir, { recursive: true });
await mkdir(logsDir, { recursive: true });

const started = Date.now();
const elapsed = () => `${((Date.now() - started) / 1000).toFixed(1)}s`;
const results = [];
const check = (label, ok, detail = '') => {
  results.push({ label, ok: Boolean(ok), detail });
  console.log(`[${elapsed()}] ${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` —— ${detail}` : ''}`);
};

/**
 * 在任何应用脚本之前给 window.open / window.print 装探针（同源窗口共用同一 JS 全局）。
 *
 * 反向验证不开源码补丁：4173 提供的是**已构建产物**，改 `src/` 不会生效。
 * 因此这里在运行时把打印窗口的 `close()` 与 `pagehide`/`unload` 监听改坏，
 * 让"关闭即取消"失效——预期判定 4 失败。
 */
const PROBE_HOOK = `
  (() => {
    const breakClose = ${REVERSE};
    window.__openCalls = 0;
    window.__openResults = [];
    window.__printCalls = 0;
    window.__printTimeline = [];
    window.__gateAtCreation = null;
    window.__popupPrintCalls = 0;
    const originalOpen = window.open.bind(window);
    window.open = (...args) => {
      window.__openCalls += 1;
      const opened = originalOpen(...args);
      window.__openResults.push(Boolean(opened));
      if (opened) {
        if (breakClose) {
          // E2E-REVERSE：故意改坏——关闭按钮不再关掉窗口，"关闭即取消"失效。
          try {
            Object.defineProperty(opened, 'close', {
              configurable: true, writable: true,
              value: () => { window.__closeAttempts = (window.__closeAttempts ?? 0) + 1; }
            });
          } catch (cause) { window.__breakCloseError = String(cause); }
          try {
            const originalAdd = opened.addEventListener.bind(opened);
            Object.defineProperty(opened, 'addEventListener', {
              configurable: true, writable: true,
              value: (type, listener, options) => {
                if (type === 'pagehide' || type === 'unload') {
                  window.__ignoredUnloadListeners = (window.__ignoredUnloadListeners ?? 0) + 1;
                  return;
                }
                return originalAdd(type, listener, options);
              }
            });
          } catch (cause) { window.__breakListenerError = String(cause); }
        }
        // 打印窗口的 print 也包一层：证明"没有真的调起系统打印"是在**打印窗口自己**身上测的。
        try {
          const originalPopupPrint = opened.print.bind(opened);
          opened.print = (...printArgs) => {
            opened.__printCalls = (opened.__printCalls ?? 0) + 1;
            window.__popupPrintCalls += 1;
            window.__printCalls += 1;
            window.__printTimeline.push({ at: Math.round(performance.now()), images: opened.document.querySelectorAll('img.page').length });
            return originalPopupPrint(...printArgs);
          };
          opened.__printCalls = 0;
        } catch (cause) {
          window.__popupPrintPatchError = String(cause);
        }
        // 就绪门必须**在窗口刚建出来时就存在**：观察第一次出现打印按钮时的禁用状态。
        // 同步采样赶不上应用写入工具栏，因此用 MutationObserver 抓"首次出现"的那个瞬间。
        try {
          const observer = new MutationObserver(() => {
            const button = opened.document.querySelector('.print-actions button.primary');
            if (button && window.__gateAtCreation === null) {
              window.__gateAtCreation = { disabled: Boolean(button.disabled), ready: opened.document.body.classList.contains('print-ready') };
            }
          });
          observer.observe(opened.document.documentElement, { childList: true, subtree: true, attributes: true });
        } catch (cause) {
          window.__gateObserverError = String(cause);
        }
      }
      return opened;
    };
    const originalPrint = window.print.bind(window);
    window.print = (...args) => {
      window.__printCalls += 1;
      window.__printTimeline.push({ ready: document.body.classList.contains('print-ready'), images: document.querySelectorAll('img.page').length, at: Math.round(performance.now()) });
      return originalPrint(...args);
    };
    return true;
  })();
`;

/** 编辑页就绪。 */
const EDITOR_READY = `document.querySelectorAll('.pair-row').length >= 2`;
const pdfBlobCount = `window.__blobs.filter(item => item.type === 'application/pdf').length`;

/** 等预览产物就绪（「打印预览」按钮解禁）。 */
const PRINT_BUTTON_READY = `
  const printButton = [...document.querySelectorAll('button')].find(b => (b.textContent || '').trim() === '打印预览');
  return (printButton ? !printButton.disabled : false);
`;

/** 打印按钮当前状态（诊断用）。 */
const PRINT_BUTTON_STATE = `
  const printButton = [...document.querySelectorAll('button')].find(b => (b.textContent || '').trim() === '打印预览');
  return {
    found: Boolean(printButton),
    disabled: printButton ? Boolean(printButton.disabled) : null,
    block: Boolean(document.querySelector('.preview-block')),
    canvas: document.querySelectorAll('.preview-block canvas').length,
    notice: document.querySelector('.preview-block')?.innerText?.replace(/\\s+/g, ' ').slice(0, 120) ?? ''
  };
`;

const CLICK = text => `
  const target = [...document.querySelectorAll('button')].find(b => (b.textContent || '').trim() === ${JSON.stringify(text)});
  if (!target) return { ok: false, reason: '找不到按钮' };
  if (target.disabled) return { ok: false, reason: '按钮被禁用' };
  target.click();
  return { ok: true };
`;

/** 列出页面型 target。 */
async function listPages(cdp) {
  const { targetInfos } = await cdp.send('Target.getTargets');
  return targetInfos.filter(info => info.type === 'page');
}

/** 在应用脚本之前挂钩 blob（取回预览 PDF 字节）。 */
const BLOB_HOOK = `
  (() => {
    window.__blobs = [];
    const originalCreate = URL.createObjectURL.bind(URL);
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true, writable: true, enumerable: false,
      value: blob => {
        const url = originalCreate(blob);
        window.__blobs.push({ url, size: blob.size, type: blob.type || '' });
        return url;
      }
    });
    return true;
  })();
`;

/** 让预览面板出现并产出 PDF（打印按钮在此之前一直是禁用的）。 */
async function preparePreview(cdp) {
  const before = Number(await evaluate(cdp, `return ${pdfBlobCount};`)) || 0;
  const clicked = await evaluate(cdp, CLICK('预览草稿'));
  if (!clicked?.ok) return { ok: false, clicked };
  const produced = await waitFor(cdp, `${pdfBlobCount} > ${before}`, '预览产出新的 PDF blob', 60000).catch(() => null);
  const ready = await waitFor(cdp, PRINT_BUTTON_READY, '「打印预览」按钮解禁', 30000).catch(() => null);
  if (ready !== true) console.log(`  打印按钮未解禁：${JSON.stringify(await evaluate(cdp, PRINT_BUTTON_STATE))}`);
  return { ok: produced !== null && ready === true, clicked, produced: produced !== null, ready: ready === true };
}

const exe = findChrome();
if (!exe) { console.error('找不到 Chrome。本次未执行（退出码 2）。'); process.exit(2); }

const port = Number(process.env.E2E_CDP_PORT ?? 9372);
let chrome;
let cdp;
let profileDir = null;
let popupTargetId = null;
let exitCode = 1;
try {
  if (REVERSE) {
    console.log(`[${elapsed()}] 反向验证：运行时把打印窗口的 close/addEventListener 改坏（不改源码、不必重建）`);
  }

  profileDir = await createProfileDir('tb-print-');
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: profileDir });
  cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: cookieName, value: cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: PROBE_HOOK });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: BLOB_HOOK });

  // 前置：先把草稿预览生成出来（打印按钮在此之前是禁用的，点它不会有任何反应）。
  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
  await waitFor(cdp, EDITOR_READY, '编辑页就绪', 60000);
  const prepared = await preparePreview(cdp);
  check('前置：草稿预览已生成、打印按钮已解禁', prepared.ok === true,
    `点击=${JSON.stringify(prepared.clicked)} 产出=${prepared.produced} 解禁=${prepared.ready}`);
  if (!prepared.ok) throw new Error('预览未就绪，无法继续打印验收');

  const before = await listPages(cdp);
  const printCallsBefore = Number(await evaluate(cdp, `return window.__printCalls;`)) || 0;
  const openCallsBefore = Number(await evaluate(cdp, `return window.__openCalls;`)) || 0;

  // 1) 合成点击「打印预览」。
  const clicked = await evaluate(cdp, CLICK('打印预览'));
  check('手法：合成 el.click() 触发「打印预览」', clicked.ok === true, JSON.stringify(clicked));

  // 2) 观察：CDP 里真的多出一个 page target（打印预览是 window.open 出来的独立窗口）。
  let popup = null;
  const popupDeadline = Date.now() + 20000;
  while (Date.now() < popupDeadline && !popup) {
    const now = await listPages(cdp);
    popup = now.find(info => !before.some(old => old.targetId === info.targetId)) ?? null;
    if (!popup) await sleep(400);
  }
  const after = await listPages(cdp);
  const openCallsAfter = Number(await evaluate(cdp, `return window.__openCalls;`)) || 0;
  popupTargetId = popup?.targetId ?? null;
  check('★判定 1：点「打印预览」真的开出了新窗口（CDP 多出一个 page target）',
    Boolean(popup), popup ? `新 target=${popup.targetId.slice(0, 12)} url=${popup.url}` : `前后 target 数 ${before.length} → ${after.length}`);
  check('证据：window.open 确实被应用调用了一次并返回了窗口对象',
    openCallsAfter === openCallsBefore + 1,
    `open 调用 ${openCallsBefore} → ${openCallsAfter}；返回值=${JSON.stringify(await evaluate(cdp, `return window.__openResults.slice(-2);`))}`);

  // 3) 在打印窗口自己的 target 会话里读状态：就绪门 + 打印按钮解禁。
  let printState = null;
  if (popup) {
    const attached = await cdp.send('Target.attachToTarget', { targetId: popup.targetId, flatten: true }).catch(() => null);
    if (attached?.sessionId) {
      const sessionId = attached.sessionId;
      try {
        await cdp.send('Runtime.enable', {}, sessionId).catch(() => undefined);
        // 先确认这个会话真的在打印窗口里（而不是因为没带 sessionId 落到主页面）。
        const sessionUrl = await evaluate(cdp, `return location.href + ' | ' + document.title;`, sessionId).catch(() => null);
        console.log(`[${elapsed()}] 打印窗口会话自检：${JSON.stringify(sessionUrl)}`);
        const deadline = Date.now() + 60000;
        while (Date.now() < deadline) {
          printState = await evaluate(cdp, `
            const printButton = document.querySelector('.print-actions button.primary');
            return {
              hasToolbar: Boolean(document.querySelector('.print-actions')),
              ready: document.body.classList.contains('print-ready'),
              printDisabled: printButton ? Boolean(printButton.disabled) : null,
              pages: document.querySelectorAll('img.page').length,
              decoded: [...document.querySelectorAll('img.page')].filter(image => image.complete && image.naturalWidth > 0).length,
              summary: document.querySelector('.page-summary')?.textContent?.trim() ?? '',
              status: document.querySelector('.print-status')?.textContent?.trim() ?? '',
              title: document.title,
              buttons: [...document.querySelectorAll('.print-actions button, .print-actions a')].map(item => item.textContent.trim())
            };
          `, sessionId).catch(() => null);
          if (printState?.ready) break;
          await sleep(700);
          if (Date.now() > deadline && !printState?.hasToolbar) {
            console.log(`  打印窗口 60s 内未出现工具栏：${JSON.stringify(printState)}`);
          }
        }
        const shot = await cdp.send('Page.captureScreenshot', { format: 'png' }, sessionId).catch(() => null);
        if (shot) {
          await writeFile(
            join(shotsDir, REVERSE ? 'print-e2e-window-ready-reverse.png' : 'print-e2e-window-ready.png'),
            Buffer.from(shot.data, 'base64')
          );
        }
      } finally {
        await cdp.send('Target.detachFromTarget', { sessionId }).catch(() => undefined);
      }
    }
  }
  const summaryPages = Number((/(\d+)\s*页/.exec(printState?.summary ?? '') ?? [])[1] ?? 0);
  check('★判定 2：打印页全部就位后才解禁打印（就绪门生效）',
    printState?.ready === true && printState?.printDisabled === false && printState?.pages === summaryPages && summaryPages >= 1,
    `ready=${printState?.ready} 打印按钮禁用=${printState?.printDisabled} 页图=${printState?.pages}/${summaryPages} 已解码=${printState?.decoded} 状态=「${printState?.status}」`);
  check('证据：打印窗口是应用自己搭的结构（标题/工具栏/按钮文案）',
    printState?.hasToolbar === true && printState?.title === '教材草稿预览',
    `title=「${printState?.title}」 按钮=${JSON.stringify(printState?.buttons)}`);

  // 4) 没有真的调用系统打印。
  const printCalls = Number(await evaluate(cdp, `return window.__printCalls;`)) || 0;
  const popupPrintCalls = Number(await evaluate(cdp, `return window.__popupPrintCalls;`)) || 0;
  check('★判定 3：全程 window.print 一次都没被调用（没有真的调起系统打印）',
    printCallsBefore === 0 && printCalls === 0 && popupPrintCalls === 0,
    `主页面计数=${printCalls}；打印窗口自身的 print 计数=${popupPrintCalls}；时间线=${JSON.stringify(await evaluate(cdp, `return window.__printTimeline;`))}`);

  const gateAtCreation = await evaluate(cdp, `return window.__gateAtCreation;`);
  check('★判定 2b：打印按钮**在窗口刚建出来时是禁用的**（就绪门不是事后补的）',
    gateAtCreation?.disabled === true && gateAtCreation?.ready === false,
    `首次出现打印按钮时：禁用=${gateAtCreation?.disabled} 已就绪=${gateAtCreation?.ready}` +
    `（观察器错误=${JSON.stringify(await evaluate(cdp, `return [window.__gateObserverError ?? null, window.__popupPrintPatchError ?? null];`))}）`);

  // 5) 关掉窗口即取消：点窗口里的「关闭窗口」。
  let closedByButton = null;
  if (popup) {
    const attached = await cdp.send('Target.attachToTarget', { targetId: popup.targetId, flatten: true }).catch(() => null);
    if (attached?.sessionId) {
      const popupOwnPrint = await evaluate(cdp, `return { own: window.__printCalls ?? null, images: document.querySelectorAll('img.page').length };`, attached.sessionId).catch(() => null);
      console.log(`[${elapsed()}] 关闭前，打印窗口自身的 print 计数=${JSON.stringify(popupOwnPrint)}`);
      closedByButton = await evaluate(cdp, `
        const closeButton = [...document.querySelectorAll('.print-actions button')].find(item => item.textContent.trim() === '关闭窗口');
        if (!closeButton) return { ok: false, reason: '找不到「关闭窗口」按钮' };
        closeButton.click();
        return { ok: true };
      `, attached.sessionId).catch(error => ({ ok: false, reason: String(error?.message ?? error) }));
    }
    const deadline = Date.now() + 15000;
    let stillOpen = true;
    while (Date.now() < deadline) {
      const now = await listPages(cdp);
      stillOpen = now.some(info => info.targetId === popup.targetId);
      if (!stillOpen) break;
      await sleep(400);
    }
    check('★判定 4：关掉打印窗口后该 target 消失（窗口关闭即取消）',
      closedByButton?.ok === true && stillOpen === false,
      `点击关闭=${JSON.stringify(closedByButton)} 之后仍在=${stillOpen}`);
    if (stillOpen) {
      await cdp.send('Target.closeTarget', { targetId: popup.targetId }).catch(() => undefined);
      await sleep(800);
    }
  }

  const printCallsFinal = Number(await evaluate(cdp, `return window.__printCalls;`)) || 0;
  const popupProbe = await evaluate(cdp, `return { printCalls: window.__printCalls, openCalls: window.__openCalls, timeline: window.__printTimeline };`);
  check('兜底：关闭全程结束后依然没有任何系统打印调用',
    printCallsFinal === 0 && (popupProbe.timeline ?? []).length === 0,
    `主页面计数=${printCallsFinal} 探针=${JSON.stringify(popupProbe)}`);

  const failed = results.filter(item => !item.ok);
  await writeFile(join(logsDir, REVERSE ? 'print-preview-reverse.json' : 'print-preview-e2e.json'), `${JSON.stringify({
    mode: MODE,
    edition: EDITION,
    printState,
    gateAtCreation: await evaluate(cdp, `return window.__gateAtCreation;`),
    popupPrintCalls: await evaluate(cdp, `return window.__popupPrintCalls;`),
    summaryPages,
    openCalls: { before: openCallsBefore, after: openCallsAfter },
    printCalls: { before: printCallsBefore, after: printCallsFinal, timeline: popupProbe.timeline },
    targets: { before: before.map(item => item.targetId), after: after.map(item => item.targetId), popup: popupTargetId },
    reverseBreak: REVERSE ? {
      closeAttempts: await evaluate(cdp, `return window.__closeAttempts ?? null;`),
      ignoredUnloadListeners: await evaluate(cdp, `return window.__ignoredUnloadListeners ?? null;`),
      errors: await evaluate(cdp, `return [window.__breakCloseError ?? null, window.__breakListenerError ?? null];`)
    } : null,
    totals: { checks: results.length, failed: failed.length },
    results
  }, null, 2)}\n`);
  console.log(`\n[${elapsed()}] ===== 任务 2 结果：${results.length - failed.length}/${results.length} 通过（${MODE}）=====`);
  for (const item of failed) console.log(`  未通过：${item.label} —— ${item.detail}`);
  exitCode = failed.length ? 1 : 0;
} catch (cause) {
  check('脚本整体执行完成', false, `异常中止：${cause?.message ?? cause}`);
  console.log(String(cause?.stack ?? cause));
  exitCode = 1;
} finally {
  if (popupTargetId) {
    await cdp?.send('Target.closeTarget', { targetId: popupTargetId }).catch(() => undefined);
  }
  try { cdp?.close(); } catch { /* 已断开 */ }
  await stopChrome(chrome, profileDir);
  await removeProfileDir(profileDir);
}
process.exit(exitCode);
