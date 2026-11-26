import assert from 'node:assert/strict';
import test from 'node:test';
import type { TextMeasure } from '../src/lib/worksheet';
import {
  LAYOUT_DEFAULTS, MM_TO_PT, checkQuestionHeights, firstRowCount, fourLineHeightMm,
  foldQuestion, layoutQuestions, measureQuestion, numberWidthMm, packRows, paginate,
  type LayoutParams, type LayoutQuestion
} from '../src/lib/worksheet-layout-v2';

/**
 * 标定过的测量桩：按契约 §6.3 给出的真实字体实测值反推每字符宽度，
 * 使 9 pt 的 `1.` 为 3.444775 mm、`10.` 为 5.2069 mm，从而可以核对契约里的密度数字。
 */
const measure: TextMeasure = (text, size) => {
  const scale = size / 9;
  let total = 0;
  for (const character of text) {
    if (character === '.') total += 2.5025 * scale;
    else if (character >= '0' && character <= '9') total += 4.9949 * scale;
    else if (/\p{Script=Han}/u.test(character)) total += size;
    else total += size * 0.4;
  }
  return total;
};

/** 参数覆盖。 */
function params(overrides: Partial<LayoutParams> = {}): LayoutParams {
  return { ...LAYOUT_DEFAULTS, ...overrides };
}

/** 构造单字题项。 */
function single(number: number, text: string, syllable: string): LayoutQuestion {
  return { number, kind: 'mi-grid', prompt: '', segments: [{ grapheme: text, syllable }] };
}

const SINGLE_HANZI = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '山', '水'];
const SINGLE_PINYIN = ['yī', 'èr', 'sān', 'sì', 'wǔ', 'liù', 'qī', 'bā', 'jiǔ', 'shí', 'shān', 'shuǐ'];

test('测量桩与契约实测值一致：9pt 的 1. 为 3.444775 mm，10. 为 5.2069 mm', () => {
  const p = params();
  // 测量桩按契约实测值反推，存在标定舍入，用容差比较。
  assert.ok(Math.abs(numberWidthMm(1, p, measure) - 3.444775) < 0.001, `1. => ${numberWidthMm(1, p, measure)}`);
  assert.ok(Math.abs(numberWidthMm(10, p, measure) - 5.2069) < 0.001, `10. => ${numberWidthMm(10, p, measure)}`);
  // 题号区不得小于 3 mm。
  assert.equal(numberWidthMm(1, params({ numberFontPt: 1 }), measure), 3);
});

test('汉字字帖：12 mm 米字格短读音，首页首行容纳 10 项，宽度约 178.71 mm', () => {
  const questions = SINGLE_HANZI.map((text, index) => single(index + 1, text, SINGLE_PINYIN[index]!));
  const outcome = layoutQuestions(questions, params(), measure);
  const firstRow = outcome.pages[0]!.rows[0]!;
  assert.equal(firstRow.boxes.length, 10, '契约 §6.3：首行应容纳 10 项');
  assert.equal(Number(firstRow.widthMm.toFixed(2)), 178.71, '契约 §6.3 的实测值');
  assert.ok(firstRow.widthMm <= 180, '不得超出 180 mm 内容宽');
  // 第二行是剩下的两项。
  assert.equal(outcome.pages[0]!.rows[1]!.boxes.length, 2);
});

test('汉字字帖：12 mm 米字格双字，首页首行容纳 6 项，宽度约 177.17 mm', () => {
  const words: Array<[string, string, string]> = [
    ['大山', 'dà', 'shān'], ['小河', 'xiǎo', 'hé'], ['白云', 'bái', 'yún'],
    ['红花', 'hóng', 'huā'], ['小手', 'xiǎo', 'shǒu'], ['大地', 'dà', 'dì']
  ];
  const questions = words.map(([word, first, second], index) => ({
    number: index + 1,
    kind: 'mi-grid' as const,
    prompt: '',
    segments: [
      { grapheme: [...word][0]!, syllable: first },
      { grapheme: [...word][1]!, syllable: second }
    ]
  }));
  const outcome = layoutQuestions(questions, params(), measure);
  const firstRow = outcome.pages[0]!.rows[0]!;
  assert.equal(firstRow.boxes.length, 6, '契约 §6.3：双字首行应容纳 6 项');
  assert.equal(Number(firstRow.widthMm.toFixed(2)), 177.17);
});

test('拼音字帖：首页首行至少 8 项，宽度约 173.06 mm', () => {
  const questions = SINGLE_HANZI.map((text, index) => ({
    number: index + 1,
    kind: 'pinyin-cell' as const,
    prompt: text,
    segments: []
  }));
  const outcome = layoutQuestions(questions, params(), measure);
  const firstRow = outcome.pages[0]!.rows[0]!;
  assert.ok(firstRow.boxes.length >= 8, `契约 §6.3：拼音字帖首行至少 8 项，实际 ${firstRow.boxes.length}`);
  assert.equal(firstRow.boxes.length, 8);
  assert.equal(Number(firstRow.widthMm.toFixed(2)), 173.06);
});

test('项间距 3 mm 时 10 个单字放不下，证明 2.5 mm 不是随意取值', () => {
  const questions = SINGLE_HANZI.map((text, index) => single(index + 1, text, SINGLE_PINYIN[index]!));
  const outcome = layoutQuestions(questions, params({ itemGapMm: 3 }), measure);
  const firstRow = outcome.pages[0]!.rows[0]!;
  // 契约 §6.3：3 mm 间距下 10 个单字约 183.21 mm，放不进 180 mm。
  assert.equal(firstRow.boxes.length, 9, '3 mm 间距下首行只能容纳 9 项');
  const loose = layoutQuestions(questions, params({ itemGapMm: 6 }), measure);
  assert.ok(loose.pages[0]!.rows[0]!.boxes.length < firstRow.boxes.length, '间距越大每行越少');
});

test('米字格列距随拼音变宽，但格子本身不放大', () => {
  const short = measureQuestion(single(1, '山', 'shān'), params(), measure);
  assert.equal(short.pitchesMm[0], 12, '短读音时列距等于格距');
  // 很长的音节必须把列距撑开，而不是缩小格子。
  const long = measureQuestion(single(2, '双', 'shuāngchuāng'), params(), measure);
  assert.ok(long.pitchesMm[0]! > 12, '长音节必须扩宽列距');
  assert.equal(long.widthMm - long.numberWidthMm, long.pitchesMm[0], '格子宽度不因长音节变大变小');
});

test('拼音书写区每字至少 16 mm，不因换模式缩水', () => {
  const box = measureQuestion({ number: 1, kind: 'pinyin-cell', prompt: '一', segments: [] }, params(), measure);
  assert.equal(box.pitchesMm[0], 16);
  assert.equal(Number(box.heightMm.toFixed(6)), Number((14 / MM_TO_PT * 1.2 + fourLineHeightMm(params())).toFixed(6)));
  assert.equal(fourLineHeightMm(params()), 9, '四线三格总高 9 mm');
});

test('装行保持顺序，不从后面找短项塞空隙', () => {
  const questions: LayoutQuestion[] = [
    { number: 1, kind: 'english-line', prompt: 'a very long chinese prompt that is wide', segments: [] },
    { number: 2, kind: 'english-line', prompt: '短', segments: [] },
    { number: 3, kind: 'english-line', prompt: '也短', segments: [] }
  ];
  const boxes = questions.map(question => measureQuestion(question, params(), measure));
  const { rows } = packRows(boxes, { contentWidthMm: 180, params: params() });
  // 顺序必须严格保持：行内题号递增，且不得为了塞满而打乱次序。
  const numbers = rows.flatMap(row => row.boxes.map(box => box.question.number));
  assert.deepEqual(numbers, [1, 2, 3], '装行不得改变题目顺序');
  for (const row of rows) {
    const inRow = row.boxes.map(box => box.question.number);
    assert.deepEqual(inRow, [...inRow].sort((left, right) => left - right), '行内顺序也必须递增');
  }
  assert.equal(rows[0]!.boxes[0]!.question.number, 1, '第一项必须在第一行第一位');
});

// 本用例在 2026-10-03 字帖改造中更新：原实现断言「单题超宽一律报错」，
// 契约 §6.2.7 改为「按书写单元折为同一题的续行组，仅首行编号」，
// 因此断言改为验证折行不变量，并保留「无法折叠时才报错」的那一半。
test('单题超宽折为同一题的续行组，仅首行编号且不越界', () => {
  const wide: LayoutQuestion = {
    number: 1, kind: 'mi-grid', prompt: '',
    segments: Array.from({ length: 20 }, (_item, index) => ({ grapheme: '字', syllable: `shuāng${index}` }))
  };
  const box = measureQuestion(wide, params(), measure);
  const lines = foldQuestion(box, 180);
  assert.ok(lines.length > 1, '20 个长音节必须折行');
  assert.equal(lines[0]!.numbered, true, '只有首行编号');
  assert.ok(lines.slice(1).every(line => line.numbered === false), '续行不得重复题号');
  // 折行不丢单元，也不重复。
  assert.equal(lines.reduce((sum, line) => sum + line.pitchesMm.length, 0), 20);
  assert.deepEqual(lines.map(line => line.cellOffset), [0, lines[0]!.pitchesMm.length]);
  // 每一行都必须落在内容区内。
  for (const line of lines) assert.ok(line.widthMm <= 180 + 1e-9, `折行后仍超宽：${line.widthMm}`);
});

test('单个书写单元就超宽时无法折叠，定位报错并阻断', () => {
  const impossible: LayoutQuestion = {
    number: 1, kind: 'mi-grid', prompt: '',
    segments: [{ grapheme: '字', syllable: 'shuāng'.repeat(40) }]
  };
  const outcome = layoutQuestions([impossible], params(), measure);
  assert.equal(outcome.blocked, true);
  assert.ok(outcome.notices.some(notice => notice.code === 'WORKSHEET_LAYOUT_OVERFLOW'));
});

test('分页不产生多余空白尾页，行不跨页', () => {
  // 单页可写高度约 235 mm，每行约 16.2 mm、10 项，约 13 行/页；用 200 项确保跨页。
  const questions = Array.from({ length: 200 }, (_item, index) => single(index + 1, '字', 'zì'));
  const outcome = layoutQuestions(questions, params(), measure);
  assert.ok(outcome.pages.length >= 2, `200 项必须跨页，实际 ${outcome.pages.length} 页`);
  const lastPage = outcome.pages.at(-1)!;
  assert.ok(lastPage.rows.length > 0, '最后一页不得为空');
  // 每一页的用量都要落在可写高度内。
  const usable = params().pageHeightMm - params().marginMm * 2 - params().headerMm - params().footerMm;
  for (const page of outcome.pages) {
    assert.ok(page.usedHeightMm <= usable + 1e-9, `第 ${page.index + 1} 页超出可写高度`);
  }
});

test('同一题的续行不被分到两页', () => {
  // 可写高度约 235 mm：先放一道 120 mm 的题，再放「一题两行」共 233 mm。
  // 若按行独立分页，第二行会被挤到下一页；按整组分页则两行必须同页。
  const makeBox = (number: number) => measureQuestion(single(number, '字', 'zì'), params(), measure);
  const rows = [
    { boxes: [makeBox(1)], widthMm: 0, heightMm: 120 },
    { boxes: [makeBox(2)], widthMm: 0, heightMm: 120 },
    { boxes: [makeBox(2)], widthMm: 0, heightMm: 110 }
  ];
  const pages = paginate(rows, params());
  const pageOf = (rowIndex: number) => pages.findIndex(page => page.rows.includes(rows[rowIndex]!));
  assert.equal(pageOf(1), pageOf(2), '同一题的两行必须在同一页');
  assert.notEqual(pageOf(1), pageOf(0), '放不下时应整组换页');
});

test('单题高度超过可写区时定位报错', () => {
  const tall = measureQuestion(single(1, '字', 'zì'), params({ gridMm: 400 }), measure);
  const problems = checkQuestionHeights([tall], params({ gridMm: 400 }));
  assert.equal(problems.length, 1);
  assert.equal(problems[0]!.code, 'WORKSHEET_LAYOUT_QUESTION_TOO_TALL');
  assert.equal(problems[0]!.questionNumber, 1);
});

test('默认参数即契约值：紧凑、2.5/3 mm、米字格 12 mm、页边距 15 mm', () => {
  assert.equal(LAYOUT_DEFAULTS.density, 'compact');
  assert.equal(LAYOUT_DEFAULTS.itemGapMm, 2.5);
  assert.equal(LAYOUT_DEFAULTS.rowGapMm, 3);
  assert.equal(LAYOUT_DEFAULTS.gridMm, 12);
  assert.equal(LAYOUT_DEFAULTS.marginMm, 15);
  assert.equal(LAYOUT_DEFAULTS.pinyinCellMinMm, 16);
  assert.equal(LAYOUT_DEFAULTS.pageWidthMm - LAYOUT_DEFAULTS.marginMm * 2, 180);
  assert.equal(Number((25.4 * MM_TO_PT).toFixed(6)), 72, '毫米换算必须精确');
});

test('分页只依赖行高，行内项数不被分页改变', () => {
  const questions = Array.from({ length: 10 }, (_item, index) => single(index + 1, '字', 'zì'));
  const rows = [{
    boxes: questions.map(question => measureQuestion(question, params(), measure)),
    widthMm: 0,
    heightMm: 20
  }];
  const pages = paginate(rows, params());
  assert.equal(pages.length, 1);
  assert.equal(pages[0]!.rows[0]!.boxes.length, 10);
  assert.equal(firstRowCount({ pages, notices: [], blocked: false }), 10);
});
