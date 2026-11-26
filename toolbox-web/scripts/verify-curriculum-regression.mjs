#!/usr/bin/env node
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * W43：已发布教材回归。
 *
 * 契约要求「执行时全部已发布版本均回归；至少形成 10 条版本×模式记录」。
 * 本脚本对每个已发布版本各生成两种模式的字帖，并记录页数、几何与条目数。
 *
 * 数据取自 `content/textbooks/`（与后端 `/public/v1` 提供的是同一份内容），
 * 生成走 `src/lib/worksheet.ts#generateWorksheet`（与页面「导出 PDF」同一入口）。
 */

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const contentDir = join(root, 'content', 'textbooks');
const outDir = join(root, '..', 'docs', 'verification', 'worksheet-workflow-v2', 'samples');

// 把同域请求映射到本地 public/，让 Node 具备浏览器的取字体能力。
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

const { generateWorksheet, selectEntries } = await import('../src/lib/worksheet.ts');

/** 每个已发布版本使用的模式（按学科）。 */
const MODES_BY_SUBJECT = {
  chinese: ['chinese-dictation', 'chinese-pinyin'],
  english: ['english-dictation', 'english-meaning']
};

/** 读取一个版本的条目。 */
async function loadEdition(editionId, version) {
  const book = JSON.parse(await readFile(join(contentDir, editionId, version, 'book.json'), 'utf8'));
  return Array.isArray(book) ? book : (book.entries ?? []);
}

/** 把教材条目转成生成器需要的形状。 */
function toEntries(raw) {
  return raw.map((entry, index) => ({
    id: entry.entryId ?? `e${index}`,
    text: entry.text,
    pinyin: (entry.pinyinSegments ?? []).map(segment => segment.syllable).join(' '),
    meaning: entry.meaning ?? '',
    unit: 1,
    kind: entry.form === 'word' ? 'word' : 'character'
  }));
}

const editions = [];
for (const editionId of (await readdir(contentDir)).sort()) {
  const versions = (await readdir(join(contentDir, editionId))).sort();
  const version = versions[versions.length - 1];
  const raw = await loadEdition(editionId, version);
  const subject = editionId.startsWith('en-') ? 'english' : 'chinese';
  editions.push({ editionId, version, subject, entryCount: raw.length, entries: toEntries(raw) });
}

const records = [];
for (const edition of editions) {
  for (const mode of MODES_BY_SUBJECT[edition.subject]) {
    const record = { editionId: edition.editionId, version: edition.version, subject: edition.subject, mode, entryCount: edition.entryCount };
    try {
      // 契约 §2.3：全册超 200 项时按数量抽取，不自动截断。
      const count = Math.min(200, edition.entries.length);
      const picked = edition.entries.length > count
        ? selectEntries(edition.entries, count, `${edition.editionId}-${mode}`)
        : edition.entries;
      const artifact = await generateWorksheet(picked, {
        mode, title: '教材回归', grade: 1, gridMm: 12, repetitions: 1, answer: false
      });
      const { PDFDocument } = await import('pdf-lib');
      const document = await PDFDocument.load(artifact.bytes);
      const { width, height } = document.getPage(0).getSize();
      record.ok = true;
      record.usedEntries = picked.length;
      record.pageCount = artifact.pageCount;
      record.reloadedPageCount = document.getPageCount();
      record.bytes = artifact.bytes.byteLength;
      record.widthPt = Number(width.toFixed(4));
      record.heightPt = Number(height.toFixed(4));
      record.geometryErrorPt = Number(Math.max(
        Math.abs(width - 595.2755905511812), Math.abs(height - 841.8897637795277)
      ).toFixed(4));
    } catch (error) {
      record.ok = false;
      record.error = String(error?.message ?? error);
    }
    records.push(record);
  }
}

const report = {
  generatedAt: new Date().toISOString(),
  entryPoint: 'src/lib/worksheet.ts#generateWorksheet（与页面「导出 PDF」同一入口）',
  contentSource: 'content/textbooks/（与后端 /public/v1 同一份内容）',
  editionCount: editions.length,
  recordCount: records.length,
  allPassed: records.every(record => record.ok === true),
  records
};
await writeFile(join(outDir, 'curriculum-regression.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');

for (const record of records) {
  const outcome = record.ok
    ? `${record.pageCount} 页 · ${record.bytes} 字节 · ${record.widthPt}×${record.heightPt} pt · 误差 ${record.geometryErrorPt} pt`
    : `失败：${record.error}`;
  console.log(`${record.editionId.padEnd(22)} ${record.mode.padEnd(18)} ${outcome}`);
}
console.log('');
console.log(`版本 ${report.editionCount} 个，记录 ${report.recordCount} 条（契约要求 ≥10），全部通过：${report.allPassed ? '是' : '否'}`);
console.log(`报告已写入：${join(outDir, 'curriculum-regression.json')}`);
process.exit(report.allPassed && report.recordCount >= 10 ? 0 : 1);
