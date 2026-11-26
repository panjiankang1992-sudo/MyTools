import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import { effectScope } from 'vue';
import { useWorksheetV2Session, type WorksheetSessionDependencies } from '../src/composables/useWorksheetV2Session';
import type { EditableWorksheetPair } from '../src/domain/worksheet-editable-pairs';
import { projectWorksheet } from '../src/lib/worksheet';
import type { WorksheetMode } from '../src/types';

/** 执行真实处理和投影，只替换网络、持久化与 PDF 字节边界。 */
function harness(subject: 'chinese' | 'english') {
  const translated: string[] = [];
  const renders: Array<{ mode: WorksheetMode; answer: boolean; prompts: string[] }> = [];
  const values = new Map<string, string>();
  const dependencies: WorksheetSessionDependencies = {
    storage: { getItem: key => values.get(key) ?? null, setItem: (key, value) => { values.set(key, value); }, removeItem: key => { values.delete(key); } },
    translate: async items => { translated.push(...items.map(item => item.text)); return []; },
    release() {}, recordSuccess() {}, recordPrint() {},
    render: async (entries, options) => {
      const projected = projectWorksheet(entries, options);
      renders.push({ mode: options.mode, answer: options.answer, prompts: projected.map(item => item.prompt) });
      return { bytes: new TextEncoder().encode(JSON.stringify(projected)), filename: 'worksheet.pdf', pageCount: 1, url: `blob:${renders.length}` };
    }
  };
  return { flow: useWorksheetV2Session(subject, dependencies), dependencies, translated, renders };
}

const cases: Array<{ subject: 'chinese' | 'english'; row: EditableWorksheetPair; mode: WorksheetMode; prompt: string }> = [
  { subject: 'chinese', row: { text: '春天' }, mode: 'chinese-pinyin', prompt: '春天' },
  { subject: 'chinese', row: { pinyin: 'chūn tiān' }, mode: 'chinese-dictation', prompt: 'chūn tiān' },
  { subject: 'english', row: { meaning: '星期一' }, mode: 'english-dictation', prompt: '星期一' },
  { subject: 'english', row: { english: 'Monday' }, mode: 'english-meaning', prompt: 'Monday' }
];

for (const item of cases) {
  test(`${item.mode} 单侧内容可预览、往返和恢复，不再补全或生成虚假答案`, async () => {
    const { flow, translated, renders } = harness(item.subject);
    assert.equal(flow.importEditablePairs([{ ...item.row, automaticInitialized: true }]), true);
    flow.prepareConfirmation();
    assert.equal(flow.selectedMode.value, item.mode);
    assert.deepEqual(flow.modeOptions.value.filter(option => !option.disabled).map(option => option.value), [item.mode]);
    assert.equal(flow.canGenerateAnswer.value, false);
    await flow.generateNow();
    assert.equal(flow.canOutput.value, true);
    assert.deepEqual(renders[0]?.prompts, [item.prompt]);
    const projected = JSON.parse(new TextDecoder().decode(flow.artifact.value!.bytes));
    assert.ok(projected.every((row: object) => !Object.hasOwn(row, 'answerText')));
    await flow.generateAnswerNow();
    assert.equal(renders.length, 1);
    assert.equal(flow.answerArtifact.value, null);
    flow.goBackToEdit();
    flow.prepareConfirmation();
    assert.equal(flow.selectedMode.value, item.mode);
    assert.equal(flow.saveDraft(), true);
    flow.clearAll();
    flow.loadDraft();
    flow.prepareConfirmation();
    assert.equal(flow.selectedMode.value, item.mode);
    assert.deepEqual(translated, []);
    flow.dispose();
  });
}

test('两列完整允许两种模板，清空一侧立即切换到兼容模板并使旧答案失效', async () => {
  for (const subject of ['chinese', 'english'] as const) {
    const { flow } = harness(subject);
    flow.importEditablePairs([{ ...(subject === 'chinese' ? { text: '春天', pinyin: 'chūn tiān' }
      : { meaning: '星期一', english: 'Monday' }), automaticInitialized: true }]);
    flow.prepareConfirmation();
    assert.equal(flow.modeOptions.value.filter(option => !option.disabled).length, 2);
    assert.equal(flow.canGenerateAnswer.value, true);
    flow.applyMode(subject === 'chinese' ? 'chinese-pinyin' : 'english-meaning');
    await flow.generateAnswerNow();
    assert.equal(flow.canOutputAnswer.value, true);
    const id = flow.editablePairs.value[0]!.tokenId!;
    assert.equal(flow.updatePair(id, subject === 'chinese' ? { text: '' } : { english: '' }), true);
    assert.equal(flow.selectedMode.value, subject === 'chinese' ? 'chinese-dictation' : 'english-dictation');
    assert.equal(flow.canGenerateAnswer.value, false);
    assert.equal(flow.canOutputAnswer.value, false);
    await flow.generateNow();
    assert.equal(flow.canOutput.value, true);
    flow.dispose();
  }
});

test('混用互斥的单侧行不通过随机抽取绕过，补齐后自动选择兼容题型', async () => {
  const { flow, renders } = harness('english');
  flow.importEditablePairs([{ meaning: '星期一', automaticInitialized: true }, { english: 'Tuesday', automaticInitialized: true }]);
  flow.prepareConfirmation();
  flow.applySelection('random-count', 1);
  assert.equal(flow.selectedMode.value, null);
  assert.ok(flow.modeOptions.value.every(option => option.disabled));
  await flow.generateNow();
  assert.equal(renders.length, 0);
  const id = flow.editablePairs.value[1]!.tokenId!;
  flow.updatePair(id, { meaning: '星期二' });
  assert.equal(flow.selectedMode.value, 'english-dictation');
  await flow.generateNow();
  assert.equal(flow.canOutput.value, true);
  assert.equal(flow.session.value.document.tokens.length, 2);
  flow.dispose();
});

test('纯拼音逐字练习按音节各留一个米字格，不把字母当成汉字', async () => {
  const { flow, translated } = harness('chinese');
  assert.equal(flow.importEditablePairs([{ pinyin: 'chūn tiān' }]), true);
  flow.prepareConfirmation();
  flow.applyProcessing({ chineseUnit: 'character' });
  await flow.generateNow();
  assert.equal(flow.canOutput.value, true);
  const questions = JSON.parse(new TextDecoder().decode(flow.artifact.value!.bytes)) as Array<{ prompt: string; cells: number; segments: string[] }>;
  assert.deepEqual(questions.map(item => ({ prompt: item.prompt, cells: item.cells, segments: item.segments })), [
    { prompt: 'chūn', cells: 1, segments: ['chūn'] }, { prompt: 'tiān', cells: 1, segments: ['tiān'] }
  ]);
  assert.deepEqual(translated, [], '没有汉字时不能调用拼音补全或推测汉字');
  flow.dispose();
});

/** 编译实际页面 setup，验证页面入口不会绕过模板禁用状态。 */
function page(subject: 'chinese' | 'english') {
  const current = harness(subject);
  const filename = fileURLToPath(new URL('../src/views/WorksheetV2View.vue', import.meta.url));
  const { descriptor } = parse(readFileSync(filename, 'utf8'), { filename });
  const compiled = compileScript(descriptor, { id: 'single-sided-flow-test' });
  const output = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } });
  const require = createRequire(filename);
  const module = { exports: {} as { default: { setup: (...args: unknown[]) => Record<string, any> } } };
  new Function('require', 'module', 'exports', output.outputText)((name: string) => {
    if (name === '../composables/useWorksheetV2Session') return { useWorksheetV2Session: () => current.flow };
    if (name === 'vue') return { ...require('vue'), onActivated() {}, onDeactivated() {} };
    if (name.endsWith('.vue')) return { default: {} };
    return require(name);
  }, module, module.exports);
  const scope = effectScope();
  const ui = scope.run(() => module.exports.default.setup({ subject }, { expose() {} }))!;
  return { ...current, ui, dispose: () => { current.flow.dispose(); scope.stop(); } };
}

for (const item of cases) {
  test(`${item.mode} 页面下一步接受单侧输入，并拒绝点击不兼容模板`, async () => {
    const current = page(item.subject);
    current.ui.chooseSource('custom');
    current.ui.customPairs.value = [{ ...item.row, automaticInitialized: true }];
    await current.ui.applyCustomPairs();
    assert.equal(current.ui.stage.value, 'confirm');
    assert.equal(current.ui.sourceIssue.value, '');
    const disabled = current.flow.modeOptions.value.find(option => option.disabled)!;
    current.ui.chooseMode(disabled.value);
    assert.equal(current.flow.selectedMode.value, item.mode);
    await current.ui.generatePreview();
    assert.equal(current.ui.stage.value, 'preview');
    assert.match(current.ui.answerHint.value, /填写完整的两列/);
    await current.ui.generateAnswer();
    assert.equal(current.renders.length, 1);
    current.ui.returnToSource();
    assert.deepEqual(current.ui.customPairs.value.filter((row: EditableWorksheetPair) => row.tokenId)
      .map((row: EditableWorksheetPair) => [row.text ?? '', row.pinyin ?? '', row.meaning ?? '', row.english ?? '']),
    [[item.row.text ?? '', item.row.pinyin ?? '', item.row.meaning ?? '', item.row.english ?? '']]);
    current.dispose();
  });
}
