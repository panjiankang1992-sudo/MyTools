import assert from 'node:assert/strict';

export type CheckStatus = 'pass' | 'fail' | 'blocked' | 'not_run';
export interface Requirement { id: string; description: string }
export interface CaseDefinition { id: string; title: string; required: Requirement[] }
export interface AssertionResult { message: string; actual: unknown; expected: unknown; passed: boolean }
export interface CheckResult {
  id: string; caseId: string; title: string; covers: string[];
  provenance: 'current' | 'historical'; status: CheckStatus;
  startedAt: string | null; finishedAt: string | null;
  assertions: AssertionResult[]; evidence: string[]; reason: string | null;
}
export interface ScopeDecision {
  caseId: string; reason: string; authorizationReference: string;
}

/** 从验收契约读取固定的 44 个主项及每项必测步骤，不把片段计入主项。 */
export function readAcceptanceContract(markdown: string): CaseDefinition[] {
  const definitions = markdown.split('\n').filter(line => /^\| W\d{2} /.test(line)).map(line => {
    const cells = line.split(' | ');
    const matched = cells[0]!.match(/^\| (W\d{2}) (.+?) \/ /);
    if (!matched || cells.length !== 5) throw new Error('Invalid acceptance contract row');
    const steps = cells[2]!.split(/[①②③④⑤⑥⑦⑧⑨⑩]/u).filter(Boolean);
    if (steps.length === 0) throw new Error(`Missing required steps: ${matched[1]}`);
    return {
      id: matched[1]!, title: matched[2]!,
      required: [
        ...steps.map((description, index) => ({ id: `step-${index + 1}`, description })),
        { id: 'criteria', description: cells[3]! },
        { id: 'evidence', description: cells[4]!.replace(/ \|$/, '') }
      ]
    };
  });
  const expected = Array.from({ length: 44 }, (_, index) => `W${String(index + 1).padStart(2, '0')}`);
  assert.deepEqual(definitions.map(item => item.id), expected, 'Acceptance contract must contain W01-W44 exactly once');
  return definitions;
}

/** 等待实际检查完成并保存断言；空回调及纯日志回调不能得到通过状态。 */
export async function executeAcceptanceCheck(
  meta: Pick<CheckResult, 'id' | 'caseId' | 'title' | 'covers'>,
  body: (check: { equal(actual: unknown, expected: unknown, message: string): void; evidence(path: string): void }) => void | Promise<void>
): Promise<CheckResult> {
  const result: CheckResult = {
    ...meta, provenance: 'current', status: 'not_run', startedAt: new Date().toISOString(),
    finishedAt: null, assertions: [], evidence: [], reason: null
  };
  try {
    await body({
      equal(actual, expected, message) {
        const assertion = { message, actual, expected, passed: false };
        result.assertions.push(assertion);
        // 每条断言必须比较本轮实际值；失败在报告中保留实际值及期望值。
        assert.deepEqual(actual, expected, message);
        assertion.passed = true;
      },
      evidence(path) { result.evidence.push(path); }
    });
    result.status = result.assertions.length > 0 ? 'pass' : 'not_run';
    if (result.status === 'not_run') result.reason = 'No assertions executed in this run';
  } catch (error) {
    result.status = 'fail';
    result.reason = error instanceof Error ? error.message : String(error);
  }
  result.finishedAt = new Date().toISOString();
  return result;
}

/** 将真实子项归并到 44 个主项；历史附件永远不满足当前必测条件。 */
export function summarizeAcceptance(
  definitions: CaseDefinition[], checks: CheckResult[], exclusions: ScopeDecision[] = []
) {
  const expected = Array.from({ length: 44 }, (_, index) => `W${String(index + 1).padStart(2, '0')}`);
  assert.deepEqual(definitions.map(item => item.id), expected);
  const ids = new Set(definitions.map(item => item.id));
  const checkIds = new Set<string>();
  for (const check of checks) {
    if (!ids.has(check.caseId) || checkIds.has(check.id)) throw new Error(`Invalid or repeated check: ${check.id}`);
    checkIds.add(check.id);
    const required = definitions.find(item => item.id === check.caseId)!.required;
    if (check.covers.some(id => !required.some(item => item.id === id))) throw new Error(`Unknown requirement: ${check.id}`);
  }
  const excludedIds = new Set<string>();
  for (const decision of exclusions) {
    // 排除必须由调用方显式传入并记录出处，不从旧报告推断授权。
    if (!ids.has(decision.caseId) || excludedIds.has(decision.caseId)
      || !decision.reason?.trim() || !decision.authorizationReference?.trim()) {
      throw new Error(`Invalid scope decision: ${decision.caseId}`);
    }
    excludedIds.add(decision.caseId);
  }
  const cases = definitions.map(definition => {
    const subcases = checks.filter(check => check.caseId === definition.id).map(check => {
      // 旧 JSON 的 pass、纯日志和无断言条目都不能重新盖上本轮通过戳。
      if (check.provenance === 'historical' || (check.status === 'pass'
        && (!check.startedAt || !check.finishedAt || check.assertions.length === 0
          || check.assertions.some(assertion => !assertion.passed)))) {
        return { ...check, status: 'not_run' as const, reason: 'Historical or unverified evidence; not executed in this run' };
      }
      return check;
    });
    const current = subcases.filter(check => check.provenance === 'current');
    const covered = new Set(current.filter(check => check.status === 'pass').flatMap(check => check.covers));
    const requirements = definition.required.map(requirement => {
      const relevant = current.filter(check => check.covers.includes(requirement.id));
      const status: CheckStatus = relevant.some(check => check.status === 'fail') ? 'fail'
        : relevant.some(check => check.status === 'blocked') ? 'blocked'
          : covered.has(requirement.id) ? 'pass' : 'not_run';
      return { ...requirement, status, checkIds: relevant.map(check => check.id) };
    });
    const observedStatus: CheckStatus = current.some(check => check.status === 'fail') ? 'fail'
      : current.some(check => check.status === 'blocked') ? 'blocked'
        : requirements.length > 0 && requirements.every(item => item.status === 'pass') ? 'pass' : 'not_run';
    const exclusion = exclusions.find(item => item.caseId === definition.id) ?? null;
    return {
      ...definition, required: requirements, subcases, observedStatus,
      status: exclusion ? 'excluded' as const : observedStatus,
      scope: exclusion ? 'excluded' as const : 'included' as const, exclusion,
      missingRequirements: requirements.filter(item => item.status !== 'pass').map(item => item.id)
    };
  });
  const counts = { pass: 0, fail: 0, blocked: 0, not_run: 0, excluded: 0 };
  for (const item of cases) counts[item.status] += 1;
  // 失败为 1；尚未执行或阻塞为 2。未完成不能作为默认放行条件。
  const exitCode = counts.fail > 0 ? 1 : counts.blocked + counts.not_run > 0 ? 2 : 0;
  return { total: cases.length, inScope: cases.length - counts.excluded, counts, gate: { passed: exitCode === 0, exitCode }, cases };
}
