#!/usr/bin/env node
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PDFDocument } from 'pdf-lib';

/**
 * 调用站点真实的字帖生成入口产出 PDF，并核对页面几何。
 *
 * 与 `generate-layout-samples.mjs` 的区别：那份是脚本自己画的几何示意；
 * 本脚本调用 `generateWorksheet`（页面上「导出 PDF」走的同一条路径），
 * 因此它的产物才代表产品实际生成的字帖。
 *
 * `getFontBytes` 在浏览器里用同域 fetch 取字体，本脚本把 fetch 指到本地 `public/`，
 * 只是换一个文件来源，不替换字体、不改渲染逻辑。
 */

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const outDir = join(root, '..', 'docs', 'verification', 'worksheet-workflow-v2', 'samples');

// 把同域请求映射到本地 public/，仅用于让 Node 具备浏览器的取字体能力。
const originalFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = typeof input === 'string' ? input : input?.url ?? String(input);
  if (url.startsWith('/')) {
    const rel = url.replace(/^\//u, '').split('?')[0];
    const bytes = await readFile(join(root, 'public', rel));
    return new Response(bytes, { status: 200, headers: { 'content-type': 'font/otf' } });
  }
  return originalFetch(input, init);
};

const { generateWorksheet } = await import('../src/lib/worksheet.ts');

/** 契约 §6.3 的米字格单字夹具，读音按教材常用读音给定。 */
const FIXTURE = [
  ['一', 'yī'], ['二', 'èr'], ['三', 'sān'], ['四', 'sì'], ['五', 'wǔ'], ['六', 'liù'],
  ['七', 'qī'], ['八', 'bā'], ['九', 'jiǔ'], ['十', 'shí'], ['山', 'shān'], ['水', 'shuǐ']
];

const entries = FIXTURE.map(([text, pinyin], index) => ({
  id: `fixture-${index + 1}`,
  text,
  pinyin,
  meaning: '',
  unit: 1,
  kind: 'character'
}));

await mkdir(outDir, { recursive: true });

/** 生成一份并落盘，返回产物信息。 */
async function render(mode, answer, filename, gridMm = 12) {
  const artifact = await generateWorksheet(entries, {
    mode,
    title: '米字格夹具',
    grade: 1,
    gridMm,
    repetitions: 1,
    answer
  });
  await writeFile(join(outDir, filename), artifact.bytes);
  const document = await PDFDocument.load(artifact.bytes);
  const { width, height } = document.getPage(0).getSize();
  return {
    file: filename,
    mode,
    answer,
    pageCount: artifact.pageCount,
    reloadedPageCount: document.getPageCount(),
    bytes: artifact.bytes.byteLength,
    widthPt: Number(width.toFixed(4)),
    heightPt: Number(height.toFixed(4)),
    widthErrorPt: Number(Math.abs(width - 595.2755905511812).toFixed(4)),
    heightErrorPt: Number(Math.abs(height - 841.8897637795277).toFixed(4))
  };
}

// 英语夹具：用于核对四线区与汉义区横线（契约 §6.2）。
const ENGLISH_FIXTURE = [['apple', '苹果'], ['banana', '香蕉'], ['orange', '橘子'], ['pear', '梨'], ['grape', '葡萄'], ['peach', '桃子']];
const englishEntries = ENGLISH_FIXTURE.map(([text, meaning], index) => ({
  id: `en-${index + 1}`, text, pinyin: '', meaning, unit: 1, kind: 'word'
}));

/** 生成英语字帖（与页面导出同一路径）。 */
async function renderEnglish(mode, answer, filename) {
  const artifact = await generateWorksheet(englishEntries, {
    mode, title: '英语夹具', grade: 3, gridMm: 12, repetitions: 1, answer
  });
  await writeFile(join(outDir, filename), artifact.bytes);
  const document = await PDFDocument.load(artifact.bytes);
  const { width, height } = document.getPage(0).getSize();
  return {
    file: filename, mode, answer, pageCount: artifact.pageCount,
    reloadedPageCount: document.getPageCount(), bytes: artifact.bytes.byteLength,
    widthPt: Number(width.toFixed(4)), heightPt: Number(height.toFixed(4)),
    widthErrorPt: Number(Math.abs(width - 595.2755905511812).toFixed(4)),
    heightErrorPt: Number(Math.abs(height - 841.8897637795277).toFixed(4))
  };
}

const results = [];
results.push(await render('chinese-dictation', false, 'page-chinese-dictation-student.pdf'));
results.push(await render('chinese-dictation', true, 'page-chinese-dictation-answer.pdf'));
results.push(await render('chinese-pinyin', false, 'page-chinese-pinyin-student.pdf'));
results.push(await renderEnglish('english-dictation', false, 'page-english-dictation-student.pdf'));
results.push(await renderEnglish('english-meaning', false, 'page-english-meaning-student.pdf'));
// 契约 §6.2：10/12/14 mm 三档格距都要能从 PDF 里核对格宽。
results.push(await render('chinese-dictation', false, 'page-mi-grid-10mm.pdf', 10));
results.push(await render('chinese-dictation', false, 'page-mi-grid-14mm.pdf', 14));

const report = {
  generatedAt: new Date().toISOString(),
  entryPoint: 'src/lib/worksheet.ts#generateWorksheet（与页面「导出 PDF」同一路径）',
  fontSource: 'public/fonts/NotoSansCJKsc-Regular.otf（通过本地 fetch 垫片提供）',
  fixture: FIXTURE,
  results,
  maxGeometryErrorPt: Number(Math.max(...results.flatMap(item => [item.widthErrorPt, item.heightErrorPt])).toFixed(4))
};
await writeFile(join(outDir, 'page-render.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');

console.log('站点真实入口产出的字帖：');
for (const item of results) {
  console.log(`  ${item.file.padEnd(38)} ${item.pageCount} 页  ${item.bytes} 字节  ${item.widthPt}×${item.heightPt} pt  误差 ${item.widthErrorPt}/${item.heightErrorPt} pt`);
}
console.log('');
console.log(`重新载入后的页数与生成时一致：${results.every(item => item.pageCount === item.reloadedPageCount) ? '是' : '否'}`);
console.log(`A4 几何最大误差：${report.maxGeometryErrorPt} pt（契约要求 ≤0.5 pt）`);
console.log(`报告已写入：${join(outDir, 'page-render.json')}`);
process.exit(report.maxGeometryErrorPt <= 0.5 ? 0 : 1);
