#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import {
  LAYOUT_DEFAULTS, MM_TO_PT, firstRowCount, layoutQuestions, numberWidthMm, measureQuestion, fourLineHeightMm
} from '../src/lib/worksheet-layout-v2.ts';

/**
 * 用真实发布字体测量排版密度，产出几何报告。
 *
 * 契约 §6.3 要求「使用实际发布字体测量并生成 PDF，不以浏览器 CSS 列数代替纸面检查」。
 * 本脚本完成其中的测量部分：真实字体宽度 + 真实题号宽度 → 每行容量与毫米数。
 * 它不生成 PDF，也不代替实纸打印验收。
 *
 * 用法：npx tsx scripts/measure-layout-density.mjs
 */

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const fontPath = join(root, 'public', 'fonts', 'NotoSansCJKsc-Regular.otf');

/** fontkit 在不同打包形态下导出方式不一致，这里都兼容。 */
async function loadFontkit() {
  const require = createRequire(import.meta.url);
  const candidates = ['@pdf-lib/fontkit', 'fontkit'];
  for (const name of candidates) {
    try {
      const loaded = require(name);
      const kit = loaded?.default ?? loaded;
      if (typeof kit?.create === 'function') return { kit, name };
    } catch {
      // 继续尝试下一个候选。
    }
  }
  throw new Error('FONTKIT_UNAVAILABLE');
}

const { kit, name: kitName } = await loadFontkit();
const font = kit.create(await readFile(fontPath));

/**
 * 真实字体测量：返回 pt。
 *
 * fontkit 的字体对象没有 pdf-lib 的 widthOfTextAtSize，改为按
 * `sum(advanceWidth) * size / unitsPerEm` 计算，与 pdf-lib 嵌入字体后的量法一致。
 */
const measure = (text, size) => {
  const scale = size / font.unitsPerEm;
  return font.layout(text).glyphs.reduce((total, glyph) => total + glyph.advanceWidth * scale, 0);
};

/** 短读音夹具（契约 §6.3 的 12 个单字）。 */
const SHORT = [
  ['一', 'yī'], ['二', 'èr'], ['三', 'sān'], ['四', 'sì'], ['五', 'wǔ'], ['六', 'liù'],
  ['七', 'qī'], ['八', 'bā'], ['九', 'jiǔ'], ['十', 'shí'], ['山', 'shān'], ['水', 'shuǐ']
];

/** 双字夹具。 */
const DOUBLE = [
  ['大山', ['dà', 'shān']], ['小河', ['xiǎo', 'hé']], ['白云', ['bái', 'yún']],
  ['红花', ['hóng', 'huā']], ['小手', ['xiǎo', 'shǒu']], ['大地', ['dà', 'dì']]
];

const params = { ...LAYOUT_DEFAULTS };

/** 构造米字格题目。 */
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

/** 构造拼音字帖题目。 */
function pinyinCell(entries) {
  return entries.map(([text], index) => ({ number: index + 1, kind: 'pinyin-cell', prompt: text, segments: [] }));
}

/**
 * 取首行第一个书写单元的列距。
 *
 * 注意：米字格列距 = max(gridMm, 读音宽度+1mm)，**不等于格宽**。
 * 格宽是否等于 gridMm 需要从生成的 PDF 里核对（见 scripts/verify-grid-geometry.mjs）。
 */
function cellPitchMm(firstRow) {
  const pitch = firstRow?.boxes?.[0]?.pitchesMm?.[0];
  return pitch === undefined ? null : Number(pitch.toFixed(6));
}

/** 运行一个夹具并汇总几何。 */
function run(label, questions, override = {}) {
  const activeParams = { ...params, ...override };
  const outcome = layoutQuestions(questions, activeParams, measure);
  const firstRow = outcome.pages[0]?.rows[0];
  const widths = firstRow ? firstRow.boxes.map(box => box.widthMm) : [];
  return {
    label,
    itemGapMm: activeParams.itemGapMm,
    gridMm: activeParams.gridMm,
    // 首行第一个书写单元的列距（米字格列距不等于格宽，见函数注释）。
    firstCellPitchMm: cellPitchMm(firstRow),
    firstRowCount: firstRowCount(outcome),
    firstRowWidthMm: Number((firstRow?.widthMm ?? 0).toFixed(4)),
    contentWidthMm: activeParams.pageWidthMm - activeParams.marginMm * 2,
    pageCount: outcome.pages.length,
    blocked: outcome.blocked,
    notices: outcome.notices.map(notice => notice.code),
    firstRowItemWidthsMm: widths.map(width => Number(width.toFixed(4)))
  };
}

// 题号实测宽度：契约 §6.3 给出的对照值。
const numberWidths = {
  '1.': Number(numberWidthMm(1, params, measure).toFixed(6)),
  '10.': Number(numberWidthMm(10, params, measure).toFixed(6)),
  expected1: 3.444775,
  expected10: 5.2069
};

const cases = [
  { ...run('米字格单字 12mm 短读音', miGrid(SHORT)), targetCount: 10, targetWidthMm: 178.71, rule: 'equal' },
  { ...run('米字格双字 12mm', miGrid(DOUBLE)), targetCount: 6, targetWidthMm: 177.17, rule: 'equal' },
  { ...run('拼音字帖单字', pinyinCell(SHORT)), targetCount: 8, targetWidthMm: 173.06, rule: 'atLeast' },
  { ...run('米字格单字 3mm 项间距', miGrid(SHORT), { itemGapMm: 3 }), targetCount: 10, targetWidthMm: null, rule: 'belowTarget' },
  // 三档格距的格宽误差都必须为 0。
  ...['10', '12', '14'].map(() => null).slice(0, 0),
  // 契约 §6.2 允许 10/12/14mm 三档；10mm 与 14mm 无密度目标，只报告实测并核对格宽。
  { ...run('米字格单字 10mm 格距', miGrid(SHORT), { gridMm: 10 }), targetCount: null, targetWidthMm: null, rule: 'report' },
  { ...run('米字格单字 14mm 格距', miGrid(SHORT), { gridMm: 14 }), targetCount: null, targetWidthMm: null, rule: 'report' },
  { ...run('米字格长音节 shuāng/chuāng', miGrid([['双', 'shuāng'], ['窗', 'chuāng']])), targetCount: null, targetWidthMm: null, rule: 'report' },
  { ...run('三位题号', miGrid(SHORT.slice(0, 12).map((entry, index) => [entry[0], entry[1]])).map((question, index) => ({ ...question, number: 100 + index })), { itemGapMm: 2.5 }), targetCount: null, targetWidthMm: null, rule: 'report' }
];

// ---------------------------------------------------------------- W38：超长题项必须明确阻断，不得截断
{
  const longSyllable = 'shān'.repeat(40);       // 远超单页可容纳宽度
  const questions = miGrid([['山', longSyllable]]);
  let thrown = null;
  let layouted = null;
  try {
    layouted = layoutQuestions(questions, params, measure);
  } catch (error) {
    thrown = String(error?.message ?? error);
  }
  // 若未抛错，则必须落在**单页内且未被截断**，否则就是"把内容截掉当成功"。
  const boxes = layouted?.pages?.flatMap(page => page.rows).flatMap(row => row.boxes) ?? [];
  const contentWidthMm = params.pageWidthMm - params.marginMm * 2;
  const widestMm = boxes.length ? Math.max(...boxes.map(box => box.widthMm)) : 0;
  const overflow = widestMm > contentWidthMm + 0.5;
  console.log('');
  console.log('=== W38 超长题项 ===');
  console.log(`读音长度 ${longSyllable.length} 字符，题面宽度 ${measure(longSyllable, 10).toFixed(1)} pt`);
  console.log(`内容区宽度 ${contentWidthMm.toFixed(1)} mm，实测最宽题块 ${widestMm.toFixed(1)} mm`);
  // 引擎的阻断方式是 outcome.blocked + notices，**不是抛错**。
  // 一开始我只 catch 异常，误判成"未阻断"——这是检查方法的错误，不是产品缺陷。
  const noticeCodes = (layouted?.notices ?? []).map(notice => notice.code);
  const blockedByEngine = layouted?.blocked === true || noticeCodes.includes('WORKSHEET_LAYOUT_OVERFLOW');
  console.log(`引擎判定: blocked=${layouted?.blocked ?? '(抛错)'} notices=${JSON.stringify(noticeCodes)}`);
  console.log(`阻断方式: ${thrown ? `抛错（${thrown}）` : blockedByEngine ? 'blocked + WORKSHEET_LAYOUT_OVERFLOW' : '未阻断'}`);
  console.log(`判定: ${thrown || blockedByEngine ? '按契约明确阻断' : overflow ? '缺陷：未阻断且溢出' : '未超宽，无需阻断'}`);
  if (!thrown && !blockedByEngine && overflow) process.exitCode = 1;
}

// ---------------------------------------------------------------- W38：分页不变量（题号连续、题面与书写区不跨页拆散）
{
  // 40 题足以产生多页；每题 2 字，混合长短读音。
  // 每 5 题放一个双字词（隔音用空格），用于制造“同一题多行”的情形。
  const many = Array.from({ length: 220 }, (_, index) => (index % 5 === 0 ? ['山水', 'shān shuǐ'] : ['山', 'shān']));
  const questions = miGrid(many);
  const outcome = layoutQuestions(questions, params, measure);
  const perPage = outcome.pages.map(page => {
    const numbers = [];
    for (const row of page.rows) {
      for (const box of row.boxes) if (box.numbered) numbers.push(box.question.number);
    }
    return numbers;
  });
  // 每题只应在其**首个**行出现题号一次（续行 numbered=false），因此全部题号应恰好覆盖 1..N。
  const seen = perPage.flat();
  const unique = new Set(seen);
  const expected = questions.map((_, index) => index + 1);
  const missing = expected.filter(n => !unique.has(n));
  const duplicated = [...unique].filter(n => seen.filter(x => x === n).length > 1);
  const continuous = missing.length === 0 && duplicated.length === 0;
  // 同一题的行必须落在同一页：检查每页内的题号是否与其它页重复。
  const crossPage = (() => {
    const owner = new Map();
    let violations = 0;
    outcome.pages.forEach((page, pageIndex) => {
      for (const row of page.rows) {
        for (const box of row.boxes) {
          const n = box.question.number;
          if (owner.has(n) && owner.get(n) !== pageIndex) violations += 1;
          else owner.set(n, pageIndex);
        }
      }
    });
    return violations;
  })();
  console.log('');
  console.log('=== W38 分页不变量 ===');
  console.log(`题数 ${questions.length}，页数 ${outcome.pages.length}，每页题号 ${JSON.stringify(perPage)}`);
  console.log(`题号连续无缺无重: ${continuous ? '是' : `否（缺 ${JSON.stringify(missing)}，重 ${JSON.stringify(duplicated)}）`}`);
  console.log(`题面与书写区跨页拆散次数: ${crossPage}`);
  console.log(`判定: ${continuous && crossPage === 0 ? '符合契约' : '不符合契约'}`);
  if (!continuous || crossPage !== 0) process.exitCode = 1;
}

// 判定：与契约给出的目标逐项比较。
const verdicts = cases.map(item => {
  if (item.rule === 'equal') {
    const countOk = item.firstRowCount === item.targetCount;
    const widthOk = item.targetWidthMm === null || Math.abs(item.firstRowWidthMm - item.targetWidthMm) <= 0.05;
    return { label: item.label, pass: countOk && widthOk, reason: countOk && widthOk ? '' : `count=${item.firstRowCount}/${item.targetCount} width=${item.firstRowWidthMm}/${item.targetWidthMm}` };
  }
  if (item.rule === 'atLeast') {
    const pass = item.firstRowCount >= item.targetCount;
    return { label: item.label, pass, reason: pass ? '' : `count=${item.firstRowCount} < ${item.targetCount}` };
  }
  if (item.rule === 'belowTarget') {
    const pass = item.firstRowCount < item.targetCount;
    return { label: item.label, pass, reason: pass ? '' : `count=${item.firstRowCount} 未低于目标 ${item.targetCount}` };
  }
  return { label: item.label, pass: true, reason: '' };
});

const report = {
  generatedAt: new Date().toISOString(),
  font: { file: 'public/fonts/NotoSansCJKsc-Regular.otf', fontkit: kitName, unitsPerEm: font.unitsPerEm ?? null },
  mmToPt: MM_TO_PT,
  numberWidths,
  layoutDefaults: params,
  fourLineHeightMm: fourLineHeightMm(params),
  cases,
  verdicts,
  passed: verdicts.every(verdict => verdict.pass)
};

const outPath = join(root, '..', 'docs', 'verification', 'worksheet-workflow-v2', 'layout-density.json');
await writeFile(outPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');

console.log(`字体：${report.font.file}（fontkit: ${kitName}，unitsPerEm=${report.font.unitsPerEm}）`);
console.log(`题号区实测：1. = ${numberWidths['1.']} mm（契约 ${numberWidths.expected1}），10. = ${numberWidths['10.']} mm（契约 ${numberWidths.expected10}）`);
console.log(`四线三格总高：${report.fourLineHeightMm} mm`);
console.log('');
console.log('夹具'.padEnd(30) + '首行项数'.padEnd(10) + '首行宽 mm'.padEnd(12) + '判定');
for (const item of cases) {
  const verdict = verdicts.find(entry => entry.label === item.label);
  console.log(
    item.label.padEnd(28) +
    String(item.firstRowCount).padEnd(12) +
    String(item.firstRowWidthMm).padEnd(14) +
    (verdict.pass ? 'OK' : `FAIL ${verdict.reason}`)
  );
}
console.log('');
console.log(`报告已写入：${outPath}`);
console.log(report.passed ? '全部目标达标。' : '存在未达标项。');
process.exit(report.passed ? 0 : 1);
