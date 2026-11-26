import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  coercePreviewMode, defaultPreviewMode, modeBelongsToSubject, previewModeLabel, previewModeOptions,
  previewModesFor
} from '../src/lib/admin-preview-mode.ts';

/**
 * F10：**预览的学科与题型必须跟着教材走**。
 *
 * 审查指出的问题：编辑器里写死 `{ mode: 'chinese-pinyin', subject: 'chinese' }`，
 * 于是**英语教材的预览也走语文链路**。
 */

test('英语教材只能得到英语题型', () => {
  const modes = previewModesFor('english');
  assert.deepEqual([...modes], ['english-dictation', 'english-meaning'],
    '英语预览不能出现语文字帖题型');
  for (const mode of modes) {
    assert.ok(mode.startsWith('english-'), `题型 ${mode} 不属于英语`);
  }
});

test('语文教材只能得到语文题型', () => {
  const modes = previewModesFor('chinese');
  for (const mode of modes) {
    assert.ok(mode.startsWith('chinese-'), `题型 ${mode} 不属于语文`);
  }
});

test('默认题型属于该学科——英语不能默认成 chinese-pinyin', () => {
  // 这正是原实现写死的那个值：`{ mode: 'chinese-pinyin', subject: 'chinese' }`。
  assert.notEqual(defaultPreviewMode('english'), 'chinese-pinyin');
  assert.equal(defaultPreviewMode('english'), 'english-dictation');
  assert.equal(defaultPreviewMode('chinese'), 'chinese-dictation');
});

test('学科切换后，不属于新学科的题型被纠回默认', () => {
  // 用户在语文下选了拼音，然后切到英语：那个题型必须被替换。
  assert.equal(coercePreviewMode('english', 'chinese-pinyin'), 'english-dictation');
  // 已经属于该学科的题型保持不变（用户的显式选择要留住）。
  assert.equal(coercePreviewMode('english', 'english-meaning'), 'english-meaning');
  assert.equal(coercePreviewMode('chinese', 'chinese-pinyin'), 'chinese-pinyin');
});

test('题型归属判断覆盖四种题型', () => {
  assert.ok(modeBelongsToSubject('chinese', 'chinese-pinyin'));
  assert.ok(modeBelongsToSubject('chinese', 'chinese-dictation'));
  assert.ok(modeBelongsToSubject('english', 'english-dictation'));
  assert.ok(modeBelongsToSubject('english', 'english-meaning'));
  assert.ok(!modeBelongsToSubject('chinese', 'english-meaning'));
  assert.ok(!modeBelongsToSubject('english', 'chinese-pinyin'));
});

test('四种题型都有中文名，且与公开端字帖名称逐字一致', () => {
  // **鉴别力**：编辑器此前**只有值没有中文名**，界面无法让用户选择题型
  // （下拉里出现 `chinese-pinyin` 这种机器标识是另一种"没做完"）。
  // 名称必须与公开端 `useWorksheetV2Session` 的 MODES 一致，否则
  // "管理端预览的是拼音字帖"与"公开端生成拼音字帖"会对不上话。
  assert.equal(previewModeLabel('chinese-pinyin'), '拼音字帖');
  assert.equal(previewModeLabel('chinese-dictation'), '汉字字帖');
  assert.equal(previewModeLabel('english-dictation'), '英语默写');
  assert.equal(previewModeLabel('english-meaning'), '中文释义练习');
});

test('选项列表按学科给出两种题型（语文两型、英语两型），带中文名', () => {
  const chinese = previewModeOptions('chinese');
  assert.deepEqual(chinese.map(option => option.value), ['chinese-dictation', 'chinese-pinyin']);
  assert.deepEqual(chinese.map(option => option.label), ['汉字字帖', '拼音字帖']);

  const english = previewModeOptions('english');
  assert.deepEqual(english.map(option => option.value), ['english-dictation', 'english-meaning']);
  // 英语选项里不得出现语文题型——这是"预览走错学科链路"的直接判据。
  assert.equal(english.every(option => option.value.startsWith('english-')), true);
});
