import { AudiobookAutoNextDecision, AudiobookPlaybackManifest } from './AudiobookModels';

/**
 * 根据固定版本播放清单决定章节结束时的下一步，不跳过相邻未完成章节。
 */
export class AudiobookPlaybackPolicy {
  /** 计算当前章节完成后的连续播放决策。 */
  next(manifest: AudiobookPlaybackManifest, completedIndex: number): AudiobookAutoNextDecision {
    if (!Number.isSafeInteger(completedIndex) || completedIndex < 0) {
      throw new Error('有声书章节序号无效');
    }
    const position = manifest.chapters.findIndex((chapter) => chapter.index === completedIndex);
    if (position < 0 || position === manifest.chapters.length - 1) return { kind: 'COMPLETED' };
    const next = manifest.chapters[position + 1];
    if (next.availability === 'READY') return { kind: 'PLAY', chapter: next };
    if (next.availability === 'PENDING') return { kind: 'WAIT', chapter: next };
    return { kind: 'FAILED', chapter: next };
  }
}
