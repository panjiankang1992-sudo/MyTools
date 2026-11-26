#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readAcceptanceContract, executeAcceptanceCheck, summarizeAcceptance } from './worksheet-acceptance-report.ts';
import { createInputDocument, syncInputDocument, tokenize } from '../src/domain/worksheet-input.ts';
import { validateImageFile, planWorkingImage } from '../src/lib/image-preprocess.ts';
import { createSession, setMode, setRawText } from '../src/domain/worksheet-session.ts';

/** 只运行明确列出的领域探针，未完成的界面、PDF、真实 OCR 等验收保持未执行。 */
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const evidenceRoot = join(root, '..', 'docs/verification/worksheet-workflow-v2');
const contractPath = join(root, '..', 'docs/design/2026-10-03-worksheet-workflow-revision/acceptance.md');
const startedAt = new Date().toISOString();
const runId = `acceptance-${startedAt.replace(/[:.]/g, '-')}-${process.pid}`;
const args = process.argv.slice(2);
let outPath = join(evidenceRoot, runId, 'acceptance-results.json');
let scopePath = null;
for (let index = 0; index < args.length; index += 1) {
  if (args[index] === '--help') {
    console.log('Usage: npm run test:acceptance -- [--output <new-file.json>] [--scope <scope.json>]');
    console.log('Scope JSON: {"exclusions":[{"caseId":"W40","reason":"...","authorizationReference":"..."}]}');
    console.log('Existing output files are never overwritten. Exit: 0 complete, 1 failed, 2 incomplete.');
    process.exit(0);
  }
  if (!['--output', '--scope'].includes(args[index]) || !args[index + 1] || args[index + 1].startsWith('--')) {
    throw new Error(`Unknown or incomplete argument: ${args[index]}`);
  }
  if (args[index] === '--output') outPath = resolve(args[++index]);
  else scopePath = resolve(args[++index]);
}
const contract = await readFile(contractPath, 'utf8');
const definitions = readAcceptanceContract(contract);
let exclusions = [];
if (scopePath) {
  const scope = JSON.parse(await readFile(scopePath, 'utf8'));
  if (!Array.isArray(scope.exclusions)) throw new Error('Scope manifest must explicitly list exclusions');
  exclusions = scope.exclusions;
}
const checks = [];
/** 保存本轮执行断言，covers 只列该探针实际完成的契约步骤。 */
async function probe(caseId, id, title, covers, body) {
  checks.push(await executeAcceptanceCheck({ caseId, id, title, covers }, body));
}
await probe('W01', 'W01.empty-document', '新草稿原文为空（不覆盖六路径界面闭环）', [], check => {
  const document = createInputDocument('chinese');
  check.equal(document.rawText, '', '新草稿没有自动填入示例');
  check.equal(document.tokens.length, 0, '新草稿没有候选');
});
await probe('W04', 'W04.raw-string', '解析保留原字符串（不覆盖界面切换及撤销）', [], check => {
  const raw = 'apple   "ice cream"\ndon\'t';
  const result = syncInputDocument(createInputDocument('english'), raw, 'whitespace');
  check.equal(result.document.rawText, raw, '原字符串空白及引号完整保留');
  check.equal(result.document.tokens.map(token => token.text), ['apple', 'ice cream', "don't"], '派生分项正确');
});
await probe('W05', 'W05.whitespace', '中文分隔符和未分隔串', ['step-2', 'step-3'], check => {
  for (const separator of [' ', '\t', '\n', '\u3000']) {
    check.equal(tokenize(`春天${separator}花朵`, 'whitespace').spans.map(item => item.text), ['春天', '花朵'], `分隔符 ${JSON.stringify(separator)}`);
  }
  check.equal(tokenize('春花秋月', 'whitespace').spans.map(item => item.text), ['春花秋月'], '未分隔串不自动拆字');
});
await probe('W06', 'W06.phrase-parser', '短语、逐行及引号错误领域检查', [], check => {
  check.equal(tokenize('apple "ice cream" don\'t', 'whitespace').spans.map(item => item.text), ['apple', 'ice cream', "don't"], '引号短语保持一项');
  check.equal(tokenize('ice cream\nNew York', 'one-per-line').spans.map(item => item.text), ['ice cream', 'New York'], '逐行短语保持一项');
  check.equal(tokenize('"unclosed', 'whitespace').blocked, true, '未闭合引号被阻断');
});
await probe('W09', 'W09.signature', '文件签名检查（不覆盖真实解码及页面拒绝）', [], check => {
  const common = { name: 'test.png', type: 'image/png', bytes: 100, width: 100, height: 100 };
  const accepted = validateImageFile({ ...common, header: new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]) });
  const rejected = validateImageFile({ ...common, header: new Uint8Array([0x47, 0x49, 0x46, 0x38]) });
  check.equal(accepted.length, 0, '合法 PNG 签名通过');
  check.equal(rejected.some(issue => issue.code === 'WORKSHEET_IMAGE_UNSUPPORTED_FORMAT'), true, '伪装 GIF 签名被拒');
});
await probe('W10', 'W10.working-size', '工作图缩放边界（不覆盖队列删除额度）', [], check => {
  const result = planWorkingImage(8000, 6000);
  check.equal(result.width * result.height <= 6 * 1024 * 1024, true, '工作图像素不超限');
  check.equal(Math.max(result.width, result.height) <= 4096, true, '工作图最长边不超限');
});
await probe('W19', 'W19.global-mode', '切全局模式保留原文（不覆盖教材选择及 UI）', [], check => {
  const input = setRawText(createSession('chinese'), '春 天').session;
  const pinyin = setMode(input, 'chinese-pinyin').session;
  const hanzi = setMode(pinyin, 'chinese-dictation').session;
  check.equal(hanzi.document.rawText, input.document.rawText, '切换全局模式不改原文');
  check.equal(hanzi.processing.mode, 'chinese-dictation', '全局模式更新');
});

/** 记录源码快照而非冒充构建或浏览器版本。 */
async function collectFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await collectFiles(path));
    else if (entry.isFile()) files.push(path);
  }
  return files.sort();
}
const sourceFiles = [
  ...await collectFiles(join(root, 'src')),
  join(root, 'package.json'), join(root, 'package-lock.json'),
  fileURLToPath(import.meta.url), join(root, 'scripts/worksheet-acceptance-report.ts')
];
const sourceHash = createHash('sha256');
for (const path of sourceFiles) sourceHash.update(path.slice(root.length + 1)).update('\0').update(await readFile(path));
const historicalEvidence = [];
for (const name of ['acceptance-results.json', 'ocr-fixtures.json', 'ocr-ab.json', 'layout-density.json', 'responsive-report.json', 'accessibility-report.json']) {
  try {
    const bytes = await readFile(join(evidenceRoot, name));
    historicalEvidence.push({ path: name, sha256: createHash('sha256').update(bytes).digest('hex'), provenance: 'historical', reusedAsCurrentPass: false });
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}
const result = summarizeAcceptance(definitions, checks, exclusions);
const report = {
  schemaVersion: 2, runId, startedAt, finishedAt: new Date().toISOString(),
  environment: { node: process.version, platform: process.platform, architecture: process.arch, executor: 'local-domain-probes', browser: null, device: null },
  buildId: null, commit: null, sourceSnapshot: { sha256: sourceHash.digest('hex'), fileCount: sourceFiles.length },
  contract: { path: contractPath, sha256: createHash('sha256').update(contract).digest('hex') },
  scope: { manifestPath: scopePath, exclusions, recordedHistoricalDecisions: 'FINAL-REPORT.md records assisted Chinese OCR and PDF-only verification. These records are retained, not automatically converted into scope authorization or current execution evidence.' },
  note: 'Only listed domain probes ran. UI, current PDF downloads, real OCR and device checks did not run. Historical attachments never satisfy current requirements.',
  historicalEvidence, ...result
};
await mkdir(dirname(outPath), { recursive: true });
// wx 防止重跑或显式 --output 意外覆盖任何原始证据。
await writeFile(outPath, `${JSON.stringify(report, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
console.log(JSON.stringify({ total: result.total, inScope: result.inScope, counts: result.counts, gate: result.gate, report: outPath }, null, 2));
process.exitCode = result.gate.exitCode;
