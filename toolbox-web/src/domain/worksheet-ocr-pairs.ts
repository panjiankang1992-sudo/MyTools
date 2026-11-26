import type { OcrTextBox, OcrTextLine, OcrTextWord } from './worksheet-ocr-text';

/** 词表图片中的一组对应内容；中文缺失时留空，不能用下一条释义补齐。 */
export interface OcrVocabularyEntry { text: string; meaning: string; confidence?: number }

/** 中文片段与其在原图中的位置，所有识别阶段必须先统一坐标。 */
type MeaningSpan = OcrTextWord;

/** 同一列英文起点的范围；右边界用于阻止跨栏配对。 */
interface TextColumn { left: number; right: number; indices: number[] }

/** 图片坐标缺失时不进行推测性位置匹配。 */
function validBox(box: OcrTextBox): boolean {
  return [box.x0, box.y0, box.x1, box.y1].every(Number.isFinite) && box.x1 > box.x0 && box.y1 > box.y0;
}

/** 仅删除页码括号，保留“在（某时间）”这样的释义正文。 */
function removePageNumbers(text: string): string {
  return text.replace(/[（(]\s*\d{1,4}\s*[)）]/gu, '').trim();
}

/** 中文释义中的字符和标点必须可解释，不把拉丁乱码当作中文。 */
function cleanMeaning(text: string): string {
  const clean = removePageNumbers(text).replace(/\s+/gu, '').replace(/^[,，;；:：]+|[,，;；:：]+$/gu, '');
  if (!/\p{Script=Han}/u.test(clean) || /[^\p{Script=Han}，。！？、；：,.!?;:（）()“”‘’「」『』·—…/\-]/u.test(clean)) return '';
  return clean;
}

/** 合并同一释义中各个字的边界，不把整行英文和页码包括进来。 */
function unionBoxes(words: readonly OcrTextWord[]): OcrTextBox {
  return {
    x0: Math.min(...words.map(word => word.bbox.x0)), y0: Math.min(...words.map(word => word.bbox.y0)),
    x1: Math.max(...words.map(word => word.bbox.x1)), y1: Math.max(...words.map(word => word.bbox.y1))
  };
}

/** 不可用的置信度不参与聚合，也不能伪造出满分。 */
function minimumConfidence(values: readonly (number | undefined)[]): number | undefined {
  const known = values.filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
  return known.length ? Math.min(...known) : undefined;
}

/** 逐行收集中文词段，英文和显著栏间空白均形成边界。 */
function meaningSpans(lines: readonly OcrTextLine[]): MeaningSpan[] {
  const spans: MeaningSpan[] = [];
  lines.forEach(line => {
    // 纯中文裁剪结果可以直接使用；混排整行没有词级坐标时不能假定释义位置。
    if (!line.words?.length) {
      const text = cleanMeaning(line.text);
      if (text && validBox(line.bbox) && (line.confidence === undefined || line.confidence >= 45)) spans.push({ ...line, text });
      return;
    }
    let run: OcrTextWord[] = [];
    const flush = () => {
      if (!run.length) return;
      const text = cleanMeaning(run.map(word => word.text).join(''));
      // 手写线附近括号和逗号常常低分，释义质量以汉字词块判断，不能只因括号低分丢掉完整释义。
      const confidence = minimumConfidence(run.filter(word => /\p{Script=Han}/u.test(word.text)).map(word => word.confidence)) ?? line.confidence;
      if (text && (confidence === undefined || confidence >= 45)) spans.push({ text, bbox: unionBoxes(run), confidence });
      run = [];
    };
    for (const word of line.words) {
      const text = removePageNumbers(word.text).replace(/\s+/gu, '');
      // 页码不进入释义；英文或无法解释的字符则结束当前片段。
      if (!text) { flush(); continue; }
      if (!validBox(word.bbox) || /[^\p{Script=Han}，。！？、；：,.!?;:（）()“”‘’「」『』·—…/\-]/u.test(text)) { flush(); continue; }
      const previous = run[run.length - 1];
      if (previous) {
        const height = Math.max(previous.bbox.y1 - previous.bbox.y0, word.bbox.y1 - word.bbox.y0);
        // 两栏都只有中文时也不能跨越栏间大空白拼成同一释义。
        if (word.bbox.x0 - previous.bbox.x1 > height * 2.4) flush();
      }
      run.push({ ...word, text });
    }
    flush();
  });
  return spans;
}

/** 按英文左边缘聚类，短语宽度不能将后面一栏吞并。 */
function textColumns(lines: readonly OcrTextLine[]): TextColumn[] {
  const positioned = lines.map((line, index) => ({ line, index })).filter(({ line }) => validBox(line.bbox));
  const heights = positioned.map(({ line }) => line.bbox.y1 - line.bbox.y0).sort((left, right) => left - right);
  const tolerance = Math.max(20, (heights[Math.floor(heights.length / 2)] ?? 20) * 1.4);
  const columns: TextColumn[] = [];
  for (const { line, index } of positioned.sort((left, right) => left.line.bbox.x0 - right.line.bbox.x0)) {
    const current = columns[columns.length - 1];
    if (current && line.bbox.x0 - current.left <= tolerance) current.indices.push(index);
    else columns.push({ left: line.bbox.x0, right: Infinity, indices: [index] });
  }
  columns.forEach((column, index) => { column.right = columns[index + 1]?.left ?? Infinity; });
  return columns;
}

/** 同行需要明显的纵向重合，单纯接近不能将下一行错配。 */
function verticalOverlap(left: OcrTextBox, right: OcrTextBox): number {
  return Math.max(0, Math.min(left.y1, right.y1) - Math.max(left.y0, right.y0)) / Math.min(left.y1 - left.y0, right.y1 - right.y0);
}

/** 中文必须留在所属英文列范围内，不越过下一列的英文起点。 */
function withinColumn(span: MeaningSpan, column: TextColumn, height: number): boolean {
  return span.bbox.x0 >= column.left - height * 0.35 && span.bbox.x1 <= column.right;
}

/** 同一坐标的重复识别结果只能占用一次，避免回退阶段重复追加释义。 */
function overlapsMeaning(left: MeaningSpan, right: MeaningSpan): boolean {
  const horizontal = Math.max(0, Math.min(left.bbox.x1, right.bbox.x1) - Math.max(left.bbox.x0, right.bbox.x0));
  return verticalOverlap(left.bbox, right.bbox) >= 0.65 && horizontal >= Math.min(left.bbox.x1 - left.bbox.x0, right.bbox.x1 - right.bbox.x0) * 0.65;
}

/**
 * 按原图坐标将英文与中文释义一一对应，先同行右侧，再尝试同栏紧邻的下一行。
 * 保留英文输入顺序、短语标点和重复项；无法可靠匹配的中文留空，交给用户校对。
 */
export function pairEnglishOcrLines(
  englishLines: readonly OcrTextLine[], chineseLines: readonly OcrTextLine[]
): OcrVocabularyEntry[] {
  const entries = englishLines.map(line => ({ text: line.text, meaning: '', confidence: line.confidence }));
  const spans = meaningSpans(chineseLines);
  const columns = textColumns(englishLines);
  const used = new Set<number>();
  const matched = new Set<number>();
  const applyMatch = (index: number, spanIndex: number) => {
    const span = spans[spanIndex]!;
    entries[index] = { text: englishLines[index]!.text, meaning: span.text, confidence: minimumConfidence([englishLines[index]!.confidence, span.confidence]) };
    matched.add(index);
    // 原图和裁剪结果重叠时，共享空间的重复片段也一起标记为已用。
    spans.forEach((candidate, candidateIndex) => { if (candidateIndex === spanIndex || overlapsMeaning(span, candidate)) used.add(candidateIndex); });
  };
  // 同行关系优先于换行关系，保证下一项的中文不会被前一项占用。
  for (const column of columns) {
    for (const index of column.indices) {
      const english = englishLines[index]!;
      const height = english.bbox.y1 - english.bbox.y0;
      const candidates = spans.map((span, spanIndex) => ({ span, spanIndex })).filter(({ span, spanIndex }) =>
        !used.has(spanIndex) && withinColumn(span, column, height) && span.bbox.x0 >= english.bbox.x1 - height * 0.3 && verticalOverlap(english.bbox, span.bbox) >= 0.4
      ).sort((left, right) => {
        const distance = (span: MeaningSpan) => Math.abs((span.bbox.y0 + span.bbox.y1) - (english.bbox.y0 + english.bbox.y1)) + Math.max(0, span.bbox.x0 - english.bbox.x1) * 0.2;
        return distance(left.span) - distance(right.span) || (right.span.confidence ?? 0) - (left.span.confidence ?? 0);
      });
      if (candidates[0]) applyMatch(index, candidates[0].spanIndex);
    }
  }
  for (const column of columns) {
    for (const index of column.indices) {
      if (matched.has(index)) continue;
      const english = englishLines[index]!;
      const height = english.bbox.y1 - english.bbox.y0;
      const following = column.indices.map(other => englishLines[other]!).filter(line => line.bbox.y0 > english.bbox.y0);
      const nextTop = Math.min(...following.map(line => line.bbox.y0));
      const candidates = spans.map((span, spanIndex) => ({ span, spanIndex })).filter(({ span, spanIndex }) => {
        if (used.has(spanIndex) || !withinColumn(span, column, height)) return false;
        // 换行译文须在下一英文行之前，并与所有其他英文行错开。
        if (span.bbox.y0 < english.bbox.y1 - height * 0.15 || span.bbox.y0 - english.bbox.y1 > height * 1.6 || span.bbox.y1 > nextTop) return false;
        if (column.indices.some(other => other !== index && verticalOverlap(englishLines[other]!.bbox, span.bbox) >= 0.25)) return false;
        // 缩进换行译文必须与原英文横向相交；右下方一段文字不能随意归属。
        return span.bbox.x0 < english.bbox.x1 && span.bbox.x1 > english.bbox.x0;
      }).sort((left, right) => left.span.bbox.y0 - right.span.bbox.y0 || (right.span.confidence ?? 0) - (left.span.confidence ?? 0));
      // 同一行存在多个互不相交的释义时，归属不明确，留待校对。
      if (candidates[0] && !candidates.slice(1).some(candidate => verticalOverlap(candidate.span.bbox, candidates[0]!.span.bbox) >= 0.4 && !overlapsMeaning(candidate.span, candidates[0]!.span))) {
        applyMatch(index, candidates[0].spanIndex);
      }
    }
  }
  return entries;
}

/**
 * 只恢复规则词表中间缺失英文的一行，保留中文供后续自动翻译。
 * 需要同栏两侧已配对释义和另一处正常行距作为依据，不将首尾、标题或换行释义扩充成词条。
 */
export function recoverMissingEnglishOcrEntries(
  englishLines: readonly OcrTextLine[], chineseLines: readonly OcrTextLine[], entries: readonly OcrVocabularyEntry[]
): OcrVocabularyEntry[] {
  // 原配对的下标契约必须成立，不能在数量不明的结果中推测插入位置。
  if (entries.length !== englishLines.length) return [...entries];
  const spans = meaningSpans(chineseLines);
  const insertions = new Map<number, OcrVocabularyEntry>();
  const center = (box: OcrTextBox) => (box.y0 + box.y1) / 2;
  for (const column of textColumns(englishLines)) {
    const indices = [...column.indices].sort((left, right) => center(englishLines[left]!.bbox) - center(englishLines[right]!.bbox));
    // 至少三条英文才可能用额外的正常间隔验证中间空行，两个孤立词条不构成规则词表。
    if (indices.length < 3) continue;
    const anchors = new Map<number, MeaningSpan>();
    for (const index of indices) {
      const english = englishLines[index]!;
      const height = english.bbox.y1 - english.bbox.y0;
      const entry = entries[index]!;
      if (!entry.meaning || (entry.confidence ?? 0) < 75) continue;
      const candidates = spans.filter(span => span.text === entry.meaning && (span.confidence ?? 0) >= 75 &&
        withinColumn(span, column, height) && span.bbox.x0 >= english.bbox.x1 - height * 0.3 && verticalOverlap(english.bbox, span.bbox) >= 0.4
      ).sort((left, right) => Math.abs(center(left.bbox) - center(english.bbox)) - Math.abs(center(right.bbox) - center(english.bbox)) || (right.confidence ?? 0) - (left.confidence ?? 0));
      if (candidates[0]) anchors.set(index, candidates[0]);
    }
    const gaps = indices.slice(1).map((index, offset) => center(englishLines[index]!.bbox) - center(englishLines[indices[offset]!]!.bbox));
    for (let offset = 1; offset < indices.length; offset += 1) {
      const beforeIndex = indices[offset - 1]!;
      const afterIndex = indices[offset]!;
      const before = anchors.get(beforeIndex);
      const after = anchors.get(afterIndex);
      // 逆序输入仍按原顺序输出，不在逆序锚点之间插入推测的行。
      if (!before || !after || beforeIndex >= afterIndex) continue;
      const height = ((before.bbox.y1 - before.bbox.y0) + (after.bbox.y1 - after.bbox.y0)) / 2;
      const gap = center(after.bbox) - center(before.bbox);
      const normalGap = gap / 2;
      // 中间候选必须位于两倍正常行距的空隙，并且两端释义在同一中文列。
      if (normalGap < height * 1.5 || normalGap > height * 3.5 || Math.abs(before.bbox.x0 - after.bbox.x0) > height * 0.6) continue;
      if (!gaps.some((value, gapIndex) => gapIndex !== offset - 1 && Math.abs(value - normalGap) <= height * 0.4)) continue;
      const midpoint = (center(before.bbox) + center(after.bbox)) / 2;
      const candidates = spans.filter(span => {
        if ((span.confidence ?? 0) < 75 || !withinColumn(span, column, height)) return false;
        if (Math.abs(center(span.bbox) - midpoint) > height * 0.4) return false;
        if (Math.abs(span.bbox.x0 - before.bbox.x0) > height * 0.6 || Math.abs(span.bbox.x0 - after.bbox.x0) > height * 0.6) return false;
        const spanHeight = span.bbox.y1 - span.bbox.y0;
        if (spanHeight < height * 0.65 || spanHeight > height * 1.5) return false;
        // 任意英文行已经占用该高度时不能增加重复项；换行释义也不能作为额外答案。
        return !column.indices.some(index => verticalOverlap(englishLines[index]!.bbox, span.bbox) >= 0.2) &&
          ![...anchors.values()].some(anchor => overlapsMeaning(anchor, span));
      }).sort((left, right) => (right.confidence ?? 0) - (left.confidence ?? 0));
      const candidate = candidates[0];
      // 两路 OCR 可以有同一坐标的重复结果，但同高度两个独立中文片段意味着归属不明确。
      if (candidate && candidates.every(span => overlapsMeaning(candidate, span))) {
        insertions.set(afterIndex, { text: '', meaning: candidate.text, confidence: candidate.confidence });
      }
    }
  }
  return entries.flatMap((entry, index) => insertions.has(index) ? [insertions.get(index)!, entry] : [entry]);
}
