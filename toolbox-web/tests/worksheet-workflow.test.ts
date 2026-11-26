import assert from 'node:assert/strict';
import test from 'node:test';
import {
  beginGeneration, canOutput, clearWorkflow, completeGeneration, createWorkflow,
  failGeneration, goToOutput, goToProcess, onInputChanged, onLayoutChanged,
  onProcessingChanged, returnToEdit
} from '../src/domain/worksheet-workflow';

/** 生成一个成功的产物描述。 */
function artifact(generationId: number, kind: 'student' | 'answer' = 'student') {
  return {
    generationId,
    kind,
    snapshotHash: `snap-${generationId}`,
    pageCount: 2,
    sha256: `sha-${generationId}`
  };
}

/** 走完一次「发起 → 成功」，返回新状态。 */
function generateOnce(state: ReturnType<typeof createWorkflow>) {
  const started = beginGeneration(state);
  return completeGeneration(started.state, started.generationId, artifact(started.generationId));
}

test('初始状态停在输入步骤，没有产物也不能输出', () => {
  const state = createWorkflow();
  assert.equal(state.step, 'input');
  assert.equal(state.artifact, null);
  assert.equal(state.busy, false);
  assert.equal(canOutput(state), false);
});

test('生成成功后进入预览，产物为 current 且可输出', () => {
  const state = generateOnce(createWorkflow());
  assert.equal(state.step, 'preview');
  assert.equal(state.busy, false);
  assert.equal(state.artifact?.status, 'current');
  assert.equal(canOutput(state), true);
  assert.equal(goToOutput(state).step, 'output');
});

test('没有产物时禁止进入输出步骤', () => {
  assert.equal(goToOutput(createWorkflow()).step, 'input');
  const stale = onInputChanged(generateOnce(createWorkflow()));
  assert.equal(canOutput(stale), false, '产物过期后不得输出');
  assert.equal(goToOutput(stale).step, 'preview', '过期时不得进入输出步骤');
});

test('返回编辑但未改任何值：原文与产物都保留，产物不过期', () => {
  const generated = generateOnce(createWorkflow());
  const back = returnToEdit(generated);
  assert.equal(back.step, 'input');
  assert.equal(back.artifact?.status, 'current');
  assert.equal(canOutput(back), true, '未改动时旧产物仍可沿用');
  assert.equal(back.inputRevision, generated.inputRevision);
  assert.equal(back.renderRevision, generated.renderRevision);
});

test('修改原文使旧产物过期，并递增原文修订号', () => {
  const generated = generateOnce(createWorkflow());
  const changed = onInputChanged(generated);
  assert.equal(changed.artifact?.status, 'stale');
  assert.equal(changed.inputRevision, generated.inputRevision + 1);
  assert.equal(canOutput(changed), false);
});

test('只改空白且解析结果等价时保留产物可用性', () => {
  const generated = generateOnce(createWorkflow());
  const padded = onInputChanged(generated, true);
  assert.equal(padded.inputRevision, generated.inputRevision + 1, '仍然绑定新的原文修订号');
  assert.equal(padded.artifact?.status, 'current', '语义等价时产物继续有效');
  assert.equal(canOutput(padded), true);
});

test('纯版式变化只递增版式修订号，操作身份不变', () => {
  const generated = generateOnce(createWorkflow());
  const restyled = onLayoutChanged(generated);
  assert.equal(restyled.renderRevision, generated.renderRevision + 1);
  assert.equal(restyled.processingRevision, generated.processingRevision);
  assert.equal(restyled.inputRevision, generated.inputRevision);
  assert.equal(restyled.operationId, generated.operationId, '改版式不产生新操作身份');
  assert.equal(restyled.artifact?.status, 'stale');
});

test('处理参数变化使产物过期并递增处理修订号', () => {
  const generated = generateOnce(createWorkflow());
  const reprocessed = onProcessingChanged(generated);
  assert.equal(reprocessed.processingRevision, generated.processingRevision + 1);
  assert.equal(reprocessed.artifact?.status, 'stale');
});

test('内容变化后重新生成才产生新的操作身份', () => {
  const generated = generateOnce(createWorkflow());
  assert.equal(generated.operationId, 1, '第一次有效生成产生操作身份');
  // 只改版式再生成：沿用同一操作身份。
  const restyled = generateOnce(onLayoutChanged(generated));
  assert.equal(restyled.operationId, 1, '纯版式变化不新建操作');
  // 改原文再生成：产生新的操作身份。
  const edited = generateOnce(onInputChanged(restyled));
  assert.equal(edited.operationId, 2);
});

test('迟到结果按代次丢弃，不覆盖新状态', () => {
  const first = beginGeneration(goToProcess(createWorkflow()));
  const second = beginGeneration(first.state);
  // 第一次的结果晚到。
  const late = completeGeneration(second.state, first.generationId, artifact(first.generationId));
  assert.equal(late.artifact, null, '迟到结果不得被采纳');
  assert.equal(late.busy, true, '仍在等待当前代次');
  const current = completeGeneration(second.state, second.generationId, artifact(second.generationId));
  assert.equal(current.artifact?.generationId, second.generationId);
  assert.equal(current.artifact?.status, 'current');
});

test('失败的迟到结果同样被忽略，失败不伪装成功', () => {
  const first = beginGeneration(createWorkflow());
  const second = beginGeneration(first.state);
  assert.equal(failGeneration(second.state, first.generationId, 'OLD').lastError, null);
  const failed = failGeneration(second.state, second.generationId, 'FONT_UNAVAILABLE');
  assert.equal(failed.busy, false);
  assert.equal(failed.step, 'process', '失败留在处理步骤而不是预览');
  assert.equal(failed.lastError, 'FONT_UNAVAILABLE');
  assert.equal(failed.artifact, null, '失败不得留下看似可用的产物');
});

test('失败后保留输入与校对状态，修订号不变', () => {
  const edited = onInputChanged(onProcessingChanged(generateOnce(createWorkflow())));
  const started = beginGeneration(edited);
  const failed = failGeneration(started.state, started.generationId, 'GENERATE_FAILED');
  assert.equal(failed.inputRevision, edited.inputRevision);
  assert.equal(failed.processingRevision, edited.processingRevision);
  assert.equal(failed.renderRevision, edited.renderRevision);
});

test('清空：取消任务、清空产物、退出 busy，且不更换操作身份', () => {
  const generated = generateOnce(createWorkflow());
  const cleared = clearWorkflow({ ...generated, busy: true });
  assert.equal(cleared.busy, false);
  assert.equal(cleared.artifact, null);
  assert.equal(cleared.step, 'input');
  assert.equal(canOutput(cleared), false);
  assert.equal(cleared.operationId, generated.operationId, '清空不产生新的操作身份');
  assert.equal(cleared.lastError, null);
});

test('清空后迟到结果不得复活旧产物', () => {
  const started = beginGeneration(createWorkflow());
  const cleared = clearWorkflow(started.state);
  const revived = completeGeneration(cleared, started.generationId, artifact(started.generationId));
  assert.equal(revived.artifact, null, '被清空的任务不得因迟到结果复活');
});

test('重新生成同一已确认配置沿用原操作身份', () => {
  const generated = generateOnce(createWorkflow());
  const again = generateOnce(generated);
  assert.equal(again.operationId, generated.operationId, '同一配置重跑不新建操作');
  assert.equal(again.artifact?.status, 'current');
});

test('生成中不允许输出', () => {
  const generated = generateOnce(createWorkflow());
  const busyState = { ...generated, busy: true };
  assert.equal(canOutput(busyState), false, '生成中必须禁用输出，避免下载过期文件');
});

test('产物版本必须与原文、处理和版式全部一致', () => {
  const generated = generateOnce(createWorkflow());
  for (const key of ['inputRevision', 'processingRevision', 'renderRevision'] as const) {
    assert.equal(canOutput({ ...generated, [key]: generated[key] + 1 }), false, `${key} 不一致必须阻断`);
  }
  assert.equal(canOutput({ ...generated, generatedRevisions: null }), false, '没有产物版本不能输出');
});
