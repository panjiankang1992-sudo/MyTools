import type { OcrWorkerLike, OcrWorkerOptions } from './worksheet-ocr';
import { prepareOcrInkImages } from '../lib/ocr-ink';
import { prepareOcrMeaningImage } from '../lib/ocr-meaning-image';
import { createLayoutOcrWorker, type LayoutOcrWorker } from './ocr-recognition';

/**
 * 真实 OCR 引擎的 worker 工厂。
 *
 * 契约 §4.3：`tesseract.js` 只在**使用图片入口时**动态加载；worker、核心 WASM 与语言包
 * 全部从本站 `public/ocr/<assetVersion>/` 读取，生产浏览器不去第三方 CDN 补缺件。
 *
 * 本模块不做任何静态导入 `tesseract.js`，以保证首屏包不含 OCR 代码。
 */

/** 资源版本目录；与 scripts/prepare-ocr-assets.mjs 的 ASSET_VERSION 保持一致。 */
export const OCR_ASSET_VERSION = 'tesseract-7.0.0-lang-4.0.0-best-int-1';

/** 同域资产前缀。 */
export function ocrAssetBase(baseUrl = '/'): string {
  const prefix = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  return `${prefix}ocr/${OCR_ASSET_VERSION}/`;
}

/**
 * 创建真实 worker。
 *
 * @param language 语言包组合，例如 `chi_sim+eng`
 * @param options 同域资产路径
 * @returns 引擎 worker
 */
export async function createOcrWorker(language: string, options: OcrWorkerOptions): Promise<OcrWorkerLike> {
  // 动态导入：未使用图片入口时不会下载 OCR 代码。
  const module = await import('tesseract.js');
  const create = module.createWorker ?? module.default?.createWorker;
  if (typeof create !== 'function') throw new Error('WORKSHEET_OCR_ENGINE_UNAVAILABLE');
  const worker = await create(language, 1, {
    workerPath: options.workerPath,
    corePath: options.corePath,
    langPath: options.langPath,
    gzip: options.gzip,
    cacheMethod: options.cacheMethod,
    // 识别阶段按学科显式开启词表提取所需的行框。
    logger: () => undefined
  });
  const english = language.startsWith('eng');
  return createLayoutOcrWorker(worker as unknown as LayoutOcrWorker, english, prepareOcrInkImages, english ? {
    prepareImage: prepareOcrMeaningImage,
    // 复用已经按需加载的引擎；下一张原图会恢复原语言，避免多图越识别越错。
    selectLanguage: selected => worker.reinitialize(selected === 'chinese' ? 'chi_sim' : language, 1)
  } : undefined);
}
