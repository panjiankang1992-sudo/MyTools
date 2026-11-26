#!/usr/bin/env node
/**
 * B09：**真实断网／丢响应的端到端 + 幂等**（真实浏览器 + 真实后端 + 真实 MySQL）。
 *
 * 缺口原文：`{ id: 'B09', note: '同键重试有测试；真实断网/丢响应端到端未做' }`
 *
 * 选的写路径：**保存草稿** `PUT /admin/v1/textbooks/{editionId}/draft`。
 * 理由（先读代码得出）：
 * - 它是编辑器**每次内容变化约 2 秒后**自动触发的唯一写路径
 *   （`TextbookEditorView.markDirtyAndSchedule` → `save()` → `saveDraft`），
 *   因此是"断网"最可能真实命中的那条；
 * - 它同时带 `If-Match`（CAS 并发控制）与 `Idempotency-Key`（幂等回执），
 *   两项正是本缺口要验的机制（`AdminTextbookController.save`）；
 * - 发布路径更重（会重建目录快照），本脚本只在最后一相用**同键重放**验证版本不重复。
 *
 * 三相：
 * A. **真断网**（请求根本没到服务器：CDP `Fetch` 请求阶段 `failRequest(InternetDisconnected)`）
 *    → 断言界面如实报错、不谎报成功、服务端草稿一个字节都没变、保存时间戳不前进；
 * B. **恢复网络后重试**（真实点击「保存草稿」）→ 断言成功、修订号**只前进 1**、
 *    内容只写一次、条目与 rowId 不变、教材总数不变（不产生重复草稿）；
 * C. **丢响应**（服务器已写、响应在返回途中被丢弃：`Fetch` 响应阶段 `failRequest`）
 *    → 用**同一个幂等键 + 同一份正文**重放 → 断言服务端返回**原结果**、修订号**不再前进**、
 *    内容没有第二份（真幂等，而不是"又写了一次"）。
 * D. 发布路径**同键重放两次** → 断言只产生**一个**版本记录。
 *
 * 用法（**只允许回环**）：
 *   E2E_TARGET=http://127.0.0.1:4175 E2E_ACCOUNT=local-admin E2E_PASSWORD=local-admin \
 *     node tests/e2e/remaining-b09-offline-retry.mjs
 */

import { connect, createProfileDir, evaluate, findChrome, launchChrome, removeProfileDir, stopChrome, sleep, waitFor } from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';
import {
  armWatchdog, CLICK_TEXT, NATIVE_SET, READ_ROWS,
  adminLogin, api, createDraft, createHarness, draftPayload, listTextbooks, listVersions, readDraft
} from './remaining-lib.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const ACCOUNT = process.env.E2E_ACCOUNT ?? 'local-admin';
const PASSWORD = process.env.E2E_PASSWORD ?? 'local-admin';
const stamp = Date.now().toString(36).slice(-5);
const EDITION = process.env.E2E_EDITION ?? `e2e-b09-${stamp}`;
const MARK_A = `断网${stamp}`;
const MARK_B = `丢响应${stamp}`;
const publishKey = `publish-${stamp}-idem`;
const disarm = armWatchdog('B09 断网/丢响应');
const harness = createHarness('remaining-b09-offline-retry');
const { check, info } = harness;

/** 草稿修订号轨迹：在每个相位边界记一次（回答"修订号为什么跳这么多"）。 */
const revisionTrace = [];

const READ_ALERTS = `() => [...document.querySelectorAll('.editor-alert')].map(el => (el.textContent ?? '').replace(/\\s+/g, ' ').trim())`;
const READ_REVISION_NOTE = `() => [...document.querySelectorAll('.admin-note')].map(el => (el.textContent ?? '').trim()).filter(text => text.includes('修订')).join(' | ')`;
const ROW1 = `() => document.querySelector('.n-input[aria-label="第 1 行汉字"]')?.querySelector('textarea, input')?.value ?? null`;

let chrome;
let cdp;
let profileDir = null;
let exitCode = 1;
try {
  const auth = await adminLogin(base, ACCOUNT, PASSWORD);
  const created = await createDraft(base, auth, draftPayload(EDITION, [
    { text: '山', pinyin: 'shān' },
    { text: '水', pinyin: 'shuǐ' }
  ]));
  if (created.status !== 201) throw new Error(`建草稿失败：${created.status} ${created.text.slice(0, 300)}`);
  const baseline = await readDraft(base, auth, EDITION);
  const baselineIds = baseline.draft.entries.map(entry => entry.rowId);
  const textbooksBefore = await listTextbooks(base, auth);
  revisionTrace.push({ at: '基线（建册后）', revision: baseline.revision });
  console.log(`样册 ${EDITION} 已建，revision=${baseline.revision}，教材总数=${textbooksBefore.total}`);

  const exe = findChrome();
  if (!exe) { console.error('找不到 Chrome：本次未执行（退出码 2）。'); process.exit(2); }
  const port = Number(process.env.E2E_CDP_PORT ?? 9381);
  profileDir = await createProfileDir('tb-b09-');
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: profileDir });
  cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: auth.cookieName, value: auth.cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });

  /** 捕获写请求的幂等键与正文（用于"同键重放"）。 */
  const writes = [];
  /** 捕获响应状态（按 requestId 与上面的写请求对应）。 */
  const responses = [];
  cdp.on(message => {
    if (message.method === 'Network.responseReceived') {
      responses.push({ requestId: message.params.requestId, status: message.params.response.status });
    }
  });
  cdp.on(message => {
    if (message.method !== 'Network.requestWillBeSent') return;
    const request = message.params.request;
    if (request.method === 'PUT' && request.url.includes(`/textbooks/${EDITION}/draft`)) {
      const headers = request.headers ?? {};
      writes.push({
        requestId: message.params.requestId,
        url: request.url,
        method: request.method,
        idempotencyKey: headers['Idempotency-Key'] ?? headers['idempotency-key'] ?? null,
        ifMatch: headers['If-Match'] ?? headers['if-match'] ?? null,
        postData: request.postData ?? null
      });
    }
  });

  // ---------- 控制 CDP 的请求拦截：决定"这次写请求去哪儿" ----------
  const fetchState = { mode: 'off' }; // off | request-phase-fail | response-phase-fail
  const pausedLog = [];
  cdp.on(message => {
    if (message.method !== 'Fetch.requestPaused') return;
    const { requestId, request, responseStatusCode, responseErrorReason } = message.params;
    const isWrite = request.method === 'PUT' && request.url.includes(`/textbooks/${EDITION}/draft`);
    const stage = responseStatusCode !== undefined || responseErrorReason !== undefined ? 'Response' : 'Request';
    // 只拦写请求；GET 一律放行（否则页面加载会被我们自己挂住）。
    if (!isWrite || fetchState.mode === 'off') {
      void cdp.send('Fetch.continueRequest', { requestId }).catch(() => {});
      return;
    }
    if (fetchState.mode === 'request-phase-fail' && stage === 'Request') {
      pausedLog.push({ stage, action: 'fail', key: request.headers['Idempotency-Key'] ?? null, ifMatch: request.headers['If-Match'] ?? null });
      void cdp.send('Fetch.failRequest', { requestId, errorReason: 'InternetDisconnected' }).catch(() => {});
      return;
    }
    if (fetchState.mode === 'response-phase-fail' && stage === 'Response') {
      pausedLog.push({
        stage,
        action: 'fail',
        status: responseStatusCode,
        key: null,
        captured: writes.at(-1) ?? null
      });
      void cdp.send('Fetch.failRequest', { requestId, errorReason: 'ConnectionFailed' }).catch(() => {});
      return;
    }
    void cdp.send('Fetch.continueRequest', { requestId }).catch(() => {});
  });

  const enableFetch = async stage => {
    await cdp.send('Fetch.enable', { patterns: [{ urlPattern: `*/textbooks/${EDITION}/draft*`, requestStage: stage }] });
  };
  const disableFetch = async () => {
    await cdp.send('Fetch.disable');
  };

  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
  await waitFor(cdp, `(${READ_ROWS})().length === 2`, '编辑页渲染出 2 条内容', 60000);
  await waitFor(cdp, `(${ROW1})() === '山'`, '第 1 行显示样册原值', 20000);
  const revisionNoteBefore = await evaluate(cdp, `return (${READ_REVISION_NOTE})();`);
  info('基线：修订/保存于 文案', JSON.stringify(revisionNoteBefore));

  // ================= 相位 A：真断网（请求未达服务器） =================
  await enableFetch('Request');
  fetchState.mode = 'request-phase-fail';
  await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', ${JSON.stringify(`山${MARK_A}`)});`);
  let errorShown = true;
  try {
    await waitFor(cdp, `(${READ_ALERTS})().some(text => text.includes('操作失败'))`, '断网后页面出现「操作失败」', 30000);
  } catch { errorShown = false; }

  const alertsAfterFail = await evaluate(cdp, `return (${READ_ALERTS})();`);
  const revisionNoteAfterFail = await evaluate(cdp, `return (${READ_REVISION_NOTE})();`);
  check('相位A：界面如实显示失败', errorShown && alertsAfterFail.some(text => text.includes('操作失败')), JSON.stringify(alertsAfterFail));
  check('相位A：**没有**谎报成功（页面没有「已保存」提示）',
    !alertsAfterFail.some(text => text.includes('草稿已保存')), JSON.stringify(alertsAfterFail));
  check('相位A：保存时间戳没有前进（savedAt 不变）',
    revisionNoteAfterFail === revisionNoteBefore, `${JSON.stringify(revisionNoteBefore)} → ${JSON.stringify(revisionNoteAfterFail)}`);

  const afterOffline = await readDraft(base, auth, EDITION);
  check('相位A：服务端草稿**一个字节都没变**（内容与修订号都未动）',
    afterOffline.revision === baseline.revision && !JSON.stringify(afterOffline.draft.entries).includes(MARK_A),
    `revision ${baseline.revision} → ${afterOffline.revision}，条目=${JSON.stringify(afterOffline.draft.entries.map(entry => entry.text))}`);
  revisionTrace.push({ at: '相位A：断网写被拦下之后', revision: afterOffline.revision });
  const offlineWrite = writes.find(item => (item.postData ?? '').includes(MARK_A));
  info('相位A：被拦下的写请求', JSON.stringify({
    idempotencyKey: offlineWrite?.idempotencyKey ?? null,
    ifMatch: offlineWrite?.ifMatch ?? null,
    payloadHasMark: Boolean(offlineWrite?.postData?.includes(MARK_A))
  }));
  info('相位A：CDP 拦截动作', JSON.stringify(pausedLog));

  // ================= 相位 B：恢复网络后重试（真实点击） =================
  fetchState.mode = 'off';
  await disableFetch();
  const revisionBeforeRetry = afterOffline.revision;
  check('相位B：合成 click 点到「保存草稿」', await evaluate(cdp, `return (${CLICK_TEXT})('保存草稿');`));
  try {
    await waitFor(cdp, `(${READ_ALERTS})().some(text => text.includes('草稿已保存'))`, '重试后页面显示「草稿已保存」', 30000);
  } catch { /* 由下面的服务端断言报出 */ }

  const afterRetry = await readDraft(base, auth, EDITION);
  const retryWrite = writes.filter(item => (item.postData ?? '').includes(MARK_A)).at(-1);
  // 判据刻意**不写成"修订号恰好 +1"**：本轮实测发现自动保存会每约 2 秒重复写一次
  // （见下一条 INFO 与 `remaining-b09b-autosave-loop.mjs`），
  // 因此"恰好 +1"只是时序巧合。这里断言的是**与循环无关**的性质：
  // 重试的那次写请求被服务端接受（200）、内容落库且只出现一次。
  const acceptedPut = responses.find(item => item.requestId
    && writes.some(write => write.requestId === item.requestId && (write.postData ?? '').includes(MARK_A))
    && item.status === 200);
  check('相位B：重试的写请求被服务端接受（200）',
    Boolean(acceptedPut) && afterRetry.revision > revisionBeforeRetry,
    JSON.stringify({ status: acceptedPut?.status ?? null, revision: `${revisionBeforeRetry} → ${afterRetry.revision}` }));
  check('相位B：内容写入且只出现一次（不产生重复条目）',
    afterRetry.draft.entries.filter(entry => String(entry.text).includes(MARK_A)).length === 1
      && afterRetry.draft.entries.length === baselineIds.length,
    JSON.stringify(afterRetry.draft.entries.map(entry => `${entry.rowId}:${entry.text}`)));
  check('相位B：条目标识与顺序不变',
    JSON.stringify(afterRetry.draft.entries.map(entry => entry.rowId)) === JSON.stringify(baselineIds),
    JSON.stringify(afterRetry.draft.entries.map(entry => entry.rowId)));
  const textbooksAfterRetry = await listTextbooks(base, auth);
  check('相位B：教材总数不变（不产生重复草稿）',
    textbooksAfterRetry.total === textbooksBefore.total
      && textbooksAfterRetry.items.filter(item => item.editionId === EDITION).length === 1,
    `total ${textbooksBefore.total} → ${textbooksAfterRetry.total}`);
  // 循环实测：重试成功后静置 6 秒，看服务端修订号有没有被"继续自动保存"推动。
  const revisionAtQuietStart = afterRetry.revision;
  revisionTrace.push({ at: '相位B：重试成功后', revision: revisionAtQuietStart });
  await sleep(6500);
  const revisionAfterQuiet = (await readDraft(base, auth, EDITION)).revision;
  revisionTrace.push({
    at: '相位B：静置 6.5 秒（自动保存循环自己推进）',
    revision: revisionAfterQuiet,
    autoSavesDuringQuiet: revisionAfterQuiet - revisionAtQuietStart
  });
  info('实测（新发现缺陷）：重试成功后静置 6.5 秒，修订号变化',
    `${revisionAtQuietStart} → ${revisionAfterQuiet}（多出 ${revisionAfterQuiet - revisionAtQuietStart} 次自动保存；详见 remaining-b09b-autosave-loop.mjs）`);
  info('相位B：重试用的幂等键 vs 失败那次的键',
    JSON.stringify({ failedKey: offlineWrite?.idempotencyKey ?? null, retriedKey: retryWrite?.idempotencyKey ?? null, reused: offlineWrite?.idempotencyKey === retryWrite?.idempotencyKey }));

  // ================= 相位 C：丢响应（服务器已写、响应丢失）+ 同键重放 =================
  //
  // **必须在触发这次写之前**读一次服务端修订号，作为相位C 的锚点 ✗。
  // 此前这条断言锚在 `afterRetry.revision`（相位B 结束时的值）✗，而中间还隔着
  // "静置 6.5 秒"里自动保存循环自己的若干次推进 ✗ —— 于是 detail 打印出
  // "revision 3 → 7" ✗，看起来像"多写了 4 次"✗，实际是"3 →(循环 3 次)→ 6 →(相位C 一次)→ 7"✓。
  // 真正的"多写一次"由后面的**两次同键重放修订号必须相同**把关 ✓。
  const revisionBeforeLost = (await readDraft(base, auth, EDITION)).revision;
  revisionTrace.push({ at: '相位C：触发丢响应写之前（本断言的锚点）', revision: revisionBeforeLost });
  await enableFetch('Response');
  fetchState.mode = 'response-phase-fail';
  await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', ${JSON.stringify(`山${MARK_B}`)});`);
  try {
    await waitFor(cdp, `(${READ_ALERTS})().some(text => text.includes('操作失败'))`, '丢响应后页面出现「操作失败」', 30000);
  } catch { /* 由相位C 的断言报出 */ }
  fetchState.mode = 'off';
  await disableFetch();

  const lostWrite = writes.find(item => (item.postData ?? '').includes(MARK_B)) ?? null;
  const afterLost = await readDraft(base, auth, EDITION);
  revisionTrace.push({ at: '相位C：丢响应写之后', revision: afterLost.revision });
  // 同样避开"恰好 +1"（自动保存循环会额外推进修订号）：这里断言的是
  // **服务器确实写入了这次内容**（响应被丢在路上），修订号相对**本次写之前**的实测值前进了。
  check('相位C：服务器其实已经写入（响应丢在路上，客户端却看到失败）',
    afterLost.revision > revisionBeforeLost && JSON.stringify(afterLost.draft.entries).includes(MARK_B),
    `revision ${revisionBeforeLost}（本次写之前实测） → ${afterLost.revision}（响应被丢弃之后），条目=${JSON.stringify(afterLost.draft.entries.map(entry => entry.text))}`);
  check('相位C：界面看到的是失败（不谎报成功）',
    !(await evaluate(cdp, `return (${READ_ALERTS})();`)).some(text => text.includes('草稿已保存')),
    JSON.stringify(await evaluate(cdp, `return (${READ_ALERTS})();`)));
  info('相位C：被丢响应的那次写请求',
    JSON.stringify({ idempotencyKey: lostWrite?.idempotencyKey ?? null, ifMatch: lostWrite?.ifMatch ?? null }));

  // 用**同一个键 + 同一份正文 + 同一个 If-Match**重放（同一会话、同一端点、真实服务端）。
  //
  // 判据刻意选成**对"自动保存循环"免疫**的形式：同键重放**两次**，两次必须返回
  // **同一个修订号**。若回执没有被复用，第二次重放就会真的再写一次、修订号必然前进，
  // 于是两次返回值不可能相同。这个判据与"后台有没有别的写"无关。
  const replay = lostWrite
    ? await evaluate(cdp, `
        const session = await fetch('/admin/v1/session', { credentials: 'same-origin' }).then(r => r.json());
        const token = session?.data?.csrfToken;
        const response = await fetch(${JSON.stringify(`/admin/v1/textbooks/${EDITION}/draft`)}, {
          method: 'PUT',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/json',
            'X-Toolbox-Admin-Csrf': token,
            'Idempotency-Key': ${JSON.stringify(lostWrite.idempotencyKey)},
            'If-Match': ${JSON.stringify(lostWrite.ifMatch)}
          },
          body: ${JSON.stringify(lostWrite.postData)}
        });
        return { status: response.status, body: await response.text() };
      `)
    : null;
  const replayAgain = lostWrite
    ? await evaluate(cdp, `
        const session = await fetch('/admin/v1/session', { credentials: 'same-origin' }).then(r => r.json());
        const token = session?.data?.csrfToken;
        const response = await fetch(${JSON.stringify(`/admin/v1/textbooks/${EDITION}/draft`)}, {
          method: 'PUT',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/json',
            'X-Toolbox-Admin-Csrf': token,
            'Idempotency-Key': ${JSON.stringify(lostWrite.idempotencyKey)},
            'If-Match': ${JSON.stringify(lostWrite.ifMatch)}
          },
          body: ${JSON.stringify(lostWrite.postData)}
        });
        return { status: response.status, body: await response.text() };
      `)
    : null;
  info('相位C：同键重放第一次的响应', JSON.stringify(replay));
  info('相位C：同键重放第二次的响应', JSON.stringify(replayAgain));

  const revisionOf = body => Number(/"revision":(\d+)/.exec(String(body ?? ''))?.[1] ?? NaN);
  const afterReplay = await readDraft(base, auth, EDITION);
  check('相位C：同键重放返回 200 且**返回被丢响应那次的修订号**',
    replay?.status === 200 && revisionOf(replay?.body) === afterLost.revision,
    JSON.stringify({ replay: replay?.body, lostRevision: afterLost.revision }));
  check('相位C：**两次**同键重放返回同一个修订号（回执被复用、没有再写一次）',
    replayAgain?.status === 200 && revisionOf(replayAgain?.body) === revisionOf(replay?.body),
    JSON.stringify({ first: revisionOf(replay?.body), second: revisionOf(replayAgain?.body) }));
  check('相位C：重放返回的修订号不超过当时服务端修订号（没有新写入）',
    revisionOf(replay?.body) <= afterReplay.revision, `${revisionOf(replay?.body)} <= ${afterReplay.revision}`);
  check('相位C：内容没有第二份（同一条内容只出现一次）',
    afterReplay.draft.entries.filter(entry => String(entry.text).includes(MARK_B)).length === 1,
    JSON.stringify(afterReplay.draft.entries.map(entry => `${entry.rowId}:${entry.text}`)));
  check('相位C：条目标识与顺序仍然不变',
    JSON.stringify(afterReplay.draft.entries.map(entry => entry.rowId)) === JSON.stringify(baselineIds),
    JSON.stringify(afterReplay.draft.entries.map(entry => entry.rowId)));
  revisionTrace.push({ at: '相位C：同键重放两次之后', revision: afterReplay.revision });
  info('草稿修订号轨迹（每个相位边界实测一次）', JSON.stringify(revisionTrace));

  // 丢响应之后再点一次「保存草稿」，**两种结果都是安全的**：
  //  (a) 客户端用**新键 + 旧的 If-Match** 再发 → 服务端 409 → 冲突面板（不静默覆盖）；
  //  (b) 客户端用**同一个幂等键**重发冻住的 payload（`SaveState.fail(frozen)` → `pendingRetry`）
  //      → 服务端返回**原回执**（200）→ 修订号**不前进**，界面显示「草稿已保存。」
  //      —— 这是 §5.7 设计的"同键重试"，比 (a) 更好（用户不必走冲突恢复）。
  //
  // **不允许**的是第三种：用**新键**真的又写了一次（修订号前进 = 多写一次 / 静默覆盖）。
  // 判据刻意写成"不是第三种"，而不是写死"必须出现冲突面板" ✗ ——
  // 后者只是 `SaveState.fail(frozen)` 尚未接上时的**时序产物** ✗：
  // 当时 `pendingRetry` 永远为 null，重试每次都换新键（脚本自己的 INFO 实测 `reused=false`），
  // 所以必然撞 409。接上之后 (b) 成立，界面不再进冲突面板 ✓，而安全性**只增不减** ✓。
  const writeCountBeforeResave = writes.length;
  const revisionBeforeResave = (await readDraft(base, auth, EDITION)).revision;
  await evaluate(cdp, `return (${CLICK_TEXT})('保存草稿');`);
  let conflictAppeared = true;
  try {
    await waitFor(cdp, `document.querySelector('.conflict-block') !== null`, '冲突面板出现', 25000);
  } catch {
    conflictAppeared = false;
  }
  const resaveWrites = writes.slice(writeCountBeforeResave);
  const revisionAfterResave = (await readDraft(base, auth, EDITION)).revision;
  const resaveSameKey = resaveWrites.length > 0
    && resaveWrites.every(item => item.idempotencyKey === lostWrite?.idempotencyKey);
  info('实测：丢响应后再点一次「保存草稿」的结果', JSON.stringify({
    alerts: await evaluate(cdp, `return (${READ_ALERTS})();`),
    conflictAppeared,
    lostKey: lostWrite?.idempotencyKey ?? null,
    resaveKeys: resaveWrites.map(item => item.idempotencyKey),
    revision: `${revisionBeforeResave} → ${revisionAfterResave}`
  }));
  check('实测：丢响应后再点「保存草稿」不会**用新键多写一次**（要么 409 冲突面板，要么同键重发被回执接住）',
    conflictAppeared || (resaveSameKey && revisionAfterResave === revisionBeforeResave),
    JSON.stringify({ conflictAppeared, resaveSameKey, revision: `${revisionBeforeResave} → ${revisionAfterResave}` }));
  if (conflictAppeared) {
    // 走**真实恢复路径**：读取最新 → 二次确认。
    check('恢复：合成 click 点到「读取最新」', await evaluate(cdp, `return (${CLICK_TEXT})('读取最新');`));
    await waitFor(cdp, `[...document.querySelectorAll('button')].some(button => (button.textContent ?? '').trim() === '确认读取最新')`, '出现二次确认', 10000);
    check('恢复：合成 click 点到「确认读取最新」', await evaluate(cdp, `return (${CLICK_TEXT})('确认读取最新');`));
    await waitFor(cdp, `document.querySelector('.conflict-block') === null`, '冲突面板关闭（已对齐服务器修订号）', 25000);
  }

  // ================= 相位 D：发布同键重放两次 → 只产生一个版本 =================
  // 发布要过服务端草稿级校验（未确认条目／拼音音节数都要对），
  // 因此先把内容改成"1 个汉字 + 1 个音节"，再走界面上的「确认内容」+「保存草稿」。
  await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', '海');`);
  await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行拼音"]', 'hǎi');`);
  await waitFor(cdp, `(${ROW1})() === '海'`, '第 1 行改成可发布的「海／hǎi」', 10000);
  await waitFor(cdp, `(${READ_ALERTS})().some(text => text.includes('草稿已保存'))`, '改动已自动保存', 30000);
  check('相位D：合成 click 点到「确认内容」', await evaluate(cdp, `return (${CLICK_TEXT})('确认内容');`));
  await waitFor(cdp, `(${READ_ALERTS})().some(text => text.includes('已确认'))`, '页面提示已确认', 15000);
  check('相位D：合成 click 点到「保存草稿」', await evaluate(cdp, `return (${CLICK_TEXT})('保存草稿');`));
  await sleep(1500);

  // **发布前先卸载编辑器**：自动保存循环会每约 2 秒把草稿修订号推进一次，
  // 而发布请求会绑定 `draftRevision`（绑定管理员确认过的那一版），
  // 循环与发布抢修订号会让结论变成时序巧合。这里切到首页让编辑器真正卸载，
  // 再等到"连续 6 秒没有任何草稿写请求"为止。
  const putCountAtUnmount = writes.length;
  await evaluate(cdp, `location.hash = '#/'; return true;`);
  await sleep(6000);
  const quiet = writes.length === putCountAtUnmount;
  info('实测（同一缺陷的延伸）：切到首页卸载编辑器后草稿写请求是否停下',
    JSON.stringify({ quiet, writesBefore: putCountAtUnmount, writesAfter: writes.length }));

  const publishable = await (async () => {
    const deadline = Date.now() + 30000;
    let last = null;
    while (Date.now() < deadline) {
      const result = await api(base, `/admin/v1/textbooks/${encodeURIComponent(EDITION)}/validate`, {
        method: 'POST', cookie: auth.cookie, csrf: auth.csrfToken
      });
      last = result.payload?.data ?? null;
      if (last?.publishable) return last;
      await sleep(800);
    }
    return last;
  })();
  check('相位D：草稿已可发布（服务端校验通过）', Boolean(publishable?.publishable),
    JSON.stringify(publishable?.issues ?? publishable));
  const revisionBeforePublish = (await readDraft(base, auth, EDITION)).revision;

  const intent = { draftRevision: revisionBeforePublish, baseContentVersion: null, changeNote: `B09 幂等验收 ${stamp}` };
  const publishUrl = `/admin/v1/textbooks/${encodeURIComponent(EDITION)}/publish?draftRevision=${revisionBeforePublish}`;
  const publishOnce = await api(base, publishUrl, {
    method: 'POST', cookie: auth.cookie, csrf: auth.csrfToken, idempotencyKey: publishKey, body: intent
  });
  const publishTwice = await api(base, publishUrl, {
    method: 'POST', cookie: auth.cookie, csrf: auth.csrfToken, idempotencyKey: publishKey, body: intent
  });
  const revisionAfterPublish = (await readDraft(base, auth, EDITION)).revision;
  const versions = await listVersions(base, auth, EDITION);
  info('相位D：两次发布响应', JSON.stringify({ first: publishOnce.payload?.data ?? publishOnce.text, second: publishTwice.payload?.data ?? publishTwice.text }));
  check('相位D：第一次发布成功', publishOnce.status === 200, `${publishOnce.status} ${publishOnce.text.slice(0, 200)}`);
  check('相位D：同键第二次发布返回同一个版本号（复用回执）',
    publishTwice.status === 200
      && publishTwice.payload?.data?.contentVersion === publishOnce.payload?.data?.contentVersion,
    JSON.stringify({ first: publishOnce.payload?.data?.contentVersion, second: publishTwice.payload?.data?.contentVersion }));
  check('相位D：版本记录只有一条（不产生重复版本）',
    versions.length === 1, JSON.stringify(versions));
  // 发布本身会把 baseContentVersion 写回草稿（修订号会前进一次，这是既有设计），
  // 因此这里比较的是"**第一次发布之后**"与"第二次发布之后"。
  const revisionAfterFirstPublish = revisionAfterPublish;
  // 修订号比较在"自动保存循环"下天然带竞态，因此只作实测记录；
  // 幂等的**确定性**判据是下面这条：第二次发布返回的版本三要素完全一致。
  info('实测：两次发布之间草稿修订号',
    JSON.stringify({ beforePublish: revisionBeforePublish, afterFirstPublish: revisionAfterFirstPublish, now: (await readDraft(base, auth, EDITION)).revision }));
  const sameVersion = ['contentVersion', 'versionId', 'contentSha256']
    .every(field => publishOnce.payload?.data?.[field] === publishTwice.payload?.data?.[field]);
  check('相位D：第二次发布返回的版本三要素与第一次完全一致（回执被复用）',
    sameVersion, JSON.stringify({ first: publishOnce.payload?.data, second: publishTwice.payload?.data }));

  exitCode = harness.failed().length ? 1 : 0;
  console.log(`\n通过 ${harness.results.filter(item => item.ok && !item.informational).length}/${harness.results.filter(item => !item.informational).length}`);
} catch (error) {
  check('脚本执行完成', false, error?.message ?? String(error));
  exitCode = 1;
} finally {
  await stopChrome(chrome, profileDir);
  await removeProfileDir(profileDir);
  await harness.write({ editionId: EDITION, markA: MARK_A, markB: MARK_B });
  disarm();
  process.exit(exitCode);
}
