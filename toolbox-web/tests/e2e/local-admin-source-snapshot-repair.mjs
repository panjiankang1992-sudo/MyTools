#!/usr/bin/env node
/**
 * 「补录来源快照」入口的**真实浏览器**验收（设计第 297 行：来源元数据随不可变版本保存）。
 *
 * 组件测试（`tests/component/admin-source-snapshot-repair.test.ts`）已经覆盖了闸门逻辑；
 * 本脚本只回答组件测试回答不了的问题：**真实后端 + 真实构建**下，
 * 1. 抽屉里的补录入口是否**恰好**只出现在 `hasSourceSnapshot === false` 的版本上
 *    （用页面会话真取一次 `GET /admin/v1/curriculum/versions/{editionId}` 做对照）；
 * 2. 抽屉里的控件是否**没有**覆盖／恢复／回滚／下架／删除；
 * 3. 后端对补录接口的契约是否与界面读到的一致：
 *    缺 `basis` → 422；已有快照 → `applied=false`、`overwroteOriginal=false`（**不写库**）。
 *
 * 第 3 条挑的是**只读调用**：对已有快照的版本补录会在服务层提前返回，
 * 不改任何一行；缺依据的请求更是在参数校验就被拒。因此本脚本不会写入数据库，
 * 也不会为某个版本制造快照——不拿共享的隔离库当试验田。
 *
 * 用法（**只允许回环**）：
 *   ADMIN_COOKIE='toolbox_admin_local=xxx' \
 *     E2E_TARGET=http://127.0.0.1:4173 \
 *     node tests/e2e/local-admin-source-snapshot-repair.mjs
 *
 * 退出码：0=全部断言通过；1=有断言失败（真缺陷，脚本原样打印）；2=目标不是回环。
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

/** 有历史版本的样册（本地隔离库）。 */
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

let cdp;
const chromeState = {};

/** 截图。 */
async function shot(name) {
  const data = (await cdp.send('Page.captureScreenshot', { format: 'png' })).data;
  const file = join(shotsDir, `${name}.png`);
  await writeFile(file, Buffer.from(data, 'base64'));
  log(`  截图 ${file}`);
}

/** 轮询等待一个条件成立（避免固定 sleep 在慢环境下误判）。 */
async function waitFor(expression, attempts = 50, intervalMs = 120) {
  for (let index = 0; index < attempts; index += 1) {
    if (await evaluate(cdp, expression)) return true;
    await sleep(intervalMs);
  }
  return false;
}

const main = async () => {
  await mkdir(shotsDir, { recursive: true });
  await mkdir(logsDir, { recursive: true });
  const executablePath = findChrome();
  if (!executablePath) {
    console.error('找不到 Chrome（可用 CHROME_PATH 指定）。本次未执行（退出码 2）。');
    process.exit(2);
  }
  const workDir = await mkdtemp(join(tmpdir(), 'tb-repair-'));
  chromeState.workDir = workDir;
  const port = Number(process.env.E2E_CDP_PORT ?? 9346);
  chromeState.child = await launchChrome({ executablePath, port, userDataDir: join(workDir, 'chrome') });
  cdp = await connect(port);
  const probe = createProbe(cdp);
  await cdp.send('Network.enable');
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.setCookie', {
    name: cookieName, value: cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true
  });
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1440, height: 900, deviceScaleFactor: 1, mobile: false
  });

  // 1) 直达编辑页。
  await cdp.send('Page.navigate', { url: `${base}/#/admin/textbooks/${EDITION}/edit` });
  const editorReady = await waitFor(`return !!document.querySelector('.admin-editor');`, 40);
  check('进入管理编辑页（注入会话 Cookie 后直达）', editorReady, `编辑页渲染=${editorReady}`);
  if (!editorReady) throw new Error('编辑页未渲染');

  // 2) 合成 click 打开版本记录抽屉（已知 CDP 真实鼠标不稳，合成 click 可用）。
  const clicked = await evaluate(cdp, `
    const trigger = [...document.querySelectorAll('.editor-header button')]
      .find(el => (el.textContent ?? '').trim() === '版本记录');
    if (!trigger) return false;
    trigger.click();
    return true;
  `);
  check('找到并点击「版本记录」触发按钮', clicked, `触发按钮=${clicked}`);
  const drawerOpen = await waitFor(`return !!document.querySelector('[data-testid="version-drawer"]');`, 40);
  check('版本记录抽屉已打开', drawerOpen, `打开=${drawerOpen}`);
  if (!drawerOpen) throw new Error('抽屉未打开');

  // 3) 页面会话内真取后端历史，作为 DOM 的对照。
  const history = await evaluate(cdp, `
    const response = await fetch('/admin/v1/curriculum/versions/${EDITION}',
      { credentials: 'same-origin', cache: 'no-store' });
    const payload = await response.json();
    return { status: response.status, code: payload.code, versions: payload.data ?? [] };
  `);
  check('页面会话可读到该册历史（对照数据源）',
    history.status === 200 && Array.isArray(history.versions) && history.versions.length > 0,
    `HTTP ${history.status} code=${history.code} 版本数=${history.versions?.length}`);

  const withoutSnapshot = history.versions.filter(item => !item.hasSourceSnapshot)
    .map(item => item.contentVersion).sort();
  const withSnapshot = history.versions.filter(item => item.hasSourceSnapshot)
    .map(item => item.contentVersion).sort();
  log(`  后端报告：有快照=${JSON.stringify(withSnapshot)}；无快照=${JSON.stringify(withoutSnapshot)}`);
  if (withoutSnapshot.length === 0) {
    // 如实记录覆盖范围：本册所有版本都有快照，"入口出现"这一分支在本册**不可达**，
    // 由组件测试（`admin-source-snapshot-repair.test.ts`）覆盖，不能当成浏览器已验证。
    log('  ⚠ 本册没有"无来源快照"的版本：补录入口的**出现**分支在本次真实数据下未被触发，'
      + '仅组件测试覆盖（不写库、不制造快照）。');
  }

  // 4) DOM 侧：抽屉里到底渲染了哪些补录入口、哪些快照开关。
  const dom = await evaluate(cdp, `
    const drawer = document.querySelector('[data-testid="version-drawer"]');
    const rows = [...drawer.querySelectorAll('[data-version]')];
    return {
      rowVersions: rows.map(row => row.getAttribute('data-version')),
      repairEntries: [...drawer.querySelectorAll('[data-repair-open]')]
        .map(el => el.getAttribute('data-repair-open')),
      snapshotToggles: [...drawer.querySelectorAll('[data-snapshot-toggle]')]
        .map(el => el.getAttribute('data-snapshot-toggle')),
      rowSnapshotFlags: rows.map(row => ({
        version: row.getAttribute('data-version'),
        flag: row.querySelector('[data-has-snapshot]')?.getAttribute('data-has-snapshot') ?? null
      })),
      controlLabels: [...drawer.querySelectorAll('button, a')]
        .map(el => (el.textContent ?? '').trim()).filter(Boolean),
      text: (drawer.innerText ?? '').replace(/\\s+/g, ' ').trim().slice(0, 600)
    };
  `);
  log(`  抽屉行=${JSON.stringify(dom.rowVersions)}`);
  log(`  补录入口=${JSON.stringify(dom.repairEntries)} 快照开关=${JSON.stringify(dom.snapshotToggles)}`);
  log(`  抽屉文本：${dom.text}`);

  // 核心不变量：补录入口集合 === 后端报告"没有快照"的版本集合。
  check('补录入口恰好只出现在"没有来源快照"的版本上（有快照的版本不显示，事后不可修改）',
    JSON.stringify([...dom.repairEntries].sort()) === JSON.stringify(withoutSnapshot),
    `DOM 入口=${JSON.stringify(dom.repairEntries)} 后端无快照=${JSON.stringify(withoutSnapshot)}`);

  // 判别力：抽屉真的渲染了版本行；每行的 hasSourceSnapshot 标记与后端一致。
  const flagsMatch = dom.rowSnapshotFlags
    .every(row => (row.flag === 'true') === withSnapshot.includes(row.version));
  check('每行的 hasSourceSnapshot 标记与后端一致（对照不是空集）',
    dom.rowVersions.length === history.versions.length && flagsMatch,
    `行数=${dom.rowVersions.length}/${history.versions.length} 标记=${JSON.stringify(dom.rowSnapshotFlags)}`);

  // 5) 不提供覆盖路径：控件里不得出现覆盖／恢复／回滚／下架／删除。
  const forbidden = dom.controlLabels.filter(label => /覆盖|恢复|回滚|下架|删除/.test(label));
  check('抽屉控件里没有覆盖／恢复／回滚／下架／删除（本界面不提供覆盖路径）',
    forbidden.length === 0,
    `违禁控件=${JSON.stringify(forbidden)} 全部控件=${JSON.stringify(dom.controlLabels)}`);

  await shot('source-snapshot-repair-drawer');

  // 6) 后端契约：缺 basis → 422；已有快照 → applied=false / overwroteOriginal=false（不写库）。
  const contract = await evaluate(cdp, `
    const session = await (await fetch('/admin/v1/session',
      { credentials: 'same-origin', cache: 'no-store' })).json();
    const token = session.data?.csrfToken ?? '';
    const target = ${JSON.stringify(withSnapshot[0] ?? null)};
    const url = version => '/admin/v1/curriculum/versions/${EDITION}/' + encodeURIComponent(version)
      + '/repair-source-snapshot?basis=';
    const post = async version => {
      const response = await fetch(url(version), {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'X-Toolbox-Admin-Csrf': token,
          'Idempotency-Key': crypto.randomUUID()
        }
      });
      let body = null;
      try { body = await response.json(); } catch { body = null; }
      return { version, status: response.status, code: body?.code, data: body?.data, details: body?.details };
    };
    const missingBasis = target ? await post(target) : null;
    const existing = target
      ? await fetch(url(target) + encodeURIComponent('e2e 只读探针：该版本已有快照，必须不被覆盖'),
          {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'X-Toolbox-Admin-Csrf': token, 'Idempotency-Key': crypto.randomUUID() }
          }).then(async response => ({
            version: target, status: response.status,
            body: await response.json().catch(() => null)
          }))
      : null;
    return { tokenPresent: Boolean(token), missingBasis, existing };
  `);
  check('页面会话持有可用的管理 CSRF 令牌', contract.tokenPresent, `token=${contract.tokenPresent}`);

  check('缺 basis 的补录请求被后端拒绝（422）——界面"依据必填"与后端一致',
    contract.missingBasis?.status === 422 && contract.missingBasis?.code === 'TOOLBOX_ADMIN_005',
    `${JSON.stringify(contract.missingBasis)}`);

  const existingData = contract.existing?.body?.data ?? null;
  check('对已有快照的版本补录：applied=false、overwroteOriginal=false、reason=snapshotAlreadyPresent（不写库）',
    contract.existing?.status === 200
      && existingData?.applied === false
      && existingData?.overwroteOriginal === false
      && existingData?.reason === 'snapshotAlreadyPresent',
    `${JSON.stringify(contract.existing)}`);

  // 7) 全过程无渲染进程异常、无异常响应。
  check('期间无渲染进程异常', probe.pageErrors.length === 0,
    `pageErrors=${JSON.stringify(probe.pageErrors.slice(0, 3))}`);
  const bad = probe.badResponses.filter(item => !item.includes('/bytes') && !item.includes('repair-source-snapshot'));
  check('期间无非预期异常响应（4xx/5xx）', bad.length === 0,
    `badResponses=${JSON.stringify(bad.slice(0, 5))}`);

  await writeFile(join(logsDir, 'source-snapshot-repair-ui.json'), `${JSON.stringify({
    target: base,
    edition: EDITION,
    history: { withSnapshot, withoutSnapshot, status: history.status },
    dom,
    contract,
    results,
    consoleErrors: probe.consoleErrors,
    pageErrors: probe.pageErrors,
    failedRequests: probe.failedRequests,
    badResponses: probe.badResponses,
    transcript
  }, null, 2)}\n`);
  log(`证据写入 ${join(logsDir, 'source-snapshot-repair-ui.json')}`);
};

try {
  await main();
} catch (cause) {
  check('脚本执行完成', false, `异常：${cause?.message ?? cause}`);
  console.error(cause);
} finally {
  chromeState.child?.kill?.();
  if (chromeState.workDir) {
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
