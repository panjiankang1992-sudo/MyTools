#!/usr/bin/env node
import { writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { connect, findChrome, launchChrome, evaluate, sleep } from '../tests/e2e/cdp.mjs';

/**
 * 生成清晰中文 OCR 夹具：同一份 20 词真值，多种字号与版面。
 *
 * 目的是让 W13 的 CER 判定建立在**一批**输入上，而不是单张图——单张图上调参是契约禁止的。
 */

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const outDir = join(root, '..', 'docs', 'verification', 'worksheet-workflow-v2', 'samples');

const GROUND_TRUTH = '山水风云春天花朵日月江河森林田野白云小路';
const WORDS = [...'山水风云春天花朵日月江河森林田野白云小路'].map((_, index) => GROUND_TRUTH.slice(index, index + 1));

/** 版面：字号、每行词数、字距、行高。 */
const LAYOUTS = [
  { file: 'ocr-songti-32px.png', size: 32, perLine: 5, spacing: 6, lineHeight: 2.0, font: 'Songti SC' },
  { file: 'ocr-songti-44px.png', size: 44, perLine: 5, spacing: 8, lineHeight: 2.0, font: 'Songti SC' },
  { file: 'ocr-songti-64px.png', size: 64, perLine: 4, spacing: 8, lineHeight: 1.8, font: 'Songti SC' },
  { file: 'ocr-clear-24px.png', size: 24, perLine: 5, spacing: 4, lineHeight: 2.2 },
  { file: 'ocr-clear-32px.png', size: 32, perLine: 5, spacing: 6, lineHeight: 2.0 },
  { file: 'ocr-clear-44px.png', size: 44, perLine: 5, spacing: 8, lineHeight: 2.0 },
  { file: 'ocr-clear-64px.png', size: 64, perLine: 4, spacing: 8, lineHeight: 1.8 },
  { file: 'ocr-clear-40px-4per.png', size: 40, perLine: 4, spacing: 10, lineHeight: 2.0 },
  { file: 'ocr-clear-48px-7per.png', size: 48, perLine: 7, spacing: 6, lineHeight: 1.9 }
];

const port = 9706;
const chrome = await launchChrome({ executablePath: findChrome(), port, userDataDir: '/tmp/ocr-fixtures' });
const cdp = await connect(port);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');

for (const layout of LAYOUTS) {
  const lines = [];
  for (let i = 0; i < WORDS.length; i += layout.perLine) lines.push(WORDS.slice(i, i + layout.perLine).join(''));
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
    body { margin: 0; background: #fff; }
    div { font-family: ${layout.font ? `"${layout.font}", ` : ''}"Noto Sans CJK SC", "PingFang SC", sans-serif; color: #000;
          font-size: ${layout.size}px; letter-spacing: ${layout.spacing}px;
          line-height: ${layout.lineHeight}; padding: 24px; white-space: pre; }
  </style></head><body><div>${lines.join('\n')}</div></body></html>`;
  await writeFile(join(outDir, '.fixture.html'), html, 'utf8');
  await cdp.send('Page.navigate', { url: `file://${join(outDir, '.fixture.html')}` });
  await sleep(1200);
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
  await writeFile(join(outDir, layout.file), Buffer.from(shot.data, 'base64'));
  console.log(`${layout.file}  ${layout.size}px × ${layout.perLine}词/行`);
}
cdp.close();
chrome.kill('SIGKILL');
