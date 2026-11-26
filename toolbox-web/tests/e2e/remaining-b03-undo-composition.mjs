#!/usr/bin/env node
/**
 * B03：**撤销在真实页面里的组合**（真实浏览器 + 真实后端 + 真实草稿）。
 *
 * 缺口原文：`{ id: 'B03', note: 'ID 不按位置生成有反向验证；撤销在真实页面的组合未测' }`
 *
 * 先读代码确认"手势"（这是本脚本存在的前提）：
 * - `TextbookEditorView.vue` 里撤销**只有按钮**一条入口
 *   （模板中 `:disabled="history.depth === 0" @click="undo"`，文本「撤销」）；
 * - `src/main.ts` 与 `App.vue` **没有注册任何 Cmd+Z／Ctrl+Z 快捷键**
 *   （`grep -n "undo\|metaKey\|ctrlKey" src/main.ts` 无命中）；
 * - 撤销栈是**快照式**（`src/lib/admin-textbook-history.ts`），
 *   `recordBeforeChange` 只在**结构操作**前调用：
 *   新增单元／附录／课文、删除单元／课文、重命名、调整顺序、批量粘贴、
 *   设置表类别／来源、恢复本地改动。**纯内容输入不记录快照**。
 *
 * **设计边界（本脚本显式断言，不是缺陷）**：设计 B03 只承诺
 * 「重命名／重排／删除**后撤销**」——**纯内容输入不记快照**，因此
 * 单独改文本**不会**让撤销按钮变可用（见"设计边界：纯文本输入不进入撤销栈"一条）。
 * 但快照是**整份** `{ outline, entries }`，所以"结构操作 → 再改文本 → 撤销"
 * 会连带回到结构操作**之前**的文本状态（场景2 验的就是这个组合语义）。
 *
 * 因此本脚本按真实手势（按钮）验收下面三件事：
 * 1. 结构操作后撤销 → 目录回到原样，**行标识（rowId）逐个不变、顺序不变**；
 * 2. 组合场景（**设计边界·组合语义**）：先结构操作（记快照）→ 再合成输入改内容（自动保存成功）→ 撤销
 *    → 页面内容回到**原值**，且标识与顺序仍不变；
 * 3. 撤销之后再输入一次 → 行标识**仍然是同一批**（原缺陷 F04 的症状是
 *    "同一行两次输入之间换了身份"）。
 * 另附一条**实测记录**（不计成败）：Cmd+Z 在真实页面上的实际效果。
 *
 * 用法（**只允许回环**）：
 *   E2E_TARGET=http://127.0.0.1:4175 E2E_ACCOUNT=local-admin E2E_PASSWORD=local-admin \
 *     node tests/e2e/remaining-b03-undo-composition.mjs
 *
 * 退出码：0=全部断言通过；1=有断言失败；2=目标/账号不合法；3=超时被看门狗终止。
 */

import { connect, createProfileDir, evaluate, findChrome, launchChrome, removeProfileDir, stopChrome, sleep, waitFor } from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';
import {
  armWatchdog, CLICK_TEXT, NATIVE_SET, READ_OUTLINE, READ_ROWS,
  adminLogin, api, createDraft, createHarness, draftPayload, readDraft
} from './remaining-lib.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const ACCOUNT = process.env.E2E_ACCOUNT ?? 'local-admin';
const PASSWORD = process.env.E2E_PASSWORD ?? 'local-admin';
const stamp = Date.now().toString(36).slice(-5);
const EDITION = process.env.E2E_EDITION ?? `e2e-b03-${stamp}`;
const MARK = `撤销${stamp}`;
const disarm = armWatchdog('B03 撤销组合');
const harness = createHarness('remaining-b03-undo-composition');
const { check, info } = harness;

/** 页面里读所有提示条文本。 */
const READ_ALERTS = `() => [...document.querySelectorAll('.editor-alert')].map(el => (el.textContent ?? '').replace(/\\s+/g, ' ').trim())`;
const ROW1_VALUE = `() => document.querySelector('.n-input[aria-label="第 1 行汉字"]')?.querySelector('textarea, input')?.value ?? null`;
const UNDO_DISABLED = `() => { const b = [...document.querySelectorAll('button')].find(i => (i.textContent ?? '').trim() === '撤销'); return b ? Boolean(b.disabled) : null; }`;

let chrome;
let cdp;
let profileDir = null;
let exitCode = 1;
try {
  const auth = await adminLogin(base, ACCOUNT, PASSWORD);
  console.log(`登录成功：${auth.username}（${auth.userId}）`);
  const created = await createDraft(base, auth, draftPayload(EDITION, [
    { text: '山', pinyin: 'shān' },
    { text: '水', pinyin: 'shuǐ' }
  ]));
  if (created.status !== 201) throw new Error(`建草稿失败：${created.status} ${created.text.slice(0, 300)}`);
  console.log(`样册 ${EDITION} 已建，revision=${created.payload.data.revision}`);

  const exe = findChrome();
  if (!exe) { console.error('找不到 Chrome：本次未执行（退出码 2）。'); process.exit(2); }
  profileDir = await createProfileDir('tb-b03-');
  chrome = await launchChrome({ executablePath: exe, port: Number(process.env.E2E_CDP_PORT ?? 9371), userDataDir: profileDir });
  cdp = await connect(Number(process.env.E2E_CDP_PORT ?? 9371));
  await cdp.send('Network.enable');
  await cdp.send('Network.setCookie', { name: auth.cookieName, value: auth.cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true });
  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });

  await waitFor(cdp, `document.querySelectorAll('.metadata-list li').length === 2`, '编辑页渲染出 2 条内容', 60000);
  await waitFor(cdp, `(${ROW1_VALUE})() === '山'`, '第 1 行显示样册原值「山」', 20000);

  // ---------- 基线 ----------
  const baselineRows = await evaluate(cdp, `return (${READ_ROWS})();`);
  const baselineOutline = await evaluate(cdp, `return (${READ_OUTLINE})();`);
  const baselineServer = await readDraft(base, auth, EDITION);
  const baselineIds = baselineServer.draft.entries.map(entry => entry.rowId);
  info('基线（页面可见条目）', JSON.stringify(baselineRows));
  info('基线（服务端 rowId 顺序）', JSON.stringify(baselineIds));
  info('基线（目录）', JSON.stringify(baselineOutline));

  // ---------- 设计边界：纯文本输入**不**单独进入撤销栈 ----------
  //
  // 设计 B03 原文是「重命名／重排／删除**后撤销**」——只有**结构操作**记快照。
  // 这条断言把这个边界**显式钉住**：只改文本（不碰结构）时撤销按钮必须仍然禁用。
  // 场景2 验的是另一个语义（快照是整份状态，结构撤销会连带回退其后的文本改动），
  // 二者不矛盾：文本不是**独立**的撤销单元，但会被结构快照**连带**回退。
  check('设计边界：纯文本输入不进入撤销栈（撤销按钮保持禁用）',
    await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', ${JSON.stringify(`${baselineRows[0].text}${MARK}边界`)});`) !== 'no-element'
      && (await evaluate(cdp, `return (${UNDO_DISABLED})() === true;`)),
    `输入后 disabled=${await evaluate(cdp, `return (${UNDO_DISABLED})();`)}`);
  // 还原成基线值（同样是"纯文本输入"，不记快照），保证后面的基线比对仍然成立。
  await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', ${JSON.stringify(baselineRows[0].text)});`);
  await waitFor(cdp, `(${ROW1_VALUE})() === ${JSON.stringify(baselineRows[0].text)}`, '第 1 行已还原为基线值', 10000);

  // ---------- 场景 1：结构操作（新增单元）后撤销 ----------
  check('合成输入进了「新单元名称」输入框',
    await evaluate(cdp, `return (${NATIVE_SET})('[aria-label="新单元名称"]', ${JSON.stringify(`临时单元${MARK}`)}) === ${JSON.stringify(`临时单元${MARK}`)};`));
  check('合成 click 点到「新增单元」', await evaluate(cdp, `return (${CLICK_TEXT})('新增单元');`));
  await waitFor(cdp, `(${READ_OUTLINE})().some(item => item.unit.includes(${JSON.stringify(MARK)}))`, '新单元出现在页面目录里', 10000);
  check('撤销按钮在新结构改动后变为可用', await evaluate(cdp, `return (${UNDO_DISABLED})() === false;`), `disabled=${await evaluate(cdp, `return (${UNDO_DISABLED})();`)}`);

  check('合成 click 点到「撤销」', await evaluate(cdp, `return (${CLICK_TEXT})('撤销');`));
  // 软等待：失败不抛异常，交给下面的显式断言报出来（这样"改坏产品"时能看到是哪一条断言失败）。
  let unitGone = true;
  try {
    await waitFor(cdp, `(${READ_OUTLINE})().every(item => !item.unit.includes(${JSON.stringify(MARK)}))`, '撤销后新单元从目录消失', 10000);
  } catch { unitGone = false; }
  let undoStackEmpty = true;
  try {
    await waitFor(cdp, `(${UNDO_DISABLED})() === true`, '撤销栈回到空（按钮重新禁用）', 10000);
  } catch { undoStackEmpty = false; }
  check('场景1：撤销后新单元真的从目录消失', unitGone, JSON.stringify(await evaluate(cdp, `return (${READ_OUTLINE})();`)));
  check('场景1：撤销栈回到空（按钮重新禁用）', undoStackEmpty, `disabled=${await evaluate(cdp, `return (${UNDO_DISABLED})();`)}`);

  const afterUndoOutline = await evaluate(cdp, `return (${READ_OUTLINE})();`);
  const afterUndoRows = await evaluate(cdp, `return (${READ_ROWS})();`);
  const afterUndoAlerts = await evaluate(cdp, `return (${READ_ALERTS})();`);
  check('场景1：目录与基线逐字相同', JSON.stringify(afterUndoOutline) === JSON.stringify(baselineOutline), JSON.stringify(afterUndoOutline));
  check('场景1：条目标识与顺序与基线逐字相同', JSON.stringify(afterUndoRows) === JSON.stringify(baselineRows), JSON.stringify(afterUndoRows));
  check('场景1：页面提示「已撤销：新增单元」', afterUndoAlerts.some(text => text.includes('已撤销：新增单元')), JSON.stringify(afterUndoAlerts));

  // ---------- 场景 2（设计边界·组合语义）：结构操作 + 合成输入改内容 → 撤销 ----------
  const original = baselineRows[0].text;
  const edited = `${original}${MARK}`;
  await evaluate(cdp, `return (${NATIVE_SET})('[aria-label="新单元名称"]', ${JSON.stringify(`临时单元二${MARK}`)});`);
  await evaluate(cdp, `return (${CLICK_TEXT})('新增单元');`);
  await waitFor(cdp, `(${READ_OUTLINE})().some(item => item.unit.includes(${JSON.stringify(`临时单元二${MARK}`)}))`, '第二个临时单元出现在目录里', 10000);
  info('场景2 快照点', '结构改动已记录一份快照（此时 entries 仍是原值）');

  const applied = await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', ${JSON.stringify(edited)});`);
  check('场景2：合成输入让第 1 行变成新值（Vue 已渲染）',
    (await waitFor(cdp, `(${ROW1_VALUE})() === ${JSON.stringify(edited)}`, '第 1 行显示新值', 10000)) === true,
    `setter 返回 ${JSON.stringify(applied)}`);

  // 等真实自动保存把新值落库（等条件，不猜时间）。
  let savedOnServer = null;
  const saveDeadline = Date.now() + 20000;
  while (Date.now() < saveDeadline) {
    const current = await readDraft(base, auth, EDITION);
    const entry = (current.draft.entries ?? []).find(item => String(item.text ?? '').includes(MARK));
    if (entry) { savedOnServer = { revision: current.revision, rowId: entry.rowId, text: entry.text }; break; }
    await sleep(500);
  }
  check('场景2：自动保存把新内容真的写到服务端', Boolean(savedOnServer), JSON.stringify(savedOnServer));
  if (savedOnServer) {
    check('场景2：新内容写在**同一行标识**上（没有新增行）',
      savedOnServer.rowId === baselineIds[0] && (await readDraft(base, auth, EDITION)).draft.entries.length === baselineIds.length,
      `服务端 rowId=${savedOnServer.rowId}，条目数=${(await readDraft(base, auth, EDITION)).draft.entries.length}`);
  }

  check('场景2：合成 click 点到「撤销」', await evaluate(cdp, `return (${CLICK_TEXT})('撤销');`));
  let contentRestored = true;
  try {
    await waitFor(cdp, `(${ROW1_VALUE})() === ${JSON.stringify(original)}`, '撤销后第 1 行回到原值「山」', 10000);
  } catch { contentRestored = false; }
  check('场景2：撤销后第 1 行真的回到原值', contentRestored, `实际=${JSON.stringify(await evaluate(cdp, `return (${ROW1_VALUE})();`))}`);
  try {
    await waitFor(cdp, `(${READ_OUTLINE})().every(item => !item.unit.includes(${JSON.stringify(MARK)}))`, '撤销后第二个临时单元也消失', 10000);
  } catch { /* 由下面的目录比对断言报出 */ }

  const scene2Rows = await evaluate(cdp, `return (${READ_ROWS})();`);
  const scene2Outline = await evaluate(cdp, `return (${READ_OUTLINE})();`);
  check('场景2：内容真的回到原值（页面 DOM）', (await evaluate(cdp, `return (${ROW1_VALUE})();`)) === original, `实际=${JSON.stringify(await evaluate(cdp, `return (${ROW1_VALUE})();`))}`);
  check('场景2：rowId 与顺序逐个未变', JSON.stringify(scene2Rows) === JSON.stringify(baselineRows), JSON.stringify(scene2Rows));
  check('场景2：目录与基线逐字相同', JSON.stringify(scene2Outline) === JSON.stringify(baselineOutline), JSON.stringify(scene2Outline));

  // 实测记录：撤销的结果**没有**安排保存（undo 不走 onRowsUpdate/markDirtyAndSchedule）。
  const afterUndoServer = await readDraft(base, auth, EDITION);
  info('实测：撤销后服务端草稿第 1 行',
    JSON.stringify((afterUndoServer.draft.entries ?? []).map(entry => `${entry.rowId}:${entry.text}`)));
  info('实测：撤销后服务端 revision', String(afterUndoServer.revision));

  // ---------- 场景 3：撤销之后再输入，标识必须仍是同一批 ----------
  const secondEdit = `${original}${MARK}${MARK}`;
  await evaluate(cdp, `return (${NATIVE_SET})('.n-input[aria-label="第 1 行汉字"]', ${JSON.stringify(secondEdit)});`);
  try {
    await waitFor(cdp, `(${ROW1_VALUE})() === ${JSON.stringify(secondEdit)}`, '第 1 行显示第二次输入的值', 10000);
  } catch { /* 由场景3 的标识断言报出 */ }
  await sleep(1200);
  const scene3Rows = await evaluate(cdp, `return (${READ_ROWS})();`);
  check('场景3：撤销后再输入，rowId 仍是同一批、顺序不变',
    JSON.stringify(scene3Rows.map(row => row.rowId)) === JSON.stringify(baselineRows.map(row => row.rowId)),
    JSON.stringify(scene3Rows.map(row => row.rowId)));

  // ---------- 实测记录（不计成败）：Cmd+Z 的真实效果 ----------
  const cmdZ = await evaluate(cdp, `
    const textarea = document.querySelector('.n-input[aria-label="第 1 行汉字"]')?.querySelector('textarea, input');
    textarea?.focus();
    return { focused: document.activeElement === textarea, valueBefore: textarea?.value ?? null };
  `);
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'z', code: 'KeyZ', windowsVirtualKeyCode: 90, nativeVirtualKeyCode: 90, modifiers: 4 });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'z', code: 'KeyZ', windowsVirtualKeyCode: 90, nativeVirtualKeyCode: 90, modifiers: 4 });
  await sleep(600);
  const afterCmdZ = await evaluate(cdp, `
    const textarea = document.querySelector('.n-input[aria-label="第 1 行汉字"]')?.querySelector('textarea, input');
    const stateText = document.querySelector('.metadata-list li .metadata-text')?.textContent ?? null;
    return { valueAfter: textarea?.value ?? null, vueStateText: stateText, undoDisabled: (${UNDO_DISABLED})() };
  `);
  info('实测：Cmd+Z（keyDown/keyUp + Meta）',
    `聚焦=${cmdZ.focused} 输入框值 ${JSON.stringify(cmdZ.valueBefore)} → ${JSON.stringify(afterCmdZ.valueAfter)}；`
    + `Vue 状态(.metadata-text)=${JSON.stringify(afterCmdZ.vueStateText)}；撤销按钮 disabled=${afterCmdZ.undoDisabled}`);
  info('实测：撤销入口', '本页没有注册 Cmd+Z 快捷键（main.ts/App.vue 无 undo/ctrlKey/metaKey 命中），唯一入口是「撤销」按钮');

  exitCode = harness.failed().length ? 1 : 0;
  console.log(`\n通过 ${harness.results.filter(item => item.ok && !item.informational).length}/${harness.results.filter(item => !item.informational).length}`);
} catch (error) {
  check('脚本执行完成', false, error?.message ?? String(error));
  exitCode = 1;
} finally {
  await stopChrome(chrome, profileDir);
  await removeProfileDir(profileDir);
  await harness.write({ editionId: EDITION, mark: MARK });
  disarm();
  process.exit(exitCode);
}
