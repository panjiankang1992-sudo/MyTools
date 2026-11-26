import type { CurriculumEntry, ListType, PinyinSegment } from '../domain/curriculum';
import { sampleEntries } from './worksheet-samples';
import type { Subject } from '../types';

/**
 * 把原创排版示例转换为教材条目结构。
 * 这些条目仅用于排版与流程验证，明确标记为示例，不代表任何教材词库。
 */

/** 示例内容使用的虚拟版本标识，不与真实教材 ID 冲突。 */
export const SAMPLE_EDITION_ID = 'sample-layout-only';

/** 示例内容版本。 */
export const SAMPLE_CONTENT_VERSION = 'sample-1';

/** 将带声调拼音按空格切分为逐字片段，音节数量不一致时返回空数组。 */
function splitPinyin(text: string, pinyin: string): PinyinSegment[] {
  const graphemes = Array.from(text);
  const syllables = pinyin.split(/\s+/u).filter(Boolean);
  if (!pinyin || syllables.length !== graphemes.length) return [];
  return graphemes.map((grapheme, index) => ({ grapheme, syllable: syllables[index]! }));
}

/** 返回指定学科的示例条目，单元编号沿用示例数据。 */
export function sampleCurriculumEntries(subject: Subject): CurriculumEntry[] {
  const listTypes: ListType[] = subject === 'chinese' ? ['vocabulary'] : ['vocabulary'];
  return sampleEntries(subject).map((entry, index) => ({
    entryId: `sample:${entry.id}`,
    entryVersion: SAMPLE_CONTENT_VERSION,
    editionId: SAMPLE_EDITION_ID,
    unitId: `${SAMPLE_EDITION_ID}-unit-${entry.unit}`,
    lessonId: null,
    order: index,
    form: entry.kind,
    listTypes,
    text: entry.text,
    pinyinDisplay: entry.pinyin || null,
    pinyinSegments: subject === 'chinese' ? splitPinyin(entry.text, entry.pinyin) : [],
    meaning: entry.meaning || null,
    partOfSpeech: null,
    senseId: `${entry.id}:sample`,
    context: null,
    promptContext: null,
    acceptableAnswers: [],
    sourceRefs: [],
    reviewStatus: 'draft',
    blockingIssues: []
  }));
}

/** 返回示例内容的单元清单，用于范围树展示。 */
export function sampleUnits(): Array<{ unitId: string; title: string; order: number }> {
  return [1, 2, 3].map(unit => ({
    unitId: `${SAMPLE_EDITION_ID}-unit-${unit}`,
    title: `${unit}`,
    order: unit
  }));
}
