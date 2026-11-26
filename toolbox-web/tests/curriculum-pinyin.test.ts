import assert from 'node:assert/strict';
import test from 'node:test';
import { completePinyinFromCurriculum } from '../src/lib/curriculum-pinyin';
import { suggestPinyin } from '../src/lib/pinyin-adapter';
import type { TranslationResult } from '../src/services/public-api';

function local(item: { id: string; text: string }, reading = 'cháng dà'): TranslationResult {
  return { id: item.id, sourceText: item.text, englishText: null, meaning: null, status: 'translated',
    pinyinSegments: [...item.text].map((grapheme, index) => ({ grapheme, syllable: reading.split(' ')[index]! })) };
}

test('先采用教材上下文读音，只对未命中词调用本机拼音', async () => {
  const fallback: string[] = [];
  const items = [{ id: 'one', text: '长大' }, { id: 'two', text: '春天' }];
  const results = await completePinyinFromCurriculum(items, new AbortController().signal, async (batch, _signal, subject) => {
    assert.equal(subject, 'chinese');
    assert.deepEqual(batch, items);
    return [local(batch[0]!)];
  }, text => { fallback.push(text); return suggestPinyin(text); });
  assert.deepEqual(fallback, ['春天']);
  assert.deepEqual(results.map(result => result.suggestion.segments.map(segment => segment.syllable).join(' ')), ['cháng dà', 'chūn tiān']);
});

test('来源字段与确认页快照只保留在本机，教材查询请求仅含id和text', async () => {
  const items = [
    { id: 'source-row', text: '长大', source: 'text', target: 'pinyin' },
    { id: 'confirm-row', text: '长大', fields: '["private snapshot"]' }
  ];
  let fallback = 0;
  const result = await completePinyinFromCurriculum(items, new AbortController().signal, async batch => {
    assert.deepEqual(batch, [{ id: 'source-row', text: '长大' }, { id: 'confirm-row', text: '长大' }]);
    return batch.map(item => local(item));
  }, () => { fallback++; throw new Error('textbook match must not fall back'); });
  assert.equal(result.length, 2);
  assert.equal(fallback, 0);
});

test('超过20条按批查教材，保持每行身份与顺序', async () => {
  const sizes: number[] = [];
  const items = Array.from({ length: 43 }, (_, index) => ({ id: String(index), text: '长大' }));
  const results = await completePinyinFromCurriculum(items, new AbortController().signal, async batch => {
    sizes.push(batch.length); return batch.map(item => local(item));
  });
  assert.deepEqual(sizes, [20, 20, 3]);
  assert.deepEqual(results.map(result => result.id), items.map(item => item.id));
});

test('教材网络失败只尝试一批，其余内容本机降级，不排队重试', async () => {
  let requests = 0;
  const results = await completePinyinFromCurriculum(Array.from({ length: 25 }, (_, index) => ({ id: String(index), text: '春天' })),
    new AbortController().signal, async () => { requests++; throw new Error('offline'); });
  assert.equal(requests, 1);
  assert.equal(results.length, 25);
  assert.ok(results.every(result => result.suggestion.segments[0]?.syllable === 'chūn'));
});

test('原文错配、音节错位、无效音节及重复归属回包都不能当作教材命中', async () => {
  const item = { id: 'one', text: '长大' };
  const malformed = [
    [{ ...local(item), sourceText: '银行' }],
    [{ ...local(item), pinyinSegments: [{ grapheme: '大', syllable: 'dà' }, { grapheme: '长', syllable: 'cháng' }] }],
    [{ ...local(item), pinyinSegments: [{ grapheme: '长', syllable: 'wrong!' }, { grapheme: '大', syllable: 'dà' }] }],
    [local(item), local(item)],
    [{ ...local(item), pinyinSegments: [] }]
  ];
  for (const response of malformed) {
    let fallback = 0;
    const result = await completePinyinFromCurriculum([item], new AbortController().signal, async () => response,
      text => { fallback++; return suggestPinyin(text); });
    assert.equal(fallback, 1);
    assert.equal(result[0]?.suggestion.segments[0]?.syllable, 'zhǎng');
  }
});

test('请求取消后即使回包迟到也不计算降级，不产生可写回结果', async () => {
  const controller = new AbortController();
  let finish!: (results: TranslationResult[]) => void;
  let fallback = 0;
  const task = completePinyinFromCurriculum([{ id: 'one', text: '长大' }], controller.signal,
    async () => new Promise(resolve => { finish = resolve; }), text => { fallback++; return suggestPinyin(text); });
  controller.abort();
  finish([]);
  assert.deepEqual(await task, []);
  assert.equal(fallback, 0);
});
