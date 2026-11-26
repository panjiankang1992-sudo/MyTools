import assert from 'node:assert/strict';
import test from 'node:test';
import {
  INPUT_LIMITS, appendToRawText, cleanupBoundaryPunctuation, createInputDocument,
  findBoundaryPunctuation, locate, normalizeTokenText, rawTextBytes, serializeTokens,
  setTokenMeaning, setTokenPinyin, syncInputDocument, tokenize, type InputSourceRef
} from '../src/domain/worksheet-input';

/** 只取派生文本，便于断言分词结果。 */
function texts(rawText: string, policy: 'whitespace' | 'one-per-line' = 'whitespace'): string[] {
  return tokenize(rawText, policy).spans.map(span => span.text);
}

/** 构造来源引用。 */
function ref(overrides: Partial<InputSourceRef> = {}): InputSourceRef {
  return {
    sourceId: 's1', kind: 'textbook', editionId: 'e1', contentVersion: '1.0.0',
    entryId: 'en1', unitId: 'cn-ty-1-1-pep-2024-u01', imageId: null, localOverride: false, ...overrides
  };
}

test('中文按空白分项，连续空格/全角空格/Tab/换行都不产生空项', () => {
  assert.deepEqual(texts('春天 花朵 山 水'), ['春天', '花朵', '山', '水']);
  assert.deepEqual(texts('春天　　花朵'), ['春天', '花朵'], '全角空格');
  assert.deepEqual(texts('春天\t\t花朵'), ['春天', '花朵'], 'Tab');
  assert.deepEqual(texts('春天\n花朵'), ['春天', '花朵'], '换行');
  assert.deepEqual(texts('  春天   \n\n 花朵 \n'), ['春天', '花朵'], '首尾与连续空白');
  assert.deepEqual(texts('春天 花朵\u00a0山'), ['春天', '花朵', '山'], '不换行空格');
});

test('未分隔的中文串保留为一项，不自动分词', () => {
  assert.deepEqual(texts('春天花朵'), ['春天花朵']);
  assert.deepEqual(texts('春花秋月'), ['春花秋月'], '契约示例：不自动拆成四字');
});

test('解析保留原文：规范化只作用于派生数据，不回写 rawText', () => {
  const raw = '  春天　　花朵  ';
  const result = tokenize(raw, 'whitespace');
  assert.deepEqual(result.spans.map(span => span.text), ['春天', '花朵']);
  // 原文切片必须仍然指向用户原字符串。
  for (const span of result.spans) {
    assert.equal(raw.slice(span.rawStart, span.rawEnd).includes(span.text), true);
  }
  assert.equal(normalizeTokenText('  春天  '), '春天');
  assert.equal(normalizeTokenText('a   b'), 'a b', '内部连续空白折叠为一个空格');
});

test('offset 是 UTF-16 下标且指向原文中的片段', () => {
  const raw = '春天 花朵';
  const spans = tokenize(raw, 'whitespace').spans;
  assert.deepEqual(spans.map(span => [span.rawStart, span.rawEnd]), [[0, 2], [3, 5]]);
  assert.equal(raw.slice(0, 2), '春天');
  // 补充平面字符按 UTF-16 占两个下标，切片仍然正确。
  const astral = '𠀀 山水';
  const astralSpan = tokenize(astral, 'whitespace').spans[0]!;
  assert.equal(astral.slice(astralSpan.rawStart, astralSpan.rawEnd), '𠀀');
});

test('英语双引号短语、撇号与连字符按契约处理', () => {
  assert.deepEqual(texts('apple "ice cream" don\'t'), ['apple', 'ice cream', "don't"]);
  const raw = 'apple "ice cream" don\'t';
  const spans = tokenize(raw, 'whitespace').spans;
  assert.equal(spans[1]!.quoted, true);
  // 包裹引号属于原文区间，但派生文本不含引号。
  assert.equal(raw.slice(spans[1]!.rawStart, spans[1]!.rawEnd), '"ice cream"');
  assert.deepEqual(texts('T-shirt well-known'), ['T-shirt', 'well-known']);
});

test('逐行模式只按换行分项，行内空格保留', () => {
  assert.deepEqual(texts('ice cream\napple pie', 'one-per-line'), ['ice cream', 'apple pie']);
  assert.deepEqual(texts('ice cream', 'one-per-line'), ['ice cream']);
  // 制表符不是逐行模式的分隔符，所以不拆项；但派生文本按契约做规范化，制表符折叠为一个空格。
  assert.deepEqual(texts('apple\tbanana', 'one-per-line'), ['apple banana']);
});

test('大小写不折叠：Spring 与 spring 是两项', () => {
  assert.deepEqual(texts('Spring spring'), ['Spring', 'spring']);
});

test('引用内转义支持 \\" 与 \\\\', () => {
  assert.deepEqual(texts('"a\\"b"'), ['a"b']);
  assert.deepEqual(texts('"a\\\\b"'), ['a\\b']);
});

test('未闭合引号定位到行列并阻断', () => {
  const raw = '春天 花朵\n"未闭合';
  const result = tokenize(raw, 'whitespace');
  assert.equal(result.blocked, true);
  const problem = result.issues.find(item => item.code === 'WORKSHEET_PARSE_QUOTE');
  assert.ok(problem, '必须给出引号错误');
  assert.equal(problem.line, 2);
  assert.equal(problem.column, 1);
  assert.equal(locate(raw, problem.offset!).line, 2);
});

test('不可见控制字符定位报错且不静默剔除', () => {
  const raw = '春天\u0007花朵';
  const result = tokenize(raw, 'whitespace');
  assert.equal(result.blocked, true);
  assert.equal(result.issues[0]!.code, 'WORKSHEET_INPUT_CONTROL_CHARACTER');
  assert.equal(result.issues[0]!.offset, 2);
});

test('限额：原文字节与 token 数量都阻断并给出实测值', () => {
  const tooBig = 'a'.repeat(INPUT_LIMITS.rawTextBytes + 1);
  const byteResult = tokenize(tooBig, 'whitespace');
  assert.equal(byteResult.blocked, true);
  assert.equal(byteResult.issues[0]!.code, 'WORKSHEET_INPUT_LIMIT');
  assert.equal(rawTextBytes('春天'), 6, '中文按 UTF-8 计 3 字节');

  const tooMany = Array.from({ length: INPUT_LIMITS.maxTokens + 1 }, () => '字').join(' ');
  const tokenResult = tokenize(tooMany, 'whitespace');
  assert.equal(tokenResult.blocked, true);
  assert.match(tokenResult.issues[0]!.detail ?? '', new RegExp(`limit=${INPUT_LIMITS.maxTokens}`));
});

test('标点默认不是词边界，清理必须显式且可撤销', () => {
  assert.deepEqual(texts('春天,花朵'), ['春天,花朵'], '逗号不自动分词');
  assert.deepEqual(texts('apple,banana'), ['apple,banana']);
  const positions = findBoundaryPunctuation('春天,花朵；山水。');
  assert.equal(positions.length, 3);
  const cleaned = cleanupBoundaryPunctuation('春天,花朵；山水。');
  assert.equal(cleaned.replaced, 3);
  assert.deepEqual(texts(cleaned.text), ['春天', '花朵', '山水'], '清理后才分项');
  // 引号内的标点不受清理影响。
  assert.equal(cleanupBoundaryPunctuation('"a,b"').replaced, 0);
  assert.equal(cleanupBoundaryPunctuation('春天花朵').text, '春天花朵', '无标点时原样返回');
});

test('序列化会为含空格的短语加引号，且能往返', () => {
  const serialized = serializeTokens(['apple', 'ice cream', 'don\'t']);
  assert.equal(serialized, 'apple "ice cream" don\'t');
  assert.deepEqual(texts(serialized), ['apple', 'ice cream', "don't"]);
  assert.equal(serializeTokens(['春天', '花朵'], 'one-per-line'), '春天\n花朵');
});

test('追加保持已有原文与其原始空白', () => {
  assert.equal(appendToRawText('春天  ', '花朵'), '春天 花朵');
  assert.equal(appendToRawText('', '花朵'), '花朵');
  assert.equal(appendToRawText('已\n有', '', 'one-per-line'), '已\n有', '空追加不改原文');
  assert.equal(appendToRawText('a', 'b', 'one-per-line'), 'a\nb');
});

test('删除前一个重复词时，后一个词仍保留自己校对过的义项', () => {
  let document = createInputDocument('english');
  document = syncInputDocument(document, 'bank river bank', 'whitespace').document;
  const lastId = document.tokens[2]!.tokenId;
  document = setTokenMeaning(document, document.tokens[0]!.tokenId, '银行', 'confirmed');
  document = setTokenMeaning(document, lastId, '河岸', 'confirmed');
  const next = syncInputDocument(document, 'river bank', 'whitespace').document;
  assert.equal(next.tokens[1]!.tokenId, lastId);
  assert.equal(next.tokens[1]!.meaning, '河岸');
});

test('稳定匹配：文本与来源不变的条目保留 tokenId 与已确认读音', () => {
  let document = createInputDocument('chinese');
  document = syncInputDocument(document, '春天 花朵', 'whitespace', [[ref()], [ref({ entryId: 'en2' })]]).document;
  const firstId = document.tokens[0]!.tokenId;
  document = setTokenPinyin(document, firstId, [{ grapheme: '春', syllable: 'chūn' }, { grapheme: '天', syllable: 'tiān' }], 'confirmed');

  // 只在末尾追加内容：已有条目的标识与读音必须保留。
  const next = syncInputDocument(document, '春天 花朵 山水', 'whitespace', [[ref()], [ref({ entryId: 'en2' })], [ref({ entryId: 'en3' })]]);
  assert.equal(next.blocked, false);
  assert.equal(next.document.tokens[0]!.tokenId, firstId);
  assert.equal(next.document.tokens[0]!.pinyinState, 'confirmed');
  assert.equal(next.document.tokens[0]!.pinyinSegments[1]!.syllable, 'tiān');
  assert.equal(next.document.tokens[2]!.pinyinState, 'missing', '新条目必须是未校对');
});

test('改动正文的条目不再沿用旧读音，也不与其他同字条目互相覆盖', () => {
  let document = createInputDocument('chinese');
  document = syncInputDocument(document, '长 长', 'whitespace', [[ref({ entryId: 'a' })], [ref({ entryId: 'b' })]]).document;
  const [first, second] = document.tokens;
  document = setTokenPinyin(document, first!.tokenId, [{ grapheme: '长', syllable: 'cháng' }], 'confirmed');
  document = setTokenPinyin(document, second!.tokenId, [{ grapheme: '长', syllable: 'zhǎng' }], 'confirmed');

  // 把第二个字改成别的字：该条目必须变成新条目并丢失确认状态。
  const changed = syncInputDocument(document, '长 短', 'whitespace', [[ref({ entryId: 'a' })], [ref({ entryId: 'c' })]]);
  assert.equal(changed.blocked, false);
  assert.equal(changed.document.tokens[0]!.tokenId, first!.tokenId);
  assert.equal(changed.document.tokens[0]!.pinyinSegments[0]!.syllable, 'cháng');
  assert.equal(changed.document.tokens[1]!.text, '短');
  assert.equal(changed.document.tokens[1]!.pinyinState, 'missing');
  assert.notEqual(changed.document.tokens[1]!.tokenId, second!.tokenId);
});

test('来源变化时同一文本不沿用旧修正', () => {
  let document = createInputDocument('chinese');
  document = syncInputDocument(document, '山', 'whitespace', [[ref({ entryId: 'a' })]]).document;
  const id = document.tokens[0]!.tokenId;
  document = setTokenPinyin(document, id, [{ grapheme: '山', syllable: 'shān' }], 'confirmed');

  const swapped = syncInputDocument(document, '山', 'whitespace', [[ref({ entryId: 'other' })]]);
  assert.notEqual(swapped.document.tokens[0]!.tokenId, id, '来源不同必须视为新条目');
  assert.equal(swapped.document.tokens[0]!.pinyinState, 'missing');
});

test('解析失败时保留旧 token，允许继续修正原文', () => {
  let document = createInputDocument('chinese');
  document = syncInputDocument(document, '春天', 'whitespace', [[ref()]]).document;
  const before = document.tokens.length;
  const broken = syncInputDocument(document, '"未闭合', 'whitespace');
  assert.equal(broken.blocked, true);
  assert.equal(broken.document.tokens.length, before, '原文出错不得清空已解析条目');
});

test('自定义词条默认未校对，人为确认后才标为已确认', () => {
  let document = createInputDocument('english');
  document = syncInputDocument(document, 'apple', 'whitespace', [[ref({ kind: 'custom', editionId: null, entryId: null })]]).document;
  assert.equal(document.tokens[0]!.meaningState, 'missing');
  assert.equal(document.tokens[0]!.meaning, null);
  document = setTokenMeaning(document, document.tokens[0]!.tokenId, '苹果', 'confirmed');
  assert.equal(document.tokens[0]!.meaning, '苹果');
  assert.equal(document.tokens[0]!.meaningState, 'confirmed');
});
