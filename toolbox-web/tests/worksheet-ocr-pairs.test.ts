import assert from 'node:assert/strict';
import test from 'node:test';
import { pairEnglishOcrLines, recoverMissingEnglishOcrEntries } from '../src/domain/worksheet-ocr-pairs';
import type { OcrTextLine, OcrTextWord } from '../src/domain/worksheet-ocr-text';

/** 构造已经换算回原图坐标的识别行。 */
function line(text: string, x0: number, y0: number, x1: number, y1 = y0 + 24, confidence = 94): OcrTextLine {
  return { text, bbox: { x0, y0, x1, y1 }, confidence };
}

/** 模拟一条中英混排行及其词级坐标。 */
function mixed(words: OcrTextWord[]): OcrTextLine {
  return {
    text: words.map(word => word.text).join(' '), words, confidence: 94,
    bbox: {
      x0: Math.min(...words.map(word => word.bbox.x0)), y0: Math.min(...words.map(word => word.bbox.y0)),
      x1: Math.max(...words.map(word => word.bbox.x1)), y1: Math.max(...words.map(word => word.bbox.y1))
    }
  };
}

test('中英混排逐项对应，保留短语和句末标点，仅剔除页码', () => {
  const english = [line('Monday', 10, 10, 120), line('after school', 10, 70, 175), line('See you tomorrow!', 10, 130, 265)];
  const source = [
    mixed([english[0]!, line('星期一', 150, 10, 225), line('(24)', 600, 10, 650)]),
    mixed([english[1]!, line('放学后', 210, 70, 285), line('（28）', 600, 70, 650)]),
    mixed([english[2]!, line('明天见！', 300, 130, 400)])
  ];
  assert.deepEqual(pairEnglishOcrLines(english, source).map(({ text, meaning }) => ({ text, meaning })), [
    { text: 'Monday', meaning: '星期一' }, { text: 'after school', meaning: '放学后' }, { text: 'See you tomorrow!', meaning: '明天见！' }
  ]);
});

test('去页码不删除释义中的中文括号，也不遗漏括号内的汉字', () => {
  const english = [line('at', 10, 10, 45), line('walk', 10, 70, 85)];
  const source = [line('在（某时间） (26)', 80, 10, 330), mixed([
    line('牵着', 120, 70, 170), line('（', 175, 70, 190, 94, 12), line('动物', 195, 70, 245), line('）', 250, 70, 265, 94, 15),
    line('走，', 270, 70, 320), line('遛', 325, 70, 350), line('(22)', 620, 70, 680)
  ])];
  assert.deepEqual(pairEnglishOcrLines(english, source).map(entry => entry.meaning), ['在（某时间）', '牵着（动物）走，遛']);
});

test('长句下一行中文归属原句，下一词同行中文不得被抢占', () => {
  const english = [line('What day is it today?', 10, 10, 380), line('cinema', 10, 110, 115), line('after school', 10, 170, 210)];
  const source = [line('今天星期几？ (26)', 80, 50, 260), line('电影院', 150, 110, 230), line('放学后', 245, 170, 330)];
  assert.deepEqual(pairEnglishOcrLines(english, source).map(entry => entry.meaning), ['今天星期几？', '电影院', '放学后']);
});

test('缺失释义、无坐标和乱码保留空值，不猜测下一项翻译', () => {
  const english = [line('dancing', 10, 10, 125), line('lesson', 10, 60, 115), line('dog', 10, 110, 75),
    { text: 'free', bbox: { x0: 0, y0: 0, x1: 0, y1: 0 } }];
  const source = [line('一节课，一课时', 150, 60, 390), line('BAX, EHX', 150, 110, 340), line('空闲的', 150, 160, 250)];
  assert.deepEqual(pairEnglishOcrLines(english, source).map(entry => entry.meaning), ['', '一节课，一课时', '', '']);
});

test('重复英文按图片位置分别配对，结果数量与输入顺序不变', () => {
  const english = [line('play', 10, 80, 75), line('play', 10, 10, 75)];
  const source = [line('玩耍', 110, 10, 180), line('戏剧', 110, 80, 180)];
  assert.deepEqual(pairEnglishOcrLines(english, source).map(({ text, meaning }) => ({ text, meaning })), [
    { text: 'play', meaning: '戏剧' }, { text: 'play', meaning: '玩耍' }
  ]);
});

test('双栏词表同行四段文字分别配对，不越过第二栏英文起点', () => {
  const english = [line('apple', 10, 10, 90), line('pear', 10, 70, 80), line('Monday', 500, 10, 610), line('Tuesday', 500, 70, 620)];
  const source = [
    mixed([english[0]!, line('苹果', 130, 10, 190), english[2]!, line('星期一', 650, 10, 750), line('(24)', 900, 10, 960)]),
    mixed([english[1]!, english[3]!, line('星期二', 650, 70, 750)])
  ];
  assert.deepEqual(pairEnglishOcrLines(english, source).map(entry => entry.meaning), ['苹果', '', '星期一', '星期二']);
});

test('双栏换行释义只能归属同栏英文，不能沿高度串到另一栏', () => {
  const english = [line('Good morning!', 10, 10, 270), line('Good night!', 500, 10, 700), line('next', 10, 110, 85), line('last', 500, 110, 580)];
  const source = [line('晚安！', 530, 50, 650), line('下一个', 130, 110, 220), line('最后的', 650, 110, 740)];
  assert.deepEqual(pairEnglishOcrLines(english, source).map(entry => entry.meaning), ['', '晚安！', '下一个', '最后的']);
});

test('低置信中文不自动配对，成功配对置信度不超过任意一侧', () => {
  const english = [line('apple', 10, 10, 90, 34, 84), line('pear', 10, 70, 80, 94, 91)];
  const source = [line('苹果', 130, 10, 190, 34, 74), line('梨', 130, 70, 160, 94, 31)];
  const result = pairEnglishOcrLines(english, source);
  assert.equal(result[0]!.confidence, 74);
  assert.equal(result[0]!.meaning, '苹果');
  assert.equal(result[1]!.meaning, '');
});

test('一个中文区域只分配一次，重复识别区域也不得分给第二个英文', () => {
  const english = [line('week', 10, 10, 90), line('Week', 12, 12, 92)];
  const source = [line('周，星期', 130, 10, 270), line('周，星期', 132, 12, 271)];
  assert.deepEqual(pairEnglishOcrLines(english, source).map(entry => entry.meaning), ['周，星期', '']);
});

test('没有词级坐标的混排文本不靠字符宽度臆测中文位置', () => {
  const english = [line('dog', 10, 10, 90)];
  const source = [line('dog 狗 (22)', 10, 10, 300)];
  assert.equal(pairEnglishOcrLines(english, source)[0]!.meaning, '');
});

test('规则词表中间英文漏识别但中文清晰时保留中文空英文行，并保持词条顺序', () => {
  const english = [line('Monday', 10, 10, 120), line('Wednesday', 10, 110, 140), line('Thursday', 10, 160, 130)];
  const source = [line('星期一', 180, 10, 260), line('星期二', 180, 60, 260), line('星期三', 180, 110, 260), line('星期四', 180, 160, 260)];
  const paired = pairEnglishOcrLines(english, source);
  assert.equal(paired.length, 3, '基础配对继续保持英文输入的数量契约');
  assert.deepEqual(recoverMissingEnglishOcrEntries(english, source, paired).map(({ text, meaning }) => ({ text, meaning })), [
    { text: 'Monday', meaning: '星期一' }, { text: '', meaning: '星期二' },
    { text: 'Wednesday', meaning: '星期三' }, { text: 'Thursday', meaning: '星期四' }
  ]);
});

test('首尾中文、标题、页码、错列和低分中间中文都不能被扩张为词条', () => {
  const english = [line('Monday', 10, 10, 120), line('Wednesday', 10, 110, 140), line('Thursday', 10, 160, 130)];
  const anchors = [line('星期一', 180, 10, 260), line('星期三', 180, 110, 260), line('星期四', 180, 160, 260)];
  for (const unsupported of [line('词汇表', 180, -40, 280), line('放学后', 180, 210, 280), line('(24)', 180, 60, 260), line('第二单元', 10, 60, 100), line('星期二', 180, 60, 260, 84, 60)]) {
    const source = [...anchors, unsupported];
    const paired = pairEnglishOcrLines(english, source);
    assert.deepEqual(recoverMissingEnglishOcrEntries(english, source, paired), paired);
  }
});

test('没有第三条正常行距作为证据时不猜测独立中文，双倍行距不能是普通换行释义', () => {
  const english = [line('Monday', 10, 10, 120), line('Wednesday', 10, 110, 140)];
  const source = [line('星期一', 180, 10, 260), line('星期二', 180, 60, 260), line('星期三', 180, 110, 260)];
  const paired = pairEnglishOcrLines(english, source);
  assert.deepEqual(recoverMissingEnglishOcrEntries(english, source, paired), paired);
});

test('原图和增强图重复的中文区域只恢复一次，已有英文所在高度不重复追加', () => {
  const english = [line('Monday', 10, 10, 120), line('Wednesday', 10, 110, 140), line('Thursday', 10, 160, 130)];
  const source = [line('星期一', 180, 10, 260), line('星期二', 180, 60, 260), line('星期三', 180, 110, 260), line('星期四', 180, 160, 260)];
  const duplicated = [...source, line('星期二', 181, 61, 261)];
  const paired = pairEnglishOcrLines(english, duplicated);
  assert.equal(recoverMissingEnglishOcrEntries(english, duplicated, paired).filter(entry => !entry.text).length, 1);
  const complete = [english[0]!, line('Tuesday', 10, 60, 125), ...english.slice(1)];
  const completePairs = pairEnglishOcrLines(complete, duplicated);
  assert.deepEqual(recoverMissingEnglishOcrEntries(complete, duplicated, completePairs), completePairs);
});

test('双栏独立恢复、逆序锚点和不匹配数量都不扰动原英文配对', () => {
  const english = [line('Monday', 10, 10, 120), line('Wednesday', 10, 110, 140), line('Thursday', 10, 160, 130),
    line('apple', 500, 10, 600), line('pear', 500, 60, 600), line('banana', 500, 110, 610)];
  const source = [line('星期一', 180, 10, 260), line('星期二', 180, 60, 260), line('星期三', 180, 110, 260), line('星期四', 180, 160, 260),
    line('苹果', 660, 10, 720), line('梨', 660, 60, 690), line('香蕉', 660, 110, 720)];
  const paired = pairEnglishOcrLines(english, source);
  assert.deepEqual(recoverMissingEnglishOcrEntries(english, source, paired).map(entry => entry.text), ['Monday', '', 'Wednesday', 'Thursday', 'apple', 'pear', 'banana']);
  const reversed = [...english.slice(0, 3)].reverse();
  const reversedPairs = pairEnglishOcrLines(reversed, source);
  assert.deepEqual(recoverMissingEnglishOcrEntries(reversed, source, reversedPairs), reversedPairs);
  assert.deepEqual(recoverMissingEnglishOcrEntries(english, source, paired.slice(1)), paired.slice(1));
});
