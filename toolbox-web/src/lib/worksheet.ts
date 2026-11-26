import { PDFDocument, rgb, type PDFFont, type PDFPage } from 'pdf-lib';
import type { WordEntry, WorksheetArtifact, WorksheetMode, WorksheetOptions } from '../types';
import { embedChineseFont } from './pdf-runtime';
import { LAYOUT_DEFAULTS, foldQuestion, fourLineHeightMm, measureQuestion, packRows, paginate, type LayoutQuestion } from './worksheet-layout-v2';
import { isValidPinyinSyllable, splitGraphemes } from '../domain/worksheet-processing';

export const MM_TO_PT = 72 / 25.4;
export const PAGE_WIDTH = 210 * MM_TO_PT;
export const PAGE_HEIGHT = 297 * MM_TO_PT;
export const PAGE_MARGIN = 15 * MM_TO_PT;
export const CONTENT_TOP = PAGE_MARGIN + 22 * MM_TO_PT;
export const CONTENT_BOTTOM = PAGE_HEIGHT - PAGE_MARGIN - 10 * MM_TO_PT;
const CONTENT_WIDTH = PAGE_WIDTH - PAGE_MARGIN * 2;
const FONT_SIZE = 12;
const LINE_HEIGHT = 18;

/** 学生投影不包含词条标识、原始词条或任何答案文本。 */
export interface ProjectedQuestion {
  number: number;
  prompt: string;
  writing: 'tian' | 'four-line' | 'underline';
  cells: number;
  segments: string[];
  answerText?: string;
  wide: boolean;
}

export interface QuestionLayout {
  question: ProjectedQuestion;
  x: number;
  top: number;
  width: number;
  height: number;
  promptLines: string[];
  answerLines: string[];
  gridColumns: number;
  gridRows: number;
  gridTop: number;
  cellPitch: number;
  /** 题号区实际宽度（pt）。真实编号宽度参与装行，不能给每题固定一整列。 */
  numberWidth: number;
  /** 每个书写单元的列距（pt），长度等于书写单元数；米字格固定边长居中于列距内。 */
  cellPitches: number[];
  /** 是否为该题首行；续行不再重复题号。 */
  numbered: boolean;
  segmented: boolean;
}

export interface WorksheetPageLayout {
  questions: QuestionLayout[];
}

export type TextMeasure = (text: string, fontSize: number) => number;

/** 去掉首尾空白并统一组合字符，保持字母大小写与实际读音。 */
export function normalizeEntry(entry: WordEntry): WordEntry {
  const normalize = (value: string): string => value.normalize('NFC').trim().replace(/\s+/gu, ' ');
  return { ...entry, text: normalize(entry.text), pinyin: normalize(entry.pinyin), meaning: normalize(entry.meaning) };
}

/** 仅合并内容、读音与释义相同的词条，保留第一次出现的顺序。 */
export function deduplicateEntries(entries: WordEntry[]): WordEntry[] {
  const seen = new Set<string>();
  return entries.map(normalizeEntry).filter((entry) => {
    const key = JSON.stringify([entry.text, entry.pinyin, entry.meaning]);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** 使用稳定种子进行无放回抽题，数量不足时返回全部可用题目。 */
export function selectEntries(entries: WordEntry[], count: number, seed: string | number): WordEntry[] {
  const pool = deduplicateEntries(entries);
  let state = 2166136261;
  for (const character of String(seed)) state = Math.imul(state ^ character.codePointAt(0)!, 16777619) >>> 0;
  const random = (): number => {
    state += 0x6D2B79F5;
    let value = Math.imul(state ^ (state >>> 15), 1 | state);
    value ^= value + Math.imul(value ^ (value >>> 7), 61 | value);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
  for (let index = pool.length - 1; index > 0; index -= 1) {
    // 固定输入顺序和种子时，洗牌与抽题结果完全一致。
    const target = Math.floor(random() * (index + 1));
    [pool[index], pool[target]] = [pool[target]!, pool[index]!];
  }
  return pool.slice(0, Math.max(0, Math.floor(count)));
}

/** 将词条投影为独立学生题面或答案页，答案在进入排版器前完成隔离。 */
export function projectWorksheet(entries: WordEntry[], options: WorksheetOptions): ProjectedQuestion[] {
  const modes: WorksheetMode[] = ['chinese-dictation', 'chinese-pinyin', 'english-dictation', 'english-meaning'];
  if (!modes.includes(options.mode) || !Number.isInteger(options.repetitions)
    || options.repetitions < 1 || options.repetitions > 5
    || !Number.isFinite(options.gridMm) || options.gridMm < 10 || options.gridMm > 18) {
    throw new Error('INVALID_OPTIONS');
  }
  if (!entries.length) throw new Error('EMPTY_WORKSHEET');
  // 新流程传入最终题序，选入项上限已由处理层验证，不能再次重复或截成 200 题。
  if (options.expandedQuestions && options.repetitions !== 1) throw new Error('INVALID_OPTIONS');
  if ((!options.expandedQuestions && entries.length > 200) || entries.length * options.repetitions > 500) throw new Error('TOO_MANY_ITEMS');
  const result: ProjectedQuestion[] = [];
  for (const rawEntry of entries) {
    const entry = normalizeEntry(rawEntry);
    const chinese = options.mode.startsWith('chinese');
    // 契约 §3：阻断条件只取**当前输出对象**所需字段。
    // 汉字字帖的学生卷以拼音为题面、答案卷以汉字为答案；拼音字帖相反。
    const needsPinyin = chinese && (options.mode === 'chinese-dictation' || options.answer);
    // 英语默写的学生卷以中文义项为题面；中文释义练习的答案卷以义项为答案。
    const needsMeaning = !chinese && (options.mode === 'english-dictation' || options.answer);
    const needsText = options.answer || options.mode === 'chinese-pinyin' || options.mode === 'english-meaning';
    if ((needsText && !entry.text) || (needsPinyin && !entry.pinyin) || (needsMeaning && !entry.meaning)) throw new Error('INCOMPLETE_ENTRY');
    const syllables = entry.pinyin ? entry.pinyin.split(' ') : [];
    // 只有拼音的题目按合法音节留格，既不依赖未知汉字，也不把拼音字母当字数。
    if (needsPinyin && !entry.text && syllables.some(syllable => !isValidPinyinSyllable(syllable))) throw new Error('INCOMPLETE_ENTRY');
    if (Array.from(entry.text).length > 200 || entry.pinyin.length > 1000 || entry.meaning.length > 1000) {
      throw new Error('ITEM_TOO_LARGE');
    }
    const prompt = options.mode === 'chinese-dictation' ? entry.pinyin
      : options.mode === 'english-dictation' ? entry.meaning : entry.text;
    const answer = options.mode === 'chinese-pinyin' ? entry.pinyin
      : options.mode === 'english-meaning' ? entry.meaning : entry.text;
    const cells = chinese ? entry.text ? splitGraphemes(entry.text).length : syllables.length : 0;
    for (let repeat = 0; repeat < options.repetitions; repeat += 1) {
      // 学生数据仅保留题面及所需几何尺寸，不把答案藏在渲染属性中。
      const question: ProjectedQuestion = {
        number: result.length + 1,
        prompt,
        writing: options.mode === 'chinese-dictation' ? 'tian'
          : options.mode === 'english-meaning' ? 'underline' : 'four-line',
        cells,
        segments: options.mode === 'chinese-pinyin' ? splitGraphemes(entry.text)
          : options.mode === 'chinese-dictation' && syllables.length === cells ? syllables : [],
        wide: chinese ? cells > 5 : Math.max(entry.text.length, Array.from(prompt).length * 2) > 18,
      };
      if (options.answer) question.answerText = answer;
      result.push(question);
    }
  }
  return result;
}

/** 根据实际字体宽度换行，超长英文也可按字符安全折行。 */
export function wrapText(text: string, maxWidth: number, measure: TextMeasure, size = FONT_SIZE): string[] {
  const lines: string[] = [];
  let line = '';
  // 按码点遍历，避免将扩展汉字和组合后的声调字符截成两个代理项。
  for (const character of Array.from(text.normalize('NFC'))) {
    if (character === '\n') {
      lines.push(line.trimEnd());
      line = '';
    } else if (line && measure(line + character, size) > maxWidth) {
      // 英文和拼音优先在已有空格处换行，单个超长词才按码点折行。
      const separator = line.lastIndexOf(' ');
      if (separator > 0 && character !== ' ') {
        lines.push(line.slice(0, separator).trimEnd());
        line = line.slice(separator + 1) + character;
      } else {
        lines.push(line.trimEnd());
        line = character.trimStart();
      }
    } else {
      line += character;
    }
  }
  if (line || !lines.length) lines.push(line.trimEnd());
  return lines;
}

/** 按固定 A4 毫米坐标分页；每一道题和其完整书写区始终处于同一页。 */
export function layoutWorksheet(
  questions: ProjectedQuestion[],
  options: WorksheetOptions,
  measure: TextMeasure,
): WorksheetPageLayout[] {
  // 排版引擎以毫米计算，绘制以 pt 计算，这里统一换算一次。
  const params = { ...LAYOUT_DEFAULTS, gridMm: options.gridMm };
  const contentWidthMm = params.pageWidthMm - params.marginMm * 2;
  const gridMm = params.gridMm;
  const fourLineMm = fourLineHeightMm(params);

  // 1. 测量每个题块：题号区按实测编号宽度，书写区按每字列距。
  const measured = questions.map((question) => {
    const isPinyinSheet = question.writing === 'four-line' && question.cells > 0;
    const layoutQuestion: LayoutQuestion = {
      number: question.number,
      kind: question.writing === 'tian' ? 'mi-grid' : isPinyinSheet ? 'pinyin-cell' : 'english-line',
      // 拼音字帖的题面是汉字本身；汉字字帖的题面是拼音。
      prompt: isPinyinSheet ? question.segments.join('') : question.prompt,
      segments: question.writing === 'tian'
        ? question.segments.map((syllable) => ({ grapheme: '', syllable }))
        : [],
      ...(question.cells > 0 ? { cellCount: question.cells } : {}),
    };
    return { question, layoutQuestion, box: measureQuestion(layoutQuestion, params, measure) };
  });

  // 2. 单题超宽时折为同一题的续行组，仅首行编号；整题高度仍超一页才定位报错。
  const usableHeightMm = params.pageHeightMm - params.marginMm * 2 - params.headerMm - params.footerMm;
  const flattened: Array<{ source: ProjectedQuestion; box: ReturnType<typeof measureQuestion> }> = [];
  for (const item of measured) {
    const lines = foldQuestion(item.box, contentWidthMm);
    // 无法折叠（单个书写单元就超宽）或整题高于一页可写区：定位报错，不截断也不缩字。
    if (lines.length === 1 && item.box.widthMm > contentWidthMm) throw new Error('ITEM_TOO_LARGE');
    if (lines.length * item.box.heightMm > usableHeightMm) throw new Error('ITEM_TOO_LARGE');
    for (const box of lines) flattened.push({ source: item.question, box });
  }

  // 3. 顺序装行：当前行剩余宽度放得下就追加，放不下才换行，不从后面找短项塞空隙。
  const { rows } = packRows(flattened.map((item) => item.box), { contentWidthMm, params });
  const byBox = new Map(flattened.map((item) => [item.box, item.source]));
  const itemGap = params.itemGapMm * MM_TO_PT;

  // 4. 分页与定位：同一题的续行由 paginate 保证不被分到两页。
  const usableHeightPt = CONTENT_BOTTOM - CONTENT_TOP;
  for (const row of rows) {
    if (row.heightMm * MM_TO_PT > usableHeightPt) throw new Error('ITEM_TOO_LARGE');
  }
  const pages: WorksheetPageLayout[] = [];
  for (const layoutPage of paginate(rows, params)) {
    const page: WorksheetPageLayout = { questions: [] };
    let top = CONTENT_TOP;
    for (const row of layoutPage.rows) {
      const rowHeight = row.heightMm * MM_TO_PT;
      let x = PAGE_MARGIN;
      for (const box of row.boxes) {
        const source = byBox.get(box)!;
        const width = box.widthMm * MM_TO_PT;
        const numberWidth = box.numberWidthMm * MM_TO_PT;
        const cellPitches = box.pitchesMm.map((pitch) => pitch * MM_TO_PT);
        const innerWidth = width - numberWidth;
        const promptLines = wrapText(`${source.number}. ${source.prompt}`, width, measure);
        const answerLines = source.answerText === undefined ? [] : wrapText(source.answerText, innerWidth, measure);
        const isPinyinSheet = source.writing === 'four-line' && source.cells > 0;
        const cellsInLine = box.pitchesMm.length;
        const segments = source.writing === 'tian' || isPinyinSheet
          ? source.segments.slice(box.cellOffset, box.cellOffset + cellsInLine)
          : source.segments;
        const lineQuestion = { ...source, cells: cellsInLine, segments };
        const segmented = segments.length > 0 && !options.answer;
        const writingMm = box.question.kind === 'mi-grid' ? gridMm : fourLineMm;
        const gridTop = (box.heightMm - writingMm) * MM_TO_PT;
        page.questions.push({
          question: lineQuestion,
          x,
          top,
          width,
          height: rowHeight,
          promptLines,
          answerLines,
          gridColumns: Math.max(1, box.question.cellCount ?? 1),
          gridRows: 1,
          gridTop,
          cellPitch: cellPitches[0] ?? gridMm * MM_TO_PT,
          numberWidth,
          cellPitches,
          numbered: box.numbered,
          segmented,
        });
        x += width + itemGap;
      }
      top += rowHeight + params.rowGapMm * MM_TO_PT;
    }
    pages.push(page);
  }
  return pages;
}

/** 对学生投影实际出现的字符检查字形，未使用的答案不参与字体嵌入。 */
function checkGlyphs(font: PDFFont, texts: string[]): void {
  const supported = new Set(font.getCharacterSet());
  for (const text of texts) {
    for (const character of text) {
      if (!/\s/u.test(character) && !supported.has(character.codePointAt(0)!)) throw new Error('GLYPH_MISSING');
    }
  }
}

/** 绘制一段四线三格，其中第三条是加深的英语书写基线。 */
function drawFourLines(page: PDFPage, x: number, top: number, width: number): void {
  for (let line = 0; line < 4; line += 1) {
    const y = PAGE_HEIGHT - top - line * 3 * MM_TO_PT;
    page.drawLine({ start: { x, y }, end: { x: x + width, y }, thickness: line === 2 ? 0.8 : 0.45, color: rgb(0.38, 0.38, 0.38) });
  }
}

/** 汉字字帖使用的格型。契约 §6.1：新汉字字帖必用米字格，不再默认为田字格。 */
export type ChineseGrid = 'mi' | 'tian';

/**
 * 绘制书写格，全部使用矢量坐标。
 *
 * 米字格为外框加两条对角线与中心十字；田字格只有中心虚线十字，保留以便对照与回退。
 *
 * @param page 目标页
 * @param x 左边界
 * @param top 上边界，自页面顶端起算
 * @param size 边长
 * @param kind 格型，默认米字格
 */
function drawGrid(page: PDFPage, x: number, top: number, size: number, kind: ChineseGrid = 'mi'): void {
  const y = PAGE_HEIGHT - top - size;
  page.drawRectangle({ x, y, width: size, height: size, borderWidth: 0.65, borderColor: rgb(0.4, 0.4, 0.4) });
  const center = { thickness: 0.35, color: rgb(0.6, 0.6, 0.6), dashArray: [2, 2] };
  if (kind === 'mi') {
    // 米字格的对角线同样使用浅色虚线，避免与学生的笔画混淆。
    page.drawLine({ start: { x, y }, end: { x: x + size, y: y + size }, ...center });
    page.drawLine({ start: { x, y: y + size }, end: { x: x + size, y }, ...center });
  }
  page.drawLine({ start: { x: x + size / 2, y }, end: { x: x + size / 2, y: y + size }, ...center });
  page.drawLine({ start: { x, y: y + size / 2 }, end: { x: x + size, y: y + size / 2 }, ...center });
}

/** 按已冻结的题目位置绘制，学生与答案页面使用不同数据投影。 */
function drawQuestion(page: PDFPage, layout: QuestionLayout, options: WorksheetOptions, font: PDFFont): void {
  const { question, x, top, width, numberWidth } = layout;
  const text = (value: string, tx: number, textTop: number, size = FONT_SIZE): void => {
    page.drawText(value, { x: tx, y: PAGE_HEIGHT - textTop - size, size, font, color: rgb(0.12, 0.12, 0.12) });
  };
  const segmented = layout.segmented;
  if (segmented && layout.numbered) text(`${question.number}.`, x, top);
  else layout.promptLines.forEach((line, number) => text(line, x, top + number * LINE_HEIGHT));
  const gridTop = top + layout.gridTop;
  if (options.answer) {
    layout.answerLines.forEach((line, number) => text(line, x + numberWidth, gridTop + number * LINE_HEIGHT));
    return;
  }
  if (question.writing === 'tian') {
    const size = options.gridMm * MM_TO_PT;
    let offset = 0;
    for (let cell = 0; cell < question.cells; cell += 1) {
      // 每个字用自己的列距，长音节列距更宽；米字格居中在列距内。
      const pitch = layout.cellPitches[cell] ?? layout.cellPitch;
      const cellX = x + numberWidth + offset;
      const cellTop = gridTop;
      if (segmented) {
        const syllable = question.segments[cell]!;
        text(syllable, cellX + (pitch - font.widthOfTextAtSize(syllable, FONT_SIZE)) / 2, cellTop - LINE_HEIGHT);
      }
      drawGrid(page, cellX + (pitch - size) / 2, cellTop, size, 'mi');
      offset += pitch;
    }
  } else if (segmented) {
    let pinyinOffset = 0;
    question.segments.forEach((character, cell) => {
      const cellWidth = layout.cellPitches[cell] ?? layout.cellPitch;
      const cellX = x + numberWidth + pinyinOffset;
      const cellTop = gridTop;
      text(character, cellX + (cellWidth - font.widthOfTextAtSize(character, FONT_SIZE)) / 2, cellTop - LINE_HEIGHT);
      drawFourLines(page, cellX + 2, cellTop + 3, cellWidth - 4);
      pinyinOffset += cellWidth;
    });
  } else if (question.writing === 'four-line') {
    drawFourLines(page, x + numberWidth, gridTop + 2, width - numberWidth);
  } else {
    const y = PAGE_HEIGHT - gridTop - 15;
    page.drawLine({ start: { x: x + numberWidth, y }, end: { x: x + width, y }, thickness: 0.6, color: rgb(0.38, 0.38, 0.38) });
  }
}

/** 生成具有嵌入字体及固定 A4 分页的 PDF，供预览、下载与打印共同使用。 */
export async function generateWorksheet(entries: WordEntry[], inputOptions: WorksheetOptions): Promise<WorksheetArtifact> {
  // 在异步字体加载之前冻结设置，防止用户切换模式后旧任务混入新设置。
  const options = Object.freeze({ ...inputOptions });
  const projection = projectWorksheet(entries, options);
  const document = await PDFDocument.create();
  const font = await embedChineseFont(document);
  const measure: TextMeasure = (value, size) => font.widthOfTextAtSize(value, size);
  const rawTitle = options.title.trim().normalize('NFC') || '\u7ec3\u4e60\u5b57\u5e16';
  // 标题长度必须在输入侧处理，导出阶段不再静默截断。
  if (Array.from(rawTitle).length > 40) throw new Error('TITLE_TOO_LONG');
  const title = rawTitle;
  const caption = options.answer ? '\u53c2\u8003\u7b54\u6848' : '\u5b66\u751f\u7ec3\u4e60';
  const nameLine = '\u59d3\u540d\uff1a____________    \u65e5\u671f\uff1a____________';
  checkGlyphs(font, [title, caption, nameLine, '\u7b2c / \u9875 0123456789.', ...projection.flatMap((question) => [question.prompt, ...(question.answerText === undefined ? [] : [question.answerText])])]);
  const layouts = layoutWorksheet(projection, options, measure);
  // 元数据只写通用练习标记，不复制词条、答案或用户输入为隐藏附件。
  document.setTitle(options.answer ? 'Worksheet answer sheet' : 'Student worksheet');
  document.setCreator('Yuyutian Toolbox');
  document.setProducer('Yuyutian Toolbox');
  layouts.forEach((layout, index) => {
    const page = document.addPage([PAGE_WIDTH, PAGE_HEIGHT]);
    const titleLines = wrapText(title, CONTENT_WIDTH, measure, 16).slice(0, 2);
    titleLines.forEach((line, lineNumber) => page.drawText(line, {
      x: (PAGE_WIDTH - measure(line, 16)) / 2,
      y: PAGE_HEIGHT - PAGE_MARGIN - 16 - lineNumber * 18,
      size: 16, font,
    }));
    const headerTop = PAGE_MARGIN + (titleLines.length > 1 ? 41 : 29);
    page.drawText(options.answer ? caption : nameLine, { x: PAGE_MARGIN, y: PAGE_HEIGHT - headerTop - 9, size: 9, font });
    page.drawLine({ start: { x: PAGE_MARGIN, y: PAGE_HEIGHT - CONTENT_TOP + 7 }, end: { x: PAGE_WIDTH - PAGE_MARGIN, y: PAGE_HEIGHT - CONTENT_TOP + 7 }, thickness: 0.5, color: rgb(0.6, 0.6, 0.6) });
    layout.questions.forEach((question) => drawQuestion(page, question, options, font));
    const footer = `${caption}  |  \u7b2c ${index + 1} / ${layouts.length} \u9875`;
    page.drawText(footer, { x: (PAGE_WIDTH - measure(footer, 9)) / 2, y: PAGE_MARGIN, size: 9, font, color: rgb(0.4, 0.4, 0.4) });
  });
  const bytes = await document.save();
  const blob = new Blob([new Uint8Array(bytes)], { type: 'application/pdf' });
  return {
    bytes,
    pageCount: layouts.length,
    filename: `${options.mode}-${options.answer ? 'answers' : 'student'}.pdf`,
    url: URL.createObjectURL(blob),
  };
}
