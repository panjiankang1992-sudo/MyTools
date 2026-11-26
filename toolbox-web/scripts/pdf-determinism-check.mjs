import { readFile as rf } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const of = globalThis.fetch;
globalThis.fetch = async (i, init) => {
  const u = typeof i === 'string' ? i : i?.url ?? String(i);
  if (u.startsWith('/')) { const b = await rf(join('/Users/pankang/mycode/MyTools/toolbox-web/public', u.replace(/^\//u,'').split('?')[0])); return new Response(b, { status: 200 }); }
  return of(i, init);
};
const { generateWorksheet } = await import('/Users/pankang/mycode/MyTools/toolbox-web/src/lib/worksheet.ts');
const sha = (b) => createHash('sha256').update(b).digest('hex').slice(0, 16);
const base = [{ text: '山', pinyin: 'shān', meaning: '' }, { text: '水', pinyin: 'shuǐ', meaning: '' }];
const edited = [{ text: '山MARK123', pinyin: 'shān', meaning: '' }, { text: '水', pinyin: 'shuǐ', meaning: '' }];
const opts = { mode: 'chinese-pinyin', title: '练习字帖', grade: '一年级', gridMm: 12, repetitions: 1, answer: false, expandedQuestions: true };
const a1 = await generateWorksheet(base, opts);
const a2 = await generateWorksheet(base, opts);
const b1 = await generateWorksheet(edited, opts);
console.log('同输入两次渲染:', sha(a1.bytes) === sha(a2.bytes) ? '逐字节相同（确定性 ✓）' : `不同 ✗ ${sha(a1.bytes)} vs ${sha(a2.bytes)}`);
console.log('原始  vs 编辑后:', sha(a1.bytes) === sha(b1.bytes) ? '相同 ✗（编辑没影响 PDF）' : `不同 ✓ ${sha(a1.bytes)} vs ${sha(b1.bytes)}`);
if (typeof a1.extractText === 'function') { try { console.log('extractText(edited):', String(await a1.extractText()).slice(0,120)); } catch {} }
