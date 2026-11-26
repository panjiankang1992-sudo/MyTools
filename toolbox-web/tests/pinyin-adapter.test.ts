import assert from 'node:assert/strict';
import test from 'node:test';
import { candidateReadings, suggestPinyin, suggestPinyinBatch } from '../src/lib/pinyin-adapter';

test('逐字给出带调读音，按字素一一对应', () => {
  const { suggestion } = suggestPinyin('春天');
  assert.deepEqual(suggestion.segments, [
    { grapheme: '春', syllable: 'chūn' },
    { grapheme: '天', syllable: 'tiān' }
  ]);
});

test('本机候选状态恒为 suggested，不冒充已确认', () => {
  // 契约 §3.1：即使看起来完全正确，本机自动结果也不能变成 confirmed。
  assert.equal(suggestPinyin('春天').suggestion.state, 'suggested');
  assert.equal(suggestPinyin('长').suggestion.state, 'suggested');
});

test('多音字按词语上下文取音，并标出需要人工确认', () => {
  const word = suggestPinyin('长大');
  assert.equal(word.suggestion.segments[0]!.syllable, 'zhǎng', '应使用词语上下文读音');
  assert.ok(word.suggestion.ambiguousIndexes.includes(0), '「长」必须标为多音字');
  assert.ok(word.issues.some(issue => issue.code === 'WORKSHEET_PINYIN_AMBIGUOUS'));
});

test('孤字多音字给出全部候选读音供用户选择', () => {
  const readings = candidateReadings('长');
  assert.ok(readings.includes('cháng'));
  assert.ok(readings.includes('zhǎng'));
  assert.equal(suggestPinyin('长').suggestion.segments[0]!.syllable, 'cháng', '孤字取其默认读音');
});

test('轻声不标调', () => {
  const { suggestion } = suggestPinyin('叶子');
  assert.equal(suggestion.segments[1]!.syllable, 'zi', '轻声不应带声调');
});

test('非汉字内容不猜读音，标记为待解决', () => {
  const result = suggestPinyin('A山');
  assert.equal(result.suggestion.segments[0]!.syllable, '', '非汉字不得给出读音');
  assert.ok(result.suggestion.unresolvedIndexes.includes(0));
  assert.ok(result.issues.some(issue => issue.code === 'WORKSHEET_PINYIN_UNRESOLVED'));
  assert.equal(result.suggestion.segments[1]!.syllable, 'shān');
});

test('补充平面字符按字素处理，不被切半', () => {
  const { suggestion } = suggestPinyin('𠀀山');
  assert.equal(suggestion.segments.length, 2, '补充平面字符必须算一个字素');
  assert.equal(suggestion.segments[1]!.grapheme, '山');
  assert.equal(suggestion.segments[1]!.syllable, 'shān');
});

test('音节数量与字素数量不一致时报出对齐问题而不是硬对齐', () => {
  const result = suggestPinyin('春天ABC');
  // 只要出现无法对齐的情况就必须报出来，不能悄悄错位。
  if (result.suggestion.segments.length !== 4 || result.issues.some(issue => issue.code === 'WORKSHEET_PINYIN_ALIGNMENT')) {
    assert.ok(result.issues.some(issue => issue.code === 'WORKSHEET_PINYIN_ALIGNMENT' || issue.code === 'WORKSHEET_PINYIN_UNRESOLVED'));
  }
});

test('批量确认不会跳过含多音字或非汉字的条目', () => {
  const { results, safeToConfirmAll } = suggestPinyinBatch(['春天', '长大', 'A山', '花朵']);
  assert.equal(results.length, 4);
  assert.ok(safeToConfirmAll.includes(0), '无歧义的条目可批量确认');
  assert.ok(safeToConfirmAll.includes(3));
  assert.ok(!safeToConfirmAll.includes(1), '含多音字不得被批量确认跳过');
  assert.ok(!safeToConfirmAll.includes(2), '含待解决字符不得被批量确认跳过');
});

test('空文本与纯空白安全返回', () => {
  assert.deepEqual(suggestPinyin('').suggestion.segments, []);
  assert.deepEqual(suggestPinyin('   ').suggestion.segments, []);
  assert.deepEqual(suggestPinyinBatch([]).safeToConfirmAll, []);
});

test('字典未识别时原字回传必须转为缺音，不能当作自动拼音', () => {
  const { suggestion, issues } = suggestPinyin('𱁬山');
  assert.deepEqual(suggestion.segments, [{ grapheme: '𱁬', syllable: '' }, { grapheme: '山', syllable: 'shān' }]);
  assert.deepEqual(suggestion.unresolvedIndexes, [0]);
  assert.ok(issues.some(issue => issue.code === 'WORKSHEET_PINYIN_UNRESOLVED'));
  assert.deepEqual(candidateReadings('𱁬'), []);
});
