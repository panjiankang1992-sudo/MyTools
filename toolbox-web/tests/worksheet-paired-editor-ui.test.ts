import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import { effectScope, nextTick, reactive } from 'vue';
import { useWorksheetV2Session, type WorksheetSessionDependencies } from '../src/composables/useWorksheetV2Session';
import { useWorksheetPairCompletion } from '../src/composables/useWorksheetPairCompletion';
import type { EditableWorksheetPair } from '../src/domain/worksheet-editable-pairs';

/** 编译真实组件的 setup，让测试执行实际输入处理函数，不复制 UI 逻辑。 */
function loadSetup(relativePath: string, overrides: Record<string, unknown> = {}) {
  const filename = fileURLToPath(new URL(relativePath, import.meta.url));
  const { descriptor } = parse(readFileSync(filename, 'utf8'), { filename });
  const compiled = compileScript(descriptor, { id: 'paired-editor-test' });
  const output = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } });
  const require = createRequire(filename);
  const module = { exports: {} as { default: { setup: (...args: unknown[]) => Record<string, any> } } };
  new Function('require', 'module', 'exports', output.outputText)((name: string) => {
    if (name in overrides) return overrides[name];
    if (name.endsWith('.vue')) return { default: {} };
    return require(name);
  }, module, module.exports);
  return module.exports.default.setup;
}
const editorSetup = loadSetup('../src/components/worksheet/WorksheetPairedEditor.vue');

/** 输入组件使用真实 Vue 响应式 props，事件立即回填模拟 v-model。 */
function editor(subject: 'chinese' | 'english', initial: EditableWorksheetPair[], autoComplete = false,
  options: { paginated?: boolean; autoAppend?: boolean } = {}) {
  const props = reactive({ subject, rows: initial, label: '测试对应内容', busy: false, autoComplete,
    paginated: true, autoAppend: false, ...options });
  const emitted: string[] = [];
  const scope = effectScope();
  const setup = scope.run(() => editorSetup(props, { expose() {}, emit(event: string, value: EditableWorksheetPair[]) {
    emitted.push(event);
    if (event === 'update:rows') props.rows = value;
  } }))!;
  return { props, setup, emitted, dispose: () => scope.stop() };
}

test('语文和英语的左右字段固定为汉字/拼音与中文/英语', () => {
  for (const subject of ['chinese', 'english'] as const) {
    const ui = editor(subject, [{}]);
    assert.equal(ui.setup.leftField.value, subject === 'chinese' ? 'text' : 'meaning');
    assert.equal(ui.setup.rightField.value, subject === 'chinese' ? 'pinyin' : 'english');
    ui.dispose();
  }
});

test('来源编辑器首次补音后修改汉字保留读音，主动清空后仅显式按钮补空', async () => {
  const ui = editor('chinese', [{ text: '春天' }], true);
  await new Promise(resolve => setTimeout(resolve, 550));
  assert.equal(ui.props.rows[0]!.pinyin, 'chūn tiān');
  assert.ok(ui.emitted.includes('update:rows'));
  ui.setup.updateCell(0, 'text', '校园');
  ui.setup.commitCell();
  await ui.setup.completion.flush();
  assert.equal(ui.props.rows[0]!.pinyin, 'chūn tiān');
  ui.setup.updateCell(0, 'pinyin', '');
  ui.setup.commitCell();
  await ui.setup.completion.flush();
  assert.equal(ui.props.rows[0]!.pinyin, '');
  await ui.setup.completion.completeNow(true);
  assert.equal(ui.props.rows[0]!.pinyin, 'xiào yuán');
  ui.dispose();
});

test('批量粘贴保留空行对应、完整短语与另一列内容', async () => {
  const ui = editor('english', [{ meaning: '冰淇淋' }, { meaning: '保留第二行' }, { meaning: '放学后' }]);
  let prevented = false;
  ui.setup.pasteCell({ clipboardData: { getData: () => 'ice cream\r\n\r\nafter school' }, preventDefault: () => { prevented = true; } }, 0, 'english');
  await nextTick();
  assert.equal(prevented, true);
  assert.deepEqual(ui.props.rows.map(row => [row.meaning, row.english]), [['冰淇淋', 'ice cream'], ['保留第二行', ''], ['放学后', 'after school']]);
  assert.equal(ui.emitted.at(-1), 'commit');
  ui.dispose();
});

test('批量粘贴扩展整组空行而不把中文移配到其它英文', () => {
  const ui = editor('english', [{ meaning: '原中文', english: 'old' }]);
  ui.setup.applyLines(0, 'english', 'Monday\nTuesday\nWednesday');
  assert.deepEqual(ui.props.rows.map(row => [row.meaning ?? '', row.english]), [['原中文', 'Monday'], ['', 'Tuesday'], ['', 'Wednesday']]);
  ui.setup.removeRow(1);
  assert.deepEqual(ui.props.rows.map(row => [row.meaning ?? '', row.english]), [['原中文', 'Monday'], ['', 'Wednesday']]);
  ui.dispose();
});

test('逐键输入保留短语末尾空格与无效拼音供继续编辑', () => {
  const en = editor('english', [{ english: 'ice', meaning: '冰' }]);
  en.setup.updateCell(0, 'english', 'ice ');
  assert.equal(en.props.rows[0]!.english, 'ice ');
  const zh = editor('chinese', [{ text: '春天', pinyin: 'chūn tiān' }]);
  zh.setup.updateCell(0, 'pinyin', 'chūn ');
  assert.equal(zh.props.rows[0]!.pinyin, 'chūn ');
  assert.match(zh.setup.rowIssue(zh.props.rows[0]), /数量/);
  zh.setup.updateCell(0, 'text', '夏天');
  assert.equal(zh.props.rows[0]!.pinyin, 'chūn ');
  en.dispose(); zh.dispose();
});

test('只有拼音时校验格式，补入汉字后才要求音节数量匹配', () => {
  const ui = editor('chinese', [{ pinyin: 'chūn tiān' }]);
  assert.equal(ui.setup.rowIssue(ui.props.rows[0]), '');
  ui.setup.updateCell(0, 'pinyin', 'hello');
  assert.match(ui.setup.rowIssue(ui.props.rows[0]), /有效拼音/);
  ui.setup.updateCell(0, 'pinyin', 'chūn tiān');
  ui.setup.updateCell(0, 'text', '春');
  assert.match(ui.setup.rowIssue(ui.props.rows[0]), /数量/);
  ui.setup.updateCell(0, 'text', '春天');
  assert.equal(ui.setup.rowIssue(ui.props.rows[0]), '');
  ui.dispose();
});

test('删除最后一组后仍可输入，超限粘贴原子拒绝不截断', () => {
  const ui = editor('english', [{ english: 'after school', meaning: '放学后' }]);
  ui.setup.applyLines(0, 'english', Array.from({ length: 2001 }, () => 'word').join('\n'));
  assert.equal(ui.props.rows.length, 1);
  assert.equal(ui.props.rows[0]!.english, 'after school');
  assert.match(ui.setup.pasteIssue.value, /2000/);
  ui.setup.removeRow(0);
  assert.deepEqual(ui.props.rows, [{}]);
  ui.dispose();
});

/** 页面测试使用真实会话逻辑，仅替换浏览器存储、网络和 PDF 边界。 */
function page(subject: 'chinese' | 'english', translate?: WorksheetSessionDependencies['translate']) {
  const scope = effectScope();
  const activated: Array<() => void> = [];
  const deactivated: Array<() => void> = [];
  const setup = loadSetup('../src/views/WorksheetV2View.vue', {
    '../composables/useWorksheetV2Session': {
      useWorksheetV2Session: (value: 'chinese' | 'english') => useWorksheetV2Session(value, {
        storage: { getItem: () => null, setItem() {}, removeItem() {} },
        translate: translate ?? (async () => []),
        release() {}, recordSuccess: async () => {}, recordPrint: async () => {}
      })
    },
    vue: { ...createRequire(import.meta.url)('vue'),
      onActivated: (callback: () => void) => activated.push(callback),
      onDeactivated: (callback: () => void) => deactivated.push(callback) }
  });
  const ui = scope.run(() => setup({ subject }, { expose() {} }))!;
  return { ui, activate: () => activated.forEach(callback => callback()), deactivate: () => deactivated.forEach(callback => callback()),
    dispose: () => { ui.flow.dispose(); scope.stop(); } };
}

test('KeepAlive 离开来源页立即取消两种来源的任务，返回后重新开放当前来源补全', () => {
  const { ui, activate, deactivate, dispose } = page('english');
  let cancelledCustom = 0;
  let cancelledImages = 0;
  ui.customEditor.value = { cancelCompletion: () => { cancelledCustom++; } };
  ui.imageInput.value = { cancel: () => { cancelledImages++; } };
  ui.chooseSource('custom');
  assert.equal(ui.customCompletionActive.value, true);
  deactivate();
  assert.equal(ui.customCompletionActive.value, false);
  assert.equal(ui.imageSourceActive.value, false);
  assert.equal(cancelledCustom, 1);
  assert.equal(cancelledImages, 1);
  activate();
  assert.equal(ui.customCompletionActive.value, true);
  ui.chooseSource('image');
  assert.equal(ui.customCompletionActive.value, false);
  assert.equal(ui.imageSourceActive.value, true);
  deactivate();
  assert.equal(ui.imageSourceActive.value, false);
  activate();
  assert.equal(ui.imageSourceActive.value, true);
  dispose();
});

test('KeepAlive 停用后迟到来源译文不回填，返回也不自动重复首次请求', async () => {
  const { ui, activate, deactivate, dispose } = page('english');
  const completionScope = effectScope();
  let calls = 0;
  let resolveOld!: (value: Array<{ id: string; sourceText: string; englishText: string; meaning: string; status: 'translated' }>) => void;
  let oldItems: ReadonlyArray<{ id: string; text: string }> = [];
  let oldSignal!: AbortSignal;
  ui.chooseSource('custom');
  ui.customPairs.value = [{ english: 'apple' }];
  const completion = completionScope.run(() => useWorksheetPairCompletion('english', ui.customPairs, {
    enabled: () => ui.customCompletionActive.value,
    debounceMs: 5,
    translate: async (items, signal) => {
      calls++;
      if (calls === 1) {
        oldItems = items; oldSignal = signal;
        return new Promise(resolve => { resolveOld = resolve; });
      }
      return items.map(item => ({ id: item.id, sourceText: item.text, englishText: item.text, meaning: '苹果', status: 'translated' }));
    }
  }))!;
  ui.customEditor.value = { cancelCompletion: completion.cancel };
  try {
    const pending = completion.completeNow();
    await new Promise(resolve => setTimeout(resolve, 0));
    deactivate();
    assert.equal(oldSignal.aborted, true);
    resolveOld(oldItems.map(item => ({ id: item.id, sourceText: item.text, englishText: item.text, meaning: '旧结果', status: 'translated' })));
    await pending;
    assert.equal(ui.customPairs.value[0].meaning, undefined);
    assert.equal(calls, 1);
    activate();
    await new Promise(resolve => setTimeout(resolve, 30));
    assert.equal(calls, 1);
    assert.equal(ui.customPairs.value[0].meaning, undefined);
    await completion.completeNow(true);
    assert.equal(calls, 2);
    assert.equal(ui.customPairs.value[0].meaning, '苹果');
  } finally { completionScope.stop(); dispose(); }
});

test('来源首次请求在途点下一步会等待同一结果，确认页不再次翻译', async () => {
  let confirmationCalls = 0;
  const { ui, dispose } = page('english', async () => { confirmationCalls++; return []; });
  const scope = effectScope();
  let resolve!: (value: Array<{ id: string; sourceText: string; englishText: string; meaning: string; status: 'translated' }>) => void;
  let request: ReadonlyArray<{ id: string; text: string }> = [];
  let sourceCalls = 0;
  ui.chooseSource('custom');
  ui.customPairs.value = [{ english: 'apple' }];
  const completion = scope.run(() => useWorksheetPairCompletion('english', ui.customPairs, {
    enabled: () => ui.customCompletionActive.value, debounceMs: 60000,
    translate: async items => { sourceCalls++; request = items; return new Promise(done => { resolve = done; }); }
  }))!;
  ui.customEditor.value = { cancelCompletion: completion.cancel, flushCompletion: completion.flush };
  try {
    const next = ui.applyCustomPairs();
    await new Promise(done => setTimeout(done, 0));
    assert.equal(ui.stage.value, 'source');
    assert.equal(ui.submittingCustom.value, true);
    assert.equal(sourceCalls, 1);
    resolve(request.map(item => ({ id: item.id, sourceText: item.text, englishText: item.text, meaning: '苹果', status: 'translated' })));
    await next;
    await ui.prepareAutomaticFields();
    assert.equal(ui.stage.value, 'confirm');
    assert.equal(ui.confirmationPairs.value[0].meaning, '苹果');
    assert.equal(ui.confirmationPairs.value[0].automaticInitialized, true);
    assert.equal(confirmationCalls, 0);
    ui.returnToSource();
    ui.customPairs.value[0].meaning = '';
    await ui.applyCustomPairs();
    await ui.prepareAutomaticFields();
    assert.equal(ui.confirmationPairs.value[0].meaning, '');
    assert.equal(sourceCalls, 1);
    assert.equal(confirmationCalls, 0);
  } finally { scope.stop(); dispose(); }
});

test('等待首次结果时继续编辑或切换来源会取消跳页并保护修改', async () => {
  for (const action of ['edit', 'source'] as const) {
    const { ui, dispose } = page('english');
    const scope = effectScope();
    let resolve!: (value: Array<{ id: string; sourceText: string; englishText: string; meaning: string; status: 'translated' }>) => void;
    let request: ReadonlyArray<{ id: string; text: string }> = [];
    ui.chooseSource('custom');
    ui.customPairs.value = [{ english: 'apple' }];
    const completion = scope.run(() => useWorksheetPairCompletion('english', ui.customPairs, {
      enabled: () => ui.customCompletionActive.value, debounceMs: 60000,
      translate: async items => { request = items; return new Promise(done => { resolve = done; }); }
    }))!;
    ui.customEditor.value = { cancelCompletion: completion.cancel, flushCompletion: completion.flush };
    try {
      const next = ui.applyCustomPairs();
      await new Promise(done => setTimeout(done, 0));
      if (action === 'edit') ui.customPairs.value[0].english = 'banana';
      else ui.chooseSource('image');
      await next;
      resolve(request.map(item => ({ id: item.id, sourceText: item.text, englishText: item.text, meaning: '苹果', status: 'translated' })));
      await new Promise(done => setTimeout(done, 0));
      assert.equal(ui.stage.value, 'source');
      assert.equal(ui.customPairs.value[0].meaning, undefined);
      assert.equal(ui.customPairs.value[0].english, action === 'edit' ? 'banana' : 'apple');
      assert.equal(ui.submittingCustom.value, false);
    } finally { scope.stop(); dispose(); }
  }
});

test('自定义导入和确认页使用同组内容，返回再前进不重复添加或丢失修改', async () => {
  const { ui, dispose } = page('english');
  ui.chooseSource('custom');
  ui.customPairs.value = [{ meaning: '冰淇淋', english: 'ice cream' }, { meaning: '放学后', english: 'after school' }];
  if (ui.source.value !== 'custom') ui.chooseSource('custom');
  await ui.applyCustomPairs();
  await ui.prepareAutomaticFields();
  assert.equal(ui.stage.value, 'confirm');
  assert.equal(ui.confirmationPairs.value.length, 2);
  const rows = ui.confirmationPairs.value.map((row: EditableWorksheetPair) => ({ ...row }));
  rows[0].meaning = '雪糕'; rows[0].meaningState = 'confirmed';
  ui.updateConfirmationPairs(rows);
  ui.returnToSource();
  assert.equal(ui.customPairs.value[0].meaning, '雪糕');
  if (ui.source.value !== 'custom') ui.chooseSource('custom');
  await ui.applyCustomPairs();
  assert.equal(ui.session.value.document.tokens.length, 2);
  assert.equal(ui.confirmationPairs.value[0].meaning, '雪糕');
  dispose();
});

test('确认页新增空行与删除同形词不会造成配对串行', async () => {
  const { ui, dispose } = page('english');
  ui.chooseSource('custom');
  ui.customPairs.value = [{ meaning: '银行', english: 'bank' }, { meaning: '河岸', english: 'bank' }];
  if (ui.source.value !== 'custom') ui.chooseSource('custom');
  await ui.applyCustomPairs();
  await ui.prepareAutomaticFields();
  const retained = { ...ui.confirmationPairs.value[1] };
  ui.updateConfirmationPairs([{}, retained, { meaning: '放学后', english: 'after school' }]);
  assert.equal(ui.commitConfirmation(false), true);
  assert.equal(ui.confirmationPairs.value.length, 3);
  assert.equal(ui.confirmationPairs.value[0].english, undefined);
  assert.deepEqual(ui.session.value.document.tokens.map((token: { meaning: string }) => token.meaning), ['河岸', '放学后']);
  dispose();
});

test('无效拼音保留输入并阻止预览，返回来源仍可直接修正', async () => {
  const { ui, dispose } = page('chinese');
  ui.chooseSource('custom');
  ui.customPairs.value = [{ text: '春天', pinyin: 'chūn tiān', pinyinState: 'confirmed' }];
  if (ui.source.value !== 'custom') ui.chooseSource('custom');
  await ui.applyCustomPairs();
  await ui.prepareAutomaticFields();
  ui.updateConfirmationPairs([{ ...ui.confirmationPairs.value[0], pinyin: 'chūn' }]);
  assert.equal(ui.commitConfirmation(false), false);
  assert.match(ui.confirmationIssue.value, /音节/);
  ui.returnToSource();
  assert.equal(ui.customPairs.value[0].pinyin, 'chūn');
  ui.customPairs.value[0].pinyin = 'chūn tiān';
  if (ui.source.value !== 'custom') ui.chooseSource('custom');
  await ui.applyCustomPairs();
  assert.equal(ui.stage.value, 'confirm');
  assert.equal(ui.confirmationPairs.value[0].pinyin, 'chūn tiān');
  assert.equal(ui.confirmationIssue.value, '');
  dispose();
});


test('翻译回包不能覆盖正在输入的配对字段，失焦提交后仍保留人工内容', async () => {
  let complete: (() => void) | undefined;
  const { ui, dispose } = page('english', async items => new Promise(resolve => {
    complete = () => resolve(items.map(item => ({ id: item.id, sourceText: item.text, englishText: 'ice cream', meaning: '冰淇淋', status: 'translated' })));
  }));
  ui.chooseSource('custom');
  ui.customPairs.value = [{ english: 'ice cream' }];
  if (ui.source.value !== 'custom') ui.chooseSource('custom');
  await ui.applyCustomPairs();
  assert.equal(ui.automaticPreparing.value, true);
  const rows = ui.confirmationPairs.value.map((row: EditableWorksheetPair) => ({ ...row, meaning: '雪糕 ', meaningState: 'confirmed' }));
  ui.updateConfirmationPairs(rows);
  complete!();
  await ui.prepareAutomaticFields();
  assert.equal(ui.confirmationPairs.value[0].meaning, '雪糕 ');
  assert.equal(ui.commitConfirmation(false), true);
  assert.equal(ui.session.value.document.tokens[0].meaning, '雪糕');
  dispose();
});

test('确认页首次翻译在途改原文立即取消，未失焦也不能填旧释义且后续不自动重发', async () => {
  let complete!: () => void;
  let calls = 0;
  let requestSignal!: AbortSignal;
  const { ui, dispose } = page('english', async (items, signal) => {
    calls++; requestSignal = signal;
    return new Promise(resolve => {
      complete = () => resolve(items.map(item => ({ id: item.id, sourceText: item.text, englishText: 'apple', meaning: '苹果', status: 'translated' })));
    });
  });
  try {
    ui.chooseSource('custom');
    ui.customPairs.value = [{ english: 'apple' }];
    await ui.applyCustomPairs();
    assert.equal(calls, 1);
    ui.updateConfirmationPairs(ui.confirmationPairs.value.map((row: EditableWorksheetPair) => ({ ...row, english: 'banana', englishState: 'confirmed' })));
    assert.equal(requestSignal.aborted, true);
    complete();
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(ui.confirmationPairs.value[0].english, 'banana');
    assert.equal(ui.confirmationPairs.value[0].meaning, '');
    ui.commitConfirmation();
    ui.chooseMode('english-dictation');
    await ui.prepareAutomaticFields();
    assert.equal(calls, 1);
    assert.equal(ui.session.value.document.tokens[0].meaning, null);
  } finally { dispose(); }
});

test('超过30行分页按全局行号编辑，新增和删除保持每组配对', async () => {
  const ui = editor('english', Array.from({ length: 30 }, (_, index) => ({ meaning: `中文${index}`, english: `word ${index}` })));
  ui.setup.addRow();
  await nextTick();
  assert.equal(ui.setup.page.value, 2);
  assert.equal(ui.setup.shownRows.value[0].index, 30);
  ui.setup.updateCell(30, 'english', 'after school');
  ui.setup.updateCell(30, 'meaning', '放学后');
  assert.deepEqual([ui.props.rows[30]!.meaning, ui.props.rows[30]!.english], ['放学后', 'after school']);
  ui.setup.removeRow(30);
  await nextTick();
  assert.equal(ui.setup.page.value, 1);
  assert.equal(ui.props.rows[29]!.english, 'word 29');
  ui.dispose();
});

test('自定义两种字帖末行任意一栏有内容就出现一个空行，编辑空行才计入数据', () => {
  for (const subject of ['chinese', 'english'] as const) {
    const ui = editor(subject, [{}], false, { paginated: false, autoAppend: true });
    const [left, right] = subject === 'chinese' ? ['text', 'pinyin'] as const : ['meaning', 'english'] as const;
    const [source, answer] = subject === 'chinese' ? ['春天', 'chūn tiān'] : ['冰淇淋', 'ice cream'];
    ui.setup.updateCell(0, left, source);
    assert.equal(ui.setup.shownRows.value.length, 2);
    assert.equal(ui.props.rows[0]!.automaticInitialized, undefined, '备用行不能将单边内容标为已经自动补全');
    ui.setup.updateCell(0, right, answer);
    assert.equal(ui.setup.shownRows.value.length, 2);
    assert.equal(ui.props.rows.length, 1);
    assert.equal(ui.setup.usedCount.value, 1);
    ui.setup.addRow();
    assert.equal(ui.setup.shownRows.value.length, 2, '手动添加复用已经展示的空行');
    ui.setup.updateCell(1, left, source);
    assert.equal(ui.props.rows.length, 2);
    assert.equal(ui.setup.shownRows.value.length, 3);
    assert.equal(ui.props.rows[0]![right], answer);
    ui.setup.updateCell(1, right, answer);
    assert.equal(ui.setup.shownRows.value.length, 3);
    ui.setup.removeRow(0);
    assert.equal(ui.setup.shownRows.value.length, 2);
    assert.equal(ui.props.rows[0]![right], answer);
    ui.setup.removeRow(0);
    assert.equal(ui.setup.shownRows.value.length, 1);
    assert.equal(ui.setup.usedCount.value, 0);
    ui.dispose();
  }
});

test('汉字、拼音、中文或英文单独使用末行都会预留下一行，空白内容不会追加', () => {
  for (const [subject, field, value] of [
    ['chinese', 'text', '春天'], ['chinese', 'pinyin', 'chūn tiān'],
    ['english', 'meaning', '苹果'], ['english', 'english', 'apple']
  ] as const) {
    const ui = editor(subject, [{}], false, { paginated: false, autoAppend: true });
    ui.setup.updateCell(0, field, ' \t ');
    assert.equal(ui.setup.shownRows.value.length, 1);
    ui.setup.updateCell(0, field, value);
    assert.equal(ui.setup.shownRows.value.length, 2);
    assert.equal(ui.props.rows.length, 1);
    assert.equal(ui.setup.usedCount.value, 1);
    assert.equal(ui.setup.rowIssue(ui.props.rows[0]), '');
    ui.setup.updateCell(0, field, '');
    assert.equal(ui.setup.shownRows.value.length, 1);
    ui.dispose();
  }
});

test('自动补音完成后追加展示行不取消任务，也不重新补全用户清空的拼音', async () => {
  const ui = editor('chinese', [{ text: '春天' }], true, { paginated: false, autoAppend: true });
  try {
    assert.equal(await ui.setup.completion.flush(), true);
    assert.equal(ui.setup.completion.busy.value, false);
    assert.equal(ui.props.rows[0]!.pinyin, 'chūn tiān');
    assert.equal(ui.props.rows.length, 1);
    assert.equal(ui.setup.shownRows.value.length, 2);
    ui.setup.updateCell(0, 'pinyin', '');
    await ui.setup.completion.flush();
    assert.equal(ui.props.rows[0]!.pinyin, '');
    assert.equal(ui.props.rows[0]!.automaticInitialized, true);
  } finally { ui.dispose(); }
});

test('自定义粘贴超过30行完整展示，空行与短语仍保持两列对应', () => {
  const rows = Array.from({ length: 32 }, (_, index) => ({ meaning: `中文${index}` }));
  const ui = editor('english', rows, false, { paginated: false, autoAppend: true });
  ui.setup.applyLines(0, 'english', rows.map((_, index) => index === 5 ? '' : `word ${index}`).join('\n'));
  assert.equal(ui.setup.pageCount.value, 1);
  assert.equal(ui.setup.shownRows.value.length, 33);
  assert.equal(ui.props.rows[5]!.meaning, '中文5');
  assert.equal(ui.props.rows[5]!.english, '');
  ui.setup.updateCell(31, 'english', 'after school');
  assert.equal(ui.props.rows[31]!.meaning, '中文31');
  assert.equal(ui.props.rows[31]!.english, 'after school');
  assert.equal(ui.setup.shownRows.value.at(-1).index, 32);
  ui.dispose();
});

test('自定义自动新增不重复已有空行，达到2000行上限不再追加', () => {
  const ui = editor('english', [{ meaning: '苹果', english: 'apple' }, {}], false, { paginated: false, autoAppend: true });
  assert.equal(ui.setup.shownRows.value.length, 2);
  ui.props.rows = Array.from({ length: 1999 }, () => ({ meaning: '苹果' }));
  assert.equal(ui.setup.shownRows.value.length, 2000);
  ui.setup.updateCell(1999, 'english', 'apple');
  assert.equal(ui.setup.shownRows.value.length, 2000);
  assert.equal(ui.props.rows.length, 2000);
  ui.setup.addRow();
  assert.equal(ui.props.rows.length, 2000);
  ui.dispose();
});


test('确认页失焦保存提示位于导航操作之后，新增提示不推移点击中的按钮', () => {
  const filename = fileURLToPath(new URL('../src/views/WorksheetV2View.vue', import.meta.url));
  const { descriptor } = parse(readFileSync(filename, 'utf8'), { filename });
  const root = descriptor.template!.ast!;
  const hasClass = (node: any, value: string): boolean => node.type === 1 && node.props.some((prop: any) =>
    prop.type === 6 && prop.name === 'class' && prop.value?.content.split(' ').includes(value));
  const container = root.children.find((node: any) => hasClass(node, 'worksheet-v2')) as any;
  const confirm = container.children.find((node: any) => hasClass(node, 'confirm-step'));
  const actionsIndex = confirm.children.findIndex((node: any) => hasClass(node, 'step-actions'));
  const noticeIndex = confirm.children.findIndex((node: any) => node.type === 1 && node.props.some((prop: any) =>
    prop.type === 7 && prop.name === 'if' && prop.exp?.content === 'notice'));
  assert.ok(actionsIndex >= 0, '确认页需要有步骤操作栏');
  assert.ok(noticeIndex > actionsIndex, '失焦时动态新增的保存提示必须出现在步骤操作栏之后');
});
