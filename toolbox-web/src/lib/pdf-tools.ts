import { PDFDocument, rgb, type PDFFont } from 'pdf-lib';
import JSZip from 'jszip';
import { getPdfDocumentOptions } from './pdf-runtime';

export const pdfToolIds = ['images', 'text', 'markdown', 'merge', 'split', 'crop', 'render', 'extract'] as const;
export type PdfToolId = typeof pdfToolIds[number];
export type PdfErrorCode = 'invalidPages' | 'pageLimit' | 'outputLimit' | 'fileLimit' | 'fileType' | 'fileRequired' | 'mergeRequired' | 'invalidRatio' | 'invalidChunk' | 'emptyContent' | 'contentLimit' | 'rotatedPage' | 'annotatedPage' | 'imageLimit' | 'noText' | 'invalidDpi' | 'unsupportedPdf' | 'invalidMargin' | 'missingGlyph';

/** 表示可在界面中本地化展示的 PDF 参数或处理错误。 */
export class PdfToolError extends Error {
  /** 使用稳定错误码建立异常。 */
  constructor(public readonly code: PdfErrorCode) { super(code); }
}

export interface PdfToolOptions {
  tool: PdfToolId;
  files: File[];
  content: string;
  pages: string;
  splitMode: 'ranges' | 'chunks';
  groupRanges: string;
  chunkSize: number;
  direction: 'vertical' | 'horizontal';
  ratio: number;
  reverse: boolean;
  imageFormat: 'png' | 'jpg';
  dpi: number;
  paper: 'a4' | 'a5' | 'letter';
  landscape: boolean;
  margin: number;
}
export interface PdfOutput { name: string; blob: Blob; pages?: number; }
export interface PdfToolResult { files: PdfOutput[]; text?: string; }
export const PDF_LIMITS = { fileBytes: 30 * 1024 * 1024, totalBytes: 50 * 1024 * 1024, files: 30, pages: 100, outputPages: 200, imagePages: 30, imagePixels: 24_000_000, totalImagePixels: 72_000_000, textBytes: 256 * 1024 };

/** 解析用户输入的页码并保留指定顺序，结果为零基页索引。 */
export function parsePageRange(value: string, count: number): number[] {
  if (!Number.isInteger(count) || count < 1 || count > PDF_LIMITS.pages) throw new PdfToolError('pageLimit');
  if (!value.trim()) return Array.from({ length: count }, (_, index) => index);
  const pages: number[] = [];
  for (const part of value.split(',')) {
    // 只接受正整数和递增区间，避免静默截断错误输入。
    const match = /^(\d+)\s*(?:-\s*(\d+))?$/.exec(part.trim());
    if (!match) throw new PdfToolError('invalidPages');
    const first = Number(match[1]);
    const last = Number(match[2] ?? match[1]);
    if (first < 1 || last > count || first > last) throw new PdfToolError('invalidPages');
    for (let page = first; page <= last; page++) {
      // 同一输出内的重复页仅保留第一次出现的位置。
      if (!pages.includes(page - 1)) pages.push(page - 1);
    }
  }
  return pages;
}

/** 按固定页数或逐行页码范围生成拆分分组。 */
export function createSplitGroups(count: number, mode: 'ranges' | 'chunks', ranges: string, chunkSize: number): number[][] {
  const all = parsePageRange('', count);
  if (mode === 'chunks') {
    if (!Number.isInteger(chunkSize) || chunkSize < 1 || chunkSize > count) throw new PdfToolError('invalidChunk');
    return Array.from({ length: Math.ceil(count / chunkSize) }, (_, index) => all.slice(index * chunkSize, (index + 1) * chunkSize));
  }
  const lines = ranges.split('\n').map(line => line.trim()).filter(Boolean);
  if (!lines.length || lines.length > 100) throw new PdfToolError('invalidPages');
  return lines.map(line => parsePageRange(line, count));
}

/** 校验浏览器任务的输入文件数量、格式和字节配额。 */
export function validateFiles(files: File[], images: boolean): void {
  if (!files.length) throw new PdfToolError('fileRequired');
  if (files.length > PDF_LIMITS.files || files.some(file => file.size > PDF_LIMITS.fileBytes) || files.reduce((sum, file) => sum + file.size, 0) > PDF_LIMITS.totalBytes) throw new PdfToolError('fileLimit');
  const extension = images ? /\.(png|jpe?g)$/i : /\.pdf$/i;
  if (files.some(file => !extension.test(file.name))) throw new PdfToolError('fileType');
}

/** 在解码图片前检查像素尺寸，防止高压缩图片耗尽浏览器内存。 */
export function validateImageBytes(bytes: Uint8Array): number {
  const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let width = 0;
  let height = 0;
  if (bytes.length >= 24 && data.getUint32(0) === 0x89504e47 && data.getUint32(4) === 0x0d0a1a0a) {
    width = data.getUint32(16); height = data.getUint32(20);
  } else if (bytes.length >= 4 && data.getUint16(0) === 0xffd8) {
    let offset = 2;
    while (offset + 3 < bytes.length) {
      // JPEG 段可能包含填充标记；先定位下一段长度。
      if (bytes[offset++] !== 0xff) break;
      while (bytes[offset] === 0xff) offset++;
      const marker = bytes[offset++];
      if (marker === undefined || marker === 0xd9 || marker === 0xda) break;
      if (offset + 2 > bytes.length) break;
      const length = data.getUint16(offset);
      if (length < 2 || offset + length > bytes.length) break;
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker) && length >= 7) {
        height = data.getUint16(offset + 3); width = data.getUint16(offset + 5); break;
      }
      offset += length;
    }
  }
  if (!width || !height) throw new PdfToolError('fileType');
  if (width * height > PDF_LIMITS.imagePixels || width > 16_384 || height > 16_384) throw new PdfToolError('imageLimit');
  return width * height;
}

async function openPdf(bytes: Uint8Array): Promise<PDFDocument> {
  try {
    const document = await PDFDocument.load(bytes);
    // 限制页数并拒绝带有交互表单的文档，避免处理后丢失表单语义。
    if (document.getPageCount() > PDF_LIMITS.pages) throw new PdfToolError('pageLimit');
    if (document.getForm().getFields().length) throw new PdfToolError('unsupportedPdf');
    return document;
  } catch (error) {
    if (error instanceof PdfToolError) throw error;
    throw new PdfToolError('unsupportedPdf');
  }
}

function pdfBlob(bytes: Uint8Array): Blob { return new Blob([Uint8Array.from(bytes)], { type: 'application/pdf' }); }

/** 合并输入文档，保持文件与页码的原始顺序。 */
export async function mergePdfBytes(sources: Uint8Array[]): Promise<Uint8Array> {
  if (sources.length < 2) throw new PdfToolError('mergeRequired');
  const output = await PDFDocument.create();
  for (const bytes of sources) {
    const source = await openPdf(bytes);
    if (output.getPageCount() + source.getPageCount() > PDF_LIMITS.pages) throw new PdfToolError('pageLimit');
    const pages = await output.copyPages(source, source.getPageIndices());
    for (const page of pages) output.addPage(page);
  }
  return output.save();
}

/** 根据页索引分组拆分文档，输出真实 PDF 字节。 */
export async function splitPdfBytes(bytes: Uint8Array, groups: number[][]): Promise<Uint8Array[]> {
  const source = await openPdf(bytes);
  if (!groups.length || groups.length > 100) throw new PdfToolError('invalidPages');
  // 自定义分组允许重复选页，因此必须限制所有输出文件的总页数。
  if (groups.reduce((total, group) => total + group.length, 0) > PDF_LIMITS.outputPages) throw new PdfToolError('outputLimit');
  const output: Uint8Array[] = [];
  for (const group of groups) {
    if (!group.length || group.some(page => !Number.isInteger(page) || page < 0 || page >= source.getPageCount())) throw new PdfToolError('invalidPages');
    const document = await PDFDocument.create();
    for (const page of await document.copyPages(source, group)) document.addPage(page);
    output.push(await document.save());
  }
  return output;
}

/** 将指定页面按比例分成两页；未选页面保持原状。 */
export async function cropPdfBytes(bytes: Uint8Array, selected: number[], direction: 'vertical' | 'horizontal', ratio: number, reverse = false): Promise<Uint8Array> {
  if (!Number.isFinite(ratio) || ratio < 5 || ratio > 95) throw new PdfToolError('invalidRatio');
  const source = await openPdf(bytes);
  if (!selected.length || selected.some(page => !Number.isInteger(page) || page < 0 || page >= source.getPageCount())) throw new PdfToolError('invalidPages');
  if (source.getPageCount() + new Set(selected).size > PDF_LIMITS.outputPages) throw new PdfToolError('outputLimit');
  const output = await PDFDocument.create();
  for (let index = 0; index < source.getPageCount(); index++) {
    if (!selected.includes(index)) {
      output.addPage((await output.copyPages(source, [index]))[0]!);
      continue;
    }
    const sourcePage = source.getPage(index);
    if (sourcePage.getRotation().angle % 360 !== 0) throw new PdfToolError('rotatedPage');
    // 页面嵌入不会复制批注外观，因此明确拒绝，防止静默丢失可见内容。
    if ((sourcePage.node.Annots()?.size() ?? 0) > 0) throw new PdfToolError('annotatedPage');
    // 空白页同样建立合法内容流，使其可以被嵌入新的分割页面。
    if (!sourcePage.node.Contents()) sourcePage.drawRectangle({ x: 0, y: 0, width: 0, height: 0 });
    const { x, y, width, height } = sourcePage.getCropBox();
    const fraction = ratio / 100;
    // 横向切线按从上到下的比例计算，保持阅读顺序一致。
    const boxes = direction === 'vertical'
      ? [{ left: x, bottom: y, right: x + width * fraction, top: y + height }, { left: x + width * fraction, bottom: y, right: x + width, top: y + height }]
      : [{ left: x, bottom: y + height * (1 - fraction), right: x + width, top: y + height }, { left: x, bottom: y, right: x + width, top: y + height * (1 - fraction) }];
    if (reverse) boxes.reverse();
    for (const box of boxes) {
      const embedded = await output.embedPage(sourcePage, box);
      const page = output.addPage([box.right - box.left, box.top - box.bottom]);
      page.drawPage(embedded);
    }
  }
  return output.save();
}

function paperSize(options: PdfToolOptions): [number, number] {
  const sizes = { a4: [595.28, 841.89], a5: [419.53, 595.28], letter: [612, 792] } as const;
  const size = sizes[options.paper];
  return options.landscape ? [size[1], size[0]] : [size[0], size[1]];
}

/** 将基础 Markdown 转为安全的纯文本排版内容，不访问外部资源。 */
export function markdownToText(value: string): string {
  return value.replace(/<[^>]*>/g, '').replace(/^\s*```.*$/gm, '').replace(/^\s{0,3}#{1,6}\s+/gm, '').replace(/!\[([^\]]*)\]\([^)]*\)/g, '[$1]').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/(\*\*|__)(.*?)\1/g, '$2').replace(/`([^`]+)`/g, '$1').replace(/^\s*>\s?/gm, '');
}

function wrapText(text: string, font: PDFFont, size: number, maxWidth: number): string[] {
  const lines: string[] = [];
  for (const paragraph of text.replace(/\t/g, '    ').split('\n')) {
    let line = '';
    for (const character of paragraph) {
      // 按实际字形宽度换行，同时适配中英文混排。
      if (line && font.widthOfTextAtSize(line + character, size) > maxWidth) { lines.push(line); line = ''; }
      line += character;
    }
    lines.push(line);
  }
  return lines;
}

async function createTextPdf(options: PdfToolOptions): Promise<Uint8Array> {
  if (!options.content.trim()) throw new PdfToolError('emptyContent');
  if (new TextEncoder().encode(options.content).byteLength > PDF_LIMITS.textBytes) throw new PdfToolError('contentLimit');
  const document = await PDFDocument.create();
  const { embedChineseFont } = await import('./pdf-runtime');
  const font = await embedChineseFont(document);
  const [width, height] = paperSize(options);
  const margin = options.margin * 72 / 25.4;
  const content = options.tool === 'markdown' ? markdownToText(options.content) : options.content;
  const supported = new Set(font.getCharacterSet());
  // 在排版前检查完整文本，防止标题、段首、段尾的缺字被静默导出为空白。
  for (const character of content) {
    if (!['\r', '\n', '\t'].includes(character) && !supported.has(character.codePointAt(0)!)) throw new PdfToolError('missingGlyph');
  }
  const lines = wrapText(content.replace(/\r/g, ''), font, 12, width - margin * 2);
  const perPage = Math.floor((height - margin * 2 - 24) / 21);
  if (Math.ceil(lines.length / perPage) > PDF_LIMITS.pages) throw new PdfToolError('pageLimit');
  for (let start = 0; start < lines.length; start += perPage) {
    const page = document.addPage([width, height]);
    lines.slice(start, start + perPage).forEach((line, index) => page.drawText(line, { x: margin, y: height - margin - 15 - index * 21, font, size: 12, color: rgb(0.12, 0.16, 0.2) }));
    page.drawText(String(document.getPageCount()), { x: width / 2, y: Math.max(12, margin / 2), font, size: 9 });
  }
  return document.save();
}

async function createImagePdf(options: PdfToolOptions): Promise<Uint8Array> {
  const document = await PDFDocument.create();
  const [width, height] = paperSize(options);
  const margin = options.margin * 72 / 25.4;
  let pixels = 0;
  for (const file of options.files) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    pixels += validateImageBytes(bytes);
    if (pixels > PDF_LIMITS.totalImagePixels) throw new PdfToolError('imageLimit');
    const embedded = /\.png$/i.test(file.name) ? await document.embedPng(bytes) : await document.embedJpg(bytes);
    const dimensions = embedded.scaleToFit(width - 2 * margin, height - 2 * margin);
    const page = document.addPage([width, height]);
    page.drawImage(embedded, { x: (width - dimensions.width) / 2, y: (height - dimensions.height) / 2, width: dimensions.width, height: dimensions.height });
  }
  return document.save();
}

/** 为文档转换建立带同站字体资源的读取配置，并保留原始输入字节。 */
export function getPdfConversionOptions(bytes: Uint8Array) {
  return { data: Uint8Array.from(bytes), ...getPdfDocumentOptions() };
}

async function convertPdf(options: PdfToolOptions, bytes: Uint8Array): Promise<PdfToolResult> {
  const { getPdfJs } = await import('./pdf-runtime');
  const pdfjs = await getPdfJs();
  const loading = pdfjs.getDocument(getPdfConversionOptions(bytes));
  const document = await loading.promise;
  const outputs: PdfOutput[] = [];
  try {
    const pages = parsePageRange(options.pages, document.numPages);
    if (options.tool === 'render' && pages.length > PDF_LIMITS.imagePages) throw new PdfToolError('imageLimit');
    if (!Number.isFinite(options.dpi) || options.dpi < 72 || options.dpi > 300) throw new PdfToolError('invalidDpi');
    const extracted: string[] = [];
    for (const index of pages) {
      const page = await document.getPage(index + 1);
      if (options.tool === 'extract') {
        const content = await page.getTextContent();
        extracted.push(content.items.map(item => 'str' in item ? item.str + (item.hasEOL ? '\n' : ' ') : '').join(''));
      } else {
        const viewport = page.getViewport({ scale: options.dpi / 72 });
        if (!Number.isFinite(viewport.width * viewport.height) || viewport.width * viewport.height > PDF_LIMITS.imagePixels || viewport.width > 16_384 || viewport.height > 16_384) throw new PdfToolError('imageLimit');
        const canvas = window.document.createElement('canvas');
        canvas.width = Math.ceil(viewport.width); canvas.height = Math.ceil(viewport.height);
        await page.render({ canvas, viewport, background: '#ffffff' }).promise;
        const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new PdfToolError('imageLimit')), options.imageFormat === 'jpg' ? 'image/jpeg' : 'image/png', 0.92));
        outputs.push({ name: `page-${index + 1}.${options.imageFormat}`, blob });
        canvas.width = 0; canvas.height = 0;
      }
      page.cleanup();
    }
    if (options.tool === 'extract') {
      const text = extracted.join('\n\n');
      if (!text.trim()) throw new PdfToolError('noText');
      return { files: [{ name: 'extracted-text.txt', blob: new Blob([text], { type: 'text/plain;charset=utf-8' }) }], text };
    }
    return { files: outputs };
  } finally { await document.destroy(); }
}

/** 执行本机 PDF 任务；只有产生有效文件后才返回成功结果。 */
export async function runPdfTool(options: PdfToolOptions): Promise<PdfToolResult> {
  if (!Number.isFinite(options.margin) || options.margin < 5 || options.margin > 35) throw new PdfToolError('invalidMargin');
  if (options.tool === 'text' || options.tool === 'markdown') return { files: [{ name: `${options.tool}.pdf`, blob: pdfBlob(await createTextPdf(options)) }] };
  validateFiles(options.files, options.tool === 'images');
  if (options.tool === 'images') return { files: [{ name: 'images.pdf', blob: pdfBlob(await createImagePdf(options)), pages: options.files.length }] };
  const sources = await Promise.all(options.files.map(async file => new Uint8Array(await file.arrayBuffer())));
  if (options.tool === 'merge') return { files: [{ name: 'merged.pdf', blob: pdfBlob(await mergePdfBytes(sources)) }] };
  if (sources.length !== 1) throw new PdfToolError('fileRequired');
  const bytes = sources[0]!;
  const source = await openPdf(bytes);
  if (options.tool === 'split') {
    const groups = createSplitGroups(source.getPageCount(), options.splitMode, options.groupRanges, options.chunkSize);
    const results = await splitPdfBytes(bytes, groups);
    return { files: results.map((result, index) => ({ name: `split-${index + 1}.pdf`, blob: pdfBlob(result), pages: groups[index]!.length })) };
  }
  if (options.tool === 'crop') return { files: [{ name: 'divided.pdf', blob: pdfBlob(await cropPdfBytes(bytes, parsePageRange(options.pages, source.getPageCount()), options.direction, options.ratio, options.reverse)) }] };
  return convertPdf(options, bytes);
}

/** 将多个实际结果打包为可下载的 ZIP 文件。 */
export async function zipPdfOutputs(outputs: PdfOutput[]): Promise<Blob> {
  const zip = new JSZip();
  for (const output of outputs) zip.file(output.name, await output.blob.arrayBuffer());
  return zip.generateAsync({ type: 'blob' });
}

/** 建立含两页简单矢量内容的 PDF，供用户立即体验本机处理。 */
export async function createSamplePdf(): Promise<Uint8Array> {
  const document = await PDFDocument.create();
  for (let index = 1; index <= 2; index++) {
    const page = document.addPage([595.28, 841.89]);
    page.drawRectangle({ x: 48, y: 570, width: 499, height: 210, color: rgb(0.89, 0.95, 0.95) });
    page.drawText(`TOOLBOX / SAMPLE ${index}`, { x: 68, y: 735, size: 24, color: rgb(0.08, 0.35, 0.37) });
    page.drawText('Create. Organize. Convert.', { x: 68, y: 680, size: 16 });
    page.drawText(`Page ${index} of 2`, { x: 68, y: 90, size: 12 });
  }
  return document.save();
}
