import {
  collectOcrTextLines, extractEnglishOcrLines, mergeEnhancedEnglishOcrLines,
  type OcrTextBox, type OcrTextLayout, type OcrTextLine
} from '../domain/worksheet-ocr-text';
import { pairEnglishOcrLines, recoverMissingEnglishOcrEntries } from '../domain/worksheet-ocr-pairs';
import type { PreparedOcrInkImage } from '../lib/ocr-ink';
import { OcrError, type OcrWorkerLike, type OcrWorkerResult } from './worksheet-ocr';

/** 引擎适配层只使用文本、行坐标及可重复设置的版面参数。 */
export interface LayoutOcrWorker {
  setParameters(params: { tessedit_pageseg_mode: '3' | '6' }): Promise<unknown>;
  recognize(image: Blob, options?: object, output?: { text: boolean; blocks: boolean }): Promise<{ data: OcrTextLayout }>;
  terminate(): Promise<unknown> | unknown;
}

/** 中文释义识别复用同一引擎和总时间预算，图片预处理与语言切换均可注入验证。 */
export interface OcrMeaningRecognition {
  prepareImage(image: Blob, englishLines: readonly OcrTextLine[], cancelled: () => boolean): Promise<PreparedOcrInkImage | null>;
  selectLanguage(language: 'chinese' | 'english'): Promise<unknown>;
}

/** 将增强图坐标映射回同一裁剪图，禁止两路分别自动旋转后直接匹配。 */
function restoreCoordinates(lines: readonly OcrTextLine[], image: PreparedOcrInkImage): OcrTextLine[] {
  const box = (value: OcrTextBox): OcrTextBox => ({ x0: value.x0 / image.scaleX, x1: value.x1 / image.scaleX, y0: value.y0 / image.scaleY, y1: value.y1 / image.scaleY });
  return lines.map(line => ({ ...line, bbox: box(line.bbox), words: line.words?.map(word => ({ ...word, bbox: box(word.bbox) })) }));
}

/**
 * 英语词表保留原图全文，增强彩色印刷字后按坐标和置信度选择候选。
 * 所有处理都在一次 recognize 内运行，沿用服务的总超时和取消预算。
 */
export function createLayoutOcrWorker(
  worker: LayoutOcrWorker,
  english: boolean,
  prepareInk: (image: Blob, cancelled: () => boolean) => Promise<PreparedOcrInkImage[]>,
  meaning?: OcrMeaningRecognition
): OcrWorkerLike {
  let terminated = false;
  let termination: Promise<void> | null = null;
  let fallback: OcrWorkerResult | null = null;
  let currentLanguage: 'english' | 'chinese' | 'unknown' = 'english';
  /** 每次异步阶段之后检查释放状态，取消后不得再启动下一路识别。 */
  function checkActive(): void { if (terminated) throw new OcrError('WORKSHEET_OCR_CANCELLED'); }
  /** 切换失败视为语言不明，下一张必须重新初始化英文，不能沿用半完成状态。 */
  async function selectLanguage(language: 'chinese' | 'english'): Promise<void> {
    if (!meaning || currentLanguage === language) return;
    checkActive();
    currentLanguage = 'unknown';
    await meaning.selectLanguage(language);
    checkActive();
    currentLanguage = language;
  }
  return {
    async recognize(image) {
      checkActive();
      fallback = null;
      if (!english) {
        const result = await worker.recognize(image);
        checkActive();
        return { data: { text: result.data.text ?? '', confidence: result.data.confidence } };
      }
      // 上一张的中文阶段可能改变了引擎语言，原图识别前必须恢复中英混排。
      await selectLanguage('english');
      checkActive();
      await worker.setParameters({ tessedit_pageseg_mode: '6' });
      checkActive();
      const { data } = await worker.recognize(image, {}, { text: true, blocks: true });
      checkActive();
      const originalLines = collectOcrTextLines(data);
      const original = extractEnglishOcrLines(originalLines, { fallbackToChinese: false });
      let enhanced: OcrTextLine[] = [];
      let meaningLines: OcrTextLine[] = [];
      // 蓝色印刷字已与中文和圈线分离，此通道优先于混排原图；增强变体彼此仍按置信度比较。
      const englishCandidates = (): OcrTextLine[] => enhanced.length ? mergeEnhancedEnglishOcrLines(original, enhanced, { preferEnhanced: true }) : original;
      /** 每个成功阶段保存轻量候选，总预算耗尽时仍可回收引擎并交付已有文字。 */
      const snapshot = (): OcrWorkerResult => {
        const merged = englishCandidates();
        const candidate = merged.length ? merged : extractEnglishOcrLines(originalLines);
        const originalPairs = merged.length ? pairEnglishOcrLines(merged, originalLines) : undefined;
        const enhancedPairs = meaningLines.length ? pairEnglishOcrLines(merged, meaningLines) : [];
        // 中文增强失败或缺项时保留原图对应结果，不能因追加识别丢掉已有释义。
        const paired = originalPairs?.map((entry, index) => {
          const replacement = enhancedPairs[index];
          return replacement?.meaning && (!entry.meaning || (replacement.confidence ?? 0) >= (entry.confidence ?? 0)) ? replacement : entry;
        });
        // 两路对应关系确定后再恢复可靠的中文独立行，不能改变上面按英文下标合并的契约。
        const entries = paired ? recoverMissingEnglishOcrEntries(merged, [...originalLines, ...meaningLines], paired) : undefined;
        const scores = candidate.map(line => line.confidence).filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
        return { data: {
          text: data.text ?? '', candidateText: candidate.map(line => line.text).join('\n'),
          entries,
          // 使用最低行置信度，让被圈线遮挡的低分词仍能进入人工核对。
          confidence: scores.length ? Math.min(...scores) : data.confidence
        } };
      };
      fallback = snapshot();
      try {
        const variants = await prepareInk(image, () => terminated);
        checkActive();
        if (variants.length) await worker.setParameters({ tessedit_pageseg_mode: '3' });
        for (const variant of variants) {
          checkActive();
          const result = await worker.recognize(variant.image, {}, { text: true, blocks: true });
          checkActive();
          const lines = restoreCoordinates(extractEnglishOcrLines(collectOcrTextLines(result.data), { fallbackToChinese: false }), variant);
          enhanced = enhanced.length ? mergeEnhancedEnglishOcrLines(enhanced, lines) : lines;
          fallback = snapshot();
        }
      } catch (error) {
        // 增强是补充：失败保留原图候选；取消必须继续向上传播。
        checkActive();
        if (error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED') throw error;
      }
      if (meaning && englishCandidates().length) {
        try {
          // 只做一遍中文专用识别，继续受当前 recognize 的同一个总超时约束。
          const prepared = await meaning.prepareImage(image, englishCandidates(), () => terminated);
          checkActive();
          if (prepared) {
            await selectLanguage('chinese');
            checkActive();
            await worker.setParameters({ tessedit_pageseg_mode: '6' });
            checkActive();
            const result = await worker.recognize(prepared.image, {}, { text: true, blocks: true });
            checkActive();
            meaningLines = restoreCoordinates(collectOcrTextLines(result.data), prepared);
            fallback = snapshot();
          }
        } catch (error) {
          // 图片和中文阶段的异常不能清空已成功的英文及配对；取消不得伪装成成功。
          checkActive();
          if (error instanceof OcrError && error.code === 'WORKSHEET_OCR_CANCELLED') throw error;
        }
      }
      checkActive();
      return fallback;
    },
    getFallbackResult() { return fallback; },
    async terminate() {
      terminated = true;
      fallback = null;
      // 服务取消与超时清理可能相邻发生，底层 worker 只释放一次。
      termination ??= Promise.resolve().then(() => worker.terminate()).then(() => undefined);
      await termination;
    }
  };
}
