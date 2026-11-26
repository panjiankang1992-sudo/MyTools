#!/usr/bin/env node
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { connect, findChrome, launchChrome, sleep } from './cdp.mjs';
import { runScenario } from './scenario.mjs';
import { requireLoopbackTarget } from './target-guard.mjs';

/**
 * 端到端验收入口。
 *
 * 目标地址**必须**由 `E2E_TARGET` 显式指定，且必须是本机回环地址：
 * 未设置时不再回落到公网站点，非回环取值直接以退出码 2 报告为「未执行」。
 * 找不到浏览器时同样以退出码 2 明确报告，不允许把未运行写成通过。
 *
 * 用法：`E2E_TARGET=http://127.0.0.1:4173 npm run test:e2e`
 */

const base = requireLoopbackTarget(process.env.E2E_TARGET);
const port = Number(process.env.E2E_CDP_PORT ?? 9333);
const executablePath = findChrome();

if (!executablePath) {
  console.error('未找到 Chrome/Chromium，端到端验收未执行（可设置 CHROME_PATH）。');
  process.exit(2);
}

const workDir = await mkdtemp(join(tmpdir(), 'toolbox-e2e-'));
const downloads = join(workDir, 'downloads');
const results = [];
const check = (name, ok, detail) => {
  results.push({ name, ok: Boolean(ok), detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

let chrome;
let cdp;
let exitCode = 1;
try {
  console.log(`端到端目标：${base}`);
  console.log(`浏览器：${executablePath}`);
  chrome = await launchChrome({ executablePath, port, userDataDir: join(workDir, 'chrome') });
  cdp = await connect(port);
  await runScenario({ cdp, base, downloads, check });
  const failed = results.filter(item => !item.ok);
  console.log(`\n===== 端到端结果：${results.length - failed.length}/${results.length} 通过 =====`);
  if (failed.length) {
    console.log('未通过项：');
    for (const item of failed) console.log(`  - ${item.name}: ${item.detail ?? ''}`);
  }
  exitCode = failed.length ? 1 : 0;
} catch (cause) {
  console.error(`端到端验收异常中止：${cause.message}`);
  exitCode = 1;
} finally {
  try { cdp?.close(); } catch { /* 已断开 */ }
  try { chrome?.kill('SIGKILL'); } catch { /* 已退出 */ }
  await sleep(300);
  await rm(workDir, { recursive: true, force: true });
}
process.exit(exitCode);
