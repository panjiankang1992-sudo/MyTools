import { readFile as readFileForFont } from 'node:fs/promises';
import { dirname as dirnameForFont, join as joinForFont } from 'node:path';
import { fileURLToPath as fileURLToPathForFont } from 'node:url';
const rootForFont = joinForFont(dirnameForFont(fileURLToPathForFont(import.meta.url)), '..');

// 把同域请求映射到本地 public/，仅用于让 Node 具备浏览器的取字体能力
// （与 scripts/render-worksheet-sample.mjs 同一手法）。
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

import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { PDFDocument } from 'pdf-lib';
const { generateWorksheet } = await import('../src/lib/worksheet.ts');
const OUT = '/Users/pankang/mycode/MyTools/docs/verification/toolbox-admin-completion-2026-10-07/pdfs';
await mkdir(OUT, { recursive: true });

const cn = JSON.parse(await readFile('content/textbooks/cn-ty-3-1-pep-2024/1.0.0/book.json', 'utf8'));
const en = JSON.parse(await readFile('content/textbooks/en-yl-3-1-yilin-2024/1.0.1/book.json', 'utf8'));

/** 取真实条目；不带拼音/释义的条目跳过（否则生成的样张会有空栏）。 */
const cnEntries = cn.entries.filter(e => e.text && e.pinyinDisplay).slice(0, 320).map((e, i) => ({
  id: e.entryId, text: e.text, pinyin: e.pinyinDisplay, meaning: '', unit: 1, kind: e.form
}));
const enEntries = en.entries.filter(e => e.text && e.meaning).slice(0, 100).map((e, i) => ({
  id: e.entryId, text: e.text, pinyin: '', meaning: e.meaning, unit: 1, kind: e.form
}));
console.log('中文条目', cnEntries.length, '英语条目', enEntries.length);

const jobs = [
  ['chinese-dictation', cnEntries, '汉字听写', 1, 'chinese'],
  ['chinese-pinyin', cnEntries, '拼音字帖', 1, 'chinese'],
  ['english-dictation', enEntries, '英语默写', 3, 'english'],
  ['english-meaning', enEntries, '中文释义练习', 3, 'english']
];
const report = [];
// 学生卷与答案卷各出一份：答案卷是设计里"四题型"的一部分，不只是学生卷。
for (const [mode, entries, title, grade] of jobs) {
  for (const answer of [false, true]) {
    const a = await generateWorksheet(entries, { mode, title, grade, gridMm: 12, repetitions: 1, answer, expandedQuestions: true });
    const name = `${mode}${answer ? '-answer' : ''}-${a.pageCount}p.pdf`;
    await writeFile(`${OUT}/${name}`, a.bytes);
    const doc = await PDFDocument.load(a.bytes);
    const { width, height } = doc.getPage(0).getSize();
    report.push({ mode, answer, file: name, entries: entries.length, pageCount: a.pageCount,
      bytes: a.bytes.length, page0: `${Math.round(width)}x${Math.round(height)}pt` });
    console.log(`  ${mode}${answer ? '(答案卷)' : ''}: ${a.pageCount} 页, ${a.bytes.length} 字节, 首页 ${Math.round(width)}x${Math.round(height)}pt`);
    if (a.pageCount < 3) console.log(`  ⚠️ ${name} 不足三页`);
  }
}
await writeFile(`${OUT}/report.json`, JSON.stringify(report, null, 2) + '\n');
console.log('完成');
