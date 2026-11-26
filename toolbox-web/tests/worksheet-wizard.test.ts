import assert from 'node:assert/strict';
import test from 'node:test';
import { prepareWorksheetSourceText, useWorksheetWizard } from '../src/composables/useWorksheetWizard';

test('三步初始只选来源，无有效原文或生成结果不能跳到后续步骤', () => {
  const wizard = useWorksheetWizard();
  assert.equal(wizard.stage.value, 'source');
  assert.equal(wizard.source.value, null);
  assert.equal(wizard.toConfirm(false), false);
  assert.equal(wizard.toPreview(true), false);
  wizard.chooseSource('custom');
  assert.equal(wizard.toConfirm(true), true);
  assert.equal(wizard.toPreview(false), false);
  assert.equal(wizard.stage.value, 'confirm');
  assert.equal(wizard.toPreview(true), true);
  assert.equal(wizard.stage.value, 'preview');
});

test('步骤栏只可后退，返回来源保留选择且不重复应用教材候选', () => {
  const wizard = useWorksheetWizard();
  wizard.chooseSource('textbook');
  wizard.markApplied('textbook', 'edition-v1-entry1-entry2');
  wizard.toConfirm(true);
  wizard.toPreview(true);
  assert.equal(wizard.backTo('confirm'), true);
  assert.equal(wizard.backTo('source'), true);
  assert.equal(wizard.source.value, 'textbook');
  assert.equal(wizard.wasApplied('textbook', 'edition-v1-entry1-entry2'), true);
  assert.equal(wizard.wasApplied('textbook', 'edition-v1-entry3'), false);
  assert.equal(wizard.backTo('confirm'), false, '不能用步骤栏前进绕过确认');
});

test('切换来源保留各自候选标记，清空或恢复新草稿才重置', () => {
  const wizard = useWorksheetWizard();
  wizard.chooseSource('image');
  wizard.markApplied('image', 'spring');
  wizard.chooseSource('custom');
  wizard.markApplied('custom', 'summer');
  assert.equal(wizard.wasApplied('image', 'spring'), true);
  wizard.resetApplied();
  assert.equal(wizard.wasApplied('custom', 'summer'), false);
  assert.equal(wizard.wasApplied('image', 'spring'), false);
});

test('英文逐行短语追加到空白分项原文仍保持短语边界', () => {
  const result = prepareWorksheetSourceText('apple', 'whitespace', 'ice cream\nGood morning', 'one-per-line', 'append');
  assert.equal(result.ok, true);
  assert.equal(result.rawText, 'apple "ice cream" "Good morning"');
  assert.equal(result.parsePolicy, 'whitespace');
});

test('向逐行草稿追加候选按每项换行，显式替换保留输入原格式', () => {
  const appended = prepareWorksheetSourceText('ice cream', 'one-per-line', 'apple banana', 'whitespace', 'append');
  assert.equal(appended.rawText, 'ice cream\napple\nbanana');
  const replaced = prepareWorksheetSourceText('ice cream', 'one-per-line', '  apple  banana\n', 'whitespace', 'replace');
  assert.equal(replaced.rawText, '  apple  banana\n');
  assert.equal(replaced.parsePolicy, 'whitespace');
});

test('无效来源候选不得先覆盖现有草稿', () => {
  const result = prepareWorksheetSourceText('old content', 'one-per-line', '"unclosed', 'whitespace', 'replace');
  assert.equal(result.ok, false);
  assert.equal(result.rawText, 'old content');
  assert.equal(result.parsePolicy, 'one-per-line');
});
