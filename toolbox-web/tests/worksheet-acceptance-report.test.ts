import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import {
  executeAcceptanceCheck, readAcceptanceContract, summarizeAcceptance,
  type CaseDefinition, type CheckResult
} from '../scripts/worksheet-acceptance-report';

/** 创建固定 44 主项的最小契约，每项两个必测条件。 */
function contract(): CaseDefinition[] {
  return Array.from({ length: 44 }, (_, index) => ({
    id: `W${String(index + 1).padStart(2, '0')}`, title: 'Test case',
    required: [{ id: 'behavior', description: 'Behavior' }, { id: 'evidence', description: 'Evidence' }]
  }));
}

/** 创建实际执行且有断言的成功片段。 */
function passing(caseId: string, suffix: string, covers: string[]): Promise<CheckResult> {
  return executeAcceptanceCheck({ id: `${caseId}.${suffix}`, caseId, title: 'Measured values', covers }, check => {
    check.equal([1, 2].length, 2, 'Actual array length');
  });
}

test('验收契约严格保留 44 主项及必测步骤和证据', async () => {
  const markdown = await readFile(resolve('../docs/design/2026-10-03-worksheet-workflow-revision/acceptance.md'), 'utf8');
  const definitions = readAcceptanceContract(markdown);
  assert.equal(definitions.length, 44);
  assert.equal(definitions[0]?.required.length, 7);
  assert.equal(definitions[43]?.id, 'W44');
  assert.throws(() => readAcceptanceContract(markdown.replace('| W02 ', '| W01 ')));
});

test('异步回调完成前不能通过，异步断言失败产生 fail 及退出码 1', async () => {
  const failed = await executeAcceptanceCheck({ id: 'W01.async', caseId: 'W01', title: 'Async', covers: ['behavior'] }, async check => {
    await Promise.resolve();
    check.equal(2 + 2, 5, 'Intentional failure');
  });
  const report = summarizeAcceptance(contract(), [failed]);
  assert.equal(failed.assertions[0]?.actual, 4);
  assert.equal(failed.assertions[0]?.passed, false);
  assert.equal(report.cases[0]?.status, 'fail');
  assert.equal(report.gate.exitCode, 1);
});

test('空回调和只有日志附件的回调不得通过', async () => {
  const empty = await executeAcceptanceCheck({ id: 'W01.empty', caseId: 'W01', title: 'Empty', covers: ['behavior', 'evidence'] }, async () => {});
  const logs = await executeAcceptanceCheck({ id: 'W02.logs', caseId: 'W02', title: 'Logs', covers: ['behavior', 'evidence'] }, check => {
    check.evidence('historical-report.json');
  });
  assert.equal(empty.status, 'not_run');
  assert.equal(logs.status, 'not_run');
  assert.equal(summarizeAcceptance(contract(), [empty, logs]).counts.not_run, 44);
});

test('支持片段及历史 pass 都不能填补缺失必测条件', async () => {
  const partial = await passing('W01', 'partial', ['behavior']);
  const historical = { ...await passing('W01', 'old', ['evidence']), provenance: 'historical' as const };
  const report = summarizeAcceptance(contract(), [partial, historical]);
  assert.equal(report.total, 44);
  assert.equal(report.cases[0]?.subcases.length, 2);
  assert.equal(report.cases[0]?.status, 'not_run');
  assert.deepEqual(report.cases[0]?.missingRequirements, ['evidence']);
  assert.equal(report.cases[0]?.subcases[1]?.status, 'not_run');
  assert.equal(report.gate.exitCode, 2);
});

test('片段不增加总数，只有所有主项必测条件齐备才放行', async () => {
  const checks = await Promise.all(contract().flatMap(item => [
    passing(item.id, 'behavior', ['behavior']), passing(item.id, 'evidence', ['evidence'])
  ]));
  const report = summarizeAcceptance(contract(), checks);
  assert.equal(checks.length, 88);
  assert.equal(report.total, 44);
  assert.equal(report.counts.pass, 44);
  assert.equal(report.gate.exitCode, 0);
});

test('必测阻塞优先于部分通过，记录未完成退出码', async () => {
  const blocked: CheckResult = {
    ...await passing('W01', 'missing-printer', ['evidence']),
    status: 'blocked', assertions: [], reason: 'Printer unavailable'
  };
  const report = summarizeAcceptance(contract(), [await passing('W01', 'behavior', ['behavior']), blocked]);
  assert.equal(report.cases[0]?.status, 'blocked');
  assert.equal(report.gate.passed, false);
  assert.equal(report.gate.exitCode, 2);
});

test('排除必须显式提供出处，总数仍为 44，排除三项后范围为 41', () => {
  const exclusions = ['W18', 'W40', 'W42'].map(caseId => ({ caseId, reason: 'Scope test only', authorizationReference: 'test-fixture' }));
  const report = summarizeAcceptance(contract(), [], exclusions);
  assert.equal(report.total, 44);
  assert.equal(report.inScope, 41);
  assert.equal(report.counts.excluded, 3);
  assert.equal(report.counts.not_run, 41);
  assert.equal(report.gate.exitCode, 2);
  assert.throws(() => summarizeAcceptance(contract(), [], [{ caseId: 'W40', reason: 'Missing', authorizationReference: '' }]));
  assert.throws(() => summarizeAcceptance(contract(), [], [...exclusions, exclusions[0]!]));
});

test('拒绝未知主项、重复片段与不存在的必测条件', async () => {
  const valid = await passing('W01', 'valid', ['behavior']);
  assert.throws(() => summarizeAcceptance(contract(), [{ ...valid, caseId: 'W99' }]));
  assert.throws(() => summarizeAcceptance(contract(), [valid, valid]));
  assert.throws(() => summarizeAcceptance(contract(), [{ ...valid, covers: ['imaginary'] }]));
});

test('CLI 输出独立新文件并在未完成时非零退出，不覆盖已存在的报告', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'worksheet-acceptance-report-'));
  const output = join(directory, 'report.json');
  try {
    const run = spawnSync(process.execPath, ['--import', 'tsx', 'scripts/run-acceptance.mjs', '--output', output], { cwd: process.cwd(), encoding: 'utf8' });
    assert.equal(run.status, 2, `${run.stdout}\n${run.stderr}`);
    const original = await readFile(output, 'utf8');
    const report = JSON.parse(original);
    assert.equal(report.total, 44);
    assert.equal(report.counts.pass, 0);
    assert.equal(report.counts.not_run, 44);
    assert.equal(report.cases.find((item: { id: string }) => item.id === 'W05').subcases[0].status, 'pass');
    assert.ok(report.historicalEvidence.every((item: { reusedAsCurrentPass: boolean }) => item.reusedAsCurrentPass === false));
    const second = spawnSync(process.execPath, ['--import', 'tsx', 'scripts/run-acceptance.mjs', '--output', output], { cwd: process.cwd(), encoding: 'utf8' });
    assert.notEqual(second.status, 0);
    assert.match(second.stderr, /EEXIST/);
    assert.equal(await readFile(output, 'utf8'), original);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
