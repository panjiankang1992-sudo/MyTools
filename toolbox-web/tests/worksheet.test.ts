import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import fontkit from '@pdf-lib/fontkit';
import { PDFDocument, PDFRawStream, PDFName, decodePDFRawStream } from 'pdf-lib';
import type { WordEntry, WorksheetMode, WorksheetOptions } from '../src/types';
import { sampleEntries } from '../src/data/worksheet-samples';
import { embedChineseFont } from '../src/lib/pdf-runtime';
import {
  CONTENT_BOTTOM, CONTENT_TOP, PAGE_MARGIN, PAGE_WIDTH,
  deduplicateEntries, layoutWorksheet, projectWorksheet, selectEntries, wrapText,
} from '../src/lib/worksheet';

const defaults: WorksheetOptions = { mode: 'chinese-dictation', title: 'Practice', grade: 2, gridMm: 14, repetitions: 1, answer: false };
const measure = (text: string, size: number): number => Array.from(text).length * size;

test('all four modes remove answers before student layout and preserve them only for separate answer projection', () => {
  const modes: WorksheetMode[] = ['chinese-dictation', 'chinese-pinyin', 'english-dictation', 'english-meaning'];
  const entries: WordEntry[] = [{ id: 'secret-answer-in-id', text: '\u4e66\u5305', pinyin: 'sh\u016b b\u0101o', meaning: '', unit: 1, kind: 'word' }, { id: 'english-id', text: 'watermelon', pinyin: '', meaning: '\u897f\u74dc', unit: 1, kind: 'word' }];
  for (const mode of modes) {
    const entry = entries[mode.startsWith('chinese') ? 0 : 1]!;
    const student = projectWorksheet([entry], { ...defaults, mode })[0]!;
    const answer = projectWorksheet([entry], { ...defaults, mode, answer: true })[0]!;
    const expectedPrompt = mode === 'chinese-dictation' ? entry.pinyin : mode === 'english-dictation' ? entry.meaning : entry.text;
    const expectedAnswer = mode === 'chinese-pinyin' ? entry.pinyin : mode === 'english-meaning' ? entry.meaning : entry.text;
    assert.equal(student.prompt, expectedPrompt);
    assert.equal(answer.answerText, expectedAnswer);
    assert.equal(Object.hasOwn(student, 'answerText'), false);
    assert.equal(JSON.stringify(student).includes(expectedAnswer), false);
    assert.equal(JSON.stringify(student).includes(entry.id), false);
    assert.equal(student.number, answer.number);
  }
});

test('repeat numbering remains identical between student and answer sheets', () => {
  const entries = sampleEntries('chinese').slice(0, 8);
  const student = projectWorksheet(entries, { ...defaults, repetitions: 3 });
  const answer = projectWorksheet(entries, { ...defaults, repetitions: 3, answer: true });
  assert.equal(student.length, 24);
  assert.deepEqual(student.map((item) => [item.number, item.prompt]), answer.map((item) => [item.number, item.prompt]));
  assert.equal(student[0]!.prompt, student[2]!.prompt);
  assert.notEqual(student[2]!.prompt, student[3]!.prompt);
});

test('seeded selection is reproducible, without replacement, and respects input limits', () => {
  const entries = sampleEntries('english');
  const first = selectEntries(entries, 12, 'stable-seed');
  assert.deepEqual(first, selectEntries(entries, 12, 'stable-seed'));
  assert.notDeepEqual(first, selectEntries(entries, 12, 'other-seed'));
  assert.equal(new Set(first.map((entry) => entry.id)).size, first.length);
  assert.equal(selectEntries([...entries, ...entries], 100, 1).length, 24);
  assert.equal(selectEntries(entries, 0, 1).length, 0);
  assert.deepEqual(entries, sampleEntries('english'));
});

test('deduplication normalizes whitespace and Unicode while retaining case and contextual meanings', () => {
  const base: WordEntry = { id: 'one', text: ' apple ', meaning: '\u82f9\u679c', pinyin: '', kind: 'word', unit: 1 };
  const result = deduplicateEntries([
    base, { ...base, id: 'two', text: 'apple' }, { ...base, id: 'three', text: 'Apple' },
    { ...base, id: 'four', meaning: '\u82f9\u679c\u6811' },
  ]);
  assert.equal(result.length, 3);
  assert.equal(result[0]!.id, 'one');
  const chinese = sampleEntries('chinese')[0]!;
  assert.equal(deduplicateEntries([chinese, { ...chinese, pinyin: chinese.pinyin.normalize('NFD') }]).length, 1);
});

test('pagination keeps every complete question and writing area inside A4 margins', () => {
  const entries = Array.from({ length: 100 }, (_, index) => ({ ...sampleEntries('chinese')[index % 24]!, id: `item-${index}` }));
  const options = { ...defaults, repetitions: 5 };
  const questions = projectWorksheet(entries, options);
  const pages = layoutWorksheet(questions, options, measure);
  assert.ok(pages.length > 1);
  assert.equal(pages.flatMap((page) => page.questions).length, 500);
  assert.deepEqual(pages.flatMap((page) => page.questions.map((item) => item.question.number)), Array.from({ length: 500 }, (_, index) => index + 1));
  for (const page of pages) {
    for (const question of page.questions) {
      assert.ok(question.top >= CONTENT_TOP);
      assert.ok(question.top + question.height <= CONTENT_BOTTOM);
      assert.ok(question.x >= PAGE_MARGIN);
      assert.ok(question.x + question.width <= PAGE_WIDTH - PAGE_MARGIN + 0.01);
    }
  }
});

test('long entries expand to a full row and wrap while excessive single questions fail clearly', () => {
  const entry: WordEntry = { id: 'long', text: 'a very long English phrase for writing practice', pinyin: '', meaning: '\u8fd9\u662f\u4e00\u4e2a\u8f83\u957f\u7684\u82f1\u8bed\u77ed\u8bed\u7ec3\u4e60', unit: 1, kind: 'phrase' };
  const options: WorksheetOptions = { ...defaults, mode: 'english-meaning' };
  const layout = layoutWorksheet(projectWorksheet([entry], options), options, measure)[0]!.questions[0]!;
  assert.equal(layout.width, PAGE_WIDTH - PAGE_MARGIN * 2);
  assert.ok(layout.promptLines.length > 1);
  const hugeChinese = { ...sampleEntries('chinese')[0]!, text: '\u5c71'.repeat(200), pinyin: 'sh\u0101n '.repeat(200) };
  assert.throws(() => layoutWorksheet(projectWorksheet([hugeChinese], defaults), defaults, measure), /ITEM_TOO_LARGE/);
});

// 本用例在 2026-10-03 字帖改造中更新：原实现断言「单字固定 4 列、词语固定 2 列」，
// 该规则已被契约 §6.1/§6.2 废止（改为按真实编号宽度与逐字列距顺序装行），
// 因此断言改为验证新的装行不变量，而不是删除用例。
test('单字与词语按真实宽度顺序装行，不再使用固定列数', () => {
  const characters = sampleEntries('chinese').filter((entry) => entry.kind === 'character').slice(0, 8);
  const page = layoutWorksheet(projectWorksheet(characters, defaults), defaults, measure)[0]!;
  const firstTop = page.questions[0]!.top;
  const firstRow = page.questions.filter((item) => item.top === firstTop);
  // 旧规则固定 4 列；新规则下首行容量由真实宽度决定，必须超过 4 项。
  assert.ok(firstRow.length > 4, `首行只放下 ${firstRow.length} 项，未体现紧凑装行`);
  // 同行内 x 必须递增且题块互不重叠。
  for (let index = 1; index < firstRow.length; index += 1) {
    const previous = firstRow[index - 1]!;
    const current = firstRow[index]!;
    assert.ok(current.x > previous.x, '同行内题块必须按顺序向右排列');
    assert.ok(current.x >= previous.x + previous.width - 0.01, '同行题块不得重叠');
  }
  // 每个题块都必须在内容区内，且题号区宽度按实测编号宽度计算。
  for (const item of page.questions) {
    assert.ok(item.x >= PAGE_MARGIN - 0.01, '题块越出左边界');
    assert.ok(item.x + item.width <= PAGE_WIDTH - PAGE_MARGIN + 0.01, '题块越出右边界');
    assert.ok(item.numberWidth > 0 && item.numberWidth < item.width, '题号区宽度必须实测且小于题块宽度');
    assert.equal(item.cellPitches.length, item.question.cells, '每个书写单元都要有列距');
  }
});

test('Unicode supplementary characters remain a single grid cell and are not split during wrapping', () => {
  const entry: WordEntry = { id: 'unicode', text: '\u{20000}', pinyin: 'h\u0113', meaning: '', unit: 1, kind: 'character' };
  assert.equal(projectWorksheet([entry], defaults)[0]!.cells, 1);
  assert.deepEqual(wrapText('\u{20000}\u{20001}', 12, measure), ['\u{20000}', '\u{20001}']);
});

test('invalid, empty, and excessive worksheets are rejected before font loading', () => {
  const entries = sampleEntries('chinese');
  assert.throws(() => projectWorksheet([], defaults), /EMPTY_WORKSHEET/);
  assert.throws(() => projectWorksheet(entries, { ...defaults, repetitions: 0 }), /INVALID_OPTIONS/);
  assert.throws(() => projectWorksheet(entries, { ...defaults, gridMm: NaN }), /INVALID_OPTIONS/);
  assert.throws(() => projectWorksheet([{ ...entries[0]!, pinyin: '' }], defaults), /INCOMPLETE_ENTRY/);
  assert.throws(() => projectWorksheet(Array.from({ length: 101 }, () => entries[0]!), { ...defaults, repetitions: 5 }), /TOO_MANY_ITEMS/);
});

test('demonstration data has three units per subject and is independently editable', () => {
  for (const subject of ['chinese', 'english'] as const) {
    const entries = sampleEntries(subject);
    assert.equal(entries.length, 24);
    assert.deepEqual([...new Set(entries.map((entry) => entry.unit))], [1, 2, 3]);
    entries[0]!.text = 'changed';
    assert.notEqual(sampleEntries(subject)[0]!.text, 'changed');
  }
});

test('embedded CFF subset has a valid header and correct dictionary mappings after alternating Chinese and accented Latin', async () => {
  const sourceBytes = new Uint8Array(await readFile(new URL('../public/fonts/NotoSansCJKsc-Regular.otf', import.meta.url)));
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(sourceBytes);
  try {
    const document = await PDFDocument.create();
    const font = await embedChineseFont(document);
    const text = 'a\u6625\u016bb\u98ce\u01d0c\u5b57\u01ce';
    const encoded = font.encodeText(text).asBytes();
    document.addPage().drawText(text, { font, size: 12 });
    const loaded = await PDFDocument.load(await document.save());
    const stream = loaded.context.enumerateIndirectObjects().map(([, value]) => value)
      .find((value): value is PDFRawStream => value instanceof PDFRawStream && value.dict.get(PDFName.of('Subtype'))?.toString() === '/CIDFontType0C');
    assert.ok(stream);
    const data = decodePDFRawStream(stream).decode();
    assert.ok(data[3]! >= 1 && data[3]! <= 4, 'CFF offSize must be in its defined one-to-four byte range');

    /** 独立读取标准 CFF 索引，用序列化结果检验嵌入字体。 */
    const readIndex = (offset: number): { next: number; values: Uint8Array[] } => {
      const count = data[offset]! * 256 + data[offset + 1]!;
      if (!count) return { next: offset + 2, values: [] };
      const size = data[offset + 2]!;
      const offsets = Array.from({ length: count + 1 }, (_, item) => {
        let result = 0;
        for (let byte = 0; byte < size; byte += 1) result = result * 256 + data[offset + 3 + item * size + byte]!;
        return result;
      });
      const start = offset + 3 + (count + 1) * size - 1;
      return { next: start + offsets[count]!, values: offsets.slice(0, count).map((value, item) => data.slice(start + value, start + offsets[item + 1]!)) };
    };
    const names = readIndex(data[2]!);
    const top = readIndex(names.next).values[0]!;
    const operators = new Map<number, number[]>();
    let operands: number[] = [];
    for (let offset = 0; offset < top.length; offset += 1) {
      const byte = top[offset]!;
      if (byte <= 21) {
        operators.set(byte === 12 ? 1200 + top[++offset]! : byte, operands);
        operands = [];
      } else if (byte === 28) {
        const value = top[++offset]! * 256 + top[++offset]!;
        operands.push(value > 32767 ? value - 65536 : value);
      } else if (byte === 29) {
        let value = 0;
        for (let index = 0; index < 4; index += 1) value = value * 256 + top[++offset]!;
        operands.push(value > 2147483647 ? value - 4294967296 : value);
      } else if (byte === 30) {
        // 本测试只需要整数偏移；按规范跳过实数字段的半字节编码。
        let value: number;
        do { value = top[++offset]!; } while ((value & 15) !== 15 && value >> 4 !== 15);
        operands.push(0);
      } else if (byte <= 246) operands.push(byte - 139);
      else if (byte <= 250) operands.push((byte - 247) * 256 + top[++offset]! + 108);
      else if (byte <= 254) operands.push(-(byte - 251) * 256 - top[++offset]! - 108);
    }
    const selectOffset = operators.get(1237)![0]!;
    assert.equal(data[selectOffset], 0);
    const source = fontkit.create(sourceBytes) as unknown as {
      layout(value: string): { glyphs: { id: number }[] };
      'CFF ': { fdForGlyph(id: number): number };
    };
    const dictionaryIndices = new Map<number, number>([[source['CFF '].fdForGlyph(0), 0]]);
    source.layout(text).glyphs.forEach((glyph, index) => {
      const originalDictionary = source['CFF '].fdForGlyph(glyph.id);
      if (!dictionaryIndices.has(originalDictionary)) dictionaryIndices.set(originalDictionary, dictionaryIndices.size);
      const subsetId = encoded[index * 2]! * 256 + encoded[index * 2 + 1]!;
      assert.equal(data[selectOffset + 1 + subsetId], dictionaryIndices.get(originalDictionary), `Glyph ${index} must retain its own private dictionary`);
    });
  } finally {
    globalThis.fetch = previousFetch;
  }
});

// ---------------------------------------------------------------- 阻断条件只看当前输出对象
test('英语：中文释义练习的学生卷不需要义项，英语默写的学生卷需要', () => {
  const entries = [{ id: 'e1', text: 'apple', pinyin: '', meaning: '', unit: 1, kind: 'word' as const }];
  // 看英文写中文：题面是英文，学生卷无需义项。
  assert.doesNotThrow(() => projectWorksheet(entries, {
    mode: 'english-meaning', title: 't', grade: 3, gridMm: 12, repetitions: 1, answer: false
  }));
  // 看中文写英文：题面是中文义项，学生卷必须要有义项。
  assert.throws(() => projectWorksheet(entries, {
    mode: 'english-dictation', title: 't', grade: 3, gridMm: 12, repetitions: 1, answer: false
  }), /INCOMPLETE_ENTRY/);
  // 两种答案卷都必须保留中文与英文。
  assert.throws(() => projectWorksheet(entries, {
    mode: 'english-dictation', title: 't', grade: 3, gridMm: 12, repetitions: 1, answer: true
  }), /INCOMPLETE_ENTRY/);
  assert.throws(() => projectWorksheet(entries, {
    mode: 'english-meaning', title: 't', grade: 3, gridMm: 12, repetitions: 1, answer: true
  }), /INCOMPLETE_ENTRY/);
});

test('中文：拼音字帖的学生卷不需要读音，汉字字帖的学生卷需要', () => {
  const entries = [{ id: 'c1', text: '山', pinyin: '', meaning: '', unit: 1, kind: 'character' as const }];
  // 看汉字写拼音：题面是汉字，学生卷无需读音。
  assert.doesNotThrow(() => projectWorksheet(entries, {
    mode: 'chinese-pinyin', title: 't', grade: 1, gridMm: 12, repetitions: 1, answer: false
  }));
  // 看拼音写汉字：题面是拼音，学生卷必须有读音。
  assert.throws(() => projectWorksheet(entries, {
    mode: 'chinese-dictation', title: 't', grade: 1, gridMm: 12, repetitions: 1, answer: false
  }), /INCOMPLETE_ENTRY/);
  // 两种答案卷都必须保留汉字与拼音。
  assert.throws(() => projectWorksheet(entries, {
    mode: 'chinese-dictation', title: 't', grade: 1, gridMm: 12, repetitions: 1, answer: true
  }), /INCOMPLETE_ENTRY/);
  assert.throws(() => projectWorksheet(entries, {
    mode: 'chinese-pinyin', title: 't', grade: 1, gridMm: 12, repetitions: 1, answer: true
  }), /INCOMPLETE_ENTRY/);
});

// ---------------------------------------------------------------- W38：四模式与 500 题边界
test('组合上限：200 项 ×2 次 = 400 题可通过，超过 500 题必须阻断', () => {
  const make = (count: number) => Array.from({ length: count }, (_, index) => ({
    id: `e${index}`, text: '山', pinyin: 'shān', meaning: '', unit: 1, kind: 'character' as const
  }));
  const base = { title: 't', grade: 1, gridMm: 12 } as const;
  // 200 × 2 = 400 题：在上限内。
  assert.doesNotThrow(() => projectWorksheet(make(200), { ...base, mode: 'chinese-dictation', repetitions: 2, answer: false }));
  // 200 × 3 = 600 题：超过 500。
  assert.throws(() => projectWorksheet(make(200), { ...base, mode: 'chinese-dictation', repetitions: 3, answer: false }), /TOO_MANY_ITEMS/);
  // 201 项：超过 200 项上限。
  assert.throws(() => projectWorksheet(make(201), { ...base, mode: 'chinese-dictation', repetitions: 1, answer: false }), /TOO_MANY_ITEMS/);
});

test('四种模式都能产出题目且题号连续', () => {
  const chinese = [{ id: 'c1', text: '山', pinyin: 'shān', meaning: '', unit: 1, kind: 'character' as const }];
  const english = [{ id: 'e1', text: 'apple', pinyin: '', meaning: '苹果', unit: 1, kind: 'word' as const }];
  const cases = [
    ['chinese-pinyin', chinese], ['chinese-dictation', chinese],
    ['english-dictation', english], ['english-meaning', english]
  ] as const;
  for (const [mode, entries] of cases) {
    for (const answer of [false, true]) {
      const questions = projectWorksheet(entries, { mode, title: 't', grade: 1, gridMm: 12, repetitions: 1, answer });
      assert.equal(questions.length, 1, `${mode} 答案卷=${answer}`);
      assert.equal(questions[0].number, 1);
      // 学生卷不携带答案；答案卷必须带上答案文本。
      if (answer) assert.ok((questions[0].answerText ?? '').length > 0, `${mode} 答案卷应有答案`);
      else assert.equal(questions[0].answerText, undefined, `${mode} 学生卷不应带答案`);
    }
  }
});
