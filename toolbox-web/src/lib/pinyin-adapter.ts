import { pinyin } from 'pinyin-pro';
import { isHanCharacter, isValidPinyinSyllable, splitGraphemes } from '../domain/worksheet-processing';

/**
 * 本机拼音候选。
 *
 * 契约 §3.1：读音优先级为「用户确认 → 精确来源条目的已审读音 → 明确匹配的教材候选 → **本机自动候选**」。
 * 本模块只产生最后一级，因此结果状态**永远是 `suggested`**，界面显示「自动生成，可修改」；
 * 它不是教材校对凭据，也不写回任何公共词库。
 */

/** 逐字读音候选。 */
export interface PinyinSuggestion {
  /** 与字素一一对应；非汉字字素的音节为空串。 */
  segments: Array<{ grapheme: string; syllable: string }>;
  /** 本机候选保留机器来源，不冒充人工确认；有效候选可直接使用。 */
  state: 'suggested';
  /** 存在多个读音、供用户核对和修改的字素下标，不因此强制阻断。 */
  ambiguousIndexes: number[];
  /** 无法给出读音的字素下标（非汉字，或拼音库无此字）。 */
  unresolvedIndexes: number[];
}

/** 需要人工复核的问题码。 */
export interface PinyinIssue {
  code: string;
  index: number | null;
  detail?: string;
}

/** 自动候选连同需要复核的问题。 */
export interface PinyinSuggestionResult {
  suggestion: PinyinSuggestion;
  issues: PinyinIssue[];
}

/**
 * 取单个字素的全部读音。
 *
 * @param grapheme 单个字素
 * @returns 去重后的读音列表；非汉字返回空数组
 */
export function candidateReadings(grapheme: string): string[] {
  if (!isHanCharacter(grapheme)) return [];
  const readings = pinyin(grapheme, { multiple: true, toneType: 'symbol', type: 'array' }) as string[];
  return [...new Set(readings.filter(reading => reading !== grapheme && isValidPinyinSyllable(reading)))];
}

/**
 * 按字素给出本机读音候选。
 *
 * 使用逐字 `pinyinSegments`，不做「先拼一整串再 split 对齐」，避免多音字与轻声错位。
 * 词内上下文由 pinyin-pro 自己处理（例如「长大」取 zhǎng），因此整词调用一次即可。
 *
 * @param text 条目文本
 * @returns 逐字候选与需要复核的问题
 */
export function suggestPinyin(text: string): PinyinSuggestionResult {
  // 空白输入没有可标注的内容，按空结果返回而不是把空格当成字素。
  if (!text.trim()) {
    return { suggestion: { segments: [], state: 'suggested', ambiguousIndexes: [], unresolvedIndexes: [] }, issues: [] };
  }
  const graphemes = splitGraphemes(text);
  const issues: PinyinIssue[] = [];
  const ambiguousIndexes: number[] = [];
  const unresolvedIndexes: number[] = [];

  // 整词调用一次，让拼音库使用词语上下文判定多音字。
  const syllables = pinyin(text, { toneType: 'symbol', type: 'array' }) as string[];
  // 长度不一致时不硬对齐，保留缺音并报出问题。
  const aligned = syllables.length === graphemes.length;
  if (!aligned) {
    issues.push({
      code: 'WORKSHEET_PINYIN_ALIGNMENT',
      index: null,
      detail: `graphemes=${graphemes.length} syllables=${syllables.length}`
    });
  }

  const segments = graphemes.map((grapheme, index) => {
    if (!isHanCharacter(grapheme)) {
      // 汉字字帖里的非汉字内容必须经过字段校验，这里只标记，不猜读音。
      unresolvedIndexes.push(index);
      return { grapheme, syllable: '' };
    }
    // 整词长度不匹配时不拼装逐字猜音；字典原样回传汉字也属于未识别。
    const candidate = aligned ? (syllables[index] ?? '') : '';
    const syllable = candidate !== grapheme && isValidPinyinSyllable(candidate) ? candidate : '';
    if (!syllable) unresolvedIndexes.push(index);
    if (candidateReadings(grapheme).length > 1) ambiguousIndexes.push(index);
    return { grapheme, syllable };
  });

  if (ambiguousIndexes.length) {
    issues.push({ code: 'WORKSHEET_PINYIN_AMBIGUOUS', index: null, detail: `count=${ambiguousIndexes.length}` });
  }
  if (unresolvedIndexes.length) {
    issues.push({ code: 'WORKSHEET_PINYIN_UNRESOLVED', index: null, detail: `count=${unresolvedIndexes.length}` });
  }

  return {
    suggestion: { segments, state: 'suggested', ambiguousIndexes, unresolvedIndexes },
    issues
  };
}

/**
 * 批量给出候选，并统计需要人工确认的条目数。
 *
 * 契约 §3.1：批量确认**不得**跳过未解决的多音字与音节数量不匹配项。
 *
 * @param texts 条目文本
 * @returns 每条候选与可安全批量确认的条目下标
 */
export function suggestPinyinBatch(texts: readonly string[]): {
  results: PinyinSuggestionResult[];
  safeToConfirmAll: number[];
} {
  const results = texts.map(text => suggestPinyin(text));
  const safeToConfirmAll = results
    .map((result, index) => ({ result, index }))
    .filter(({ result }) => result.suggestion.ambiguousIndexes.length === 0
      && result.suggestion.unresolvedIndexes.length === 0
      && !result.issues.some(issue => issue.code === 'WORKSHEET_PINYIN_ALIGNMENT'))
    .map(({ index }) => index);
  return { results, safeToConfirmAll };
}
