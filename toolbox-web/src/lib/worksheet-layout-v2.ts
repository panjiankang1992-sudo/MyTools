import type { TextMeasure } from './worksheet';

/**
 * 紧凑排版：题块测量、顺序装行与分页。
 *
 * 契约见 docs/design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md §6。
 * 本模块只做几何计算，不接触 PDF 绘制，便于用夹具验证密度与不变量。
 * 所有长度在模块内部统一为毫米；只在调用 measure 时换算成 pt。
 */

/** 毫米转 pt。 */
export const MM_TO_PT = 72 / 25.4;

/** 页面与排版参数。 */
export interface LayoutParams {
  /** 纸张宽高，毫米。 */
  pageWidthMm: number;
  pageHeightMm: number;
  /** 四边页边距，毫米。 */
  marginMm: number;
  /** 顶部标题与姓名栏高度，毫米。 */
  headerMm: number;
  /** 页脚高度，毫米。 */
  footerMm: number;
  /** 密度：紧凑或宽松。 */
  density: 'compact' | 'comfortable';
  /** 米字格边长，毫米。 */
  gridMm: number;
  /** 题项间距，毫米。 */
  itemGapMm: number;
  /** 行间距，毫米。 */
  rowGapMm: number;
  /** 拼音书写区每字最小宽度，毫米。 */
  pinyinCellMinMm: number;
  /** 四线三格每格高度，毫米；总高为其三倍。 */
  fourLineGapMm: number;
  /** 题号字号，pt。 */
  numberFontPt: number;
  /** 拼音字号，pt。 */
  pinyinFontPt: number;
  /** 汉字提示字号，pt。 */
  promptFontPt: number;
  /** 英语书写区最小宽度，毫米。 */
  englishCellMinMm: number;
}

/** 契约 §6.1 的新建默认值。 */
export const LAYOUT_DEFAULTS: Readonly<LayoutParams> = Object.freeze({
  pageWidthMm: 210,
  pageHeightMm: 297,
  marginMm: 15,
  headerMm: 22,
  footerMm: 10,
  density: 'compact',
  gridMm: 12,
  itemGapMm: 2.5,
  rowGapMm: 3,
  pinyinCellMinMm: 16,
  fourLineGapMm: 3,
  numberFontPt: 9,
  pinyinFontPt: 10,
  promptFontPt: 14,
  englishCellMinMm: 40
});

/** 排版题项。 */
export interface LayoutQuestion {
  /** 题号，从 1 开始。 */
  number: number;
  /** 题型决定书写区形状。 */
  kind: 'mi-grid' | 'pinyin-cell' | 'english-line';
  /** 题面文字：汉字字帖为空（只给拼音），拼音字帖为汉字，英语为提示文字。 */
  prompt: string;
  /** 逐字拼音，用于汉字字帖的列距与对齐。 */
  segments: Array<{ grapheme: string; syllable: string }>;
  /** 需要书写的字数；未给出时按字素数量推断。 */
  cellCount?: number;
}

/** 测量出的题块几何。 */
export interface QuestionBox {
  question: LayoutQuestion;
  numberWidthMm: number;
  /** 每个书写单元的列距，长度等于 cellCount。 */
  pitchesMm: number[];
  widthMm: number;
  heightMm: number;
  /** 是否为该题的首行；续行不重复题号。 */
  numbered: boolean;
  /** 在本题书写单元中的起始下标。 */
  cellOffset: number;
}

/** 一行的排布结果。 */
export interface LayoutRow {
  boxes: QuestionBox[];
  heightMm: number;
  widthMm: number;
}

/** 一页的排布结果。 */
export interface LayoutPage {
  index: number;
  rows: LayoutRow[];
  usedHeightMm: number;
}

/** 排版问题。 */
export interface LayoutNotice {
  code: string;
  questionNumber: number | null;
  detail?: string;
}

/** 排版结果。 */
export interface LayoutOutcome {
  pages: LayoutPage[];
  notices: LayoutNotice[];
  blocked: boolean;
}

/** 按 Unicode 字素切分。 */
function graphemes(value: string): string[] {
  return [...new Intl.Segmenter('zh', { granularity: 'grapheme' }).segment(value)].map(part => part.segment);
}

/** 测量文字宽度并换算为毫米。 */
function widthMmOf(measure: TextMeasure, text: string, sizePt: number): number {
  return measure(text, sizePt) / MM_TO_PT;
}

/**
 * 题号区宽度：`max(3 mm, 实测题号宽 + 0.8 mm)`。
 * 不能给每题固定一整大列，否则单字也会被稀疏展开。
 *
 * @param number 题号
 * @param params 排版参数
 * @param measure 文字测量函数，返回 pt
 * @returns 题号区宽度，毫米
 */
export function numberWidthMm(number: number, params: LayoutParams, measure: TextMeasure): number {
  return Math.max(3, widthMmOf(measure, `${number}.`, params.numberFontPt) + 0.8);
}

/** 四线三格总高：每格 three 毫米，共三格。 */
export function fourLineHeightMm(params: LayoutParams): number {
  return params.fourLineGapMm * 3;
}

/**
 * 测量单个题块。
 *
 * - `mi-grid`：每字列距 `max(gridMm, 实测拼音宽 + 1 mm)`，米字格仍是 gridMm 正方形，居中在列距内；
 * - `pinyin-cell`：每字书写区至少 `pinyinCellMinMm`；
 * - `english-line`：书写区至少 `englishCellMinMm`。
 *
 * @param question 题项
 * @param params 排版参数
 * @param measure 文字测量函数
 * @returns 题块几何
 */
export function measureQuestion(
  question: LayoutQuestion,
  params: LayoutParams,
  measure: TextMeasure
): QuestionBox {
  const numberWidth = numberWidthMm(question.number, params, measure);
  const cells = question.cellCount ?? (question.segments.length || graphemes(question.prompt).length || 1);
  let pitches: number[];
  let heightMm: number;

  if (question.kind === 'mi-grid') {
    pitches = [];
    for (let index = 0; index < cells; index += 1) {
      const syllable = question.segments[index]?.syllable ?? '';
      // 拼音越宽，列距越大；米字格本身不放大。
      const needed = syllable ? widthMmOf(measure, syllable, params.pinyinFontPt) + 1 : 0;
      pitches.push(Math.max(params.gridMm, needed));
    }
    // 上方一行拼音 + 米字格。
    heightMm = params.pinyinFontPt / MM_TO_PT * 1.2 + params.gridMm;
  } else if (question.kind === 'pinyin-cell') {
    pitches = Array.from({ length: cells }, () => params.pinyinCellMinMm);
    heightMm = params.promptFontPt / MM_TO_PT * 1.2 + fourLineHeightMm(params);
  } else {
    // 英语：中文提示在上、连续四线在下；较长目标按实际手写容量增加。
    // 题块不得宽于内容区，超长提示交给 promptLines 换行显示。
    const available = params.pageWidthMm - params.marginMm * 2 - numberWidth;
    const promptWidth = question.prompt ? widthMmOf(measure, question.prompt, params.promptFontPt) : 0;
    pitches = [Math.min(available, Math.max(params.englishCellMinMm, promptWidth))];
    heightMm = params.promptFontPt / MM_TO_PT * 1.2 + fourLineHeightMm(params);
  }

  const cellsWidth = pitches.reduce((sum, pitch) => sum + pitch, 0);
  return {
    question,
    numberWidthMm: numberWidth,
    pitchesMm: pitches,
    widthMm: numberWidth + cellsWidth,
    heightMm,
    numbered: true,
    cellOffset: 0
  };
}

/**
 * 把宽于内容区的题块折为同一题的续行组，仅首行编号。
 *
 * 契约 §6.2.7：单题超 180 mm 时按书写单元折行，不截断尾部也不无限缩字。
 * 单个单元本身就放不下时无法折叠，原样返回并由调用方定位报错。
 *
 * @param box 已测量的题块
 * @param contentWidthMm 内容区宽度
 * @returns 折行后的题块序列；无法折叠时返回原题块
 */
export function foldQuestion(box: QuestionBox, contentWidthMm: number): QuestionBox[] {
  if (box.widthMm <= contentWidthMm) return [box];
  const total = box.pitchesMm.length;
  const folded: QuestionBox[] = [];
  let start = 0;
  let numbered = true;
  while (start < total) {
    const numberWidth = numbered ? box.numberWidthMm : 0;
    const available = contentWidthMm - numberWidth;
    let used = 0;
    let count = 0;
    while (start + count < total) {
      const pitch = box.pitchesMm[start + count]!;
      if (used + pitch > available + 1e-9) break;
      used += pitch;
      count += 1;
    }
    // 一个书写单元都放不下：不能靠缩字硬塞，交回调用方报错。
    if (count === 0) return [box];
    folded.push({
      ...box,
      numberWidthMm: numberWidth,
      pitchesMm: box.pitchesMm.slice(start, start + count),
      widthMm: numberWidth + used,
      numbered,
      cellOffset: start
    });
    start += count;
    numbered = false;
  }
  return folded;
}

/** 按可用宽度把题块顺序装行；不从后面找短项塞空隙。 */
export interface PackOptions {
  contentWidthMm: number;
  params: LayoutParams;
}

/**
 * 顺序装行。当前行剩余宽度能容纳就追加，不能容纳才换行。
 * 保持题项顺序，不做装箱优化。
 *
 * @param boxes 已测量的题块，按题目顺序
 * @param pack 可用宽度与参数
 * @returns 行序列与提示（例如单题超宽）
 */
export function packRows(boxes: readonly QuestionBox[], pack: PackOptions): { rows: LayoutRow[]; notices: LayoutNotice[] } {
  const { contentWidthMm, params } = pack;
  const rows: LayoutRow[] = [];
  const notices: LayoutNotice[] = [];
  let current: QuestionBox[] = [];
  let currentWidth = 0;

  const flush = (): void => {
    if (!current.length) return;
    rows.push({
      boxes: current,
      widthMm: currentWidth,
      heightMm: Math.max(...current.map(box => box.heightMm))
    });
    current = [];
    currentWidth = 0;
  };

  for (const box of boxes) {
    if (box.widthMm > contentWidthMm) {
      // 单题超宽：折为同一题的续行组，仅首行编号；本模块先如实报出，由绘制层处理续行。
      notices.push({
        code: 'WORKSHEET_LAYOUT_OVERFLOW',
        questionNumber: box.question.number,
        detail: `width=${box.widthMm.toFixed(2)} limit=${contentWidthMm}`
      });
      flush();
      rows.push({ boxes: [box], widthMm: box.widthMm, heightMm: box.heightMm });
      continue;
    }
    const separator = current.length ? params.itemGapMm : 0;
    if (current.length && currentWidth + separator + box.widthMm > contentWidthMm) flush();
    const nextSeparator = current.length ? params.itemGapMm : 0;
    current.push(box);
    currentWidth += nextSeparator + box.widthMm;
  }
  flush();
  return { rows, notices };
}

/**
 * 分页。整行放不下就换页；整题及其续行不跨页；不产生多余空白尾页。
 *
 * @param rows 行序列
 * @param params 排版参数
 * @returns 页序列
 */
export function paginate(rows: readonly LayoutRow[], params: LayoutParams): LayoutPage[] {
  const usableHeight = params.pageHeightMm - params.marginMm * 2 - params.headerMm - params.footerMm;

  // 同一题的续行必须落在同一页：先把行按题号合并成不可分割的块。
  const rowQuestions = rows.map(row => new Set(row.boxes.map(box => box.question.number)));
  const blockOf = new Array<number>(rows.length).fill(-1);
  const blocks: number[][] = [];
  for (let index = 0; index < rows.length; index += 1) {
    if (blockOf[index] >= 0) continue;
    const members = new Set<number>([index]);
    const questions = new Set<number>(rowQuestions[index]);
    // 一行可能同时含两道题，需要反复扩张直到不再有新的题加入。
    let changed = true;
    while (changed) {
      changed = false;
      for (let other = 0; other < rows.length; other += 1) {
        if (members.has(other)) continue;
        if (![...rowQuestions[other]].some(number => questions.has(number))) continue;
        members.add(other);
        for (const number of rowQuestions[other]) questions.add(number);
        changed = true;
      }
    }
    const id = blocks.length;
    for (const member of members) blockOf[member] = id;
    blocks.push([...members].sort((left, right) => left - right));
  }

  const pages: LayoutPage[] = [];
  let current: LayoutRow[] = [];
  let used = 0;
  const flush = (): void => {
    if (!current.length) return;
    pages.push({ index: pages.length, rows: current, usedHeightMm: used });
    current = [];
    used = 0;
  };

  for (const block of [...blocks].sort((left, right) => left[0]! - right[0]!)) {
    const height = block.reduce((sum, rowIndex, position) =>
      sum + rows[rowIndex]!.heightMm + (position ? params.rowGapMm : 0), 0);
    // 整组高于一页可写区时另起一页放置，由调用方按 notices 报错。
    if (current.length && used + (current.length ? params.rowGapMm : 0) + height > usableHeight) flush();
    block.forEach((rowIndex, position) => {
      const row = rows[rowIndex]!;
      current.push(row);
      used += (current.length > 1 ? params.rowGapMm : 0) + row.heightMm;
      void position;
    });
  }
  flush();
  return pages;
}

/** 单题高度超过可写区时定位报错，不截断也不无限缩字。 */
export interface HeightCheck {
  code: string;
  questionNumber: number;
  detail: string;
}

/**
 * 检查是否有单题高度超过一页可写区。
 *
 * @param boxes 已测量的题块
 * @param params 排版参数
 * @returns 问题列表
 */
export function checkQuestionHeights(boxes: readonly QuestionBox[], params: LayoutParams): HeightCheck[] {
  const usableHeight = params.pageHeightMm - params.marginMm * 2 - params.headerMm - params.footerMm;
  return boxes
    .filter(box => box.heightMm > usableHeight)
    .map(box => ({
      code: 'WORKSHEET_LAYOUT_QUESTION_TOO_TALL',
      questionNumber: box.question.number,
      detail: `height=${box.heightMm.toFixed(2)} limit=${usableHeight.toFixed(2)}`
    }));
}

/**
 * 完整排版：测量 → 装行 → 分页。
 *
 * @param questions 题项，按选定顺序
 * @param params 排版参数
 * @param measure 文字测量函数
 * @returns 页序列与提示
 */
export function layoutQuestions(
  questions: readonly LayoutQuestion[],
  params: LayoutParams,
  measure: TextMeasure
): LayoutOutcome {
  const contentWidthMm = params.pageWidthMm - params.marginMm * 2;
  // 先折行再装行：续行与首行同属一题，只有首行编号。
  const boxes = questions
    .map(question => measureQuestion(question, params, measure))
    .flatMap(box => foldQuestion(box, contentWidthMm));
  const { rows, notices } = packRows(boxes, { contentWidthMm, params });
  const tall = checkQuestionHeights(boxes, params);
  for (const problem of tall) {
    notices.push({ code: problem.code, questionNumber: problem.questionNumber, detail: problem.detail });
  }
  const pages = paginate(rows, params);
  return {
    pages,
    notices,
    blocked: tall.length > 0 || notices.some(notice => notice.code === 'WORKSHEET_LAYOUT_OVERFLOW')
  };
}

/** 首行题项数，供密度夹具断言使用。 */
export function firstRowCount(outcome: LayoutOutcome): number {
  return outcome.pages[0]?.rows[0]?.boxes.length ?? 0;
}
