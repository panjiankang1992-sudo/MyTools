import fs from 'node:fs/promises';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { PDFDocument } from '../../../toolbox-web/node_modules/pdf-lib/cjs/index.js';
import { getDocument } from '../../../toolbox-web/node_modules/pdfjs-dist/legacy/build/pdf.mjs';
const base = new URL('../../../output/pdf/worksheet-once-print-2026-10-05/', import.meta.url);
const checks = [
 ['chinese-pinyin','秋天','qiū'],
 ['chinese-dictation','qiū','秋天'],
 ['english-dictation','星期二','Tuesday'],
 ['english-meaning','Tuesday','星期二']
];
const combined = await PDFDocument.create();
const report = [];
for (const [name,prompt,answer] of checks) {
 const original = await fs.readFile(new URL(name+'.pdf',base));
 const exported = await fs.readFile(new URL(name+'-from-print.pdf',base));
 assert.ok(original.equals(exported), name+' print download mismatch');
 const source = await getDocument({data:new Uint8Array(original),verbosity:0}).promise;
 assert.equal(source.numPages,1);
 const text = (await (await source.getPage(1)).getTextContent()).items.map(x=>x.str||'').join('').normalize('NFC').replace(/\s/gu,'');
 assert.ok(text.includes(prompt), name+' missing prompt: '+text);
 assert.ok(!text.includes(answer), name+' leaked answer: '+text);
 const printed = await PDFDocument.load(await fs.readFile(new URL(name+'-print-layout.pdf',base)));
 assert.equal(printed.getPageCount(),1,name+' unexpected blank pages');
 const size = printed.getPage(0).getSize();
 assert.ok(Math.abs(size.width-595.276)<1 && Math.abs(size.height-841.89)<1,name+' not A4');
 if(name!=='english-meaning') { const [page] = await combined.copyPages(printed,[0]); combined.addPage(page); }
 report.push({name,pages:1,printPages:1,size,promptPresent:true,answerHidden:true,printExportIdentical:true,sha256:crypto.createHash('sha256').update(original).digest('hex')});
 await source.destroy();
}
await fs.writeFile(new URL('physical-three-modes.pdf',base),await combined.save());
await fs.writeFile(new URL('pdf-inspection.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
