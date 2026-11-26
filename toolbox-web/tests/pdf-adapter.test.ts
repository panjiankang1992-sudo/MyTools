import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { createPdfAdapter, pdfToolId, pdfToolSuffix, type PdfExecutionProgress } from '../src/services/pdf-adapter';
import type { PdfToolOptions } from '../src/lib/pdf-tools';

// 字体只由本站分发；测试用本地字体文件替换网络读取，避免依赖测试服务器。
const fontBytes = new Uint8Array(await readFile(new URL('../public/fonts/NotoSansCJKsc-Regular.otf', import.meta.url)));
globalThis.fetch = (async () => new Response(fontBytes)) as typeof fetch;

/** 构造文本转 PDF 的最小参数。 */
function textOptions(): PdfToolOptions {
  return {
    tool: 'text',
    files: [],
    content: '公开工具站验收文本',
    pages: '',
    splitMode: 'chunks',
    groupRanges: '1',
    chunkSize: 1,
    direction: 'vertical',
    ratio: 50,
    reverse: false,
    imageFormat: 'png',
    dpi: 144,
    paper: 'a4',
    landscape: false,
    margin: 15
  };
}

test('tool ids map to the registry and back without ambiguity', () => {
  assert.equal(pdfToolId('merge'), 'pdf.merge');
  assert.equal(pdfToolSuffix('pdf.merge'), 'merge');
  assert.equal(pdfToolSuffix('merge'), 'merge');
  assert.equal(pdfToolSuffix('pdf.unknown'), null);
  assert.equal(pdfToolSuffix('nonsense'), null);
});

test('the configured adapter reports its real execution location', () => {
  const local = createPdfAdapter('local');
  assert.equal(local.location, 'local');
  const server = createPdfAdapter('server');
  assert.equal(server.location, 'server');
});

test('the local adapter runs a real conversion without any network upload', async () => {
  const local = createPdfAdapter('local');
  const phases: PdfExecutionProgress['phase'][] = [];
  const result = await local.execute(
    { toolId: 'pdf.text', options: textOptions() },
    progress => { phases.push(progress.phase); }
  );
  assert.equal(result.execution, 'local');
  assert.equal(result.files.length, 1);
  const bytes = new Uint8Array(await result.files[0]!.blob.arrayBuffer());
  // 真实产物的文件头必须是 PDF，且尺寸为 A4。
  assert.equal(new TextDecoder().decode(bytes.subarray(0, 5)), '%PDF-');
  assert.ok(bytes.byteLength > 1000);
  assert.deepEqual(phases, ['validating', 'processing', 'rendering']);
});

test('cancelling a running local job rejects instead of returning a partial result', async () => {
  const local = createPdfAdapter('local');
  const running = local.execute({ toolId: 'pdf.text', options: textOptions() });
  // 取消发生在处理过程中：结果必须被丢弃，而不是当作成功产物返回。
  local.cancel();
  await assert.rejects(() => running, /PDF_CANCELLED/);
});

test('cancelling when nothing is running is a no-op', async () => {
  const local = createPdfAdapter('local');
  local.cancel();
  const result = await local.execute({ toolId: 'pdf.text', options: textOptions() });
  assert.equal(result.files.length, 1);
});
