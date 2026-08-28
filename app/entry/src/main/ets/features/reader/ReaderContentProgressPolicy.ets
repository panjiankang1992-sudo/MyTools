import { ReaderChapter } from './ReaderModels';

export interface ReaderContentLocation {
  chapterIndex: number;
  chapterFraction: number;
}

/** 按正文字符量计算文本图书的实际阅读进度。 */
export class ReaderContentProgressPolicy {
  private cachedChapters?: ReaderChapter[] = undefined;
  private cachedWeights: number[] = [];

  /** 将章节位置换算成按正文长度加权的全书百分比。 */
  percentage(chapters: ReaderChapter[], chapterIndex: number, chapterFraction: number): number {
    if (chapters.length === 0) return 0;
    const weights = this.weights(chapters);
    const safeIndex = Math.max(0, Math.min(chapters.length - 1, Math.floor(chapterIndex)));
    const safeFraction = Number.isFinite(chapterFraction) ? Math.max(0, Math.min(1, chapterFraction)) : 0;
    let completed = 0;
    for (let index = 0; index < safeIndex; index++) completed += weights[index];
    completed += weights[safeIndex] * safeFraction;
    const total = weights.reduce((sum: number, weight: number): number => sum + weight, 0);
    return total <= 0 ? 0 : completed * 100 / total;
  }

  /** 将全书百分比反向定位到按正文长度加权的章节及章内位置。 */
  location(chapters: ReaderChapter[], percentage: number): ReaderContentLocation {
    if (chapters.length === 0) return { chapterIndex: 0, chapterFraction: 0 };
    const weights = this.weights(chapters);
    const total = weights.reduce((sum: number, weight: number): number => sum + weight, 0);
    const safePercentage = Number.isFinite(percentage) ? Math.max(0, Math.min(100, percentage)) : 0;
    let remaining = total * safePercentage / 100;
    for (let index = 0; index < weights.length; index++) {
      if (remaining < weights[index] || index === weights.length - 1) {
        return {
          chapterIndex: index,
          chapterFraction: Math.max(0, Math.min(0.999, remaining / weights[index]))
        };
      }
      remaining -= weights[index];
    }
    return { chapterIndex: chapters.length - 1, chapterFraction: 0.999 };
  }

  /** 返回包含未加载章节估算值的全书正文字符总量。 */
  totalCharacters(chapters: ReaderChapter[]): number {
    return this.weights(chapters).reduce((sum: number, weight: number): number => sum + weight, 0);
  }

  private weights(chapters: ReaderChapter[]): number[] {
    if (this.cachedChapters === chapters && this.cachedWeights.length === chapters.length) {
      return this.cachedWeights;
    }
    const known = chapters.map((chapter: ReaderChapter): number => this.contentLength(chapter))
      .filter((length: number): boolean => length > 0);
    // 网络书源目录不含字数时，以已经加载正文的平均值估算；正文缓存到达后会自动校正。
    const fallback = known.length === 0 ? 1 : Math.max(1,
      Math.round(known.reduce((sum: number, length: number): number => sum + length, 0) / known.length));
    const result = chapters.map((chapter: ReaderChapter): number => {
      const length = this.contentLength(chapter);
      return length > 0 ? length : fallback;
    });
    this.cachedChapters = chapters;
    this.cachedWeights = result;
    return result;
  }

  private contentLength(chapter: ReaderChapter): number {
    const length = chapter.content.length;
    return Number.isSafeInteger(length) && length > 0 ? length : 0;
  }
}
