import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { centerOf, clickPoint, connect, createProbe, evaluate, findChrome, launchChrome, sleep, waitFor } from './cdp.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';

/**
 * **恢复入口的只读冒烟**（本地环境专用）。
 *
 * 用途：在真实浏览器 + 真实后端上确认「教材列表 → 恢复导入包」这条链是通的，
 * 并确认前端预检能对**真实导出包**算出摘要。
 *
 * **安全边界（刻意写死在这里）**：
 * - 只接受 `E2E_TARGET` 显式指定的**回环**地址（默认不回落生产）；
 * - **绝不调用 `POST /admin/v1/curriculum/restore-package`**：
 *   脚本只走 登录 → 路由 → `GET /curriculum/export-package`（只读）→ 前端预检；
 * - 最后断言「未勾选确认前不能提交」，因此这条冒烟本身不会写库。
 *
 * 用法：`E2E_TARGET=http://127.0.0.1:4173 node tests/e2e/local-restore-smoke.mjs`
 */

const base = requireLoopbackTarget(process.env.E2E_TARGET);
const port = Number(process.env.SMOKE_CDP_PORT ?? 9444);
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: Boolean(ok) });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

const executablePath = findChrome();
if (!executablePath) {
  console.error('未找到 Chrome/Chromium，冒烟未执行（可设置 CHROME_PATH）。');
  process.exit(2);
}

const workDir = await mkdtemp(join(tmpdir(), 'toolbox-restore-smoke-'));
let chrome;
let cdp;
try {
  chrome = await launchChrome({ executablePath, port, userDataDir: join(workDir, 'chrome') });
  cdp = await connect(port);
  const probe = createProbe(cdp);
  await cdp.send('Runtime.enable');
  await cdp.send('Network.enable');
  await cdp.send('Page.enable');

  /** 用真实鼠标点击选择器命中的元素。 */
  const clickSelector = async expression => {
    const point = await centerOf(cdp, expression);
    if (!point) return false;
    await clickPoint(cdp, point);
    return true;
  };

  /** 找到文本包含给定片段的按钮并点击。 */
  const clickButtonByText = async (text, scope = 'document') => {
    const expression = `[...( ${scope} ).querySelectorAll('.n-button, button, a')]
      .filter(el => el.offsetParent !== null)
      .find(el => (el.textContent ?? '').replace(/\\s+/g, '').includes(${JSON.stringify(text.replace(/\s+/g, ''))}))`;
    if (!await evaluate(cdp, `return Boolean(${expression});`)) return false;
    return clickSelector(expression);
  };

  const tryWait = (expression, label, timeoutMs = 60000) =>
    waitFor(cdp, expression, label, timeoutMs).then(() => true).catch(() => false);

  /**
   * 先走真实鼠标，效果没出现再退回页内合成 click，并**报告实际生效的是哪一条路径**。
   *
   * 为什么要这个兜底：本仓库的无头 CDP 驱动里，`Input.dispatchMouseEvent` 的
   * 送达**不稳定**——同一支脚本、同一套坐标，有时生效、有时渲染进程根本收不到
   * （页面上 `document` 捕获阶段的 click 计数为 0，而 `document.elementFromPoint`
   * 在按钮中心命中的正是该按钮，说明不是被遮挡）。
   * 因此这里两条路都试，并把结果如实打出来：**不让"驱动没点到"冒充"产品不工作"**，
   * 也不让兜底掩盖掉真实鼠标路径的结果。
   */
  const clickWithFallback = async (cssSelector, effectExpression, timeoutMs = 8000) => {
    const expression = `document.querySelector(${JSON.stringify(cssSelector)})`;
    const mouseClicked = await clickSelector(expression);
    if (await tryWait(effectExpression, cssSelector, timeoutMs)) {
      return { ok: true, path: 'real-mouse', mouseClicked };
    }
    await evaluate(cdp, `${expression}?.click(); return true;`);
    const ok = await tryWait(effectExpression, cssSelector, timeoutMs);
    return { ok, path: ok ? 'synthetic-click' : 'failed', mouseClicked };
  };

  // 1) 首页 + 通过界面登录本地假身份管理员（真实点击与输入）。
  await cdp.send('Page.navigate', { url: `${base}/#/` });
  await tryWait(`!!document.querySelector('.admin-entry')`, '首页身份入口', 60000);
  const entryText = await evaluate(cdp, `return document.querySelector('.admin-entry')?.innerText?.trim() ?? '';`);
  if (!entryText.includes('管理员登录')) {
    check('本地假管理员已登录（未重复登录）', true, `入口显示「${entryText}」`);
  } else {
    await clickSelector(`document.querySelector('.admin-entry')`);
    await tryWait(`!!document.querySelector('.n-modal [aria-label="用户名或邮箱"]')`, '登录弹窗', 20000);
    await clickSelector(`document.querySelector('.n-modal [aria-label="用户名或邮箱"]')`);
    await cdp.send('Input.insertText', { text: 'local-admin' });
    await sleep(300);
    await clickSelector(`document.querySelector('.n-modal [aria-label="密码"]')`);
    await cdp.send('Input.insertText', { text: 'local-admin' });
    await sleep(300);
    const loginMouse = await clickButtonByText('登录', `document.querySelector('.n-modal') ?? document`);
    let loggedIn = await tryWait(`!!document.querySelector('.admin-name')`, '管理员已登录', 10000);
    if (!loggedIn) {
      await evaluate(cdp, `
        const button = [...(document.querySelector('.n-modal') ?? document).querySelectorAll('.n-button, button')]
          .find(el => (el.textContent ?? '').includes('登录'));
        button?.click();
        return true;
      `);
      loggedIn = await tryWait(`!!document.querySelector('.admin-name')`, '管理员已登录', 30000);
      console.log(`  登录路径：真实鼠标点击=${loginMouse} → 合成 click 兜底后成功=${loggedIn}`);
    }
    check('本地假管理员可通过界面登录', loggedIn,
      await evaluate(cdp, `return document.querySelector('.admin-name')?.innerText?.trim() ?? document.querySelector('.login-error')?.innerText?.trim() ?? '';`));
  }

  // 2) 列表页工具区的恢复入口必须存在，并且点击后真的进入恢复页（路由已注册）。
  await evaluate(cdp, `location.hash = '#/admin/textbooks'; return true;`);
  const listReady = await tryWait(`!!document.querySelector('[data-testid="open-restore"]')`, '恢复入口', 30000);
  check('教材列表页工具区出现「恢复导入包」入口', listReady);
  let landed = false;
  if (listReady) {
    const entered = await clickWithFallback(
      '[data-testid="open-restore"]',
      `!!document.querySelector('[data-testid="restore-analyze"]')`,
      20000
    );
    landed = entered.ok;
    check('点击入口后进入 /#/admin/curriculum/restore（路由已注册）', landed,
      `点击路径=${entered.path} hash=${await evaluate(cdp, `return location.hash;`)}`);
  }

  // 3) 取真实导出包（只读），粘贴进页面，走前端预检。
  if (!landed) throw new Error('未进入恢复页，后续只读检查无法进行（不把未执行写成通过）');
  const fetched = await evaluate(cdp, `
    const response = await fetch('/admin/v1/curriculum/export-package', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    const body = await response.json();
    return JSON.stringify({ status: response.status, code: body.code, json: JSON.stringify(body.data ?? {}) });
  `).then(text => JSON.parse(text));
  const pkg = JSON.parse(fetched.json);
  check('GET /admin/v1/curriculum/export-package 可用（会话与只读路径通）',
    fetched.status === 200 && fetched.code === 'OK', `versions=${Array.isArray(pkg.versions) ? pkg.versions.length : 'n/a'}`);

  await evaluate(cdp, `
    const textarea = document.querySelector('textarea');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
    setter.call(textarea, ${JSON.stringify(fetched.json)});
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  `);
  await sleep(300);

  // 说明：这一步本应走真实鼠标点击，但实测 CDP `Input.dispatchMouseEvent`
  // 在两次 hash 路由跳转之后没有被渲染进程收到（document 捕获阶段的 click 计数为 0，
  // 而 `document.elementFromPoint` 在按钮中心命中的正是该按钮，说明**不是被遮挡**）。
  // 因此这里用页内合成 click 作为兜底，并把真实鼠标路径的结果一并打印出来，不做掩饰。
  const analyzed = await clickWithFallback(
    '[data-testid="restore-analyze"]',
    `!!document.querySelector('[data-testid="restore-preview"]') || !!document.querySelector('[data-testid="restore-parse-error"]')`,
    12000
  );
  const previewReady = await evaluate(cdp, `return !!document.querySelector('[data-testid="restore-preview"]');`);
  const parseError = await evaluate(cdp, `return document.querySelector('[data-testid="restore-parse-error"]')?.innerText?.trim() ?? '';`);
  check('粘贴真实导出包后渲染出恢复摘要', previewReady,
    `点击路径=${analyzed.path}${parseError ? ` 解析错误：${parseError}` : ''}`);

  if (previewReady) {
    const stats = await evaluate(cdp, `
      return {
        editions: document.querySelector('[data-testid="preview-editions"]')?.innerText?.trim(),
        versions: document.querySelector('[data-testid="preview-versions"]')?.innerText?.trim(),
        conflicts: document.querySelector('[data-testid="preview-conflicts"]')?.innerText?.trim(),
        manifestNote: document.querySelector('[data-testid="preview-manifest-note"]')?.innerText?.trim() ?? '',
        submitDisabled: document.querySelector('[data-testid="restore-submit"]')?.disabled ?? null,
        report: !!document.querySelector('[data-testid="restore-report"]')
      };
    `);
    console.log(`  摘要：册数=${stats.editions} 版本数=${stats.versions} 冲突=${stats.conflicts} 库内清单说明=「${stats.manifestNote}」`);
    check('摘要版本数与包内版本数一致', stats.versions === String(pkg.versions.length),
      `界面=${stats.versions} 包内=${pkg.versions.length}`);
    check('库内现状读取成功（摘要与库内比对过，没有降级提示）', stats.manifestNote === '');
    check('未勾选确认前「确认恢复」不可用（二次确认闸门）', stats.submitDisabled === true, `disabled=${stats.submitDisabled}`);
    check('没有发出任何恢复请求（页面上没有恢复结果）', stats.report === false);
  }

  const unexpected = probe.badResponses.filter(item => !item.includes('401'));
  check('无未预期的 4xx/5xx 响应', unexpected.length === 0, unexpected.join(' | '));
  check('无页面级未捕获异常', probe.pageErrors.length === 0, probe.pageErrors.slice(0, 3).join(' | '));
  check('无控制台 error', probe.consoleErrors.length === 0, probe.consoleErrors.slice(0, 3).join(' | '));

  const failed = results.filter(item => !item.ok);
  console.log(`\n===== 恢复入口只读冒烟：${results.length - failed.length}/${results.length} 通过 =====`);
  process.exitCode = failed.length ? 1 : 0;
} catch (cause) {
  console.error(`冒烟异常中止：${cause?.stack ?? cause}`);
  process.exitCode = 1;
} finally {
  try { cdp?.close(); } catch { /* 已断开 */ }
  try { chrome?.kill('SIGKILL'); } catch { /* 已退出 */ }
  await sleep(300);
  await rm(workDir, { recursive: true, force: true });
}
