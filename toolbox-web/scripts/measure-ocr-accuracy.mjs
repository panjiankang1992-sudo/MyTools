#!/usr/bin/env node
import { createWorker } from 'tesseract.js';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * W13：中文 OCR 精度 A/B 测量。
 *
 * 比较两种语言集在**清晰中文夹具**上的字符错误率（CER）与拉丁字符占比。
 * 目的是判定「中文模式加载 chi_sim+eng」是否导致汉字区被拉丁化输出。
 *
 * 这不是给单张图调参：夹具来自夹具清单，两种配置跑同一批输入，按平均 CER 判定。
 */

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const samplesDir = join(root, '..', 'docs', 'verification', 'worksheet-workflow-v2', 'samples');
const outFile = join(root, '..', 'docs', 'verification', 'worksheet-workflow-v2', 'ocr-ab.json');

const CONFIGS = [
  { name: 'chi_sim+eng（现状）', languages: ['chi_sim', 'eng'] },
  { name: 'chi_sim（假设）', languages: ['chi_sim'] }
];

/** 夹具：全部为清晰渲染的中文内容（同一份 20 字真值，多种字号与版面）。 */
const GROUND_TRUTH = '山水风云春天花朵日月江河森林田野白云小路';
const FIXTURES = [
  { file: 'ocr-fixture-zh.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-fixture-zh-32px.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-clear-24px.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-clear-32px.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-clear-44px.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-clear-64px.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-clear-40px-4per.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-clear-48px-7per.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-songti-32px.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-songti-44px.png', groundTruth: GROUND_TRUTH },
  { file: 'ocr-songti-64px.png', groundTruth: GROUND_TRUTH }
];

/** Levenshtein 编辑距离。 */
function editDistance(a, b) {
  const rows = a.length + 1;
  const cols = b.length + 1;
  let prev = Array.from({ length: cols }, (_, i) => i);
  for (let i = 1; i < rows; i += 1) {
    const current = [i];
    for (let j = 1; j < cols; j += 1) {
      current[j] = Math.min(prev[j] + 1, current[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = current;
  }
  return prev[cols - 1];
}

/** 去掉空白后逐字符比较；参考串决定分母（契约：中文按字符）。 */
function score(recognized, groundTruth) {
  const hypothesis = recognized.replace(/\s+/gu, '');
  const distance = editDistance(groundTruth, hypothesis);
  const latin = (hypothesis.match(/[A-Za-z]/gu) ?? []).length;
  const han = (hypothesis.match(/[\u4E00-\u9FFF]/gu) ?? []).length;
  return {
    hypothesis,
    editDistance: distance,
    referenceChars: groundTruth.length,
    cer: Number((distance / groundTruth.length).toFixed(4)),
    latinChars: latin,
    hanChars: han,
    latinRatio: hypothesis.length ? Number((latin / hypothesis.length).toFixed(3)) : 0
  };
}

const results = [];
for (const config of CONFIGS) {
  const worker = await createWorker(config.languages, 1, {
    workerPath: resolve(root, 'node_modules/tesseract.js/src/worker-script/node/index.js'),
    corePath: resolve(root, 'node_modules/tesseract.js-core'),
    langPath: resolve(root, 'public/ocr/tesseract-7.0.0-lang-4.0.0-best-int-1'),
    gzip: true, cacheMethod: 'none', logger: () => undefined
  });
  for (const fixture of FIXTURES) {
    const { data } = await worker.recognize(await readFile(join(samplesDir, fixture.file)));
    const measured = score(data.text, fixture.groundTruth);
    results.push({ config: config.name, languages: config.languages.join('+'), file: fixture.file, confidence: Math.round(data.confidence), ...measured });
    console.log(`${config.name.padEnd(18)} ${fixture.file.padEnd(26)} CER=${(measured.cer * 100).toFixed(2)}%  拉丁占比=${(measured.latinRatio * 100).toFixed(1)}%  文本=${JSON.stringify(measured.hypothesis.slice(0, 30))}`);
  }
  await worker.terminate();
}

/** 按配置汇总平均 CER 与平均拉丁占比。 */
const summary = CONFIGS.map(config => {
  const rows = results.filter(row => row.config === config.name);
  return {
    config: config.name,
    averageCer: Number((rows.reduce((sum, row) => sum + row.cer, 0) / rows.length).toFixed(4)),
    averageLatinRatio: Number((rows.reduce((sum, row) => sum + row.latinRatio, 0) / rows.length).toFixed(3)),
    maxCer: Math.max(...rows.map(row => row.cer))
  };
});

await writeFile(outFile, `${JSON.stringify({
  generatedAt: new Date().toISOString(),
  purpose: '判定中文模式加载 chi_sim+eng 是否导致汉字区被拉丁化输出（W13 的假设检验）',
  engine: { package: 'tesseract.js', oem: 1, assetBase: 'public/ocr/tesseract-7.0.0-lang-4.0.0-best-int-1' },
  fixtures: FIXTURES,
  results,
  summary
}, null, 2)}\n`, 'utf8');

console.log('');
for (const row of summary) console.log(`汇总 ${row.config.padEnd(18)} 平均 CER=${(row.averageCer * 100).toFixed(2)}%  平均拉丁占比=${(row.averageLatinRatio * 100).toFixed(1)}%  最差=${(row.maxCer * 100).toFixed(2)}%`);
console.log(`报告：${outFile}`);
