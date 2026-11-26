import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { after, before, test } from 'node:test';
import { PDFDocument } from 'pdf-lib';
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs';
import { useWorksheetV2Session } from '../src/composables/useWorksheetV2Session';
import type { WorksheetArtifact, WorksheetMode } from '../src/types';

interface PdfInspection {
  file: string; sha256: string; bytes: number; pageCount: number;
  pageSizes: Array<{ width: number; height: number }>;
  textPages: string[]; questionNumbers: number[]; metadata: unknown;
  attachments: unknown; javascript: unknown;
}
interface EvidenceResult {
  name: string; status: 'pass' | 'fail'; error?: string; files: PdfInspection[];
}
const results: EvidenceResult[] = [];
const fontRequests: string[] = [];
const originalFetch = globalThis.fetch;
const evidenceRoot = process.env.WORKSHEET_PDF_EVIDENCE_DIR;
const outputDirectory = evidenceRoot ? join(resolve(evidenceRoot), `run-${new Date().toISOString().replace(/[:.]/g, '-')}-${process.pid}`) : null;
const fontPath = new URL('../public/fonts/NotoSansCJKsc-Regular.otf', import.meta.url);
let fontSha = '';

before(async () => {
  const fontBytes = new Uint8Array(await readFile(fontPath));
  fontSha = createHash('sha256').update(fontBytes).digest('hex');
  if (outputDirectory) await mkdir(outputDirectory, { recursive: true });
  globalThis.fetch = async input => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    // 所有请求只允许映射到当前仓库字体，禁止回落到真实网络。
    assert.equal(url, '/fonts/NotoSansCJKsc-Regular.otf', `Unexpected network request: ${url}`);
    fontRequests.push(url);
    return new Response(fontBytes, { status: 200, headers: { 'content-type': 'font/otf' } });
  };
});
after(async () => {
  globalThis.fetch = originalFetch;
  if (outputDirectory) {
    await writeFile(join(outputDirectory, 'results.json'), `${JSON.stringify({
      generatedAt: new Date().toISOString(), entryPoint: 'useWorksheetV2Session -> generateWorksheet',
      executionBoundary: 'Local composable and real PDF generation, not browser button download, OCR, print or W39 completion',
      environment: { node: process.version, platform: process.platform },
      font: { path: fontPath.pathname, sha256: fontSha, requests: fontRequests },
      results
    }, null, 2)}\n`, { flag: 'wx' });
    console.log(`PDF regression evidence: ${outputDirectory}`);
  }
});

/** 去掉提取器增加的字间空白，保留大小写、声调及所有可见字符。 */
function compact(value: string): string { return value.normalize('NFC').replace(/\s/gu, ''); }

/** 创建真实 composable，仅隔离持久存储、统计和对象地址释放边界。 */
function sessionFor(mode: WorksheetMode, translate: NonNullable<Parameters<typeof useWorksheetV2Session>[1]>['translate'] = async () => []) {
  const stored = new Map<string, string>();
  return useWorksheetV2Session(mode.startsWith('chinese') ? 'chinese' : 'english', {
    storage: { getItem: key => stored.get(key) ?? null, setItem: (key, value) => { stored.set(key, value); }, removeItem: key => { stored.delete(key); } },
    recordSuccess: () => {}, recordPrint: () => {}, translate, release: artifact => { URL.revokeObjectURL(artifact.url); }
  });
}

/** 读取实际 PDF 的每一页、文本层、元数据与附件，独立检查签名及 A4 几何。 */
async function inspect(artifact: WorksheetArtifact, filename: string, evidence: EvidenceResult): Promise<PdfInspection> {
  assert.equal(Buffer.from(artifact.bytes.subarray(0, 5)).toString('ascii'), '%PDF-');
  if (outputDirectory) await writeFile(join(outputDirectory, filename), artifact.bytes, { flag: 'wx' });
  const pdfLib = await PDFDocument.load(artifact.bytes);
  const document = await getDocument({ data: artifact.bytes.slice(), useWorkerFetch: false, isEvalSupported: false, useSystemFonts: false }).promise;
  try {
    const textPages: string[] = [];
    const pageSizes: PdfInspection['pageSizes'] = [];
    for (let number = 1; number <= document.numPages; number += 1) {
      const page = await document.getPage(number);
      const viewport = page.getViewport({ scale: 1 });
      pageSizes.push({ width: viewport.width, height: viewport.height });
      const content = await page.getTextContent();
      textPages.push(content.items.map(item => 'str' in item ? item.str : '').join('\n'));
      assert.ok(Math.abs(viewport.width - 210 * 72 / 25.4) < 0.5, `Page ${number} must have A4 width`);
      assert.ok(Math.abs(viewport.height - 297 * 72 / 25.4) < 0.5, `Page ${number} must have A4 height`);
    }
    const metadata = await document.getMetadata();
    const result: PdfInspection = {
      file: filename, sha256: createHash('sha256').update(artifact.bytes).digest('hex'), bytes: artifact.bytes.length,
      pageCount: document.numPages, pageSizes, textPages,
      questionNumbers: [...textPages.join('\n').matchAll(/(?:^|\n)(\d+)\./g)].map(match => Number(match[1])),
      metadata: { info: metadata.info, metadata: metadata.metadata?.getRaw() ?? null },
      attachments: await document.getAttachments(), javascript: await document.getJSActions()
    };
    evidence.files.push(result);
    assert.equal(document.numPages, artifact.pageCount, 'Independent parser sees all generated pages');
    assert.equal(pdfLib.getPageCount(), document.numPages, 'Two independent readers agree on page count');
    assert.equal(result.attachments, null, 'No hidden attachments');
    assert.equal(result.javascript, null, 'No embedded scripts');
    return result;
  } finally { await document.destroy(); }
}

/** 对应题号后的真实文本必须保持输入顺序，重复只按轮展开一次。 */
function assertPrompts(inspection: PdfInspection, expected: string[]): void {
  assert.deepEqual(inspection.questionNumbers, expected.map((_, index) => index + 1), 'No doubled, missing or reordered question numbers');
  const text = compact(inspection.textPages.join('\n'));
  expected.forEach((prompt, index) => {
    assert.ok(text.includes(`${index + 1}.${compact(prompt)}`), `Question ${index + 1} must retain prompt ${prompt}`);
  });
}

const fixtures: Array<{ mode: WorksheetMode; rows: string; prompts: string[]; answers: string[] }> = [
  { mode: 'chinese-pinyin', rows: '红狐|hóng hú\n蓝鲸|lán jīng', prompts: ['红狐', '蓝鲸'], answers: ['hóng hú', 'lán jīng'] },
  { mode: 'chinese-dictation', rows: '红狐|hóng hú\n蓝鲸|lán jīng', prompts: ['hóng hú', 'lán jīng'], answers: ['红狐', '蓝鲸'] },
  { mode: 'english-dictation', rows: 'otter|水獭\n"ice cream"|冰淇淋', prompts: ['水獭', '冰淇淋'], answers: ['otter', 'ice cream'] },
  { mode: 'english-meaning', rows: 'otter|水獭\n"ice cream"|冰淇淋', prompts: ['otter', 'ice cream'], answers: ['水獭', '冰淇淋'] }
];
for (const fixture of fixtures) {
  test(`real PDF ${fixture.mode}: student/answer isolation and two repetitions preserve four questions`, async () => {
    const evidence: EvidenceResult = { name: fixture.mode, status: 'fail', files: [] };
    results.push(evidence);
    const session = sessionFor(fixture.mode);
    try {
      session.importPairedRows(fixture.rows);
      assert.equal(session.session.value.document.tokens.length, 2);
      session.applyMode(fixture.mode);
      session.applyProcessing({ repetitions: 2 });
      session.applyLayout({ title: 'PDF regression fixture', gridMm: 12 });
      await session.generateNow();
      assert.equal(session.canOutput.value, true, JSON.stringify(session.blockers.value));
      assert.ok(session.artifact.value);
      const studentBytes = session.artifact.value.bytes.slice();
      const student = await inspect(session.artifact.value, `${fixture.mode}-student.pdf`, evidence);
      assertPrompts(student, [...fixture.prompts, ...fixture.prompts]);
      await session.generateAnswerNow();
      assert.equal(session.canOutputAnswer.value, true, JSON.stringify(session.blockers.value));
      assert.ok(session.answerArtifact.value);
      const answer = await inspect(session.answerArtifact.value, `${fixture.mode}-answer.pdf`, evidence);
      assertPrompts(answer, [...fixture.prompts, ...fixture.prompts]);
      const studentText = compact(student.textPages.join('\n'));
      const answerText = compact(answer.textPages.join('\n'));
      for (const uniqueAnswer of fixture.answers) {
        assert.equal(studentText.includes(compact(uniqueAnswer)), false, `Student text must not include answer: ${uniqueAnswer}`);
        assert.equal(compact(JSON.stringify(student.metadata)).includes(compact(uniqueAnswer)), false, 'Metadata must not include answer');
        assert.equal(answerText.includes(compact(uniqueAnswer)), true, `Answer PDF must contain answer: ${uniqueAnswer}`);
      }
      assert.deepEqual(session.artifact.value.bytes, studentBytes, 'Generating answers does not change student bytes');
      assert.notEqual(student.sha256, answer.sha256, 'Student and answer are separate actual files');
      session.applyRawText(fixture.mode.startsWith('chinese') ? '山 水' : 'new words');
      assert.equal(session.canOutput.value, false, 'Editing invalidates student output');
      assert.equal(session.canOutputAnswer.value, false, 'Editing invalidates old answers');
      evidence.status = 'pass';
    } catch (error) {
      evidence.error = error instanceof Error ? error.stack ?? error.message : String(error);
      throw error;
    } finally { session.dispose(); }
  });
}

test('real multi-page PDF keeps 100 questions and all page text after 50 entries repeat twice', async () => {
  const evidence: EvidenceResult = { name: 'multi-page-100', status: 'fail', files: [] };
  results.push(evidence);
  const session = sessionFor('english-meaning');
  try {
    const words = Array.from({ length: 50 }, (_, index) => `term${String(index + 1).padStart(3, '0')}`);
    session.importPairedRows(words.map((word, index) => `${word}|释义${index + 1}`).join('\n'));
    session.applyMode('english-meaning');
    session.applyProcessing({ repetitions: 2 });
    session.applyLayout({ title: 'Multi page regression fixture', gridMm: 12 });
    await session.generateNow();
    assert.equal(session.canOutput.value, true, JSON.stringify(session.blockers.value));
    assert.ok(session.artifact.value);
    const student = await inspect(session.artifact.value, 'multi-page-student.pdf', evidence);
    assert.ok(student.pageCount > 1, 'Fixture must actually create multiple pages');
    assertPrompts(student, [...words, ...words]);
    student.textPages.forEach((text, index) => {
      assert.ok(compact(text).includes(`第${index + 1}/${student.pageCount}页`), 'Every page retains its correct page counter');
      assert.equal(text.includes('释义'), false, 'No answer meaning may leak on any student page');
    });
    await session.generateAnswerNow();
    assert.equal(session.canOutputAnswer.value, true, JSON.stringify(session.blockers.value));
    assert.ok(session.answerArtifact.value);
    const answer = await inspect(session.answerArtifact.value, 'multi-page-answer.pdf', evidence);
    assertPrompts(answer, [...words, ...words]);
    assert.ok(compact(answer.textPages.join('\n')).includes('释义50'), 'Last answer is present in actual parsed file');
    evidence.status = 'pass';
  } catch (error) {
    evidence.error = error instanceof Error ? error.stack ?? error.message : String(error);
    throw error;
  } finally { session.dispose(); }
});

test('automatic fields reach real PDF: Chinese weekday input and contextual pinyin', async () => {
  for (const subject of ['english', 'chinese'] as const) {
    const evidence: EvidenceResult = { name: `automatic-${subject}`, status: 'fail', files: [] };
    results.push(evidence);
    const chineseDays = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期天'];
    const englishDays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
    const session = sessionFor(subject === 'english' ? 'english-dictation' : 'chinese-dictation', async items => items.map(item => ({
      id: item.id, sourceText: item.text, englishText: englishDays[chineseDays.indexOf(item.text)]!, meaning: item.text, status: 'translated'
    })));
    try {
      session.applyRawText(subject === 'english' ? chineseDays.join(' ') : '长大 银行');
      session.applyMode(subject === 'english' ? 'english-dictation' : 'chinese-dictation');
      await session.generateNow();
      assert.equal(session.canOutput.value, true, JSON.stringify(session.blockers.value));
      const student = await inspect(session.artifact.value!, `automatic-${subject}-student.pdf`, evidence);
      assertPrompts(student, subject === 'english' ? chineseDays : ['zhǎng dà', 'yín háng']);
      await session.generateAnswerNow();
      assert.equal(session.canOutputAnswer.value, true, JSON.stringify(session.blockers.value));
      const answer = await inspect(session.answerArtifact.value!, `automatic-${subject}-answer.pdf`, evidence);
      for (const expected of subject === 'english' ? englishDays : ['长大', '银行']) {
        assert.equal(compact(student.textPages.join('')).includes(compact(expected)), false);
        assert.equal(compact(answer.textPages.join('')).includes(compact(expected)), true);
      }
      evidence.status = 'pass';
    } finally { session.dispose(); }
  }
});
