import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { PDFDocument, PDFName, PDFString, degrees, rgb } from 'pdf-lib';
import { createSplitGroups, cropPdfBytes, getPdfConversionOptions, markdownToText, mergePdfBytes, parsePageRange, PdfToolError, runPdfTool, splitPdfBytes, validateFiles, validateImageBytes, type PdfToolOptions } from '../src/lib/pdf-tools.ts';

async function sampleBytes(): Promise<Uint8Array> {
  const document = await PDFDocument.create();
  for (let index = 0; index < 3; index++) {
    const page = document.addPage([600 + index * 10, 800]);
    page.drawRectangle({ x: 10, y: 10, width: 100, height: 100, color: rgb(0.2, 0.5, 0.4) });
  }
  return document.save();
}

test('page ranges preserve explicit order and reject invalid boundaries', () => {
  assert.deepEqual(parsePageRange('3, 1-2, 2', 5), [2, 0, 1]);
  assert.deepEqual(parsePageRange(' ', 3), [0, 1, 2]);
  for (const invalid of ['0', '1-6', '4-2', '1,,2', '2.5', '-1', '1,']) {
    assert.throws(() => parsePageRange(invalid, 5), PdfToolError);
  }
});

test('split grouping includes the final partial chunk and validates custom groups', () => {
  assert.deepEqual(createSplitGroups(5, 'chunks', '', 2), [[0, 1], [2, 3], [4]]);
  assert.deepEqual(createSplitGroups(5, 'ranges', '1-2\n\n5,3', 1), [[0, 1], [4, 2]]);
  assert.throws(() => createSplitGroups(5, 'chunks', '', 0), PdfToolError);
  assert.throws(() => createSplitGroups(5, 'ranges', '', 1), PdfToolError);
});

test('real PDF merge and split preserve page counts and order', async () => {
  const input = await sampleBytes();
  const merged = await PDFDocument.load(await mergePdfBytes([input, input]));
  assert.equal(merged.getPageCount(), 6);
  assert.deepEqual(merged.getPages().map(page => page.getWidth()), [600, 610, 620, 600, 610, 620]);
  const split = await splitPdfBytes(input, [[2, 0], [1]]);
  const first = await PDFDocument.load(split[0]!);
  const second = await PDFDocument.load(split[1]!);
  assert.deepEqual(first.getPages().map(page => page.getWidth()), [620, 600]);
  assert.equal(second.getPageCount(), 1);
  await assert.rejects(splitPdfBytes(input, [[-1]]), PdfToolError);
  await assert.rejects(splitPdfBytes(input, Array.from({ length: 100 }, () => [0, 1, 2])), (error: unknown) => error instanceof PdfToolError && error.code === 'outputLimit');
});

test('vertical split produces proportionate halves and leaves unselected pages unchanged', async () => {
  const document = await PDFDocument.load(await cropPdfBytes(await sampleBytes(), [0], 'vertical', 25));
  assert.equal(document.getPageCount(), 4);
  assert.deepEqual(document.getPages().map(page => [page.getWidth(), page.getHeight()]), [[150, 800], [450, 800], [610, 800], [620, 800]]);
});

test('horizontal split uses a top-first ratio and supports reversed output order', async () => {
  const document = await PDFDocument.load(await cropPdfBytes(await sampleBytes(), [0], 'horizontal', 25, true));
  assert.deepEqual(document.getPages().slice(0, 2).map(page => [page.getWidth(), page.getHeight()]), [[600, 600], [600, 200]]);
  await assert.rejects(cropPdfBytes(await sampleBytes(), [0], 'vertical', 4), PdfToolError);
});

test('rotated pages produce an explicit limitation instead of a silently wrong crop', async () => {
  const source = await PDFDocument.load(await sampleBytes());
  source.getPage(0).setRotation(degrees(90));
  await assert.rejects(cropPdfBytes(await source.save(), [0], 'vertical', 50), (error: unknown) => error instanceof PdfToolError && error.code === 'rotatedPage');
});

test('blank pages and offset crop boxes remain valid when split', async () => {
  const source = await PDFDocument.create();
  const page = source.addPage([600, 800]);
  page.setCropBox(50, 60, 500, 700);
  const document = await PDFDocument.load(await cropPdfBytes(await source.save(), [0], 'vertical', 20));
  assert.deepEqual(document.getPages().map(item => [item.getWidth(), item.getHeight()]), [[100, 700], [400, 700]]);
});

test('annotated pages are rejected explicitly so visible annotations cannot disappear silently', async () => {
  const source = await PDFDocument.load(await sampleBytes());
  const annotation = source.context.register(source.context.obj({ Type: 'Annot', Subtype: 'Text', Rect: [10, 10, 80, 80] }));
  source.getPage(0).node.set(PDFName.of('Annots'), source.context.obj([annotation]));
  await assert.rejects(cropPdfBytes(await source.save(), [0], 'vertical', 50), (error: unknown) => error instanceof PdfToolError && error.code === 'annotatedPage');
});

test('basic Markdown output never includes raw HTML or remote image loading syntax', () => {
  assert.equal(markdownToText('# Title\n**Bold** [Link](https://example.com) ![Image](https://example.com/image.png)\n<b>Safe</b>'), 'Title\nBold Link [Image]\nSafe');
});

test('image conversion embeds a real PNG and produces the requested paper orientation', async () => {
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aEfkAAAAASUVORK5CYII=', 'base64');
  const options: PdfToolOptions = { tool: 'images', files: [new File([png], 'pixel.png', { type: 'image/png' })], content: '', pages: '', splitMode: 'chunks', groupRanges: '', chunkSize: 1, direction: 'vertical', ratio: 50, reverse: false, imageFormat: 'png', dpi: 144, paper: 'a4', landscape: true, margin: 15 };
  const result = await runPdfTool(options);
  const document = await PDFDocument.load(await result.files[0]!.blob.arrayBuffer());
  assert.equal(document.getPageCount(), 1);
  assert.equal(document.getPage(0).getWidth(), 841.89);
  assert.equal(document.getPage(0).getHeight(), 595.28);
});

test('input validation rejects unsupported formats and empty tasks', () => {
  assert.throws(() => validateFiles([], false), PdfToolError);
  assert.throws(() => validateFiles([new File(['text'], 'notes.txt')], false), PdfToolError);
  assert.throws(() => validateFiles([new File(['text'], 'document.pdf')], true), PdfToolError);
});

test('oversized compressed image headers are rejected before allocating a decoded bitmap', () => {
  const png = Buffer.alloc(24);
  png.writeUInt32BE(0x89504e47, 0); png.writeUInt32BE(0x0d0a1a0a, 4);
  png.writeUInt32BE(100_000, 16); png.writeUInt32BE(100_000, 20);
  assert.throws(() => validateImageBytes(png), (error: unknown) => error instanceof PdfToolError && error.code === 'imageLimit');
  assert.throws(() => validateImageBytes(new Uint8Array([1, 2, 3])), PdfToolError);
});

test('conversion reading configuration extracts real Chinese text from a non-embedded STSong PDF', async () => {
  const source = await PDFDocument.create();
  const context = source.context;
  const descriptor = context.register(context.obj({ Type: 'FontDescriptor', FontName: 'STSong-Light', Flags: 6, FontBBox: [-250, -250, 1000, 880], Ascent: 880, Descent: -120, CapHeight: 880, ItalicAngle: 0, StemV: 80 }));
  const descendant = context.register(context.obj({ Type: 'Font', Subtype: 'CIDFontType0', BaseFont: 'STSong-Light', CIDSystemInfo: { Registry: PDFString.of('Adobe'), Ordering: PDFString.of('GB1'), Supplement: 4 }, FontDescriptor: descriptor, DW: 1000 }));
  const font = context.register(context.obj({ Type: 'Font', Subtype: 'Type0', BaseFont: 'STSong-Light', Encoding: 'UniGB-UCS2-H', DescendantFonts: [descendant] }));
  const page = source.addPage([595, 842]);
  page.node.set(PDFName.of('Resources'), context.obj({ Font: { F1: font } }));
  page.node.set(PDFName.of('Contents'), context.register(context.flateStream('BT /F1 24 Tf 50 750 Td <4E2D6587> Tj ET')));
  const bytes = await source.save();
  const options = getPdfConversionOptions(bytes);
  assert.equal(options.cMapPacked, true);
  assert.equal(options.isEvalSupported, false);
  assert.equal(options.useSystemFonts, false);
  assert.notEqual(options.data, bytes);
  assert.match(options.cMapUrl, /\/pdfjs\/cmaps\/$/);
  assert.match(options.standardFontDataUrl, /\/pdfjs\/standard_fonts\/$/);
  // Node 读取同一组已发布资源，独立解析输出文字，避免仅断言配置本身。
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const document = await pdfjs.getDocument({
    ...options,
    cMapUrl: fileURLToPath(new URL(`../public${options.cMapUrl}`, import.meta.url)),
    standardFontDataUrl: fileURLToPath(new URL(`../public${options.standardFontDataUrl}`, import.meta.url)),
    useWorkerFetch: false,
  }).promise;
  try {
    const content = await (await document.getPage(1)).getTextContent();
    assert.equal(content.items.map(item => 'str' in item ? item.str : '').join(''), '\u4e2d\u6587');
    assert.ok(bytes.byteLength > 0);
  } finally { await document.destroy(); }
});

test('text and Markdown generation reject missing glyphs anywhere while supported Chinese and accents still export', async () => {
  const fontBytes = new Uint8Array(await readFile(new URL('../public/fonts/NotoSansCJKsc-Regular.otf', import.meta.url)));
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(fontBytes);
  const base: PdfToolOptions = { tool: 'text', files: [], content: '', pages: '', splitMode: 'chunks', groupRanges: '', chunkSize: 1, direction: 'vertical', ratio: 50, reverse: false, imageFormat: 'png', dpi: 144, paper: 'a4', landscape: false, margin: 15 };
  try {
    for (const content of ['\u{1f600} hello', 'hello \u{1f600} end', 'hello \u{1f600}', 'a'.repeat(2000) + '\u{1f600}']) {
      await assert.rejects(runPdfTool({ ...base, content }), (error: unknown) => error instanceof PdfToolError && error.code === 'missingGlyph');
    }
    for (const content of ['# \u{1f600}\n\nSupported body.', '# Supported title\n\nEnd \u{1f600}']) {
      await assert.rejects(runPdfTool({ ...base, tool: 'markdown', content }), (error: unknown) => error instanceof PdfToolError && error.code === 'missingGlyph');
    }
    const result = await runPdfTool({ ...base, content: '\u4e2d\u6587\nhello\tsh\u016b b\u0101o\r\nend' });
    const document = await PDFDocument.load(await result.files[0]!.blob.arrayBuffer());
    assert.equal(document.getPageCount(), 1);
  } finally { globalThis.fetch = previousFetch; }
});
