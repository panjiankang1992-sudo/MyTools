import assert from 'node:assert/strict';
import test from 'node:test';
import { computed, effectScope, nextTick, ref, type Ref } from 'vue';
import { clearStalePairCompletions, useWorksheetPairCompletion, type WorksheetPairCompletionOptions } from '../src/composables/useWorksheetPairCompletion';
import type { EditableWorksheetPair } from '../src/domain/worksheet-editable-pairs';
import type { TranslationResult } from '../src/services/public-api';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

function setup(subject: 'chinese' | 'english', initial: EditableWorksheetPair[], options: WorksheetPairCompletionOptions = {}) {
  const scope = effectScope();
  const rows = ref(initial) as Ref<EditableWorksheetPair[]>;
  const completion = scope.run(() => useWorksheetPairCompletion(subject, rows, { debounceMs: 60000, ...(subject === 'chinese' ? { translate: async () => [] } : {}), ...options }))!;
  return { rows, completion, stop: () => scope.stop() };
}

function translated(id: string, sourceText: string, englishText: string, meaning: string): TranslationResult {
  return { id, sourceText, englishText, meaning, status: 'translated' };
}

test('汉字来源直接补音，手工读音和已有识别读音均保留', async () => {
  const fixture = setup('chinese', [{ text: '春天' }, { text: '长大', pinyin: 'cháng dà', pinyinState: 'confirmed' },
    { text: '星期', pinyin: 'xīng qī', pinyinState: 'suggested' }, {}]);
  try {
    await fixture.completion.completeNow();
    assert.equal(fixture.rows.value[0]!.pinyin, 'chūn tiān');
    assert.equal(fixture.rows.value[0]!.pinyinState, 'suggested');
    assert.equal(fixture.rows.value[1]!.pinyin, 'cháng dà');
    assert.equal(fixture.rows.value[2]!.pinyin, 'xīng qī');
    assert.equal(fixture.rows.value[3]!.pinyin, undefined);
  } finally { fixture.stop(); }
});

test('英文和中文双向补齐，只发送单侧缺失项，保留短语内部空格和现有中文', async () => {
  const requested: string[] = [];
  const fixture = setup('english', [{ meaning: '冰淇淋' }, { english: 'after school' },
    { english: 'dog', meaning: '狗（课本释义）' }, {}], { translate: async items => {
    requested.push(...items.map(item => item.text));
    return items.map(item => item.text === '冰淇淋'
      ? translated(item.id, item.text, 'ice cream', '冰激凌')
      : translated(item.id, item.text, 'after school', '放学后'));
  } });
  try {
    await fixture.completion.completeNow();
    assert.deepEqual(requested, ['冰淇淋', 'after school']);
    assert.equal(fixture.rows.value[0]!.english, 'ice cream');
    assert.equal(fixture.rows.value[0]!.meaning, '冰淇淋');
    assert.equal(fixture.rows.value[1]!.meaning, '放学后');
    assert.equal(fixture.rows.value[2]!.meaning, '狗（课本释义）');
    assert.equal(fixture.completion.error.value, null);
  } finally { fixture.stop(); }
});

test('手工填写目标后不接收迟到的翻译，输入变化也会取消旧请求', async () => {
  const wait = deferred<TranslationResult[]>();
  let requested: ReadonlyArray<{ id: string; text: string }> = [];
  let signal!: AbortSignal;
  const fixture = setup('english', [{ english: 'cat' }], { translate: async (items, requestSignal) => {
    requested = items; signal = requestSignal; return wait.promise;
  } });
  try {
    const pending = fixture.completion.completeNow();
    await new Promise(resolve => setTimeout(resolve, 0));
    fixture.rows.value = fixture.rows.value.map(row => ({ ...row, meaning: '我写的猫', meaningState: 'confirmed' }));
    assert.equal(signal.aborted, true);
    wait.resolve([translated(requested[0]!.id, 'cat', 'cat', '猫')]);
    await pending;
    assert.equal(fixture.rows.value[0]!.meaning, '我写的猫');
    assert.equal(fixture.completion.busy.value, false);
  } finally { fixture.stop(); }
});

test('删除同形词行后不会把旧结果填到下一行，切换来源也取消回填', async () => {
  const enabled = ref(true);
  const wait = deferred<TranslationResult[]>();
  let requested: ReadonlyArray<{ id: string; text: string }> = [];
  const fixture = setup('english', [{ english: 'bank' }, { english: 'bank' }], {
    enabled, translate: async items => { requested = items; return wait.promise; }
  });
  try {
    const pending = fixture.completion.completeNow();
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.notEqual(requested[0]!.id, requested[1]!.id);
    fixture.rows.value = fixture.rows.value.slice(1);
    enabled.value = false;
    wait.resolve(requested.map((item, index) => translated(item.id, item.text, 'bank', index === 0 ? '银行' : '河岸')));
    await pending;
    assert.equal(fixture.rows.value.length, 1);
    assert.equal(fixture.rows.value[0]!.meaning, undefined);
  } finally { fixture.stop(); }
});

test('部分成功只保留成功值，失败项不自动重试，显式重试仅补缺项', async () => {
  const calls: Array<ReadonlyArray<{ id: string; text: string }>> = [];
  const fixture = setup('english', [{ english: 'cat' }, { english: 'dog' }], { translate: async items => {
    calls.push(items);
    return items.filter(item => item.text === 'cat').map(item => translated(item.id, item.text, item.text, '猫'));
  } });
  try {
    fixture.rows.value = fixture.rows.value.map(row => ({ ...row }));
    await fixture.completion.completeNow();
    assert.deepEqual(calls.map(items => items.map(item => item.text)), [['cat', 'dog']]);
    await fixture.completion.completeNow();
    assert.equal(calls.length, 1);
    await fixture.completion.completeNow(true);
    assert.deepEqual(calls.map(items => items.map(item => item.text)), [['cat', 'dog'], ['dog']]);
    assert.equal(fixture.rows.value[0]!.meaning, '猫');
    assert.equal(fixture.rows.value[1]!.meaning, undefined);
    assert.match(fixture.completion.error.value ?? '', /1/u);
    assert.equal(fixture.completion.busy.value, false);
  } finally { fixture.stop(); }
});

test('网络故障仅执行一次，明确重试后才重新请求', async () => {
  let calls = 0;
  const fixture = setup('english', [{ meaning: '星期一' }], { translate: async items => {
    calls++;
    if (calls === 1) throw new Error('offline');
    return items.map(item => translated(item.id, item.text, 'Monday', '星期一'));
  } });
  try {
    await fixture.completion.completeNow();
    assert.equal(calls, 1);
    await fixture.completion.completeNow();
    assert.equal(calls, 1);
    await fixture.completion.completeNow(true);
    assert.equal(calls, 2);
    assert.equal(fixture.rows.value[0]!.english, 'Monday');
    assert.equal(fixture.completion.error.value, null);
  } finally { fixture.stop(); }
  calls = 0;
  const failed = setup('english', [{ english: 'cat' }], { translate: async () => { calls++; throw new Error('offline'); } });
  try {
    await failed.completion.completeNow();
    await nextTick();
    assert.equal(calls, 1);
    assert.match(failed.completion.error.value ?? '', /1/u);
  } finally { failed.stop(); }
});

test('原文不匹配或缺目标字段的回包不能伪装成成功', async () => {
  const fixture = setup('english', [{ meaning: '星期一' }], { translate: async items =>
    items.map(item => translated(item.id, '星期二', 'Tuesday', '星期二')) });
  try {
    await fixture.completion.completeNow();
    assert.equal(fixture.rows.value[0]!.english, undefined);
    assert.match(fixture.completion.error.value ?? '', /1/u);
  } finally { fixture.stop(); }
});

test('来源编辑的 writable computed 延迟发回 props 后连续批次保留已完成值', async () => {
  const scope = effectScope();
  const parentRows = ref<EditableWorksheetPair[]>(Array.from({ length: 22 }, (_, index) => ({ english: `word${index}` })));
  const editorRows = computed({ get: () => parentRows.value, set: value => { void nextTick(() => { parentRows.value = value; }); } });
  let calls = 0;
  const completion = scope.run(() => useWorksheetPairCompletion(() => 'english', editorRows, {
    debounceMs: 60000, enabled: () => true, translate: async items => {
      calls++; return items.map(item => translated(item.id, item.text, item.text, `释义 ${item.text}`));
    }
  }))!;
  try {
    await completion.completeNow();
    await nextTick();
    assert.equal(calls, 2);
    assert.equal(parentRows.value.filter(row => row.meaning).length, 22);
    assert.equal(completion.error.value, null);
  } finally { scope.stop(); }
});

test('防抖自动执行且自身补全不重复触发翻译，销毁后不能回填', async () => {
  let calls = 0;
  const fixture = setup('english', [{ english: 'cat' }], { debounceMs: 5, translate: async items => {
    calls++; return items.map(item => translated(item.id, item.text, item.text, '猫'));
  } });
  try {
    fixture.rows.value[0]!.english = 'dog';
    fixture.rows.value[0]!.english = 'cat';
    await new Promise(resolve => setTimeout(resolve, 30));
    assert.equal(calls, 1);
    assert.equal(fixture.rows.value[0]!.meaning, '猫');
  } finally { fixture.stop(); }
  const wait = deferred<TranslationResult[]>();
  let request: ReadonlyArray<{ id: string; text: string }> = [];
  const stopped = setup('english', [{ english: 'cat' }], { translate: async items => { request = items; return wait.promise; } });
  const task = stopped.completion.completeNow();
  stopped.stop();
  wait.resolve(request.map(item => translated(item.id, item.text, item.text, '猫')));
  await task;
  assert.equal(stopped.rows.value[0]!.meaning, undefined);
});

test('首次在途修改或来源停用后不自动再请求，显式重试才处理当前原文', async () => {
  const enabled = ref(true);
  const oldResult = deferred<TranslationResult[]>();
  const requests: Array<ReadonlyArray<{ id: string; text: string }>> = [];
  const fixture = setup('english', [{ english: 'cat' }], { enabled, translate: async items => {
    requests.push(items);
    return requests.length === 1 ? oldResult.promise : items.map(item => translated(item.id, item.text, item.text, '狗'));
  } });
  try {
    const first = fixture.completion.completeNow();
    await new Promise(resolve => setTimeout(resolve, 0));
    fixture.rows.value = fixture.rows.value.map(row => ({ ...row, english: 'dog' }));
    assert.equal(await first, false);
    enabled.value = false; enabled.value = true;
    await fixture.completion.completeNow();
    assert.equal(requests.length, 1);
    oldResult.resolve(requests[0]!.map(item => translated(item.id, item.text, item.text, '猫')));
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(fixture.rows.value[0]!.meaning, undefined);
    await fixture.completion.completeNow(true);
    assert.equal(requests.length, 2);
    assert.equal(fixture.rows.value[0]!.meaning, '狗');
  } finally { fixture.stop(); }
});

test('服务连续故障不会继续排队后续批次', async () => {
  let calls = 0;
  const fixture = setup('english', Array.from({ length: 45 }, (_, index) => ({ english: `word${index}` })), {
    translate: async () => { calls++; throw new Error('offline'); }
  });
  try {
    await fixture.completion.completeNow();
    assert.equal(calls, 1);
    assert.match(fixture.completion.error.value ?? '', /45/u);
  } finally { fixture.stop(); }
});

test('首次自动得到苹果后原文改写或目标清空均保留，不会重新请求', async () => {
  let calls = 0;
  const fixture = setup('english', [{ english: 'apple', englishState: 'confirmed' }], {
    translate: async items => { calls++; return items.map(item => translated(item.id, item.text, item.text, '苹果')); }
  });
  try {
    await fixture.completion.completeNow();
    fixture.rows.value = fixture.rows.value.map(row => ({ ...row, english: 'banana', englishState: 'confirmed' }));
    await fixture.completion.completeNow();
    assert.equal(fixture.rows.value[0]!.meaning, '苹果');
    fixture.rows.value = fixture.rows.value.map(row => ({ ...row, meaning: '', meaningState: 'missing' }));
    await fixture.completion.completeNow();
    assert.equal(fixture.rows.value[0]!.meaning, '');
    assert.equal(calls, 1);
  } finally { fixture.stop(); }
});

test('中文和拼音后续修改或清空均保持原样，显式补空才重新生成', async () => {
  const chinese = setup('chinese', [{ text: '春天' }]);
  try {
    await chinese.completion.completeNow();
    chinese.rows.value = chinese.rows.value.map(row => ({ ...row, text: '夏天' }));
    await chinese.completion.completeNow();
    assert.equal(chinese.rows.value[0]!.pinyin, 'chūn tiān');
    chinese.rows.value = chinese.rows.value.map(row => ({ ...row, pinyin: '', pinyinState: 'missing' }));
    await chinese.completion.completeNow();
    assert.equal(chinese.rows.value[0]!.pinyin, '');
    await chinese.completion.completeNow(true);
    assert.equal(chinese.rows.value[0]!.pinyin, 'xià tiān');
  } finally { chinese.stop(); }
});

test('完整识别配对立即初始化，新空行不会提前占用首次补全机会', async () => {
  let calls = 0;
  const fixture = setup('english', [{ english: 'apple', meaning: '苹果', meaningState: 'suggested' }, {}], {
    translate: async items => { calls++; return items.map(item => translated(item.id, item.text, item.text, '猫')); }
  });
  try {
    assert.equal(fixture.rows.value[0]!.automaticInitialized, true);
    assert.equal(fixture.rows.value[1]!.automaticInitialized, undefined);
    fixture.rows.value[0]!.meaning = '';
    await fixture.completion.completeNow();
    assert.equal(calls, 0);
    fixture.rows.value[1]!.english = 'cat';
    await fixture.completion.completeNow();
    assert.equal(calls, 1);
    assert.equal(fixture.rows.value[0]!.meaning, '');
    assert.equal(fixture.rows.value[1]!.meaning, '猫');
    fixture.rows.value.push({ english: 'cat' });
    await fixture.completion.completeNow();
    assert.equal(calls, 2);
  } finally { fixture.stop(); }
});

test('手工确认的机器译文、修改后的译文和识别教材已有值均不随原文变化清除', async () => {
  const fixture = setup('english', [{ english: 'apple' }], {
    translate: async items => items.map(item => translated(item.id, item.text, item.text, '苹果'))
  });
  try {
    await fixture.completion.completeNow();
    const generated = fixture.rows.value[0]!;
    const manuallyConfirmed = clearStalePairCompletions({ ...generated, english: 'banana', meaningState: 'confirmed' });
    assert.equal(manuallyConfirmed.meaning, '苹果');
    const manuallyChanged = clearStalePairCompletions({ ...generated, english: 'banana', meaning: '我自己填写的释义', meaningState: 'confirmed' });
    assert.equal(manuallyChanged.meaning, '我自己填写的释义');
    const fromOcr = clearStalePairCompletions({ english: 'banana', meaning: '苹果', meaningState: 'suggested' });
    assert.equal(fromOcr.meaning, '苹果');
    const fromCurriculum = clearStalePairCompletions({ text: '夏天', pinyin: 'chūn tiān', pinyinState: 'suggested' });
    assert.equal(fromCurriculum.pinyin, 'chūn tiān');
    assert.deepEqual(JSON.parse(JSON.stringify(generated)).automaticFields,
      { meaning: { source: 'english', sourceText: 'apple', value: '苹果' } });
    assert.equal(JSON.stringify(generated).includes('client:'), false);
  } finally { fixture.stop(); }
});

test('自定义与识别共用教材优先拼音，命中后后续改写不再自动补', async () => {
  let calls = 0;
  let fallback = 0;
  const fixture = setup('chinese', [{ text: '长大' }], {
    translate: async (items, _signal, subject) => {
      calls++;
      assert.equal(subject, 'chinese');
      return items.map(item => ({ id: item.id, sourceText: item.text, englishText: null, meaning: null,
        status: 'translated', pinyinSegments: [{ grapheme: '长', syllable: 'cháng' }, { grapheme: '大', syllable: 'dà' }] }));
    },
    suggest: () => { fallback++; throw new Error('should not use machine pinyin'); }
  });
  try {
    await fixture.completion.completeNow();
    assert.equal(fixture.rows.value[0]!.pinyin, 'cháng dà');
    fixture.rows.value = fixture.rows.value.map(row => ({ ...row, text: '春天', pinyin: '' }));
    await fixture.completion.completeNow();
    assert.equal(fixture.rows.value[0]!.pinyin, '');
    assert.equal(calls, 1);
    assert.equal(fallback, 0);
  } finally { fixture.stop(); }
});

test('拼音教材查询期间人工填写与取消均拒绝迟到回包，不再自动补空', async () => {
  const wait = deferred<TranslationResult[]>();
  let request: ReadonlyArray<{ id: string; text: string }> = [];
  let signal!: AbortSignal;
  const fixture = setup('chinese', [{ text: '长大' }], { translate: async (items, requestSignal) => {
    request = items; signal = requestSignal; return wait.promise;
  } });
  try {
    const task = fixture.completion.completeNow();
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(fixture.completion.busy.value, true);
    fixture.rows.value = fixture.rows.value.map(row => ({ ...row, pinyin: 'zhǎng dà', pinyinState: 'confirmed' }));
    assert.equal(signal.aborted, true);
    wait.resolve([{ id: request[0]!.id, sourceText: '长大', englishText: null, meaning: null,
      status: 'translated', pinyinSegments: [{ grapheme: '长', syllable: 'cháng' }, { grapheme: '大', syllable: 'dà' }] }]);
    await task;
    assert.equal(fixture.rows.value[0]!.pinyin, 'zhǎng dà');
    assert.equal(fixture.completion.busy.value, false);
  } finally { fixture.stop(); }
});
