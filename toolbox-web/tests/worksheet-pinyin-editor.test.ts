import assert from 'node:assert/strict';
import test from 'node:test';
import { nextTick, ref } from 'vue';
import { pinyinEditingBlocksOutput, useWorksheetPinyinEditor } from '../src/composables/useWorksheetPinyinEditor';
import { createSession, setRawText, updateTokenPinyin } from '../src/domain/worksheet-session';

test('多字词可逐键输入完整拼音，不被旧受控值还原；失焦提交后才确认', async () => {
  const state = ref(setRawText(createSession('chinese'), '春天').session);
  const token = state.value.document.tokens[0]!;
  let calls = 0;
  const editor = useWorksheetPinyinEditor(() => state.value.document.tokens, (id, value) => {
    calls++;
    state.value = updateTokenPinyin(state.value, id, value).session;
  });
  for (const value of ['c', 'ch', 'chūn', 'chūn ', 'chūn t', 'chūn tiān']) {
    editor.drafts.value[token.tokenId] = value;
    assert.equal(editor.valueOf(token), value);
    assert.equal(calls, 0, '逐键输入不触发领域校验将输入还原');
  }
  await editor.commit(token);
  assert.equal(calls, 1);
  assert.equal(state.value.document.tokens[0]?.pinyinState, 'confirmed');
  assert.equal(editor.valueOf(state.value.document.tokens[0]!), 'chūn tiān');
});

test('多字拼音只填一个音节失焦，错误文字保留且仍阻断生成；补齐后恢复', async () => {
  const state = ref(setRawText(createSession('chinese'), '春天').session);
  const token = state.value.document.tokens[0]!;
  const editor = useWorksheetPinyinEditor(() => state.value.document.tokens, (id, value) => {
    state.value = updateTokenPinyin(state.value, id, value).session;
  });
  editor.drafts.value[token.tokenId] = 'chūn';
  await editor.commit(token);
  assert.equal(editor.valueOf(state.value.document.tokens[0]!), 'chūn');
  assert.equal(editor.invalid.value.length, 1);
  editor.drafts.value[token.tokenId] = 'chūn tiān';
  await editor.commit(token);
  assert.equal(editor.invalid.value.length, 0);
  assert.equal(state.value.document.tokens[0]?.pinyinState, 'confirmed');
  state.value = setRawText(state.value, '').session;
  await nextTick();
  assert.deepEqual(editor.drafts.value, {});
});


test('不完整拼音只阻断需要读音的输出，切为拼音学生卷仍可生成', async () => {
  let initial = setRawText(createSession('chinese'), '春天').session;
  const token = initial.document.tokens[0]!;
  initial = updateTokenPinyin(initial, token.tokenId, 'chūn tiān').session;
  const state = ref(initial);
  const editor = useWorksheetPinyinEditor(() => state.value.document.tokens, (id, value) => {
    state.value = updateTokenPinyin(state.value, id, value).session;
  });
  editor.drafts.value[token.tokenId] = 'chūn';
  assert.equal(state.value.document.tokens[0]?.pinyinState, 'confirmed', '未提交时模型仍可能保留旧确认值');
  assert.equal(editor.pending.value, true);
  assert.equal(pinyinEditingBlocksOutput('chinese-dictation', 'student', editor.invalid.value.length > 0), true);
  assert.equal(pinyinEditingBlocksOutput('chinese-pinyin', 'student', editor.invalid.value.length > 0), false);
  assert.equal(pinyinEditingBlocksOutput('chinese-pinyin', 'answer', editor.pending.value), true, '答案不能绕过未提交缓冲使用旧值');
  assert.equal(pinyinEditingBlocksOutput('chinese-dictation', 'answer', editor.pending.value), true, '答案卷同时需要拼音题面与汉字答案');
  await editor.commit(token);
  assert.equal(editor.pending.value, true, '错误提交之后仍保留缓冲阻断需要拼音的答案');
  editor.drafts.value[token.tokenId] = 'chūn tiān';
  await editor.commit(token);
  assert.equal(editor.pending.value, false);
  assert.equal(pinyinEditingBlocksOutput('chinese-pinyin', 'answer', editor.pending.value), false);
});
