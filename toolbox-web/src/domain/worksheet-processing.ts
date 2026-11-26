import type { WorksheetMode } from '../types';
import type { InputSubject, InputToken } from './worksheet-input';
import { createSeededRandom, deriveSeed, shuffleWith, balancedQuota } from './random';

/**
 * 题单处理：把已确认的原文条目转换为本次要生成的题目。
 *
 * 契约见 docs/design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md §2.4。
 * 固定顺序：解析 → 确认分词/读音/义项 → 可选逐字展开 → 等价去重 → 选题 → 显式排序 → 重复展开。
 * 任一步都不得改动 rawText 或词边界；本模块只产出派生项。
 */

/** 中文练习单位：整个词，还是逐字。只影响派生项。 */
export type ChineseUnit = 'word' | 'character';

/** 重复项处理方式。 */
export type DuplicatePolicy = 'merge-equivalent' | 'keep-occurrences';

/** 选题方式。 */
export type ProcessingSelection = 'all' | 'random-count' | 'balanced-unit';

/** 排序方式。 */
export type ProcessingOrdering = 'input' | 'random';

/** 处理参数。 */
export interface WorksheetProcessingOptions {
  mode: WorksheetMode | null;
  chineseUnit: ChineseUnit;
  duplicatePolicy: DuplicatePolicy;
  ordering: ProcessingOrdering;
  selection: ProcessingSelection;
  requestedCount: number | null;
  seed: string | null;
  repetitions: 1 | 2 | 3 | 4 | 5;
}

/** 契约规定的默认值。 */
export const PROCESSING_DEFAULTS: Readonly<WorksheetProcessingOptions> = Object.freeze({
  mode: null,
  chineseUnit: 'word',
  duplicatePolicy: 'merge-equivalent',
  ordering: 'input',
  selection: 'all',
  requestedCount: null,
  seed: null,
  repetitions: 1
});

/** 处理规模上限。 */
export const PROCESSING_LIMITS = Object.freeze({
  maxSelected: 200,
  maxQuestions: 500
});

/** 处理过程中的提示，不阻断但要显示给用户。 */
export interface ProcessingNotice {
  code: string;
  detail?: string;
}

/** 处理结果。 */
export interface ProcessingResult {
  /** 最终题目序列（已按 repetitions 展开）。 */
  items: InputToken[];
  /** 逐字展开前的条目数。 */
  tokenCountBefore: number;
  /** 去重后的条目数。 */
  tokenCountAfterDedupe: number;
  /** 选入题单的条目数。 */
  selectedCount: number;
  /** 展开重复后的题目数。 */
  questionCount: number;
  notices: ProcessingNotice[];
  /** 存在阻断问题时不得生成。 */
  blocked: boolean;
  blockers: ProcessingNotice[];
}

/** 判断是否为汉字主体（含扩展区），用于逐字展开与字帖字段校验。 */
export function isHanCharacter(value: string): boolean {
  return /\p{Script=Han}/u.test(value);
}

/** 按 Unicode 字素切分，避免把补充平面字符或组合序列切成半个。 */
export function splitGraphemes(value: string): string[] {
  const segmenter = new Intl.Segmenter('zh', { granularity: 'grapheme' });
  return [...segmenter.segment(value)].map(part => part.segment);
}

/** 可书写的英文至少含拉丁字母，不能把中文、其它文字或符号串当英文。 */
export function isValidEnglishText(value: string): boolean {
  return /\p{Script=Latin}/u.test(value) && /^[\p{Script=Latin}\p{Mark}\p{Number}\p{Punctuation}\s]+$/u.test(value);
}

/** 中文释义必须实际含有汉字，英文原样回传或纯标点不算翻译成功。 */
export function isValidChineseMeaning(value: string | null | undefined): boolean {
  return Boolean(value?.trim() && /\p{Script=Han}/u.test(value));
}

/** 取得英文题目正文；显式清空英文不能悄悄退回旧译文或中文原文。 */
export function englishTextForToken(token: InputToken): string {
  if (token.englishText !== undefined) return token.englishText?.trim() ?? '';
  return isValidEnglishText(token.text) ? token.text : '';
}

/** 取得真实汉字正文；只有拼音时不能把原文中的拉丁字母当作汉字。 */
export function chineseTextForToken(token: InputToken): string {
  return (token.chineseText === undefined ? token.text : token.chineseText).trim();
}

/** 普通话基础音节；声调另行校验，允许轻声音节与合法叹词。 */
const PINYIN_BASES = new Set((
  'a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu '
  + 'ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo '
  + 'da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo '
  + 'e ei en eng er ê fa fan fang fei fen feng fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo '
  + 'ha hai han hang hao he hei hen heng hm hng hong hou hu hua huai huan huang hui hun huo '
  + 'ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo '
  + 'la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lü lüe lüan lün '
  + 'm ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu '
  + 'n na nai nan nang nao ne nei nen neng ng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nü nüe '
  + 'o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu '
  + 'qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun ran rang rao re ren reng ri rong rou ru ruan rui run ruo '
  + 'sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo '
  + 'ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu '
  + 'xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun '
  + 'za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo'
).split(' '));

/** 校验单个拼音音节，拒绝原汉字回传、随意字母串与多个声调。 */
export function isValidPinyinSyllable(value: string): boolean {
  const decomposed = value.toLowerCase().normalize('NFD');
  if ((decomposed.match(/[\u0304\u0301\u030c\u0300]/gu)?.length ?? 0) > 1) return false;
  const base = decomposed.replace(/[\u0304\u0301\u030c\u0300]/gu, '').normalize('NFC');
  return PINYIN_BASES.has(base);
}

/** 所有音节与字素位置一一对应才可用于学生卷或答案卷。 */
export function isValidPinyinSegments(text: string, segments: InputToken['pinyinSegments']): boolean {
  const graphemes = splitGraphemes(text);
  return graphemes.length > 0 && segments.length === graphemes.length
    && segments.every((segment, index) => segment.grapheme === graphemes[index]
      && isHanCharacter(segment.grapheme) && isValidPinyinSyllable(segment.syllable));
}

/** 有汉字时逐字对齐；纯拼音只接受真实音节和空字形，不补造答案。 */
export function isValidPinyinForToken(token: InputToken): boolean {
  const text = chineseTextForToken(token);
  return text ? isValidPinyinSegments(text, token.pinyinSegments)
    : token.pinyinSegments.length > 0 && token.pinyinSegments.every(segment => !segment.grapheme && isValidPinyinSyllable(segment.syllable));
}

/** 条目的等价键：NFC 文本 + 规范读音 + 义项 + 义项编号。 */
export function equivalentKey(token: InputToken): string {
  const pinyin = token.pinyinSegments.map(segment => `${segment.grapheme}:${segment.syllable}`).join(',');
  return [token.text.normalize('NFC'), token.chineseText ?? token.text, token.englishText ?? token.text, pinyin, token.meaning ?? '', token.senseId ?? ''].join('\u0001');
}

/** 合并两个条目的来源引用，按 key 去重。 */
function mergeSourceRefs(left: InputToken['sourceRefs'], right: InputToken['sourceRefs']): InputToken['sourceRefs'] {
  const seen = new Set<string>();
  const merged: InputToken['sourceRefs'] = [];
  for (const ref of [...left, ...right]) {
    const key = [ref.sourceId, ref.kind, ref.editionId ?? '', ref.entryId ?? '', ref.imageId ?? ''].join('|');
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(ref);
  }
  return merged;
}

/**
 * 逐字展开。展开后的字继承词语内已确认的读音，绝不用孤字默认读音重新判定多音字。
 * 词语本身没有读音片段时，展开项保持未校对并提示复核。
 *
 * @param tokens 已确认的条目
 * @returns 展开后的条目与提示
 */
export function expandToCharacters(tokens: readonly InputToken[]): { items: InputToken[]; notices: ProcessingNotice[] } {
  const items: InputToken[] = [];
  const notices: ProcessingNotice[] = [];
  let missingPinyin = 0;
  for (const token of tokens) {
    const text = chineseTextForToken(token);
    // 纯拼音按音节展开，保留空汉字答案；不得按拼音字母展开或丢弃整行。
    if (!text) {
      for (const [index, segment] of token.pinyinSegments.entries()) items.push({ ...token,
        tokenId: `${token.tokenId}#${index}`, text: '', chineseText: '',
        pinyinSegments: [{ ...segment }], acceptableAnswers: [] });
      continue;
    }
    for (const [index, grapheme] of splitGraphemes(text).entries()) {
      // 位置是读音的归属，重复字也可能异音；不能按字形搜索后复用第一个读音。
      const candidate = token.pinyinSegments[index];
      const segment = candidate?.grapheme === grapheme ? candidate : undefined;
      if (!segment && token.pinyinState !== 'missing') missingPinyin += 1;
      items.push({
        ...token,
        tokenId: `${token.tokenId}#${index}`,
        text: grapheme,
        ...(token.chineseText === undefined ? {} : { chineseText: grapheme }),
        pinyinSegments: segment ? [segment] : [],
        pinyinState: segment ? token.pinyinState : 'missing',
        acceptableAnswers: segment ? [segment.syllable] : []
      });
    }
  }
  if (missingPinyin) {
    notices.push({ code: 'WORKSHEET_EXPAND_PINYIN_MISSING', detail: `requiresReview=${missingPinyin}` });
  }
  return { items, notices };
}

/**
 * 等价去重。保留第一次出现的位置，并合并全部来源；不同读音或义项不合并。
 *
 * @param tokens 条目
 * @param policy 去重策略
 * @returns 去重后的条目与前后数量
 */
export function dedupeTokens(
  tokens: readonly InputToken[],
  policy: DuplicatePolicy
): { items: InputToken[]; before: number; after: number; mergedGroups: number } {
  if (policy === 'keep-occurrences') {
    return { items: [...tokens], before: tokens.length, after: tokens.length, mergedGroups: 0 };
  }
  const index = new Map<string, number>();
  const items: InputToken[] = [];
  let mergedGroups = 0;
  for (const token of tokens) {
    const key = equivalentKey(token);
    const existing = index.get(key);
    if (existing === undefined) {
      index.set(key, items.length);
      items.push({ ...token });
      continue;
    }
    // 合并来源，但保留第一次出现的位置与身份。
    const kept = items[existing]!;
    items[existing] = { ...kept, sourceRefs: mergeSourceRefs(kept.sourceRefs, token.sourceRefs) };
    mergedGroups += 1;
  }
  return { items, before: tokens.length, after: items.length, mergedGroups };
}

/** 条目是否来自教材。 */
export function hasTextbookUnit(token: InputToken): boolean {
  return token.sourceRefs.some(ref => ref.kind === 'textbook' && Boolean(ref.editionId));
}

/** 条目是否带有可信的教材单元归属；均衡选题需要它。 */
export function hasUnitAttribution(token: InputToken): boolean {
  return token.sourceRefs.some(ref => ref.kind === 'textbook' && Boolean(ref.editionId) && Boolean(ref.unitId));
}

/** 均衡选题可用性：混入自定义或 OCR 条目时禁用，且不伪造教材单元。 */
export interface BalancedAvailability {
  available: boolean;
  reason: string | null;
  nonTextbookCount: number;
}

/**
 * 判断均衡选题是否可用。
 *
 * @param tokens 候选条目
 * @returns 是否可用、禁用原因与非教材条目数
 */
export function balancedAvailability(tokens: readonly InputToken[]): BalancedAvailability {
  const nonTextbookCount = tokens.filter(token => !hasUnitAttribution(token)).length;
  if (!tokens.length) return { available: false, reason: 'WORKSHEET_BALANCED_EMPTY', nonTextbookCount };
  if (nonTextbookCount > 0) {
    return { available: false, reason: 'WORKSHEET_BALANCED_NEEDS_TEXTBOOK_UNITS', nonTextbookCount };
  }
  return { available: true, reason: null, nonTextbookCount: 0 };
}

/** 按教材单元给条目分组，用于均衡抽题。 */
function groupByUnit(tokens: readonly InputToken[]): Array<{ unitId: string; items: InputToken[] }> {
  const groups = new Map<string, InputToken[]>();
  for (const token of tokens) {
    // 调用方通过 balancedAvailability 保证每个条目都有教材单元，这里不再伪造单元。
    const unitId = token.sourceRefs.find(ref => ref.kind === 'textbook' && ref.unitId)?.unitId ?? '';
    const list = groups.get(unitId) ?? [];
    list.push(token);
    groups.set(unitId, list);
  }
  return [...groups.entries()].map(([unitId, items]) => ({ unitId, items }));
}

/**
 * 选题。`random-count` 与 `balanced-unit` 使用固定 seed 算法，保证同 seed 可复现。
 *
 * @param tokens 去重后的条目
 * @param options 处理参数
 * @returns 选入的条目与提示
 */
export function selectTokens(
  tokens: readonly InputToken[],
  options: WorksheetProcessingOptions
): { items: InputToken[]; notices: ProcessingNotice[]; blocked: boolean } {
  const notices: ProcessingNotice[] = [];
  if (options.selection === 'all') return { items: [...tokens], notices, blocked: false };

  const requested = options.requestedCount ?? tokens.length;
  if (requested > PROCESSING_LIMITS.maxSelected) {
    return {
      items: [],
      notices,
      blocked: true
    };
  }
  if (requested <= 0) return { items: [], notices, blocked: false };

  const seed = options.seed ?? '';
  if (options.selection === 'random-count') {
    const shuffled = shuffleWith(tokens, createSeededRandom(deriveSeed(seed, 'selection')));
    return { items: shuffled.slice(0, Math.min(requested, tokens.length)), notices, blocked: false };
  }

  // 均衡：只在全部条目都有可信教材单元时可用。
  const availability = balancedAvailability(tokens);
  if (!availability.available) {
    notices.push({ code: availability.reason!, detail: `nonTextbook=${availability.nonTextbookCount}` });
    // 禁用时不猜测单元，退化为按输入顺序取前 N 项。
    return { items: tokens.slice(0, Math.min(requested, tokens.length)), notices, blocked: false };
  }
  const groups = groupByUnit(tokens);
  const quota = balancedQuota(groups.map(group => group.items.length), requested);
  const picked: InputToken[] = [];
  groups.forEach((group, index) => {
    const shuffled = shuffleWith(group.items, createSeededRandom(deriveSeed(seed, `unit:${group.unitId}`)));
    picked.push(...shuffled.slice(0, quota[index] ?? 0));
  });
  notices.push({ code: 'WORKSHEET_BALANCED_APPLIED', detail: `units=${groups.length}` });
  return { items: picked.slice(0, requested), notices, blocked: false };
}

/**
 * 显式排序。随机顺序一经确定不得再按教材顺序重排。
 *
 * @param tokens 条目
 * @param ordering 排序方式
 * @param seed 随机种子
 * @returns 排序后的条目
 */
export function orderTokens(
  tokens: readonly InputToken[],
  ordering: ProcessingOrdering,
  seed: string | null
): InputToken[] {
  if (ordering === 'input') return [...tokens];
  return shuffleWith(tokens, createSeededRandom(deriveSeed(seed ?? '', 'ordering')));
}

/**
 * 执行完整处理流水线。
 *
 * @param tokens 已确认的原文条目
 * @param options 处理参数
 * @returns 处理结果，含各阶段数量与提示
 */
export function processWorksheetInput(
  tokens: readonly InputToken[],
  options: WorksheetProcessingOptions
): ProcessingResult {
  const notices: ProcessingNotice[] = [];
  const blockers: ProcessingNotice[] = [];

  // 1. 可选逐字展开（只影响派生项）。
  const expanded = options.chineseUnit === 'character'
    ? expandToCharacters(tokens)
    : { items: [...tokens], notices: [] as ProcessingNotice[] };
  notices.push(...expanded.notices);

  // 2. 等价去重。
  const deduped = dedupeTokens(expanded.items, options.duplicatePolicy);
  if (deduped.mergedGroups) {
    notices.push({
      code: 'WORKSHEET_DEDUPE_MERGED',
      detail: `before=${deduped.before} after=${deduped.after} merged=${deduped.mergedGroups}`
    });
  }

  // 3. 选题。
  const selected = selectTokens(deduped.items, options);
  notices.push(...selected.notices);
  if (selected.blocked || selected.items.length > PROCESSING_LIMITS.maxSelected) {
    // 全部选入与抽取使用同一上限；渲染层只接收重复展开后的题序，不能代替此处检查。
    blockers.push({ code: 'WORKSHEET_LIMIT_SELECTED', detail: `selected=${selected.items.length} limit=${PROCESSING_LIMITS.maxSelected}` });
  }

  // 4. 显式排序（选中集合确定后才排序，之后不得再按教材顺序重排）。
  const ordered = orderTokens(selected.items, options.ordering, options.seed);

  // 5. 重复展开。
  const items: InputToken[] = [];
  for (let round = 0; round < options.repetitions; round += 1) {
    for (const token of ordered) {
      items.push(round === 0 ? token : { ...token, tokenId: `${token.tokenId}~${round}` });
    }
  }
  if (items.length > PROCESSING_LIMITS.maxQuestions) {
    blockers.push({ code: 'WORKSHEET_LIMIT_QUESTIONS', detail: `questions=${items.length} limit=${PROCESSING_LIMITS.maxQuestions}` });
  }

  return {
    items,
    tokenCountBefore: tokens.length,
    tokenCountAfterDedupe: deduped.after,
    selectedCount: ordered.length,
    questionCount: items.length,
    notices,
    blocked: blockers.length > 0,
    blockers
  };
}

/** 输出对象：学生卷还是答案卷。 */
export type WorksheetOutputKind = 'student' | 'answer';

/** 当前输出对象所需的字段。 */
export interface GenerationRequirement {
  /** 需要条目正文非空。 */
  needsText: boolean;
  /** 需要每个字都有完整有效的自动或已确认拼音；保留旧字段名兼容调用方。 */
  needsConfirmedPinyin: boolean;
  /** 需要有效的自动或已确认中文义项。 */
  needsMeaning: boolean;
}

/**
 * 按题型与输出对象给出字段要求。
 *
 * 契约 §3：**阻断条件只取当前输出对象所需字段**。
 * 因此「尚缺拼音」应当阻断「拼音字帖的答案卷」，但不应顺带阻断「只显示汉字的学生卷」。
 *
 * @param mode 全局题型
 * @param kind 输出对象
 * @returns 该输出的字段要求
 */
export function requirementFor(mode: string, kind: WorksheetOutputKind): GenerationRequirement {
  switch (mode) {
    case 'chinese-pinyin':
      // 学生卷显示汉字、填拼音；答案卷才需要拼音与对齐关系。
      return kind === 'answer'
        ? { needsText: true, needsConfirmedPinyin: true, needsMeaning: false }
        : { needsText: true, needsConfirmedPinyin: false, needsMeaning: false };
    case 'chinese-dictation':
      // 学生卷只需拼音题面；答案卷必须同时有题面和汉字答案。
      return kind === 'student'
        ? { needsText: false, needsConfirmedPinyin: true, needsMeaning: false }
        : { needsText: true, needsConfirmedPinyin: true, needsMeaning: false };
    case 'english-meaning':
      // 学生卷给英文填中文；答案卷需要中文义项。
      return kind === 'answer'
        ? { needsText: true, needsConfirmedPinyin: false, needsMeaning: true }
        : { needsText: true, needsConfirmedPinyin: false, needsMeaning: false };
    case 'english-dictation':
      // 学生卷只需中文题面；答案卷必须同时有题面和英文答案。
      return kind === 'student'
        ? { needsText: false, needsConfirmedPinyin: false, needsMeaning: true }
        : { needsText: true, needsConfirmedPinyin: false, needsMeaning: true };
    default:
      return { needsText: true, needsConfirmedPinyin: false, needsMeaning: false };
  }
}

/** 生成可行性判定结果。 */
export interface GenerationCheck {
  ok: boolean;
  /** 缺失字段的条目数。 */
  missingText: number;
  missingPinyin: number;
  missingMeaning: number;
  /** 阻断原因码。 */
  blockers: string[];
}

/**
 * 判断当前内容能否生成指定的输出对象。
 *
 * @param items 选入题单的条目
 * @param mode 全局题型
 * @param kind 输出对象
 * @returns 判定结果与缺失统计
 */
export function checkGeneratable(
  items: readonly InputToken[],
  mode: string,
  kind: WorksheetOutputKind
): GenerationCheck {
  const requirement = requirementFor(mode, kind);
  let missingText = 0;
  let missingPinyin = 0;
  let missingMeaning = 0;
  for (const token of items) {
    const validText = mode.startsWith('english') ? isValidEnglishText(englishTextForToken(token)) && token.englishState !== 'missing'
      : isHanCharacter(chineseTextForToken(token));
    if (requirement.needsText && !validText) missingText += 1;
    if (requirement.needsConfirmedPinyin) {
      // 自动建议保持机器来源标记；完整且有效时可直接使用，用户仍可修改。
      if (!isValidPinyinForToken(token) || token.pinyinState === 'missing') missingPinyin += 1;
    }
    if (requirement.needsMeaning && (!isValidChineseMeaning(token.meaning) || token.meaningState === 'missing')) missingMeaning += 1;
  }
  const blockers: string[] = [];
  if (missingText) blockers.push(mode.startsWith('english') ? 'WORKSHEET_MISSING_ENGLISH' : 'WORKSHEET_MISSING_TEXT');
  if (missingPinyin) blockers.push('WORKSHEET_MISSING_PINYIN');
  if (missingMeaning) blockers.push('WORKSHEET_MISSING_MEANING');
  return { ok: blockers.length === 0, missingText, missingPinyin, missingMeaning, blockers };
}

/** 可选模板必须适用于全部行；不通过选题或去重隐藏缺少题面的行。 */
export function availableWorksheetModes(items: readonly InputToken[], subject: InputSubject): WorksheetMode[] {
  if (!items.length) return [];
  const modes: WorksheetMode[] = subject === 'chinese' ? ['chinese-pinyin', 'chinese-dictation'] : ['english-dictation', 'english-meaning'];
  return modes.filter(mode => checkGeneratable(items, mode, 'student').ok);
}
