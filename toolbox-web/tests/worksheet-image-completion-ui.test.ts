import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import { effectScope, reactive, ref } from 'vue';
import { useWorksheetPairCompletion } from '../src/composables/useWorksheetPairCompletion';
import type { EditableWorksheetPair } from '../src/domain/worksheet-editable-pairs';
import type { TranslationResult } from '../src/services/public-api';

/** 执行真实图片组件，仅替换 OCR、文件和浏览器生命周期边界。 */
function imageSource(initial: EditableWorksheetPair[]) {
  const filename = fileURLToPath(new URL('../src/components/worksheet/WorksheetImageInput.vue', import.meta.url));
  const { descriptor } = parse(readFileSync(filename, 'utf8'), { filename });
  const compiled = compileScript(descriptor, { id: 'image-completion-test' });
  const output = ts.transpileModule(compiled.content.replaceAll('import.meta.env', '({})'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  });
  const require = createRequire(filename);
  const candidatePairs = ref(initial);
  const session = {
    items: ref([{ id: 'image-1', status: 'done', text: 'apple', rawText: 'apple', confidence: 90 }]),
    selected: ref(null), preview: ref(null), queue: ref({ active: null }), issues: ref([]),
    candidate: ref('apple'), candidatePairs, status: ref(''), recognizing: ref(false), picking: ref(false),
    cancel() {}, dispose() {}, reset() { candidatePairs.value = []; }
  };
  const module = { exports: {} as { default: { setup: (...args: unknown[]) => Record<string, any> } } };
  new Function('require', 'module', 'exports', output.outputText)((name: string) => {
    if (name === 'vue') return { ...require('vue'), onBeforeUnmount() {} };
    if (name.endsWith('/worksheet-image-session')) return { createWorksheetImageSession: () => session };
    if (name.endsWith('/ocr-engine')) return { createOcrWorker() {}, ocrAssetBase: () => '' };
    if (name.endsWith('.vue')) return { default: {} };
    return require(name);
  }, module, module.exports);
  const scope = effectScope();
  const props = reactive({ subject: 'english' as const, inputRevision: 0, hasExistingText: false, active: true });
  const emitted: Array<{ event: string; args: unknown[] }> = [];
  const ui = scope.run(() => module.exports.default.setup(props, {
    expose() {}, emit(event: string, ...args: unknown[]) { emitted.push({ event, args }); }
  }))!;
  return { ui, props, candidatePairs, emitted, scope, dispose: () => scope.stop() };
}

test('图片下一步等待首次翻译，将结果和一次标记一起导入', async () => {
  const fixture = imageSource([{ english: 'apple' }]);
  let resolve!: (value: TranslationResult[]) => void;
  let request: ReadonlyArray<{ id: string; text: string }> = [];
  let calls = 0;
  const completion = fixture.scope.run(() => useWorksheetPairCompletion('english', fixture.candidatePairs, {
    enabled: () => fixture.props.active, debounceMs: 60000,
    translate: async items => { calls++; request = items; return new Promise(done => { resolve = done; }); }
  }))!;
  fixture.ui.candidateEditor.value = { cancelCompletion: completion.cancel, flushCompletion: completion.flush };
  try {
    const next = fixture.ui.applyCandidate('replace');
    await new Promise(done => setTimeout(done, 0));
    assert.equal(fixture.emitted.length, 0);
    assert.equal(fixture.ui.applying.value, true);
    resolve(request.map(item => ({ id: item.id, sourceText: item.text, englishText: item.text, meaning: '苹果', status: 'translated' })));
    await next;
    assert.equal(calls, 1);
    assert.equal(fixture.emitted.length, 1);
    const rows = fixture.emitted[0]!.args[2] as EditableWorksheetPair[];
    assert.equal(rows[0]!.meaning, '苹果');
    assert.equal(rows[0]!.automaticInitialized, true);
    assert.equal(fixture.ui.applying.value, false);
  } finally { fixture.dispose(); }
});

test('图片下一步等待时修改候选或离开来源均不导入，迟到译文不覆盖', async () => {
  for (const action of ['edit', 'leave'] as const) {
    const fixture = imageSource([{ english: 'apple' }]);
    let resolve!: (value: TranslationResult[]) => void;
    let request: ReadonlyArray<{ id: string; text: string }> = [];
    const completion = fixture.scope.run(() => useWorksheetPairCompletion('english', fixture.candidatePairs, {
      enabled: () => fixture.props.active, debounceMs: 60000,
      translate: async items => { request = items; return new Promise(done => { resolve = done; }); }
    }))!;
    fixture.ui.candidateEditor.value = { cancelCompletion: completion.cancel, flushCompletion: completion.flush };
    try {
      const next = fixture.ui.applyCandidate('replace');
      await new Promise(done => setTimeout(done, 0));
      if (action === 'edit') fixture.candidatePairs.value[0]!.english = 'banana';
      else { fixture.props.active = false; fixture.ui.cancel(); }
      await next;
      resolve(request.map(item => ({ id: item.id, sourceText: item.text, englishText: item.text, meaning: '苹果', status: 'translated' })));
      await new Promise(done => setTimeout(done, 0));
      assert.equal(fixture.emitted.length, 0);
      assert.equal(fixture.candidatePairs.value[0]!.meaning, undefined);
      assert.equal(fixture.ui.applying.value, false);
    } finally { fixture.dispose(); }
  }
});
