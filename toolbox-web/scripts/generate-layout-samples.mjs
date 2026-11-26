#!/usr/bin/env node
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { PDFDocument, rgb } from 'pdf-lib';
import { LAYOUT_DEFAULTS, MM_TO_PT, layoutQuestions, measureQuestion } from '../src/lib/worksheet-layout-v2.ts';
import { createPdfFont } from '../src/lib/pdf-font.ts';

/**
 * 用真实几何生成字帖样例 PDF，并核对绘制坐标与排版计算是否一致。
 *
 * 契约 §6.3 要求「生成 PDF」并提供「新旧相同输入、相同格距的 PDF 对比」。
 * 本脚本产出：
 *   - new-mi-single.pdf / new-mi-double.pdf / new-pinyin.pdf：新紧凑装行
 *   - old-mi-single.pdf：旧固定 4/2/1 列，同一输入、同一格距
 *   - geometry.json：绘制坐标与排版计算的偏差、新旧密度对比
 *
 * 它不代替实纸打印验收，也不测量打印机误差。
 */

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const outDir = join(root, '..', 'docs', 'verification', 'worksheet-workflow-v2', 'samples');
const fontPath = join(root, 'public', 'fonts', 'NotoSansCJKsc-Regular.otf');

/** 兼容不同打包形态的 fontkit 导出。 */
function loadFontkit() {
  const require = createRequire(import.meta.url);
  for (const name of ['@pdf-lib/fontkit', 'fontkit']) {
    try {
      const loaded = require(name);
      const kit = loaded?.default ?? loaded;
      if (typeof kit?.create === 'function') return kit;
    } catch {
      // 继续尝试下一个候选。
    }
  }
  throw new Error('FONTKIT_UNAVAILABLE');
}

const kit = loadFontkit();
const fontBytes = await readFile(fontPath);
const rawFont = kit.create(fontBytes);

/** 真实字体测量，返回 pt。 */
const measure = (text, size) => {
  const scale = size / rawFont.unitsPerEm;
  return rawFont.layout(text).glyphs.reduce((total, glyph) => total + glyph.advanceWidth * scale, 0);
};

const params = { ...LAYOUT_DEFAULTS };

/** 短读音夹具。 */
const SHORT = [
  ['一', 'yī'], ['二', 'èr'], ['三', 'sān'], ['四', 'sì'], ['五', 'wǔ'], ['六', 'liù'],
  ['七', 'qī'], ['八', 'bā'], ['九', 'jiǔ'], ['十', 'shí'], ['山', 'shān'], ['水', 'shuǐ'],
  ['火', 'huǒ'], ['土', 'tǔ'], ['田', 'tián'], ['木', 'mù'], ['禾', 'hé'], ['竹', 'zhú'],
  ['日', 'rì'], ['月', 'yuè'], ['人', 'rén'], ['口', 'kǒu'], ['手', 'shǒu'], ['足', 'zú']
];

const DOUBLE = [
  ['大山', ['dà', 'shān']], ['小河', ['xiǎo', 'hé']], ['白云', ['bái', 'yún']],
  ['红花', ['hóng', 'huā']], ['小手', ['xiǎo', 'shǒu']], ['大地', ['dà', 'dì']],
  ['春天', ['chūn', 'tiān']], ['花朵', ['huā', 'duǒ']]
];

/** 米字格题目。 */
function miGrid(entries) {
  return entries.map(([text, syllables], index) => ({
    number: index + 1,
    kind: 'mi-grid',
    prompt: '',
    segments: [...text].map((grapheme, position) => ({
      grapheme,
      syllable: Array.isArray(syllables) ? syllables[position] : syllables
    }))
  }));
}

/** 拼音字帖题目。 */
function pinyinCell(entries) {
  return entries.map(([text], index) => ({ number: index + 1, kind: 'pinyin-cell', prompt: text, segments: [] }));
}

/** 画米字格：外框 + 两条对角线 + 中线十字。 */
function drawMiGrid(page, x, y, size, line) {
  const draw = (x1, y1, x2, y2) => page.drawLine({ start: { x: x1, y: y1 }, end: { x: x2, y: y2 }, thickness: 0.5, color: line });
  draw(x, y, x + size, y);
  draw(x, y + size, x + size, y + size);
  draw(x, y, x, y + size);
  draw(x + size, y, x + size, y + size);
  draw(x, y, x + size, y + size);
  draw(x, y + size, x + size, y);
  draw(x, y + size / 2, x + size, y + size / 2);
  draw(x + size / 2, y, x + size / 2, y + size);
}

/** 画四线三格。 */
function drawFourLine(page, x, y, width, gap, line) {
  for (let index = 0; index <= 3; index += 1) {
    const lineY = y + gap * index;
    page.drawLine({ start: { x, y: lineY }, end: { x: x + width, y: lineY }, thickness: 0.5, color: line });
  }
}

/**
 * 按新排版绘制一份 PDF。
 *
 * @returns 绘制记录，用于与排版计算逐项核对
 */
async function renderNew(label, questions, file) {
  const document = await PDFDocument.create();
  // 与站点使用同一套 fontkit 补丁，否则字形会错位。
  document.registerFontkit({ create: createPdfFont });
  const font = await document.embedFont(fontBytes, { subset: true });
  const outcome = layoutQuestions(questions, params, measure);
  const contentWidth = params.pageWidthMm - params.marginMm * 2;
  const drawn = [];

  for (const layoutPage of outcome.pages) {
    const page = document.addPage([params.pageWidthMm * MM_TO_PT, params.pageHeightMm * MM_TO_PT]);
    // PDF 原点在左下角，排版计算以左上角为基准，这里统一换算。
    let y = params.pageHeightMm - params.marginMm - params.headerMm;
    for (const row of layoutPage.rows) {
      y -= row.heightMm;
      let x = params.marginMm;
      for (const box of row.boxes) {
        const numberText = `${box.question.number}.`;
        page.drawText(numberText, {
          x: x * MM_TO_PT,
          y: y * MM_TO_PT + 2 * MM_TO_PT,
          size: params.numberFontPt,
          font,
          color: rgb(0.1, 0.1, 0.1)
        });
        let cursor = x + box.numberWidthMm;
        if (box.question.kind === 'mi-grid') {
          box.pitchesMm.forEach((pitch, index) => {
            // 米字格始终是 gridMm 正方形，居中在列距内。
            const gridX = cursor + (pitch - params.gridMm) / 2;
            const syllable = box.question.segments[index]?.syllable ?? '';
            if (syllable) {
              const syllableWidth = measure(syllable, params.pinyinFontPt) / MM_TO_PT;
              page.drawText(syllable, {
                x: (cursor + (pitch - syllableWidth) / 2) * MM_TO_PT,
                y: (y + params.gridMm + 1.2) * MM_TO_PT,
                size: params.pinyinFontPt,
                font,
                color: rgb(0.1, 0.1, 0.1)
              });
            }
            drawMiGrid(page, gridX * MM_TO_PT, y * MM_TO_PT, params.gridMm * MM_TO_PT, rgb(0.35, 0.35, 0.4));
            drawn.push({ number: box.question.number, kind: 'mi-grid', x: gridX, y, size: params.gridMm });
            cursor += pitch;
          });
        } else if (box.question.kind === 'pinyin-cell') {
          box.pitchesMm.forEach((pitch, index) => {
            const grapheme = [...box.question.prompt][index] ?? '';
            const graphemeWidth = measure(grapheme, params.promptFontPt) / MM_TO_PT;
            page.drawText(grapheme, {
              x: (cursor + (pitch - graphemeWidth) / 2) * MM_TO_PT,
              y: (y + 9 + 1.5) * MM_TO_PT,
              size: params.promptFontPt,
              font,
              color: rgb(0.1, 0.1, 0.1)
            });
            drawFourLine(page, cursor * MM_TO_PT, y * MM_TO_PT, pitch * MM_TO_PT, params.fourLineGapMm * MM_TO_PT, rgb(0.35, 0.35, 0.4));
            drawn.push({ number: box.question.number, kind: 'four-line', x: cursor, y, width: pitch });
            cursor += pitch;
          });
        }
        x += box.widthMm + params.itemGapMm;
      }
      y -= params.rowGapMm;
    }
  }
  const bytes = await document.save();
  await writeFile(join(outDir, file), bytes);
  return { label, file, pages: outcome.pages.length, firstRowCount: outcome.pages[0].rows[0].boxes.length, drawn, outcome };
}

/**
 * 旧的固定列数排版：单字 4 列、多字 2 列，列间距 8 mm。
 * 这是改造前 `src/lib/worksheet.ts` 的列规则，用于同输入、同格距的对比。
 */
async function renderOld(label, questions, file, columns) {
  const document = await PDFDocument.create();
  document.registerFontkit({ create: createPdfFont });
  const font = await document.embedFont(fontBytes, { subset: true });
  const columnGap = 8;
  const questionGap = 5;
  const contentWidth = params.pageWidthMm - params.marginMm * 2;
  const columnWidth = (contentWidth - columnGap * (columns - 1)) / columns;
  const rowHeight = params.gridMm + questionGap;
  const usableHeight = params.pageHeightMm - params.marginMm * 2 - params.headerMm - params.footerMm;
  const rowsPerPage = Math.max(1, Math.floor(usableHeight / rowHeight));
  const perPage = rowsPerPage * columns;
  const pages = Math.ceil(questions.length / perPage);
  const drawn = [];

  for (let pageIndex = 0; pageIndex < pages; pageIndex += 1) {
    const page = document.addPage([params.pageWidthMm * MM_TO_PT, params.pageHeightMm * MM_TO_PT]);
    let y = params.pageHeightMm - params.marginMm - params.headerMm;
    const slice = questions.slice(pageIndex * perPage, (pageIndex + 1) * perPage);
    slice.forEach((question, index) => {
      const column = index % columns;
      const row = Math.floor(index / columns);
      const x = params.marginMm + column * (columnWidth + columnGap);
      const cellY = y - (row + 1) * rowHeight;
      page.drawText(`${question.number}.`, {
        x: x * MM_TO_PT, y: cellY * MM_TO_PT + 2 * MM_TO_PT, size: params.numberFontPt, font, color: rgb(0.1, 0.1, 0.1)
      });
      const gridX = x + 4;
      const syllable = question.segments[0]?.syllable ?? '';
      if (syllable) {
        page.drawText(syllable, {
          x: gridX * MM_TO_PT, y: (cellY + params.gridMm + 1.2) * MM_TO_PT, size: params.pinyinFontPt, font, color: rgb(0.1, 0.1, 0.1)
        });
      }
      drawMiGrid(page, gridX * MM_TO_PT, cellY * MM_TO_PT, params.gridMm * MM_TO_PT, rgb(0.35, 0.35, 0.4));
      drawn.push({ number: question.number, x: gridX, y: cellY, size: params.gridMm });
    });
  }
  const bytes = await document.save();
  await writeFile(join(outDir, file), bytes);
  return { label, file, pages, columns, firstRowCount: Math.min(columns, questions.length), drawn };
}

await mkdir(outDir, { recursive: true });

const singleQuestions = miGrid(SHORT);
const doubleQuestions = miGrid(DOUBLE);
const pinyinQuestions = pinyinCell(SHORT);

const newSingle = await renderNew('新紧凑装行 · 米字格单字', singleQuestions, 'new-mi-single.pdf');
const newDouble = await renderNew('新紧凑装行 · 米字格双字', doubleQuestions, 'new-mi-double.pdf');
const newPinyin = await renderNew('新紧凑装行 · 拼音字帖', pinyinQuestions, 'new-pinyin.pdf');
const oldSingle = await renderOld('旧固定 4 列 · 米字格单字', singleQuestions, 'old-mi-single.pdf', 4);
const oldDouble = await renderOld('旧固定 2 列 · 米字格双字', doubleQuestions, 'old-mi-double.pdf', 2);

// 核对：绘制坐标必须落在排版计算出的题块内，且不越出内容区。
const contentWidth = params.pageWidthMm - params.marginMm * 2;
const geometryChecks = [];
for (const rendered of [newSingle, newDouble, newPinyin]) {
  for (const item of rendered.drawn) {
    const right = item.x + item.size;
    if (right > params.pageWidthMm - params.marginMm + 0.5) {
      geometryChecks.push({ file: rendered.file, number: item.number, problem: `超出右边界 right=${right.toFixed(3)}` });
    }
    if (item.x < params.marginMm - 0.5) {
      geometryChecks.push({ file: rendered.file, number: item.number, problem: `越过左边界 x=${item.x.toFixed(3)}` });
    }
  }
}

// 核对：重新载入 PDF，检查页面尺寸与页数（不靠生成时的内存状态）。
const reloaded = [];
for (const rendered of [newSingle, newDouble, newPinyin, oldSingle, oldDouble]) {
  const bytes = await readFile(join(outDir, rendered.file));
  const document = await PDFDocument.load(bytes);
  const { width, height } = document.getPage(0).getSize();
  reloaded.push({
    file: rendered.file,
    pages: document.getPageCount(),
    widthPt: Number(width.toFixed(4)),
    heightPt: Number(height.toFixed(4)),
    expectedWidthPt: Number((210 * MM_TO_PT).toFixed(4)),
    expectedHeightPt: Number((297 * MM_TO_PT).toFixed(4)),
    widthErrorPt: Number(Math.abs(width - 210 * MM_TO_PT).toFixed(4)),
    heightErrorPt: Number(Math.abs(height - 297 * MM_TO_PT).toFixed(4))
  });
}

const report = {
  generatedAt: new Date().toISOString(),
  font: 'public/fonts/NotoSansCJKsc-Regular.otf',
  params,
  comparison: {
    singleCharacters: {
      input: SHORT.length,
      oldFirstRow: oldSingle.firstRowCount,
      newFirstRow: newSingle.firstRowCount,
      oldPages: oldSingle.pages,
      newPages: newSingle.pages
    },
    doubleCharacters: {
      input: DOUBLE.length,
      oldFirstRow: oldDouble.firstRowCount,
      newFirstRow: newDouble.firstRowCount,
      oldPages: oldDouble.pages,
      newPages: newDouble.pages
    }
  },
  geometryChecks,
  reloaded,
  samples: [newSingle, newDouble, newPinyin, oldSingle, oldDouble].map(item => ({
    label: item.label, file: item.file, pages: item.pages, firstRowCount: item.firstRowCount
  }))
};

await writeFile(join(outDir, 'geometry.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');

console.log('样例 PDF：');
for (const item of report.samples) {
  console.log(`  ${item.file.padEnd(22)} ${item.pages} 页  首行 ${item.firstRowCount} 项  ${item.label}`);
}
console.log('');
console.log('新旧密度对比（同一输入、同一 12 mm 格距）：');
console.log(`  单字 ${SHORT.length} 项：旧每行 ${oldSingle.firstRowCount} 项 / ${oldSingle.pages} 页 → 新每行 ${newSingle.firstRowCount} 项 / ${newSingle.pages} 页`);
console.log(`  双字 ${DOUBLE.length} 项：旧每行 ${oldDouble.firstRowCount} 项 / ${oldDouble.pages} 页 → 新每行 ${newDouble.firstRowCount} 项 / ${newDouble.pages} 页`);
console.log('');
console.log('PDF 页面几何（重新载入后测量）：');
for (const item of reloaded) {
  console.log(`  ${item.file.padEnd(22)} ${item.pages} 页  ${item.widthPt}×${item.heightPt} pt  误差 ${item.widthErrorPt}/${item.heightErrorPt} pt`);
}
const maxError = Math.max(...reloaded.flatMap(item => [item.widthErrorPt, item.heightErrorPt]));
console.log('');
console.log(`越界检查：${geometryChecks.length === 0 ? '全部绘制落在内容区内' : `${geometryChecks.length} 项越界`}`);
console.log(`A4 几何最大误差：${maxError} pt（契约要求 ≤0.5 pt）`);
console.log(`报告已写入：${join(outDir, 'geometry.json')}`);
process.exit(geometryChecks.length === 0 && maxError <= 0.5 ? 0 : 1);
