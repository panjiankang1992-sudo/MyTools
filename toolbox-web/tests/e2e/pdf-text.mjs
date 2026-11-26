#!/usr/bin/env node
/**
 * 读取 PDF **文字层**并断言某个值是否出现（任务 1 的证据核验工具）。
 *
 * 为什么需要它：草稿预览的产物是真实 PDF，而"独特值是否进入 PDF"必须看文字层，
 * 不能靠"点了一下没报错"。文字是子集化 CID 字体写进内容流的，正则扫原始字节不可靠，
 * 因此这里用页面内自带的 `pdfjs-dist`（`package.json` 既有依赖，不新增任何依赖）
 * 取回 `textContent`，再交给调用方断言。
 *
 * 用法（可被其它脚本 import，也可直接命令行跑）：
 *   node tests/e2e/pdf-text.mjs <file.pdf> [search] [search2 ...]
 * 输出：JSON（pages/text 片段/每个 search 的命中情况）。
 */

import { readFile } from 'node:fs/promises';

/** 从 pdfjs-dist 取出全部页面的文字层。 */
export async function extractPdfText(path) {
  const bytes = await readFile(path);
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  // Node 环境没有 DOM canvas，但只要文字层：关掉 Worker 与字体渲染。
  const document = await pdfjs.getDocument({
    data: new Uint8Array(bytes),
    useWorkerFetch: false,
    isEvalSupported: false,
    disableFontFace: true
  }).promise;
  const pages = [];
  for (let pageNumber = 1; pageNumber <= document.numPages; pageNumber += 1) {
    const page = await document.getPage(pageNumber);
    const content = await page.getTextContent();
    pages.push(content.items.map(item => item.str).join(''));
  }
  return { pageCount: document.numPages, texts: pages, text: pages.join('\n') };
}

/** 判断文字层是否包含某个值（去掉换行差异后比较）。 */
export function textContains(text, value) {
  const flat = text.replace(/\s+/gu, ' ');
  return flat.includes(value) || flat.replace(/ /gu, '').includes(value.replace(/\s+/gu, ''));
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
  const [file, ...searches] = process.argv.slice(2);
  if (!file) {
    console.error('用法：node tests/e2e/pdf-text.mjs <file.pdf> [search ...]');
    process.exit(2);
  }
  const extracted = await extractPdfText(file);
  const hits = Object.fromEntries(searches.map(value => [value, textContains(extracted.text, value)]));
  console.log(JSON.stringify({
    file,
    pages: extracted.pageCount,
    hits,
    preview: extracted.texts.map(page => page.replace(/\s+/gu, ' ').slice(0, 300))
  }, null, 2));
  process.exit(Object.values(hits).every(Boolean) ? 0 : 1);
}
