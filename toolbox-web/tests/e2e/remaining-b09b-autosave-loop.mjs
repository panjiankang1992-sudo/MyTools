#!/usr/bin/env node
/**
 * **缺陷复现脚本**：管理编辑器的自动保存会**每约 2 秒重复写一次**，永不停止。
 *
 * 这是本轮 B09 验收过程中**新发现**的真实缺陷（不在原缺口描述里），因此单独成脚本，
 * 免得把它混进"缺口已收掉"的结论里。
 *
 * **本脚本在当前代码上应当失败**（断言"不该继续重复保存"）；修好后应当全绿。
 *
 * 机理（读代码 + 实测得出）：
 * `TextbookEditorView.save()` 一开始就调用 `onRowsUpdate(rows.value)`，
 * 而 `onRowsUpdate()` 无条件调用 `markDirtyAndSchedule()` —— 也就是
 * **每保存一次就把"脏"标回来，并再排一次 2 秒后的保存**：
 *
 * ```text
 * save() → onRowsUpdate() → markDirtyAndSchedule() → 2s 后 save() → …
 * ```
 *
 * 后果：只要编辑器开着并且发生过一次保存，服务端草稿修订号就会**每 2 秒前进一次**，
 * 每次都是整份草稿的写库（写放大）；`ETag`／`If-Match` 一直变；
 * "有新改动待保存"永远不会安定；修订号含义被稀释。
 *
 * 判据全部落在**服务端数据**与**真实网络请求**上，不依赖界面文案：
 * 1. 对照组①：完全不输入，观察 8 秒 → 期望 0 次写请求；
 * 2. 主组：一次合成输入（Vue 真收到）后观察 12 秒 → 期望**不再**重复写；
 * 3. 对照组②：**不改任何内容**，只点一次「保存草稿」，观察 8 秒 → 期望不再重复写。
 *
 * 用法（**只允许回环**）：
 *   E2E_TARGET=http://127.0.0.1:4175 node tests/e2e/remaining-b09b-autosave-loop.mjs
 */

import { connect, createProfileDir, evaluate, findChrome, launchChrome, removeProfileDir, stopChrome, sleep, waitFor } from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';
import {
  armWatchdog, CLICK_TEXT, NATIVE_SET, READ_ROWS, adminLogin, createDraft, createHarness, draftPayload, readDraft
} from './remaining-lib.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const ACCOUNT = process.env.E2E_ACCOUNT ?? 'local-admin';
const PASSWORD = process.env.E2E_PASSWORD ?? 'local-admin';
const EDITION = process.env.E2E_EDITION ?? `e2e-loop-${Date.now().toString(36).slice(-5)}`;
const disarm = armWatchdog('B09b 自动保存循环复现');
const harness = createHarness('remaining-b09b-autosave-loop');
const { check, info } = harness;

/** 判据阈值：单次输入后允许的"额外"写请求次数（正常应恰好 1 次）。 */
const ALLOWED_EXTRA_WRITES = 1;

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
  console.log(`样册 ${EDITION} 已建，revision=${created.payload.data.revision}`);

  const exe = findChrome();
  if (!exe) { console.error('找不到 Chrome：本次未执行（退出码 2）。'); process.exit(2); }
  const port = Number(process.env.E2E_CDP_PORT ?? 9481);
  profileDir = await createProfileDir('tb-loop-');
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: profileDir });
  cdp = await connect(port);
  const puts = [];
  cdp.on(message => {
    if (message.method === 'Network.requestWillBeSent'
      && message.params.request.method === 'PUT'
      && message.params.request.url.includes(`/textbooks/${EDITION}/draft`)) {
      puts.push(Date.now());
    }
  });
  await cdp.send('Network.enable');
  await cdp.send('Page.enable');
  await cdp.send('Network.setCookie', { name: auth.cookieName, value: auth.cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
  await waitFor(cdp, `(${READ_ROWS})().length === 2`, '编辑页渲染出条目', 60000);

  // ---------- 对照组①：完全不输入 ----------
  const idleStart = puts.length;
  await sleep(8000);
  const idleWrites = puts.length - idleStart;
  check('对照组①：不输入时不该有任何草稿写请求', idleWrites === 0, `${idleWrites} 次`);

  // ---------- 主组：一次合成输入 ----------
  const revisionBeforeEdit = (await readDraft(base, auth, EDITION)).revision;
  const editStart = puts.length;
  await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', '山循环');`);
  await sleep(12000);
  const editWrites = puts.length - editStart;
  const revisionAfterEdit = (await readDraft(base, auth, EDITION)).revision;
  const gaps = puts.slice(editStart + 1).map((at, index) => at - puts[editStart + index]);
  info('主组实测：一次输入后 12 秒内的写请求间隔(ms)', JSON.stringify(gaps));
  info('主组实测：修订号轨迹', `${revisionBeforeEdit} → ${revisionAfterEdit}`);
  check('主组：一次内容改动只应触发一次保存（允许 1 次，不该每 2 秒重复）',
    editWrites <= ALLOWED_EXTRA_WRITES,
    `实际 ${editWrites} 次写请求，间隔=${JSON.stringify(gaps)}，修订号 ${revisionBeforeEdit} → ${revisionAfterEdit}`);
  check('主组：静置期间服务端修订号不该继续前进',
    revisionAfterEdit - revisionBeforeEdit <= ALLOWED_EXTRA_WRITES,
    `多出 ${revisionAfterEdit - revisionBeforeEdit} 次`);

  // ---------- 对照组②：不改内容，只点一次「保存草稿」 ----------
  const revisionBeforeClick = (await readDraft(base, auth, EDITION)).revision;
  const clickStart = puts.length;
  check('对照组②：合成 click 点到「保存草稿」', await evaluate(cdp, `return (${CLICK_TEXT})('保存草稿');`));
  await sleep(8000);
  const clickWrites = puts.length - clickStart;
  const revisionAfterClick = (await readDraft(base, auth, EDITION)).revision;
  check('对照组②：没有内容改动时，保存一次之后不该继续重复写',
    clickWrites <= ALLOWED_EXTRA_WRITES,
    `实际 ${clickWrites} 次写请求，修订号 ${revisionBeforeClick} → ${revisionAfterClick}`);

  exitCode = harness.failed().length ? 1 : 0;
  console.log(`\n通过 ${harness.results.filter(item => item.ok && !item.informational).length}/${harness.results.filter(item => !item.informational).length}`);
  if (harness.failed().length) {
    console.log('（本脚本是**缺陷复现**：当前代码上失败即代表缺陷存在；修复后应当全绿。）');
  }
} catch (error) {
  check('脚本执行完成', false, error?.message ?? String(error));
  exitCode = 1;
} finally {
  await stopChrome(chrome, profileDir);
  await removeProfileDir(profileDir);
  await harness.write({ editionId: EDITION });
  disarm();
  process.exit(exitCode);
}
