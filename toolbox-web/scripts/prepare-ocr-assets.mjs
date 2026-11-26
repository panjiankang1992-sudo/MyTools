#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 准备字帖图片识别所需的同域静态资源。
 *
 * 生产浏览器不得访问第三方 CDN 补缺件，因此把 Tesseract 的 worker、WASM 核心与
 * 语言包复制到本站 public/ocr/<assetVersion>/，并写出版本、来源、许可证与 SHA-256 清单。
 * 脚本可重复执行：已存在且哈希一致的文件不重复写入。
 *
 * 用法：
 *   node scripts/prepare-ocr-assets.mjs
 *   OCR_SOURCE_DIR=/path/to/node_modules node scripts/prepare-ocr-assets.mjs
 */

/** 资源版本；更换依赖版本时必须同步递增，避免旧缓存被当成新资源。 */
const ASSET_VERSION = 'tesseract-7.0.0-lang-4.0.0-best-int-1';

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '..');
const targetRoot = join(projectRoot, 'public', 'ocr', ASSET_VERSION);

/** 依赖来源目录：默认本工程 node_modules，可用环境变量指向已安装依赖的目录。 */
const sourceRoot = resolve(process.env.OCR_SOURCE_DIR ?? join(projectRoot, 'node_modules'));

/**
 * 需要复制的资源。
 * 只挑实际会用到的核心变体，避免把全部 WASM 变体（约 60 MB）都发到浏览器。
 * LSTM 变体对应 createWorker 的默认 oem = 1。
 */
const ASSETS = [
  { package: 'tesseract.js', from: 'dist/worker.min.js', to: 'worker.min.js', role: 'ocr-worker' },
  { package: 'tesseract.js-core', from: 'tesseract-core-simd-lstm.wasm.js', to: 'tesseract-core-simd-lstm.wasm.js', role: 'ocr-core-simd' },
  { package: 'tesseract.js-core', from: 'tesseract-core-simd-lstm.wasm', to: 'tesseract-core-simd-lstm.wasm', role: 'ocr-core-simd' },
  // tesseract.js 在支持的机器上会优先挑 relaxedsimd 变体，必须一并发布，否则核心文件 404。
  { package: 'tesseract.js-core', from: 'tesseract-core-relaxedsimd-lstm.wasm.js', to: 'tesseract-core-relaxedsimd-lstm.wasm.js', role: 'ocr-core-relaxedsimd' },
  { package: 'tesseract.js-core', from: 'tesseract-core-relaxedsimd-lstm.wasm', to: 'tesseract-core-relaxedsimd-lstm.wasm', role: 'ocr-core-relaxedsimd' },
  { package: 'tesseract.js-core', from: 'tesseract-core-lstm.wasm.js', to: 'tesseract-core-lstm.wasm.js', role: 'ocr-core-fallback' },
  { package: 'tesseract.js-core', from: 'tesseract-core-lstm.wasm', to: 'tesseract-core-lstm.wasm', role: 'ocr-core-fallback' },
  { package: '@tesseract.js-data/chi_sim', from: '4.0.0_best_int/chi_sim.traineddata.gz', to: 'chi_sim.traineddata.gz', role: 'language-data' },
  { package: '@tesseract.js-data/eng', from: '4.0.0_best_int/eng.traineddata.gz', to: 'eng.traineddata.gz', role: 'language-data' }
];

/** 计算文件 SHA-256。 */
async function sha256(path) {
  return createHash('sha256').update(await readFile(path)).digest('hex');
}

/** 读取依赖包声明的版本与许可证。 */
async function packageInfo(name) {
  const manifest = JSON.parse(await readFile(join(sourceRoot, name, 'package.json'), 'utf8'));
  return { version: manifest.version, license: manifest.license ?? 'UNKNOWN', homepage: manifest.homepage ?? null };
}

const missing = [];
const entries = [];
let copied = 0;
let reused = 0;

await mkdir(targetRoot, { recursive: true });

for (const asset of ASSETS) {
  const source = join(sourceRoot, asset.package, asset.from);
  if (!existsSync(source)) {
    missing.push(source);
    continue;
  }
  const target = join(targetRoot, asset.to);
  const hash = await sha256(source);
  const bytes = (await readFile(source)).byteLength;
  const upToDate = existsSync(target) && (await sha256(target)) === hash;
  if (upToDate) {
    reused += 1;
  } else {
    await writeFile(target, await readFile(source));
    copied += 1;
  }
  const info = await packageInfo(asset.package);
  entries.push({
    file: asset.to,
    role: asset.role,
    package: asset.package,
    version: info.version,
    license: info.license,
    source: `${asset.package}/${asset.from}`,
    bytes,
    sha256: hash
  });
}

if (missing.length) {
  console.error('缺少必需的 OCR 资源，未生成清单：');
  for (const path of missing) console.error(`  - ${path}`);
  console.error('\n请先安装锁定版本的依赖，必要时用 OCR_SOURCE_DIR 指向其 node_modules。');
  process.exit(1);
}

const totalBytes = entries.reduce((sum, entry) => sum + entry.bytes, 0);
const manifest = {
  assetVersion: ASSET_VERSION,
  generatedAt: new Date().toISOString(),
  sourceRoot,
  note: '由 scripts/prepare-ocr-assets.mjs 生成。生产浏览器只从本站加载这些资源，不访问第三方 CDN。首页与纯文本字帖不加载本目录。',
  provenance: [
    'tesseract.js 与 tesseract.js-core 采用 Apache-2.0。',
    '语言包 npm 包 @tesseract.js-data/* 在 package.json 中声明 MIT；其内部 traineddata 源自 tessdata 项目（Apache-2.0）。发布前如需严格合规，请按 tessdata 的许可证一并保留声明。',
    '语言包选用 4.0.0_best_int 变体：体积远小于 4.0.0（chi_sim 约 1.6 MiB 对 19 MiB），适合浏览器按需加载。',
    '发布全部 LSTM 核心变体（relaxedsimd / simd / 非 simd），对应 createWorker 默认 oem=1。引擎会按硬件能力自动挑选，实测在支持 relaxed SIMD 的机器上优先请求 relaxedsimd，缺失会导致核心文件 404。'
  ],
  totalBytes,
  files: entries.sort((left, right) => left.file.localeCompare(right.file))
};

// 目录里多余的文件（例如换过资源版本）必须清理，避免旧件被继续引用。
const expected = new Set([...entries.map(entry => entry.file), 'manifest.json']);
for (const name of await readdir(targetRoot)) {
  if (!expected.has(name)) {
    await rm(join(targetRoot, name), { recursive: true, force: true });
  }
}

await writeFile(join(targetRoot, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

console.log(`资源目录：${targetRoot}`);
console.log(`文件 ${entries.length} 个，合计 ${(totalBytes / 1024 / 1024).toFixed(2)} MiB（新写入 ${copied}，复用 ${reused}）`);
for (const entry of manifest.files) {
  console.log(`  ${entry.file.padEnd(34)} ${(entry.bytes / 1024).toFixed(0).padStart(6)} KiB  ${entry.license}  ${entry.sha256.slice(0, 16)}`);
}
