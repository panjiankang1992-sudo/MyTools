import assert from 'node:assert/strict';
import test from 'node:test';
import { englishInputValue, worksheetEntryPresentation } from '../src/components/worksheet/worksheet-entry-presentation';
import { createSession, fillSuggestedPinyin, setRawText } from '../src/domain/worksheet-session';
import type { InputToken } from '../src/domain/worksheet-input';

/** 从实际原文解析取得条目，按所测自动字段补齐。 */
function englishToken(text: string, fields: Partial<InputToken> = {}): InputToken {
  const session = setRawText(createSession('english'), text).session;
  return { ...session.document.tokens[0]!, ...fields };
}

test('UI接受有效自动拼音，不再显示待确认或计入必填缺口', () => {
  const base = setRawText(createSession('chinese'), '春天').session;
  const prepared = fillSuggestedPinyin(base, () => ({ segments: [{ grapheme: '春', syllable: 'chūn' }, { grapheme: '天', syllable: 'tiān' }] }));
  const token = prepared.session.document.tokens[0]!;
  assert.equal(token.pinyinState, 'suggested');
  assert.deepEqual(worksheetEntryPresentation(token, 'chinese'), {
    type: 'default', label: '自动生成，可修改', missing: false, automatic: true
  });
  assert.equal(worksheetEntryPresentation({ ...token, pinyinSegments: [{ grapheme: '春', syllable: 'chūn' }] }, 'chinese').missing, true);
});

test('中文原文未翻译时英文框为空，有规范英文后独立展示英文与释义', () => {
  const pending = englishToken('苹果', { meaning: '苹果', meaningState: 'confirmed' });
  assert.equal(englishInputValue(pending), '');
  assert.equal(worksheetEntryPresentation(pending, 'english').label, '英文待补全');
  const translated = { ...pending, englishText: 'apple', englishState: 'suggested' as const };
  assert.equal(englishInputValue(translated), 'apple');
  assert.equal(translated.text, '苹果', '原文保持不变');
  assert.equal(translated.meaning, '苹果');
  assert.equal(worksheetEntryPresentation(translated, 'english').missing, false);
  assert.equal(worksheetEntryPresentation(translated, 'english').label, '自动生成，可修改');
});

test('英文输入沿用原词，自动中文可直接使用，人工英文清空不回退旧词', () => {
  const translated = englishToken('apple', { meaning: '苹果', meaningState: 'suggested' });
  assert.equal(englishInputValue(translated), 'apple');
  assert.equal(worksheetEntryPresentation(translated, 'english').missing, false);
  assert.equal(worksheetEntryPresentation(translated, 'english').automatic, true);
  const cleared = { ...translated, englishText: '', englishState: 'missing' as const };
  assert.equal(englishInputValue(cleared), '', '已明确清空的英文不能显示旧原文');
  assert.equal(worksheetEntryPresentation(cleared, 'english').label, '英文待补全');
});

test('只对缺失或非法翻译提示补全，完整人工修改不会冒充自动结果', () => {
  const manual = englishToken('苹果', { englishText: 'apple', englishState: 'confirmed', meaning: '苹果', meaningState: 'confirmed' });
  assert.equal(worksheetEntryPresentation(manual, 'english').missing, false);
  assert.equal(worksheetEntryPresentation(manual, 'english').automatic, false);
  assert.equal(worksheetEntryPresentation({ ...manual, meaning: 'apple' }, 'english').label, '中文待补全');
  assert.equal(worksheetEntryPresentation({ ...manual, englishText: '苹果apple' }, 'english').label, '英文待补全');
});
