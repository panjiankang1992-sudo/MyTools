import { isValidPinyinSyllable, splitGraphemes } from '../domain/worksheet-processing';
import { translateEntries, type TranslationResult } from '../services/public-api';
import { suggestPinyin, type PinyinSuggestion } from './pinyin-adapter';

export interface CurriculumPinyinResult {
  id: string;
  sourceText: string;
  suggestion: PinyinSuggestion;
}

/** 教材读音必须逐字对齐且完整，损坏或错误归属的回包只能按未命中处理。 */
function textbookSuggestion(item: { id: string; text: string }, results: TranslationResult[]): PinyinSuggestion | null {
  const matches = results.filter(result => result && result.id === item.id && result.sourceText === item.text && result.status === 'translated');
  if (matches.length !== 1) return null;
  const segments = matches[0]!.pinyinSegments;
  const graphemes = splitGraphemes(item.text);
  if (!Array.isArray(segments) || !segments.length || segments.length !== graphemes.length || segments.some((segment, index) =>
    !segment || segment.grapheme !== graphemes[index] || typeof segment.syllable !== 'string'
      || !isValidPinyinSyllable(segment.syllable))) return null;
  return { segments: segments.map(segment => ({ ...segment })), state: 'suggested', ambiguousIndexes: [], unresolvedIndexes: [] };
}

/** 批量优先查已发布教材；仅未命中项使用本机拼音，取消后不继续请求或计算。 */
export async function completePinyinFromCurriculum(
  items: ReadonlyArray<{ id: string; text: string }>,
  signal: AbortSignal,
  translate: typeof translateEntries = translateEntries,
  suggest: typeof suggestPinyin = suggestPinyin
): Promise<CurriculumPinyinResult[]> {
  const completed: CurriculumPinyinResult[] = [];
  let unavailable = false;
  for (let start = 0; start < items.length && !signal.aborted; start += 20) {
    const batch = items.slice(start, start + 20);
    let results: TranslationResult[] = [];
    if (!unavailable) {
      try {
        // 调用方还保存字段快照与目标栏；接口只接收明确的词条身份和原文。
        const response = await translate(batch.map(({ id, text }) => ({ id, text })), signal, 'chinese');
        results = Array.isArray(response) ? response : [];
      }
      catch { unavailable = true; }
    }
    if (signal.aborted) return [];
    for (const item of batch) {
      const suggestion = textbookSuggestion(item, results) ?? suggest(item.text).suggestion;
      completed.push({ id: item.id, sourceText: item.text, suggestion });
    }
  }
  return signal.aborted ? [] : completed;
}
