import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyCompletionResults, selectCompletionTargets, type CompletionResult } from '../src/lib/admin-textbook-draft.ts';
import type { EditableWorksheetPair } from '../src/domain/worksheet-editable-pairs.ts';

/**
 * F06：**一次补全的闸门**。
 *
 * 审查探针 `F04-stale-translation-wrong-pair` 复现的形态：
 *
 * > 用户输入 `cat`，补全请求发出；结果回来之前用户改成了 `dog`；
 * > `cat` 的回包到达 → 得到 `dog` / `猫`。
 *
 * **为什么会这样**：合并**只按 rowId 匹配**，不校验"这份结果是对哪段原文算的"。
 * 而行身份恰恰是稳定的（那是 F04 修好的性质）——
 * 于是"旧原文的结果"会安到"新原文的行"上。
 */

const rows = (english: string): EditableWorksheetPair[] => [{ tokenId: 'row-1', english }];

test('原文已改变时，旧回包必须被丢弃（不能拿 cat 的释义去配 dog）', () => {
  const stale: CompletionResult[] = [
    { rowId: 'row-1', sourceText: 'cat', meaning: '猫' }
  ];
  const { pairs, filledCount } = applyCompletionResults(rows('dog'), new Set(['row-1']), stale);

  assert.equal(pairs[0].meaning ?? '', '', '原文已变成 dog，cat 的释义绝不能填进去');
  assert.equal(filledCount, 0, '被丢弃的结果不计入补全数');
});

test('原文未变时才应用回包', () => {
  const fresh: CompletionResult[] = [
    { rowId: 'row-1', sourceText: 'cat', meaning: '猫' }
  ];
  const { pairs, filledCount } = applyCompletionResults(rows('cat'), new Set(['row-1']), fresh);

  assert.equal(pairs[0].meaning, '猫', '原文一致时应当补上');
  assert.equal(filledCount, 1);
});

test('目标一侧已被用户手工填写时不得覆盖', () => {
  // 设计：「一条新内容最多首次自动补全一次」，且**显式补全只能填空白**。
  const pairs: EditableWorksheetPair[] = [{ tokenId: 'row-1', english: 'cat', meaning: '小猫（手工）' }];
  const result: CompletionResult[] = [{ rowId: 'row-1', sourceText: 'cat', meaning: '猫' }];
  const { pairs: after, filledCount } = applyCompletionResults(pairs, new Set(['row-1']), result);

  assert.equal(after[0].meaning, '小猫（手工）', '已有内容不能被补全覆盖');
  assert.equal(filledCount, 0);
});

test('结果不带 sourceText 时保守丢弃（宁可少补，不可补错）', () => {
  // 兼容旧调用方：拿不到"对哪段原文算的"就不敢填——
  // 填错会污染用户内容，而少补只是没帮上忙。
  const result: CompletionResult[] = [{ rowId: 'row-1', meaning: '猫' }];
  const { pairs, filledCount } = applyCompletionResults(rows('cat'), new Set(['row-1']), result);

  assert.equal(pairs[0].meaning ?? '', '', '无法核对原文时不应写入');
  assert.equal(filledCount, 0);
});

/**
 * 审查反例 `F06-completion-overwrites-nonempty-pinyin`（对应 F06，P1）。
 *
 * **问题**：手工填好的 `长 / zhǎng` 会被补全覆盖成 `长 / cháng`。
 *
 * **根因**：`selectCompletionTargets` 的语文分支**只判断有没有汉字**，
 * 不看拼音是不是已经填了。
 *
 * **契约**：显式补全**只能填空白**——已填内容被覆盖是数据污染，
 * 而且不会报错，用户只会在字帖里看到错的读音。
 */

test('语文：已填拼音的行不应成为补全目标（不能把 zhǎng 改成 cháng）', () => {
  const pairs: EditableWorksheetPair[] = [{ tokenId: 'row-1', text: '长', pinyin: 'zhǎng' }];
  const targets = selectCompletionTargets(pairs, 'chinese', new Set(['row-1']));
  assert.equal(targets.length, 0, '拼音已有值时不应请求补全，否则会覆盖手工填写的读音');
});

test('语文：拼音为空的行仍然是补全目标', () => {
  const pairs: EditableWorksheetPair[] = [{ tokenId: 'row-1', text: '长' }];
  const targets = selectCompletionTargets(pairs, 'chinese', new Set(['row-1']));
  assert.equal(targets.length, 1, '缺拼音时应当补全（门槛不能变成不补）');
  assert.equal(targets[0].missing, 'pinyin');
});

test('语文：拼音只有空白同样视为缺（按"空"判断而不是按 undefined）', () => {
  const pairs: EditableWorksheetPair[] = [{ tokenId: 'row-1', text: '长', pinyin: '   ' }];
  const targets = selectCompletionTargets(pairs, 'chinese', new Set(['row-1']));
  assert.equal(targets.length, 1, '空白拼音等于没填');
});
