#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 汇总可自动执行的发布前检查，并如实输出未自动验证的人工项。
 * 任何一步以非零退出都会使整体失败；但人工实纸打印、多浏览器实机等项目永远标记为未验证。
 */

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '..');
const repoRoot = resolve(projectRoot, '..');

/** 依次执行命令并记录退出码与输出尾部。 */
function run(label, command, args, options = {}) {
  const started = Date.now();
  const result = spawnSync(command, args, { cwd: options.cwd ?? projectRoot, encoding: 'utf8', shell: false });
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`.trim();
  const exitCode = result.status ?? 1;
  const notExecuted = (options.notExecutedExitCodes ?? []).includes(exitCode);
  return {
    label,
    command: [command, ...args].join(' '),
    exitCode,
    status: exitCode === 0 ? 'pass' : notExecuted ? 'not-executed' : options.tolerateFailure ? 'warn' : 'fail',
    durationMs: Date.now() - started,
    tail: output.split('\n').slice(-12).join('\n'),
    ...(options.tolerateFailure ? { tolerated: true } : {}),
    ...(notExecuted ? { reason: '缺少运行条件（例如没有浏览器），本次未执行' } : {})
  };
}

const checks = [];
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
checks.push(run('typecheck', npm, ['run', 'typecheck']));
checks.push(run('unit-tests', npm, ['test']));
checks.push(run('curriculum-validation', npm, ['run', 'test:curriculum']));
// 20 册覆盖在内容核验完成前是已知缺口，这里只作为开发报告，不阻断其它检查。
checks.push(run('curriculum-coverage', npm, ['run', 'test:coverage', '--', '--allow-partial'], { tolerateFailure: true }));
checks.push(run('production-build', npm, ['run', 'build:production']));
// 端到端需要真实浏览器与可达站点：退出码 2 表示未执行，既不算通过也不算失败。
// 验收要求 E2E 显式指向本地或独立测试环境：默认目标是公网站点，直接跑会污染线上统计。
// 因此未设置 E2E_TARGET 时明确记为「未执行」，而不是偷偷打到生产。
const e2eTarget = process.env.E2E_TARGET;
if (!e2eTarget) {
  checks.push({
    label: 'end-to-end',
    command: 'npm run test:e2e',
    exitCode: null,
    status: 'not-executed',
    durationMs: 0,
    tail: '',
    reason: '未设置 E2E_TARGET。默认目标是公网 https://toolbox.yuyutian.top，直接运行会污染线上统计，因此本次未执行。'
  });
} else if (/yuyutian\.top/.test(e2eTarget)) {
  checks.push({
    label: 'end-to-end',
    command: 'npm run test:e2e',
    exitCode: null,
    status: 'not-executed',
    durationMs: 0,
    tail: '',
    reason: `E2E_TARGET 指向公网站点（${e2eTarget}）。本地验收不得打到生产，因此本次未执行。`
  });
} else {
  checks.push(run('end-to-end', npm, ['run', 'test:e2e'], { tolerateFailure: true, notExecutedExitCodes: [2] }));
}

const manual = [
  { id: 'A20-A29-print', item: '系统打印对话框与 A4 实纸测量', status: 'blocked', reason: '需要实体打印机与人工测量，自动检查不能替代。' },
  { id: 'A70-A75-browsers', item: 'Chrome / Edge / Safari 桌面与 iPhone Safari、Android Chrome 实机验收', status: 'blocked', reason: '需要真实设备与手动操作，本次未执行。' },
  { id: 'A76-mobile-keyboard', item: '软键盘遮挡与安全区实测', status: 'blocked', reason: '需要真实移动设备输入法。' },
  { id: 'R05-R06-textbooks', item: '语文 12 册与英语 8 册教材词库', status: 'blocked', reason: '尚无经核验与人工校对的可用来源，未发布任何教材内容，也未用示例数据顶替。' },
  { id: 'M06-M07-server-pdf', item: '公开服务端 PDF 执行链与匿名文件隔离', status: 'blocked', reason: '需要 Scheduler/Storage/Executor 的公开领域隔离与专用执行包，本次未实现；当前为本机处理。' }
];

const failed = checks.filter(check => check.status === 'fail');
const notExecuted = checks.filter(check => check.status === 'not-executed');
const report = {
  generatedAt: new Date().toISOString(),
  projectRoot,
  automated: checks,
  manual,
  result: failed.length ? 'failed' : 'automated-pass-with-open-items',
  summary: {
    automatedChecks: checks.length,
    passed: checks.filter(check => check.status === 'pass').length,
    failed: failed.length,
    notExecuted: notExecuted.length,
    openManualItems: manual.length
  }
};

const outputDir = join(repoRoot, 'docs', 'verification', 'public-toolbox');
await mkdir(outputDir, { recursive: true });
const outputFile = join(outputDir, 'release-verification.json');
await writeFile(outputFile, `${JSON.stringify(report, null, 2)}\n`, 'utf8');

for (const check of checks) {
  const tag = { pass: 'PASS', 'not-executed': 'SKIP', warn: 'WARN', fail: 'FAIL' }[check.status];
  console.log(`${tag}  ${check.label} (${check.durationMs} ms)${check.reason ? ` — ${check.reason}` : ''}`);
}
const passed = checks.filter(check => check.status === 'pass').length;
console.log(`\n自动检查：${passed}/${checks.length} 通过${notExecuted.length ? `，${notExecuted.length} 项未执行（不计为通过）` : ''}。`);
console.log(`未自动验证的人工项：${manual.length} 项（不标记为通过）。`);
console.log(`报告已写入：${outputFile}`);

if (failed.length) {
  for (const check of failed) {
    console.error(`\n[${check.label}] 退出码 ${check.exitCode}\n${check.tail}`);
  }
  process.exit(1);
}
