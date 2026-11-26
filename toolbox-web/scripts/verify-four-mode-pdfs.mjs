// 独立核验四题型 PDF：重新加载每份文件，逐份报告真实页数与页面尺寸。
import { readFile } from 'node:fs/promises';
import { PDFDocument } from 'pdf-lib';
const OUT = '/Users/pankang/mycode/MyTools/docs/verification/toolbox-admin-completion-2026-10-07/pdfs';
const report = JSON.parse(await readFile(`${OUT}/report.json`, 'utf8'));
let bad = 0;
for (const r of report) {
  const bytes = await readFile(`${OUT}/${r.file}`);
  const doc = await PDFDocument.load(bytes);
  const pages = doc.getPageCount();
  const { width, height } = doc.getPage(0).getSize();
  const ok = pages >= 3 && r.file.includes(`${pages}p`);
  if (!ok) bad += 1;
  console.log(`${ok ? 'OK ' : 'BAD'} ${r.mode}: ${pages} 页 / ${bytes.length} 字节 / ${Math.round(width)}x${Math.round(height)}pt`);
}
console.log(bad === 0 ? '四题型均达到 ≥3 页且文件名与真实页数一致' : `有 ${bad} 份不合格`);
process.exit(bad === 0 ? 0 : 1);
