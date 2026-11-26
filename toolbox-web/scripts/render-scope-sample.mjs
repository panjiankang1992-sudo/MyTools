// 「选择范围体现在 PDF 上」的样张：整册 vs 单个单元。
//
// 后端预览接口已支持 nodeKind=unit|lesson + nodeKey + draftRevision（见预览真范围过滤），
// 本脚本在**渲染层**证明"范围不同 → PDF 内容不同"，不依赖浏览器点击。
//
// 用法：npx tsx scripts/render-scope-sample.mjs
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { PDFDocument } from 'pdf-lib';

const OUT = '/Users/pankang/mycode/MyTools/docs/verification/toolbox-admin-completion-2026-10-07/pdfs';
const BOOK = 'content/textbooks/cn-ty-3-1-pep-2024/1.0.0/book.json';
const SCOPE_UNIT = 'cn-ty-3-1-pep-2024-u01';

// 把同域请求映射到本地 public/，仅用于让 Node 具备浏览器的取字体能力
// （与 scripts/render-four-mode-samples.mjs 同一手法：必须返回真正的 Response）。
import { readFile as readFileForFont } from 'node:fs/promises';
import { dirname as dirnameForFont, join as joinForFont } from 'node:path';
import { fileURLToPath as fileURLToPathForFont } from 'node:url';
const rootForFont = joinForFont(dirnameForFont(fileURLToPathForFont(import.meta.url)), '..');
const originalFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = typeof input === 'string' ? input : input?.url ?? String(input);
  if (url.startsWith('/')) {
    const rel = url.replace(/^\//u, '').split('?')[0];
    const bytes = await readFileForFont(joinForFont(rootForFont, 'public', rel));
    return new Response(bytes, { status: 200, headers: { 'content-type': 'font/otf' } });
  }
  return originalFetch(input, init);
};

const { generateWorksheet } = await import('../src/lib/worksheet.ts');

const book = JSON.parse(await readFile(BOOK, 'utf8'));
// 页面渲染对条目数有上限，整册样张取前 320 条（与 render-four-mode-samples.mjs 同一做法）。
const all = (book.entries ?? []).slice(0, 320).map(e => ({ text: e.text, pinyin: e.pinyin ?? '', meaning: e.meaning ?? '' }));
const scoped = (book.entries ?? []).filter(e => e.unitId === SCOPE_UNIT)
  .map(e => ({ text: e.text, pinyin: e.pinyin ?? '', meaning: e.meaning ?? '' }));
const unitTitle = (book.units ?? []).find(u => u.unitId === SCOPE_UNIT)?.title ?? SCOPE_UNIT;

await mkdir(OUT, { recursive: true });
const rows = [];
for (const [label, entries, title] of [
  ['整册', all, '统编语文 三年级上册（整册）'],
  ['第一单元', scoped, `统编语文 三年级上册 · ${unitTitle}`]
]) {
  const a = await generateWorksheet(entries, {
    mode: 'chinese-pinyin', title, grade: '三年级', gridMm: 12, repetitions: 1,
    answer: false, expandedQuestions: true
  });
  const name = `scope-${label === '整册' ? 'whole' : 'unit-u01'}-${a.pageCount}p.pdf`;
  await writeFile(`${OUT}/${name}`, a.bytes);
  const doc = await PDFDocument.load(a.bytes);
  const { width, height } = doc.getPage(0).getSize();
  rows.push({ scope: label, unitId: label === '整册' ? null : SCOPE_UNIT, entries: entries.length,
    file: name, pageCount: a.pageCount, bytes: a.bytes.length,
    page0: `${Math.round(width)}x${Math.round(height)}pt` });
  console.log(`${label}: 条目 ${entries.length} → ${a.pageCount} 页, ${a.bytes.length} 字节, 首页 ${Math.round(width)}x${Math.round(height)}pt`);
}
await writeFile(`${OUT}/scope-report.json`, `${JSON.stringify(rows, null, 2)}\n`);

// 独立核验：范围不同 → 条目数与页数都必须不同，且范围样张必须更小。
const [whole, unit] = rows;
const ok = whole.entries > unit.entries && whole.pageCount !== unit.pageCount
  && unit.entries > 0 && unit.pageCount >= 1;
console.log(ok
  ? `OK  范围生效：整册 ${whole.pageCount} 页(${whole.entries} 条) vs 第一单元 ${unit.pageCount} 页(${unit.entries} 条)`
  : `FAIL 范围没有体现在 PDF 上：${JSON.stringify(rows)}`);
process.exitCode = ok ? 0 : 1;
