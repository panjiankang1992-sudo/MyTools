import assert from 'node:assert/strict';
import test from 'node:test';
import { collectOcrTextLines, extractEnglishOcrLines, mergeEnhancedEnglishOcrLines, type OcrTextLine } from '../src/domain/worksheet-ocr-text';

/** 生成可以模拟单双栏位置的识别行。 */
function line(text: string, y = 0, x = 10, confidence = 90, width = 200): OcrTextLine {
  return { text, confidence, bbox: { x0: x, y0: y, x1: x + width, y1: y + 30 } };
}

test('块和段落按引擎阅读顺序展开，缺少布局时按换行兜底', () => {
  const first = line('dancing 跳舞');
  const second = line('lesson 一节课', 40);
  assert.deepEqual(collectOcrTextLines({ blocks: [{ paragraphs: [{ lines: [first] }, { lines: [second] }] }] }), [first, second]);
  const fallback = collectOcrTextLines({ text: 'after school\n\nSee you tomorrow!\n', confidence: 74 });
  assert.deepEqual(fallback.map(item => item.text), ['after school', 'See you tomorrow!']);
  assert.ok(fallback.every(item => item.bbox.x0 === item.bbox.x1));
});

test('保留英文短语空格、大小写、缩写连字符和句尾标点，删除释义及括号页码', () => {
  const input = ['after school 放学后 (28)', 'See you tomorrow! 明天见！', 'What day is it today? 今天星期几？（26）', "I'm 我是 (3)", 'mother-in-law 岳母', 'let’s 让我们', ') Monday 星期一 (24)', '(24)', '___ (24)', 'Unit 3'];
  assert.deepEqual(extractEnglishOcrLines(input.map((text, index) => line(text, index * 40))).map(item => item.text), [
    'after school', 'See you tomorrow!', 'What day is it today?', "I'm", 'mother-in-law', 'let’s', 'Monday'
  ]);
});

test('教材英文句内逗号、末尾句点和称谓缩写均完整保留，数字乱码仍停止提取', () => {
  const input = ['Hello, Mike. 你好，迈克。', 'Good morning. 早上好。', 'Mr. Brown 布朗先生', 'at a.m. (24)', 'U.S.A. 美国', 'Look 3.14 bad', 'l0ok Mike.', 'hello@ Mike.'];
  assert.deepEqual(extractEnglishOcrLines(input.map((text, index) => line(text, index * 40))).map(item => item.text), [
    'Hello, Mike.', 'Good morning.', 'Mr. Brown', 'at a.m.', 'U.S.A.', 'Look'
  ]);
});

test('整页纯中文提供翻译候选；英文词表中的单独中文释义不混入练习', () => {
  assert.deepEqual(extractEnglishOcrLines([line('星期一 (24)'), line('  星期二（24）'), line('(24)')]).map(item => item.text), ['星期一', '星期二']);
  assert.deepEqual(extractEnglishOcrLines([line('星期一')], { fallbackToChinese: false }), []);
  assert.deepEqual(extractEnglishOcrLines([line('What day is it today?'), line('今天星期几？', 40)]).map(item => item.text), ['What day is it today?']);
});

test('英文词坐标与置信度不被整行中文和页码扩大或抬高', () => {
  const row = line('after school 放学后 (28)', 40, 10, 94, 1000);
  row.words = [line('after', 40, 10, 70, 50), line('school', 40, 70, 80, 65), line('放学后', 40, 160, 100, 110), line('(28)', 40, 900, 100, 80)];
  const [result] = extractEnglishOcrLines([row]);
  assert.equal(result!.text, 'after school');
  assert.equal(result!.bbox.x1, 135);
  assert.ok(result!.confidence! < 80);
});

test('原图低置信拉丁噪声不进入候选，数字符号不能被截成猜测的单词', () => {
  const original = [line('AREA', 0, 10, 30), line('l0ok', 40), line('walk #5 (39) E', 80), line('dancing Js# sess', 120)];
  assert.deepEqual(extractEnglishOcrLines(original).map(item => item.text), ['walk', 'dancing']);
  assert.equal(original[0]!.text, 'AREA');
});

test('彩色英文替换同一行乱码且保留黑色英文和重复行的原有顺序', () => {
  const original = [line('flancina', 0, 10, 43), line('black text', 40, 10, 95), line('Monday', 80), line('Monday', 120)];
  const enhanced = [line('dancing', 1, 12, 92), line('Monday', 121, 12, 96)];
  assert.deepEqual(mergeEnhancedEnglishOcrLines(original, enhanced).map(item => item.text), ['dancing', 'black text', 'Monday', 'Monday']);
  assert.equal(original[0]!.text, 'flancina');
});

test('同一高度的双栏不能仅凭纵坐标相互覆盖', () => {
  const original = [line('left column', 0, 10, 91, 800), line('right column', 0, 700, 60, 180)];
  const merged = mergeEnhancedEnglishOcrLines(original, [line('right corrected', 1, 703, 95, 170)]);
  assert.deepEqual(merged.map(item => item.text), ['left column', 'right corrected']);
});

test('低置信、无置信增强不能覆盖高质量原行，也不追加新的噪声行', () => {
  const original = [line('Monday', 0, 10, 96), line('Tuesday', 40, 10, 92)];
  const unknown = line('Tuesdag', 40);
  delete unknown.confidence;
  const enhanced = [line('Monda', 0, 10, 74), unknown, line('noise', 80, 10, 39)];
  assert.deepEqual(mergeEnhancedEnglishOcrLines(original, enhanced).map(item => item.text), ['Monday', 'Tuesday']);
});

test('多种增强仅保留同一行最高置信度全文，轻微分数提升也有效且不凭词典猜字', () => {
  const original = [line('week', 0, 10, 77), line('jesson', 40, 10, 81), line('waik', 80, 10, 90)];
  const first = mergeEnhancedEnglishOcrLines(original, [line('Weak', 0, 10, 73), line('lesson', 40, 10, 88), line('walk', 80, 10, 95)]);
  const second = mergeEnhancedEnglishOcrLines(first, [line('Weal', 0, 10, 66), line('iesson', 40, 10, 82)]);
  assert.deepEqual(second.map(item => item.text), ['week', 'lesson', 'walk']);
  assert.deepEqual(second.map(item => item.confidence), [77, 88, 95]);
});

test('原图漏掉的高质量英文按所在列位置补回，不按文本去重', () => {
  const original = [line('Monday', 0), line('Wednesday', 80), line('right column', 0, 600)];
  const merged = mergeEnhancedEnglishOcrLines(original, [line('Tuesday', 40, 12, 93), line('Monday', 120, 12, 93)]);
  assert.deepEqual(merged.map(item => item.text), ['Monday', 'Tuesday', 'Wednesday', 'Monday', 'right column']);
});

test('未匹配的中低置信词保留供核对，但同分数不能覆盖已有候选', () => {
  const merged = mergeEnhancedEnglishOcrLines([line('walk', 0, 10, 91)], [line('waik', 0, 10, 59), line('dog', 40, 10, 59)]);
  assert.deepEqual(merged.map(item => item.text), ['walk', 'dog']);
  assert.equal(merged[1]!.confidence, 59);
});

test('彩色增强只有混色短语的半段时，保留原有整句且不追加重复残句', () => {
  const original = [line('after school', 0, 10, 90, 200)];
  const merged = mergeEnhancedEnglishOcrLines(original, [line('after', 0, 10, 99, 80)]);
  assert.deepEqual(merged.map(item => item.text), ['after school']);
  original[0]!.words = [line('after', 0, 10, 94, 80), line('school', 0, 100, 86, 110)];
  assert.deepEqual(mergeEnhancedEnglishOcrLines(original, [line('after', 0, 10, 99, 80)]).map(item => item.text), ['after school']);
});

test('原图低置信中文幻觉拉丁词不阻止高置信英文覆盖', () => {
  const original = line('free ZRH', 0, 10, 74, 300);
  original.words = [line('free', 0, 10, 91, 100), line('ZRH', 0, 160, 53, 150)];
  assert.deepEqual(mergeEnhancedEnglishOcrLines([original], [line('free', 0, 10, 96, 100)]).map(item => item.text), ['free']);
});

test('只有右半段彩色时不因起点不同追加重复词，另一栏完整新词仍可新增', () => {
  const original = line('Good morning.', 0, 10, 90, 400);
  original.words = [line('Good', 0, 10, 90, 90), line('morning.', 0, 180, 90, 230)];
  const enhanced = [line('morning.', 0, 180, 99, 230), line('afternoon', 0, 700, 95, 230)];
  assert.deepEqual(mergeEnhancedEnglishOcrLines([original], enhanced).map(item => item.text), ['Good morning.', 'afternoon']);
});

test('蓝字优先模式不让高分原图残词覆盖完整彩色单词', () => {
  const original = [line('onga', 40, 90, 88, 100)];
  const enhanced = [line('Monday', 40, 10, 85, 200)];
  assert.deepEqual(mergeEnhancedEnglishOcrLines(original, enhanced, { preferEnhanced: true }).map(item => item.text), ['Monday']);
});

test('蓝字优先模式排除高分中文幻觉，并保留被原图大框吞掉的相邻词', () => {
  const original = [line('tomorrow BAX, EHX', 0, 10, 80, 600), line('iee you t Ba', 50, 10, 59, 600)];
  original[0]!.words = [line('tomorrow', 0, 10, 86, 200), line('BAX,', 0, 250, 76, 150), line('EHX', 0, 430, 71, 180)];
  original[1]!.bbox.y1 = 130;
  const enhanced = [line('tomorrow', 0, 10, 96, 200), line('free', 50, 10, 96, 100), line('See you tomorrow!', 95, 10, 95, 600)];
  assert.deepEqual(mergeEnhancedEnglishOcrLines(original, enhanced, { preferEnhanced: true }).map(item => item.text), ['tomorrow', 'free', 'See you tomorrow!']);
});

test('蓝字优先模式仍保留可靠混色短语、黑字行和同高度另一栏', () => {
  const original = [line('after school', 0, 10, 90, 200), line('black text', 40, 10, 92), line('Good morning.', 80, 10, 90, 400), line('other column', 80, 700, 94, 230)];
  original[0]!.words = [line('after', 0, 10, 94, 80), line('school', 0, 100, 86, 110)];
  original[2]!.words = [line('Good', 80, 10, 90, 90), line('morning.', 80, 180, 90, 230)];
  const enhanced = [line('after', 0, 10, 99, 80), line('morning.', 80, 180, 99, 230)];
  assert.deepEqual(mergeEnhancedEnglishOcrLines(original, enhanced, { preferEnhanced: true }).map(item => item.text), ['after school', 'black text', 'Good morning.', 'other column']);
});
