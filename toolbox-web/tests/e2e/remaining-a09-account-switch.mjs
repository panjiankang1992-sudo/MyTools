#!/usr/bin/env node
/**
 * A09：**第二账号的真实浏览器端到端**（换账号不串草稿／不串内容）。
 *
 * 缺口原文：`{ id: 'A09', note: '…仍缺真实浏览器端到端与第二账号' }`
 *
 * 本地假身份（**只在测试源集**，双重闸门 `local-admin` profile + `local-admin-identity-stub=true`）
 * 原本只支持一个账号（`local-stub-account`）。本轮在**同一个测试专用文件**里增加了
 * **可选的第二账号** `local-stub-account-2`（默认留空 = 行为与原来完全一致），
 * 于是可以在**同一个源、同一个后端、同一个数据库**上做真实的 A→B 换账号验收；
 * 没有在生产建账号，也没有伪造"另一个账号"。
 *
 * 场景（全部走真实界面）：
 * 1. 匿名直达编辑页 → 被路由守卫挡回首页（带 redirect）；
 * 2. 用真实登录弹窗登录 **A** → 自动回到编辑页 → 输入一段**只留在编辑器缓冲里**的内容
 *    （用 CDP `Fetch` 把草稿保存请求拦掉，模拟"断网未保存"，确保它没有落库）；
 * 3. 通过右上角菜单**真实退出** → 断言：离开管理页、编辑器实例已卸载、
 *    A 的内容在 DOM 与所有输入框里都**找不到**；
 * 4. 再用真实登录弹窗登录 **B**（local-admin-2）→ 断言会话里确实是另一个账号；
 * 5. B 进入同一册编辑页 → 断言：**重新挂载并重新向服务端加载**（有新的 GET /draft）、
 *    页面内容等于**服务端草稿**，A 那段未保存内容**看不到**。
 *
 * 用法（**只允许回环**）：
 *   E2E_TARGET=http://127.0.0.1:4175 E2E_CDP_PORT=9391 \
 *     node tests/e2e/remaining-a09-account-switch.mjs
 * 第二账号由环境变量给出（默认 local-admin-2 / local-admin-2）。
 */

import { connect, createProfileDir, evaluate, findChrome, launchChrome, removeProfileDir, stopChrome, sleep, waitFor } from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';
import {
  armWatchdog, CLICK_TEXT, NATIVE_SET, READ_ROWS,
  adminLogin, api, createDraft, createHarness, draftPayload, readDraft
} from './remaining-lib.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const ACCOUNT_A = process.env.E2E_ACCOUNT_A ?? 'local-admin';
const PASSWORD_A = process.env.E2E_PASSWORD_A ?? 'local-admin';
const ACCOUNT_B = process.env.E2E_ACCOUNT_B ?? 'local-admin-2';
const PASSWORD_B = process.env.E2E_PASSWORD_B ?? 'local-admin-2';
const stamp = Date.now().toString(36).slice(-5);
const EDITION = process.env.E2E_EDITION ?? `e2e-a09-${stamp}`;
const MARK = `A私稿${stamp}`;
const disarm = armWatchdog('A09 换账号');
const harness = createHarness('remaining-a09-account-switch');
const { check, info } = harness;

const ROW1 = `() => document.querySelector('.n-input[aria-label="第 1 行汉字"]')?.querySelector('textarea, input')?.value ?? null`;
const REVISION_NOTE = `() => [...document.querySelectorAll('.admin-note')].map(el => (el.textContent ?? '').trim()).filter(t => t.includes('修订')).join(' | ')`;
/** 页面里找 A 的私稿痕迹：正文、所有输入框、以及是否还有编辑器实例。 */
const PROBE_LEAK = mark => `() => ({
  inBodyText: (document.body.innerText ?? '').includes(${JSON.stringify(mark)}),
  inInputs: [...document.querySelectorAll('input, textarea')].some(el => String(el.value ?? '').includes(${JSON.stringify(mark)})),
  editors: document.querySelectorAll('.admin-editor').length,
  hash: location.hash,
  path: location.hash.replace(/^#/, '').split('?')[0],
  adminName: (document.querySelector('.admin-name')?.textContent ?? '').trim(),
  loginEntry: [...document.querySelectorAll('button')].some(b => (b.textContent ?? '').trim() === '管理员登录')
})`;

let chrome;
let cdp;
let profileDir = null;
let exitCode = 1;
try {
  // 服务端只准备"干净的样册"：A 的私稿**不会**写进来。
  const authA = await adminLogin(base, ACCOUNT_A, PASSWORD_A);
  const authB = await adminLogin(base, ACCOUNT_B, PASSWORD_B);
  if (authA.userId === authB.userId) throw new Error('两个假账号的 userId 相同，换账号验收无意义');
  console.log(`账号 A=${authA.username}(${authA.userId}) 账号 B=${authB.username}(${authB.userId})`);
  const created = await createDraft(base, authA, draftPayload(EDITION, [
    { text: '山', pinyin: 'shān' },
    { text: '水', pinyin: 'shuǐ' }
  ]));
  if (created.status !== 201) throw new Error(`建草稿失败：${created.status} ${created.text.slice(0, 300)}`);
  const serverDraft = await readDraft(base, authA, EDITION);
  const serverTexts = serverDraft.draft.entries.map(entry => `${entry.rowId}:${entry.text}`);
  info('服务端草稿（A 的私稿不在这里）', JSON.stringify(serverTexts));

  const exe = findChrome();
  if (!exe) { console.error('找不到 Chrome：本次未执行（退出码 2）。'); process.exit(2); }
  const port = Number(process.env.E2E_CDP_PORT ?? 9391);
  profileDir = await createProfileDir('tb-a09-');
  chrome = await launchChrome({ executablePath: exe, port, userDataDir: profileDir });
  cdp = await connect(port);
  await cdp.send('Network.enable');
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });

  /** 统计草稿加载次数：它区分"重新挂载"与"复用 KeepAlive 里的旧实例"。 */
  let draftLoads = 0;
  cdp.on(message => {
    if (message.method !== 'Network.requestWillBeSent') return;
    const request = message.params.request;
    if (request.method === 'GET' && request.url.includes(`/textbooks/${EDITION}/draft`)) draftLoads += 1;
  });

  /** 用 CDP Fetch 拦掉草稿保存（让 A 的输入只存在于编辑器缓冲里，不落库）。 */
  let blockSaves = false;
  cdp.on(message => {
    if (message.method !== 'Fetch.requestPaused') return;
    const { requestId, request } = message.params;
    const isSave = request.method === 'PUT' && request.url.includes(`/textbooks/${EDITION}/draft`);
    if (blockSaves && isSave) {
      void cdp.send('Fetch.failRequest', { requestId, errorReason: 'InternetDisconnected' }).catch(() => {});
      return;
    }
    void cdp.send('Fetch.continueRequest', { requestId }).catch(() => {});
  });
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: `*/textbooks/${EDITION}/draft*`, requestStage: 'Request' }] });

  // ---------- 1. 匿名直达编辑页 → 被守卫挡回首页 ----------
  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
  await waitFor(cdp, `document.querySelector('.admin-editor') === null && location.hash.replace(/^#/, '').split('?')[0] === '/'`, '匿名直达被路由守卫挡回首页', 30000);
  const anonymous = await evaluate(cdp, `return (${PROBE_LEAK(MARK)})();`);
  check('匿名状态：没有编辑器实例，且落在非管理路由（守卫带 redirect 回来）', anonymous.editors === 0 && anonymous.path === '/', JSON.stringify(anonymous));

  // ---------- 2. 真实登录弹窗登录 A ----------
  check('合成 click 点到「管理员登录」', await evaluate(cdp, `return (${CLICK_TEXT})('管理员登录');`));
  await waitFor(cdp, `document.querySelector('input[aria-label="用户名或邮箱"]') !== null`, '登录弹窗出现', 15000);
  check('A：合成输入账号', await evaluate(cdp, `return (${NATIVE_SET})('input[aria-label="用户名或邮箱"]', ${JSON.stringify(ACCOUNT_A)});`) === ACCOUNT_A);
  check('A：合成输入密码', await evaluate(cdp, `return (${NATIVE_SET})('input[aria-label="密码"]', ${JSON.stringify(PASSWORD_A)});`) === PASSWORD_A);
  check('A：合成 click 点到「登录」', await evaluate(cdp, `return (${CLICK_TEXT})('登录');`));
  await waitFor(cdp, `(document.querySelector('.admin-name')?.textContent ?? '').includes(${JSON.stringify(ACCOUNT_A)})`, `A（${ACCOUNT_A}）登录后右上角显示账号`, 30000);
  await waitFor(cdp, `document.querySelector('.admin-editor') !== null`, '登录后自动回到编辑页（redirect 生效）', 30000);
  await waitFor(cdp, `(${ROW1})() === '山'`, '编辑页加载出服务端草稿内容', 30000);
  const aLoads = draftLoads;

  // ---------- 3. A 输入一段**不落库**的私稿 ----------
  blockSaves = true;
  const privateText = `山${MARK}`;
  await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', ${JSON.stringify(privateText)});`);
  await waitFor(cdp, `(${ROW1})() === ${JSON.stringify(privateText)}`, 'A 的私稿已进入编辑器缓冲（界面可见）', 10000);
  // 等一次自动保存真的发生（并被拦掉），证明"这段内容不会落库"。
  await sleep(3000);
  const aProbe = await evaluate(cdp, `return (${PROBE_LEAK(MARK)})();`);
  check('A 的私稿确实在编辑器里（换账号前的前提成立）', aProbe.inBodyText && aProbe.inInputs, JSON.stringify(aProbe));
  const serverWhileA = await readDraft(base, authA, EDITION);
  check('A 的私稿**没有**落库（被拦掉的保存没有写进服务端）',
    !JSON.stringify(serverWhileA.draft.entries).includes(MARK), JSON.stringify(serverWhileA.draft.entries.map(e => e.text)));

  // ---------- 4. 真实退出（右上角菜单 → 退出管理员模式） ----------
  const opened = await evaluate(cdp, `
    const trigger = document.querySelector('.admin-name');
    if (!trigger) return false;
    trigger.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
    trigger.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    trigger.click();
    return true;
  `);
  info('退出入口：派发 mouseenter/click 到 .admin-name', String(opened));
  let logoutClicked = false;
  const logoutDeadline = Date.now() + 15000;
  while (Date.now() < logoutDeadline && !logoutClicked) {
    logoutClicked = await evaluate(cdp, `
      const option = [...document.querySelectorAll('.n-dropdown-option')]
        .find(item => (item.textContent ?? '').includes('退出管理员模式'));
      if (!option) return false;
      // Naive UI 的下拉项用鼠标事件驱动：按 mousedown → mouseup → click 顺序派发。
      const target = option.querySelector('.n-dropdown-option-body__label') ?? option;
      for (const type of ['mousedown', 'mouseup', 'click']) {
        target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
        if (target !== option) option.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
      }
      return true;
    `);
    if (!logoutClicked) {
      await sleep(400);
      await evaluate(cdp, `const t = document.querySelector('.admin-name'); t?.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true })); t?.click(); return true;`);
    }
  }
  check('真实退出：点到「退出管理员模式」', logoutClicked, `logoutClicked=${logoutClicked}`);
  await sleep(1500);
  info('退出点击后的即时观察', JSON.stringify(await evaluate(cdp, `return (${PROBE_LEAK(MARK)})();`)));
  let editorUnmounted = true;
  try {
    await waitFor(cdp, `document.querySelector('.admin-editor') === null`, '退出后编辑器实例已卸载', 25000);
  } catch { editorUnmounted = false; }
  let accountCleared = true;
  try {
    await waitFor(cdp, `(document.querySelector('.admin-name')?.textContent ?? '') === ''`, '退出后右上角不再显示账号', 25000);
  } catch { accountCleared = false; }
  check('退出后编辑器实例已卸载（管理页不在 KeepAlive 里）', editorUnmounted,
    JSON.stringify(await evaluate(cdp, `return (${PROBE_LEAK(MARK)})();`)));
  check('退出后右上角不再显示账号', accountCleared,
    JSON.stringify(await evaluate(cdp, `return (document.querySelector('.admin-name')?.textContent ?? '');`)));

  const afterLogout = await evaluate(cdp, `return (${PROBE_LEAK(MARK)})();`);
  check('A09-①：退出后**离开了管理路由**（没有停在编辑页）',
    afterLogout.path !== `/admin/textbooks/${EDITION}/edit` && !afterLogout.path.startsWith('/admin')
      && afterLogout.editors === 0 && afterLogout.loginEntry,
    JSON.stringify(afterLogout));
  check('A09-②：退出后 A 的私稿在 DOM 与所有输入框里都找不到',
    !afterLogout.inBodyText && !afterLogout.inInputs, JSON.stringify(afterLogout));

  // ---------- 5. 真实登录弹窗登录 B，再进同一册编辑页 ----------
  check('合成 click 点到「管理员登录」（B）', await evaluate(cdp, `return (${CLICK_TEXT})('管理员登录');`));
  await waitFor(cdp, `document.querySelector('input[aria-label="用户名或邮箱"]') !== null`, 'B 的登录弹窗出现', 15000);
  check('B：合成输入账号', await evaluate(cdp, `return (${NATIVE_SET})('input[aria-label="用户名或邮箱"]', ${JSON.stringify(ACCOUNT_B)});`) === ACCOUNT_B);
  check('B：合成输入密码', await evaluate(cdp, `return (${NATIVE_SET})('input[aria-label="密码"]', ${JSON.stringify(PASSWORD_B)});`) === PASSWORD_B);
  check('B：合成 click 点到「登录」', await evaluate(cdp, `return (${CLICK_TEXT})('登录');`));
  await waitFor(cdp, `(document.querySelector('.admin-name')?.textContent ?? '').includes(${JSON.stringify(ACCOUNT_B)})`, `B（${ACCOUNT_B}）登录后右上角显示 B 的账号`, 30000);
  const sessionB = await evaluate(cdp, `const body = await fetch('/admin/v1/session', { credentials: 'same-origin' }).then(r => r.json()); return { username: body?.data?.username, userId: body?.data?.userId };`);
  check('B：浏览器会话确实是第二个账号（不是 A）',
    sessionB.username === ACCOUNT_B && sessionB.userId === authB.userId, JSON.stringify(sessionB));

  const loadsBeforeB = draftLoads;
  await evaluate(cdp, `location.hash = '#/admin/textbooks/${EDITION}/edit'; return true;`);
  await waitFor(cdp, `document.querySelector('.admin-editor') !== null`, 'B 进入编辑页', 30000);
  await waitFor(cdp, `(${READ_ROWS})().length === ${serverTexts.length}`, 'B 的编辑页加载出条目', 30000);
  await waitFor(cdp, `(${ROW1})() === '山'`, 'B 看到的是**服务端草稿**内容', 20000);

  const bProbe = await evaluate(cdp, `return (${PROBE_LEAK(MARK)})();`);
  const bRows = await evaluate(cdp, `return (${READ_ROWS})();`);
  check('B09/A09-③：B 进入编辑页时**重新挂载并重新加载**（观察到新的 GET /draft）',
    draftLoads > loadsBeforeB, `draftLoads ${loadsBeforeB} → ${draftLoads}`);
  check('A09-④：B 看不到 A 的私稿（DOM 与输入框都没有）',
    !bProbe.inBodyText && !bProbe.inInputs, JSON.stringify(bProbe));
  check('A09-⑤：B 看到的内容逐条等于服务端草稿',
    JSON.stringify(bRows.map(row => `${row.rowId}:${row.text}`)) === JSON.stringify(serverTexts),
    `${JSON.stringify(bRows)} vs ${JSON.stringify(serverTexts)}`);
  const revisionNote = await evaluate(cdp, `return (${REVISION_NOTE})();`);
  check('A09-⑥：页面修订号就是服务端修订号（不是缓存实例的旧状态）',
    revisionNote.includes(`修订 ${serverDraft.revision}`), `${JSON.stringify(revisionNote)} vs 服务端 ${serverDraft.revision}`);
  info('A 首次加载次数 / B 重新加载后次数', JSON.stringify({ aLoads, loadsBeforeB, afterB: draftLoads }));

  exitCode = harness.failed().length ? 1 : 0;
  console.log(`\n通过 ${harness.results.filter(item => item.ok && !item.informational).length}/${harness.results.filter(item => !item.informational).length}`);
} catch (error) {
  check('脚本执行完成', false, error?.message ?? String(error));
  exitCode = 1;
} finally {
  await stopChrome(chrome, profileDir);
  await removeProfileDir(profileDir);
  await harness.write({ editionId: EDITION, mark: MARK, accountA: ACCOUNT_A, accountB: ACCOUNT_B });
  disarm();
  process.exit(exitCode);
}
