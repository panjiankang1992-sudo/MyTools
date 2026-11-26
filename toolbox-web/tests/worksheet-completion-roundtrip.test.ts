import assert from 'node:assert/strict';
import test from 'node:test';
import { effectScope, ref, type Ref } from 'vue';
import { useWorksheetPairCompletion } from '../src/composables/useWorksheetPairCompletion';
import { useWorksheetV2Session } from '../src/composables/useWorksheetV2Session';
import {
  clearStalePairCompletions, editablePairsForSession, importEditablePairsIntoSession,
  type EditableWorksheetPair
} from '../src/domain/worksheet-editable-pairs';
import { createStoredDraft, restoreDraftSession } from '../src/domain/worksheet-draft-session';
import { createSession } from '../src/domain/worksheet-session';
import type { TranslationResult } from '../src/services/public-api';

/** 使用可控翻译边界驱动真实补全器、会话导入、确认和返编。 */
function fixture(subject: 'chinese' | 'english', input: EditableWorksheetPair[]) {
  const calls: string[] = [];
  const translate = async (items: ReadonlyArray<{ id: string; text: string }>): Promise<TranslationResult[]> => items.map(item => {
    calls.push(item.text);
    const banana = item.text === 'banana' || item.text === '香蕉';
    return { id: item.id, sourceText: item.text, englishText: banana ? 'banana' : 'apple',
      meaning: banana ? '香蕉' : '苹果', status: 'translated' };
  });
  const scope = effectScope();
  const rows = ref(input) as Ref<EditableWorksheetPair[]>;
  const completion = scope.run(() => useWorksheetPairCompletion(subject, rows, { translate, debounceMs: 60000 }))!;
  const flow = useWorksheetV2Session(subject, {
    translate, storage: { getItem: () => null, setItem: () => undefined, removeItem: () => undefined }
  });
  return { rows, completion, flow, calls, dispose: () => { scope.stop(); flow.dispose(); } };
}

test('英文初次补全经过确认返回后不再跟随改词，只有显式补全可填手动清空的栏', async () => {
  const current = fixture('english', [{ english: 'apple', englishState: 'confirmed' }]);
  try {
    await current.completion.completeNow();
    current.flow.importEditablePairs(current.rows.value);
    current.flow.prepareConfirmation();
    await current.flow.prepareAutomaticFields();
    current.flow.goBackToEdit();
    current.rows.value = current.flow.editablePairs.value.map(row => clearStalePairCompletions({
      ...row, english: 'banana', englishState: 'confirmed'
    }));
    assert.equal(current.rows.value[0]!.meaning, '苹果');
    await current.completion.completeNow();
    assert.deepEqual(current.calls, ['apple']);
    current.rows.value = current.rows.value.map(row => ({ ...row, meaning: '', meaningState: 'missing' }));
    await current.completion.completeNow();
    assert.equal(current.rows.value[0]!.meaning, '');
    await current.completion.completeNow(true);
    current.flow.importEditablePairs(current.rows.value);
    current.flow.prepareConfirmation();
    await current.flow.prepareAutomaticFields();
    assert.deepEqual(current.calls, ['apple', 'banana']);
    assert.equal(current.flow.editablePairs.value[0]!.meaning, '香蕉');
  } finally { current.dispose(); }
});

test('改写中文或汉字不联动清空配对栏，往返后显式补全才填新空栏', async () => {
  const english = fixture('english', [{ meaning: '苹果', meaningState: 'confirmed' }]);
  try {
    await english.completion.completeNow();
    english.flow.importEditablePairs(english.rows.value);
    english.flow.prepareConfirmation();
    english.flow.goBackToEdit();
    english.rows.value = english.flow.editablePairs.value.map(row => clearStalePairCompletions({
      ...row, meaning: '香蕉', meaningState: 'confirmed'
    }));
    assert.equal(english.rows.value[0]!.english, 'apple');
    await english.completion.completeNow();
    assert.equal(english.rows.value[0]!.english, 'apple');
    english.rows.value = english.rows.value.map(row => ({ ...row, english: '', englishState: 'missing' }));
    await english.completion.completeNow(true);
    assert.equal(english.rows.value[0]!.english, 'banana');
  } finally { english.dispose(); }

  const chinese = fixture('chinese', [{ text: '春天' }]);
  try {
    await chinese.completion.completeNow();
    chinese.flow.importEditablePairs(chinese.rows.value);
    chinese.flow.prepareConfirmation();
    await chinese.flow.prepareAutomaticFields();
    chinese.flow.goBackToEdit();
    chinese.rows.value = chinese.flow.editablePairs.value.map(row => clearStalePairCompletions({ ...row, text: '夏天' }));
    assert.equal(chinese.rows.value[0]!.pinyin, 'chūn tiān');
    await chinese.completion.completeNow();
    assert.equal(chinese.rows.value[0]!.pinyin, 'chūn tiān');
    chinese.rows.value = chinese.rows.value.map(row => ({ ...row, pinyin: '', pinyinState: 'missing' }));
    await chinese.completion.completeNow(true);
    assert.equal(chinese.rows.value[0]!.pinyin, 'xià tiān');
  } finally { chinese.dispose(); }
});

test('确认页处理后返回来源保留初始化标记及显示内容，不按旧来源自动清除', async () => {
  const current = fixture('english', []);
  try {
    current.flow.importEditablePairs([{ english: 'apple' }]);
    current.flow.prepareConfirmation();
    await current.flow.prepareAutomaticFields();
    const row = current.flow.editablePairs.value[0]!;
    assert.deepEqual(row.automaticFields?.meaning, { source: 'english', sourceText: 'apple', value: '苹果' });
    const updated = clearStalePairCompletions({ ...row, english: 'banana', englishState: 'confirmed' });
    assert.equal(updated.meaning, '苹果');
    assert.equal(updated.automaticInitialized, true);
    assert.equal(current.flow.editablePairs.value[0]!.meaning, '苹果', '导出的缓冲没有就地修改会话');
  } finally { current.dispose(); }
  const chinese = fixture('chinese', []);
  try {
    chinese.flow.importEditablePairs([{ text: '春天' }]);
    chinese.flow.prepareConfirmation();
    await chinese.flow.prepareAutomaticFields();
    const row = chinese.flow.editablePairs.value[0]!;
    assert.equal(clearStalePairCompletions({ ...row, text: '夏天' }).pinyin, 'chūn tiān');
    assert.equal(row.automaticInitialized, true);
  } finally { chinese.dispose(); }
});

test('OCR建议和教材已有字段不猜测成翻译来源，人工确认的相同字面值也不会再自动清除', async () => {
  for (const state of ['suggested', 'confirmed'] as const) {
    const imported = importEditablePairsIntoSession(createSession('english'), [
      { english: 'apple', meaning: '苹果', englishState: state, meaningState: state }
    ], 'replace', state === 'suggested' ? 'image' : 'custom').session;
    const row = editablePairsForSession(imported)[0]!;
    assert.equal(row.automaticFields, undefined);
    const updated = importEditablePairsIntoSession(imported, [{ ...row, english: 'banana', englishState: 'confirmed' }]).session;
    assert.equal(editablePairsForSession(updated)[0]!.meaning, '苹果');
  }
  const current = fixture('english', [{ english: 'apple' }]);
  try {
    await current.completion.completeNow();
    current.flow.importEditablePairs(current.rows.value);
    const original = current.flow.editablePairs.value[0]!;
    current.flow.updatePair(original.tokenId!, { meaning: '苹果' });
    const manual = current.flow.editablePairs.value[0]!;
    assert.equal(manual.automaticFields?.meaning, undefined);
    assert.equal(clearStalePairCompletions({ ...manual, english: 'banana', englishState: 'confirmed' }).meaning, '苹果');
  } finally { current.dispose(); }
});

test('主动保存恢复草稿保留初始化标记，旧草稿也按已初始化恢复', async () => {
  const current = fixture('english', [{ english: 'apple' }]);
  try {
    await current.completion.completeNow();
    current.flow.importEditablePairs(current.rows.value);
    const draft = createStoredDraft(current.flow.session.value);
    const restored = restoreDraftSession('english', JSON.parse(JSON.stringify(draft)));
    const row = editablePairsForSession(restored)[0]!;
    assert.equal(row.automaticInitialized, true);
    assert.equal(clearStalePairCompletions({ ...row, english: 'banana', englishState: 'confirmed' }).meaning, '苹果');
    delete draft.corrections[0]!.automaticFields;
    delete draft.corrections[0]!.automaticInitialized;
    const legacy = editablePairsForSession(restoreDraftSession('english', draft))[0]!;
    assert.equal(legacy.automaticInitialized, true);
    assert.equal(clearStalePairCompletions({ ...legacy, english: 'banana', englishState: 'confirmed' }).meaning, '苹果');
  } finally { current.dispose(); }
});
