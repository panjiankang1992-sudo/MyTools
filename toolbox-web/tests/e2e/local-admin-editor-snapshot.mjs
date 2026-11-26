#!/usr/bin/env node
/**
 * 任务 1：**当前编辑快照**参与草稿 PDF 预览的浏览器端到端验收。
 *
 * 验收目标（设计 164 行「使用当前草稿的已选范围」）：
 *   在编辑页**改一条内容的文本/读音、不点保存**，随后点「预览草稿」——
 *   改动必须出现在真实生成的 PDF **文字层**里；把编辑改回原值再预览，PDF 必须回到原值。
 *
 * 手法（真实鼠标 `Input.dispatchMouseEvent` 送达不稳定，刻意绕开）：
 *   1. **原生 setter**（`HTMLTextAreaElement.prototype.value` 的 setter）+ `input` 事件，
 *      否则直接改 `.value` 不会触发 Vue 的响应式；
 *   2. 合成 `el.click()` 触发按钮；
 *   3. 一律**轮询等待条件**，不做固定 sleep 定胜负；
 *   4. `Page.addScriptToEvaluateOnNewDocument` 在**应用脚本之前**挂钩
 *      `URL.createObjectURL`（取回预览真实产出的 PDF 字节）与 `fetch`
 *      （记录 `/draft/preview` 的响应体，反向验证要把"预览改用已保存草稿"改坏时用）；
 *   5. 用 `tests/e2e/pdf-text.mjs`（站点自带的 pdfjs-dist，不新增依赖）读 PDF 文字层。
 *
 * 反向验证（必须失败）怎么改坏：**让预览改用服务器已保存的草稿**——
 * 把预览接口的响应替换成"编辑之前那次已保存草稿"的响应体（运行时注入，不改源码、
 * 不需要重建，因为 4173 提供的是已构建产物）。预期：判定 1 失败（PDF 里没有独特值）。
 *
 * 用法（**只允许回环**）：
 *   ADMIN_COOKIE='toolbox_admin_local=...' E2E_TARGET=http://127.0.0.1:4173 \
 *     node tests/e2e/local-admin-editor-snapshot.mjs
 *   E2E_EXPECT=reverse ... 同上
 *
 * 注意：**不能用 PDF 字节的 sha256 判断内容有没有变**——pdf-lib 的子集字体名带随机后缀，
 * 同一份内容两次生成字节也不同。判据一律是 PDF 的**文字层**。
 *
 * 退出码：0=断言全部通过；1=有断言失败（反向验证时期望为 1）；2=目标非回环/缺 Cookie。
 */

import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { connect, createProfileDir, evaluate, findChrome, launchChrome, removeProfileDir, stopChrome, sleep, waitFor } from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';
import { extractPdfText, textContains } from './pdf-text.mjs';

/** 字节的 sha256 前 16 位，用于对比两次预览产物是否真的不同。 */
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex').slice(0, 16);

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
const MARK = process.env.E2E_MARK ?? `kuài zhuǎn${Date.now().toString(36).slice(-4)}`;

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const evidenceDir = process.env.E2E_EVIDENCE_DIR
  ?? join(repoRoot, 'docs/verification/toolbox-admin-completion-2026-10-07');
const shotsDir = join(evidenceDir, 'screenshots');
const logsDir = join(evidenceDir, 'evidence/browser');
const pdfsDir = join(evidenceDir, 'pdfs');
await mkdir(shotsDir, { recursive: true });
await mkdir(logsDir, { recursive: true });
await mkdir(pdfsDir, { recursive: true });

const started = Date.now();
const elapsed = () => `${((Date.now() - started) / 1000).toFixed(1)}s`;
const results = [];
const check = (label, ok, detail = '') => {
  results.push({ label, ok: Boolean(ok), detail });
  console.log(`[${elapsed()}] ${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` —— ${detail}` : ''}`);
};

/**
 * 在**应用脚本之前**挂钩 blob 与 fetch。
 *
 * 为什么必须早于应用脚本：应用打包时已经取走了 `URL.createObjectURL` 的引用，
 * 页面加载后再改 `URL` 不会被用到（第一次尝试就踩了这个坑，blob 一直是空的）。
 */
const PAGE_HOOK = `
  (() => {
    window.__blobs = [];
    window.__previewResponses = [];
    window.__reverseServeSaved = false;
    window.__reverseServed = 0;
    const originalCreate = URL.createObjectURL.bind(URL);
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true, writable: true, enumerable: false,
      value: blob => {
        const url = originalCreate(blob);
        window.__blobs.push({ url, size: blob.size, type: blob.type || '', at: Math.round(performance.now()) });
        return url;
      }
    });
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const url = typeof input === 'string' ? input : (input && input.url) || '';
      const isPreview = url.includes('/draft/preview');
      // 反向验证：把"当前编辑快照"换成"上一次已保存草稿"的响应体。
      if (isPreview && window.__reverseServeSaved && window.__previewResponses.length) {
        const saved = window.__previewResponses[window.__previewResponses.length - 1];
        window.__reverseServed += 1;
        return new Response(JSON.stringify(saved), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      const response = await originalFetch(input, init);
      if (isPreview) {
        response.clone().json().then(body => window.__previewResponses.push(body)).catch(() => undefined);
      }
      return response;
    };
    return true;
  })();
`;

/** 原生 setter + input/change 事件：让 Vue 感知输入。 */
const setNative = (selector, value) => `
  const host = document.querySelector(${JSON.stringify(selector)});
  if (!host) return { ok: false, reason: 'no-host' };
  const field = host.matches('input, textarea') ? host : host.querySelector('input, textarea');
  if (!field) return { ok: false, reason: 'no-field' };
  const proto = field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(field, ${JSON.stringify(value)});
  field.dispatchEvent(new Event('input', { bubbles: true }));
  field.dispatchEvent(new Event('change', { bubbles: true }));
  return { ok: true, value: field.value };
`;

const EDITOR_READY = `document.querySelectorAll('.pair-row').length >= 2`;
const LEFT = '.n-input[aria-label="第 1 行汉字"]';
const RIGHT = '.n-input[aria-label="第 1 行拼音"]';
const leftValue = `document.querySelector(${JSON.stringify(LEFT)})?.querySelector('textarea, input')?.value ?? null`;
const rightValue = `document.querySelector(${JSON.stringify(RIGHT)})?.querySelector('textarea, input')?.value ?? null`;
const pdfBlobCount = `window.__blobs.filter(item => item.type === 'application/pdf').length`;

/** 取回第 index 个 PDF blob 的字节（base64 回传）。 */
const fetchBlob = index => `
  try {
    const entry = window.__blobs.filter(item => item.type === 'application/pdf')[${index}];
    if (!entry) return { ok: false, reason: 'PDF blob 不存在' };
    const response = await fetch(entry.url);
    if (!response.ok) return { ok: false, reason: 'HTTP ' + response.status };
    const bytes = new Uint8Array(await response.arrayBuffer());
    let binary = '';
    const chunk = 0x8000;
    for (let cursor = 0; cursor < bytes.length; cursor += chunk) {
      binary += String.fromCharCode.apply(null, bytes.subarray(cursor, cursor + chunk));
    }
    return { ok: true, base64: btoa(binary), bytes: bytes.length, url: entry.url, recordedSize: entry.size };
  } catch (cause) {
    return { ok: false, reason: String(cause && cause.message ? cause.message : cause) };
  }
`;

/** 合成点击「预览草稿」。 */
const CLICK_PREVIEW = `
  const previewButton = [...document.querySelectorAll('button')].find(b => (b.textContent || '').trim() === '预览草稿');
  if (!previewButton) return { ok: false, reason: '找不到「预览草稿」按钮' };
  if (previewButton.disabled) return { ok: false, reason: '按钮被禁用' };
  previewButton.click();
  return { ok: true };
`;

/** 用管理端 Cookie 直接读服务器草稿（证明"服务器上现在是什么"）。 */
async function readServerDraft(cdp) {
  return await evaluate(cdp, `
    const response = await fetch('/admin/v1/textbooks/${EDITION}/draft', { credentials: 'same-origin' });
    const body = await response.json().catch(() => null);
    const draft = body?.data?.draft ?? null;
    return {
      status: response.status,
      revision: body?.data?.revision ?? null,
      entries: (draft?.entries ?? []).map(entry => ({ rowId: entry.rowId, text: entry.text, pinyin: entry.pinyin }))
    };
  `);
}

/** 点预览并等到"又有一个新的 PDF blob"，随后把字节落盘并读文字层。 */
async function previewAndFetch(cdp, fileStem) {
  const before = Number(await evaluate(cdp, `return ${pdfBlobCount};`)) || 0;
  const clicked = await evaluate(cdp, CLICK_PREVIEW);
  if (!clicked.ok) return { clicked, produced: false, pdf: null, text: null };
  const produced = await waitFor(cdp, `${pdfBlobCount} > ${before}`, '预览产出新的 PDF blob', 60000).catch(() => null);
  const count = Number(await evaluate(cdp, `return ${pdfBlobCount};`)) || 0;
  const fetched = count > before ? await evaluate(cdp, fetchBlob(count - 1)) : { ok: false, reason: '没有新的 PDF blob' };
  let file = null;
  let text = null;
  if (fetched.ok) {
    file = join(pdfsDir, `${fileStem}.pdf`);
    await writeFile(file, Buffer.from(fetched.base64, 'base64'));
    text = await extractPdfText(file);
    await writeFile(join(logsDir, `${fileStem}-text.json`), `${JSON.stringify(text, null, 2)}\n`);
  }
  return { clicked, produced: produced !== null, count, fetched, file, text };
}

const exe = findChrome();
if (!exe) { console.error('找不到 Chrome。本次未执行（退出码 2）。'); process.exit(2); }
const port = Number(process.env.E2E_CDP_PORT ?? 9371);
let chrome;
let cdp;
let profileDir = null;
let exitCode = 1;
try {
  profileDir = await createProfileDir('tb-snapshot-');
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: profileDir });
  cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: cookieName, value: cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: PAGE_HOOK });

  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
  await waitFor(cdp, EDITOR_READY, '编辑页就绪', 60000);
  await waitFor(cdp, `${leftValue} !== null`, '第一条内容文本框有值', 30000);
  check('手法：在应用脚本之前挂上页面钩子（blob 与 fetch 都可用）',
    (await evaluate(cdp, `return Array.isArray(window.__blobs) && typeof window.fetch === 'function';`)) === true,
    'window.__blobs 与 fetch 钩子均就绪');

  const serverBefore = await readServerDraft(cdp);
  console.log(`[${elapsed()}] 服务器草稿（编辑前）：revision=${serverBefore.revision} entries=${JSON.stringify(serverBefore.entries)}`);
  const pristineEntries = serverBefore.entries.map(entry => ({ rowId: entry.rowId, text: entry.text, pinyin: entry.pinyin }));
  const originalText = await evaluate(cdp, `return ${leftValue};`);
  const originalPinyin = await evaluate(cdp, `return ${rightValue};`);
  console.log(`[${elapsed()}] 第 1 行原值：text=${JSON.stringify(originalText)} pinyin=${JSON.stringify(originalPinyin)}`);

  // 反向验证的产物另存，避免覆盖正式验收（正向）的 PDF 与截图。
  const stem = REVERSE ? 'editor-snapshot-reverse' : 'editor-snapshot';

  // 0) 先做一次"编辑之前"的预览：响应体会被 fetch 钩子记录，
  //    反向验证要拿它冒充"服务器已保存的草稿"。
  const pristineRun = await previewAndFetch(cdp, `${stem}-pristine`);
  check('基线：编辑之前的预览能产出 PDF 并读到文字层',
    pristineRun.produced && Boolean(pristineRun.text),
    `PDF=${pristineRun.fetched?.bytes ?? 0} 字节 sha256=${pristineRun.fetched?.ok ? sha256(Buffer.from(pristineRun.fetched.base64, 'base64')) : null} 页数=${pristineRun.text?.pageCount ?? '—'}`);
  const recorded = Number(await evaluate(cdp, `return window.__previewResponses.length;`)) || 0;
  console.log(`[${elapsed()}] 已记录 ${recorded} 份预览响应体（供反向验证冒充"已保存草稿"）`);

  // 1) 造一次**合法**（汉字数=拼音音节数）但独特、未保存的编辑。
  const setText = await evaluate(cdp, setNative(LEFT, '快照'));
  const setPinyin = await evaluate(cdp, setNative(RIGHT, MARK));
  const renderedText = await waitFor(cdp, `${leftValue} === '快照'`, '文本框显示新汉字', 8000).catch(() => null);
  const renderedPinyin = await waitFor(cdp, `${rightValue} === ${JSON.stringify(MARK)}`, '显示新拼音', 8000).catch(() => null);
  check('手法：原生 setter + input 事件让 Vue 把两个文本框渲染成新值',
    setText?.ok === true && setPinyin?.ok === true && renderedText === '快照' && renderedPinyin === MARK,
    `汉字 setter=${JSON.stringify(setText)} 拼音 setter=${JSON.stringify(setPinyin)}`);
  const serverDuringEdit = await readServerDraft(cdp);
  console.log(`[${elapsed()}] 编辑后（预览前）服务器草稿：revision=${serverDuringEdit.revision} entries=${JSON.stringify(serverDuringEdit.entries)}`);

  // 2) 反向验证开关：让预览改用"已保存草稿"（即编辑之前那份）。
  if (REVERSE) {
    await evaluate(cdp, `window.__reverseServeSaved = true; return true;`);
    console.log(`[${elapsed()}] 反向验证：预览接口响应已被替换为"编辑之前已保存的草稿"`);
  }

  // 3) 不点保存，直接合成点击「预览草稿」。
  const editedRun = await previewAndFetch(cdp, `${stem}-edited`);
  check('手法：合成 el.click() 触发「预览草稿」并产出新的 PDF blob',
    editedRun.clicked.ok === true && editedRun.produced,
    `点击=${JSON.stringify(editedRun.clicked)} PDF blob 数=${editedRun.count}`);
  const canvasCount = Number(await evaluate(cdp, `return document.querySelectorAll('.preview-block canvas').length;`)) || 0;
  const snapshotText = await evaluate(cdp, `return document.querySelector('.preview-snapshot')?.innerText?.replace(/\\s+/g, ' ').trim() ?? '';`);
  console.log(`[${elapsed()}] 预览快照标注=「${snapshotText}」 canvas=${canvasCount}`);
  check('证据：预览面板把 PDF 画了出来（canvas 数 ≥ 1）', canvasCount >= 1, `canvas=${canvasCount}`);
  const shot1 = await cdp.send('Page.captureScreenshot', { format: 'png' });
  await writeFile(
    join(shotsDir, REVERSE ? 'editor-snapshot-preview-reverse.png' : 'editor-snapshot-preview-edited.png'),
    Buffer.from(shot1.data, 'base64')
  );

  const editedSha = editedRun.fetched?.ok ? sha256(Buffer.from(editedRun.fetched.base64, 'base64')) : null;
  check('证据：取回本次预览的真实 PDF 字节并读到文字层',
    Boolean(editedRun.fetched?.ok) && Boolean(editedRun.text),
    editedRun.fetched?.ok ? `${editedRun.fetched.bytes} 字节（记录 size=${editedRun.fetched.recordedSize}）sha256=${editedSha}` : editedRun.fetched?.reason);

  // 4) 核心判定：独特值必须出现在 PDF **文字层**里。
  const editedHasMark = Boolean(editedRun.text) && textContains(editedRun.text.text, MARK);
  check('★判定 1：未保存的编辑（独特拼音值）出现在预览 PDF 的文字层里', editedHasMark,
    editedRun.text ? `PDF 共 ${editedRun.text.pageCount} 页；文字层片段=「${editedRun.text.text.replace(/\\s+/g, ' ').slice(0, 160)}」` : '没有取到 PDF 文字层');

  const serverAfter = await readServerDraft(cdp);
  console.log(`[${elapsed()}] 服务器草稿（预览后）：revision=${serverAfter.revision} entries=${JSON.stringify(serverAfter.entries)}`);
  check('链路：预览把编辑器当前内容同步到了服务器草稿（修订号前进且含独特值）',
    serverAfter.entries.some(entry => String(entry.pinyin ?? '').includes(MARK)) && serverAfter.revision > serverBefore.revision,
    `revision ${serverBefore.revision} → ${serverAfter.revision}；条目=${JSON.stringify(serverAfter.entries)}`);

  // 5) 对照：把编辑改回原值，再预览一次，产物文字层必须不含独特值。
  const revertText = await evaluate(cdp, setNative(LEFT, originalText));
  const revertPinyin = await evaluate(cdp, setNative(RIGHT, originalPinyin));
  await waitFor(cdp, `${leftValue} === ${JSON.stringify(originalText)}`, '文本框恢复原值', 8000).catch(() => null);
  check('对照：能把编辑恢复成原值', revertText?.ok === true && revertPinyin?.ok === true,
    `text=${JSON.stringify(revertText?.value)} pinyin=${JSON.stringify(revertPinyin?.value)}`);

  const revertedRun = await previewAndFetch(cdp, `${stem}-second`);
  check('对照：恢复后再次预览也产出新的 PDF blob',
    revertedRun.clicked.ok === true && revertedRun.produced,
    `点击=${JSON.stringify(revertedRun.clicked)} PDF blob 数=${editedRun.count} → ${revertedRun.count}`);
  const revertedSha = revertedRun.fetched?.ok ? sha256(Buffer.from(revertedRun.fetched.base64, 'base64')) : null;
  check('对照：取回恢复后的 PDF 字节并读到文字层',
    Boolean(revertedRun.fetched?.ok) && Boolean(revertedRun.text),
    revertedRun.fetched?.ok ? `${revertedRun.fetched.bytes} 字节 sha256=${revertedSha}` : revertedRun.fetched?.reason);

  const revertedHasMark = Boolean(revertedRun.text) && textContains(revertedRun.text.text, MARK);
  check('★判定 2：不做修改再预览时，独特值**不再**出现在 PDF 文字层里', Boolean(revertedRun.text) && !revertedHasMark,
    revertedRun.text ? `文字层片段=「${revertedRun.text.text.replace(/\\s+/g, ' ').slice(0, 160)}」` : '没有取到 PDF 文字层');
  // **不能**用 PDF 字节的 sha256 判断"内容变了"：pdf-lib 的子集字体名带随机后缀
  // （`NotoSansCJKsc-Regular-<随机数>`），同一份内容两次生成也会得到不同字节。
  // 可靠的判据是**文字层**。
  check('对照：两次预览的 PDF 文字层不同（差异确实来自这次编辑）',
    Boolean(editedRun.text) && Boolean(revertedRun.text) && editedRun.text.text !== revertedRun.text.text,
    `编辑后文字层=「${(editedRun.text?.text ?? '').replace(/\s+/g, ' ').slice(0, 90)}」；`
    + `恢复后文字层=「${(revertedRun.text?.text ?? '').replace(/\s+/g, ' ').slice(0, 90)}」；`
    + `（字节 sha256 仅供参考、不可作判据：编辑后=${editedSha} 恢复后=${revertedSha}）`);

  const serverFinal = await readServerDraft(cdp);
  check('对照：恢复后的服务器草稿不再含独特值', !serverFinal.entries.some(entry => String(entry.pinyin ?? '').includes(MARK)),
    `revision=${serverFinal.revision} 条目=${JSON.stringify(serverFinal.entries)}`);

  const reverseServed = Number(await evaluate(cdp, `return window.__reverseServed;`)) || 0;
  if (REVERSE) {
    check('反向验证机制：预览接口确实被换成了"已保存草稿"的响应',
      reverseServed >= 1, `被替换的预览响应次数=${reverseServed}`);
  }

  // 6) 复原现场：行数与每行内容都改回验收开始时的样子（脚本可重复运行、不污染样册）。
  const restoreActions = [];
  let liveCount = Number(await evaluate(cdp, `return document.querySelectorAll('.pair-row').length;`)) || 0;
  while (liveCount > pristineEntries.length) {
    const removed = await evaluate(cdp, `
      const rows = [...document.querySelectorAll('.pair-row')];
      const last = rows[rows.length - 1];
      const button = last ? [...last.querySelectorAll('button')].find(item => (item.getAttribute('aria-label') || '').startsWith('删除第')) : null;
      if (!button) return { ok: false, reason: '找不到最后一行删除按钮' };
      button.click();
      return { ok: true, label: button.getAttribute('aria-label') };
    `);
    restoreActions.push(removed);
    if (!removed.ok) break;
    await sleep(400);
    liveCount = Number(await evaluate(cdp, `return document.querySelectorAll('.pair-row').length;`)) || 0;
  }
  for (let index = 0; index < pristineEntries.length; index += 1) {
    const entry = pristineEntries[index];
    await evaluate(cdp, setNative(`.n-input[aria-label="第 ${index + 1} 行汉字"]`, entry.text ?? ''));
    await evaluate(cdp, setNative(`.n-input[aria-label="第 ${index + 1} 行拼音"]`, entry.pinyin ?? ''));
    await sleep(200);
  }
  let restoredDraft = null;
  const restoreDeadline = Date.now() + 30000;
  while (Date.now() < restoreDeadline) {
    restoredDraft = await readServerDraft(cdp);
    const now = restoredDraft.entries.map(entry => ({ rowId: entry.rowId, text: entry.text, pinyin: entry.pinyin }));
    if (JSON.stringify(now) === JSON.stringify(pristineEntries)) break;
    await sleep(600);
  }
  const restoredSnapshot = restoredDraft?.entries.map(entry => ({ rowId: entry.rowId, text: entry.text, pinyin: entry.pinyin })) ?? null;
  check('收尾：本地样册草稿已复原成验收开始时的内容（脚本可重复运行、不污染样册）',
    JSON.stringify(restoredSnapshot) === JSON.stringify(pristineEntries),
    `复原后=${JSON.stringify(restoredSnapshot)} 原始=${JSON.stringify(pristineEntries)}（删行动作 ${restoreActions.length} 次）`);

  const failed = results.filter(item => !item.ok);
  await writeFile(join(logsDir, REVERSE ? 'editor-snapshot-reverse.json' : 'editor-snapshot-raw.json'), `${JSON.stringify({
    mode: MODE, mark: MARK, edition: EDITION, generatedAt: new Date().toISOString(),
    serverBefore, serverDuringEdit, serverAfter, serverFinal,
    pristineEntries, restoredSnapshot, restoreActions,
    pdfs: {
      pristine: { bytes: pristineRun.fetched?.bytes ?? 0, hasMark: Boolean(pristineRun.text) && textContains(pristineRun.text.text, MARK) },
      edited: { bytes: editedRun.fetched?.bytes ?? 0, sha256: editedSha, hasMark: editedHasMark },
      reverted: { bytes: revertedRun.fetched?.bytes ?? 0, sha256: revertedSha, hasMark: revertedHasMark }
    },
    previewResponsesRecorded: recorded,
    reverseServed,
    snapshotText,
    totals: { checks: results.length, failed: failed.length },
    results
  }, null, 2)}\n`);
  console.log(`\n[${elapsed()}] ===== 任务 1 结果：${results.length - failed.length}/${results.length} 通过（${MODE}）=====`);
  for (const item of failed) console.log(`  未通过：${item.label} —— ${item.detail}`);
  exitCode = failed.length ? 1 : 0;
} catch (cause) {
  check('脚本整体执行完成', false, `异常中止：${cause?.message ?? cause}`);
  console.log(String(cause?.stack ?? cause));
  exitCode = 1;
} finally {
  try { cdp?.close(); } catch { /* 已断开 */ }
  await stopChrome(chrome, profileDir);
  await removeProfileDir(profileDir);
}
process.exit(exitCode);
