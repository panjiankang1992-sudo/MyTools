/** OCR 行和单词的图片坐标；增强图必须沿用原图尺寸。 */
export interface OcrTextBox { x0: number; y0: number; x1: number; y1: number }

/** OCR 单词及其可选置信度。 */
export interface OcrTextWord { text: string; confidence?: number; bbox: OcrTextBox }

/** 保留坐标的 OCR 行，供词表提取和增强结果合并使用。 */
export interface OcrTextLine extends OcrTextWord { words?: readonly OcrTextWord[] | null }

/** 与 Tesseract 的块、段落和行结果保持结构兼容。 */
export interface OcrTextLayout {
  text?: string;
  confidence?: number;
  lines?: readonly OcrTextLine[] | null;
  blocks?: readonly {
    lines?: readonly OcrTextLine[] | null;
    paragraphs?: readonly { lines?: readonly OcrTextLine[] | null }[] | null;
  }[] | null;
}

/** 优先读取原始行顺序；没有布局时仅按换行拆分，不伪造可匹配的图片坐标。 */
export function collectOcrTextLines(data: OcrTextLayout): OcrTextLine[] {
  const lines = data.lines?.length ? [...data.lines] : (data.blocks ?? []).flatMap(block => {
    const paragraphLines = (block.paragraphs ?? []).flatMap(paragraph => paragraph.lines ?? []);
    return paragraphLines.length ? paragraphLines : [...(block.lines ?? [])];
  });
  if (lines.length) return lines;
  return (data.text ?? '').split(/\r?\n/u).filter(text => text.trim()).map(text => ({
    text, confidence: data.confidence, bbox: { x0: 0, y0: 0, x1: 0, y1: 0 }
  }));
}

/** 仅删除教材页码，不把正文中的数字或括号内容猜测成词汇。 */
function withoutPageNumbers(text: string): string {
  return text.replace(/[（(]\s*\d{1,4}\s*[)）]/gu, '').trim();
}

/** 提取释义之前的英文正文；不做拼写补全，也不根据词典猜字。 */
function englishPrefix(text: string): string {
  const clean = withoutPageNumbers(text).replace(/^[^A-Za-z\p{Script=Han}]+/u, '');
  // 单元标题不属于词表；中文一旦开始，后面的拉丁字母也不能混入英文题目。
  if (/^unit\s*\d+(?:\s|$)/iu.test(clean) || /^\p{Script=Han}/u.test(clean)) return '';
  const prefix = clean.split(/\p{Script=Han}/u, 1)[0] ?? '';
  const accepted: string[] = [];
  // 数字或杂乱符号混进一个词时停止整段，不能把 l0ok 猜成 l 或 look。
  for (const token of prefix.trim().split(/\s+/u)) {
    if (!/^[A-Za-z]+(?:['’‐‑-][A-Za-z]+)*(?:\.[A-Za-z]+)*(?:[.,]|[!?]+)?$/u.test(token)) break;
    accepted.push(token);
  }
  return accepted.join(' ');
}

/** 多个识别单词的边界合并后只覆盖英文，不把整行中文与页码的宽度带入匹配。 */
function unionBoxes(words: readonly OcrTextWord[]): OcrTextBox {
  return {
    x0: Math.min(...words.map(word => word.bbox.x0)), y0: Math.min(...words.map(word => word.bbox.y0)),
    x1: Math.max(...words.map(word => word.bbox.x1)), y1: Math.max(...words.map(word => word.bbox.y1))
  };
}

/** 依据保留下来的英文单词计算置信度，避免用高置信的中文释义抬高错误英文。 */
function wordConfidence(words: readonly OcrTextWord[], fallback?: number): number | undefined {
  const known = words.filter(word => typeof word.confidence === 'number' && Number.isFinite(word.confidence));
  if (!known.length) return fallback;
  const total = known.reduce((sum, word) => sum + Math.max(1, word.text.length), 0);
  return known.reduce((sum, word) => sum + word.confidence! * Math.max(1, word.text.length), 0) / total;
}

/** 从一行中提取英文及其对应坐标，保留短语的空格、大小写和有效标点。 */
function englishLine(line: OcrTextLine): OcrTextLine | null {
  const text = englishPrefix(line.text);
  if (!text) return null;
  if (!line.words?.length) return { ...line, text };
  const words: OcrTextWord[] = [];
  // 只收集释义之前的英文单词，后续中文和页码不得影响几何范围。
  for (const word of line.words) {
    if (/\p{Script=Han}/u.test(word.text)) break;
    const wordText = englishPrefix(word.text);
    if (wordText) words.push({ ...word, text: wordText });
    else if (words.length) break;
  }
  return words.length ? {
    ...line, text, words, bbox: unionBoxes(words), confidence: wordConfidence(words, line.confidence)
  } : { ...line, text };
}

/**
 * 将英文词表转换为每行一项的候选，去掉中文释义、页码和纯符号行。
 * 整张图片没有英文时可保留中文候选，让后续自动翻译继续工作。
 */
export function extractEnglishOcrLines(
  lines: readonly OcrTextLine[], options: { fallbackToChinese?: boolean; minConfidence?: number } = {}
): OcrTextLine[] {
  const minimum = options.minConfidence ?? 45;
  const english = lines.map(englishLine).filter((line): line is OcrTextLine => line !== null)
    .filter(line => line.confidence === undefined || (Number.isFinite(line.confidence) && line.confidence >= minimum));
  if (english.length || options.fallbackToChinese === false) return english;
  return lines.flatMap(line => {
    const text = withoutPageNumbers(line.text).replace(/^[^\p{Script=Han}]+|[^\p{Script=Han}。！？!?]+$/gu, '').trim();
    return /\p{Script=Han}/u.test(text) ? [{ ...line, text }] : [];
  });
}

/** 图片坐标有效时才能用位置匹配，纯文本兜底不能与真实图片位置混用。 */
function validBox(box: OcrTextBox): boolean {
  return [box.x0, box.y0, box.x1, box.y1].every(Number.isFinite) && box.x1 > box.x0 && box.y1 > box.y0;
}

/** 同时检查行高、横向交集与起点，防止同一高度的左右两栏互相覆盖。 */
function matchScore(left: OcrTextBox, right: OcrTextBox): number {
  if (!validBox(left) || !validBox(right)) return -1;
  const minWidth = Math.min(left.x1 - left.x0, right.x1 - right.x0);
  const minHeight = Math.min(left.y1 - left.y0, right.y1 - right.y0);
  const maxHeight = Math.max(left.y1 - left.y0, right.y1 - right.y0);
  const horizontal = Math.min(left.x1, right.x1) - Math.max(left.x0, right.x0);
  const vertical = Math.min(left.y1, right.y1) - Math.max(left.y0, right.y0);
  const startDistance = Math.abs(left.x0 - right.x0);
  const startBudget = Math.max(maxHeight * 2, minWidth * 0.45);
  if (vertical < minHeight * 0.35 || horizontal < minWidth * 0.35 || startDistance > startBudget) return -1;
  return vertical / minHeight + horizontal / minWidth - startDistance / startBudget;
}

/** 增强结果只覆盖原句右半段时，即使起点不同也不能追加成另一道题。 */
function isContainedFragment(original: OcrTextBox, incoming: OcrTextBox): boolean {
  if (!validBox(original) || !validBox(incoming)) return false;
  const vertical = Math.min(original.y1, incoming.y1) - Math.max(original.y0, incoming.y0);
  const horizontal = Math.min(original.x1, incoming.x1) - Math.max(original.x0, incoming.x0);
  const minHeight = Math.min(original.y1 - original.y0, incoming.y1 - incoming.y0);
  return vertical >= minHeight * 0.35 && horizontal >= (incoming.x1 - incoming.x0) * 0.7;
}

/** 新识别出的行按相邻同栏行插入；原有行之间的顺序和重复词均保持不变。 */
function insertByPosition(lines: OcrTextLine[], incoming: OcrTextLine): void {
  const column = lines.map((line, index) => ({ line, index })).filter(({ line }) => {
    const width = Math.min(line.bbox.x1 - line.bbox.x0, incoming.bbox.x1 - incoming.bbox.x0);
    return validBox(line.bbox) && width > 0 && Math.abs(line.bbox.x0 - incoming.bbox.x0) <= Math.max(30, width * 0.45);
  });
  const next = column.find(({ line }) => line.bbox.y0 > incoming.bbox.y0);
  if (next) lines.splice(next.index, 0, incoming);
  else if (column.length) lines.splice(column[column.length - 1]!.index + 1, 0, incoming);
  else lines.push(incoming);
}

/** 判断增强词是否只是可靠原句的一部分，避免丢失黑色印刷的半句。 */
function trustedMixedPhrase(original: OcrTextLine, enhanced: OcrTextLine): boolean {
  const words = original.text.toLowerCase().match(/[a-z]+(?:['’‐‑-][a-z]+)*/gu) ?? [];
  const fragment = enhanced.text.toLowerCase().match(/[a-z]+(?:['’‐‑-][a-z]+)*/gu) ?? [];
  if (!fragment.length || fragment.length >= words.length) return false;
  const contains = words.some((_word, index) => fragment.every((word, offset) => words[index + offset] === word));
  if (!contains) return false;
  // 中文被误认成拉丁字母时，整行分数不能证明未覆盖的部分确为英文。
  return original.words?.length
    ? original.words.every(word => typeof word.confidence === 'number' && word.confidence >= 85)
    : typeof original.confidence === 'number' && original.confidence >= 85;
}

/** 彩字图排除了中文和圈线，优先保留其逐行布局，再补回未覆盖的黑字。 */
function mergePreferredInkLines(original: readonly OcrTextLine[], enhanced: readonly OcrTextLine[]): OcrTextLine[] {
  const anchors = enhanced.map(line => ({ ...line }));
  const merged = [...anchors];
  for (const previous of original) {
    const matches = enhanced.map((line, index) => ({ line, index })).filter(({ line }) => {
      if (matchScore(previous.bbox, line.bbox) >= 0) return true;
      // 原图可能只读出被圈住单词的中间残片，完整彩字包住残片时仍应替换。
      if (isContainedFragment(line.bbox, previous.bbox)) return true;
      // 右半句可能与原句起点相隔较远，只在文本也证明它是原句片段时放宽位置匹配。
      return isContainedFragment(previous.bbox, line.bbox) && trustedMixedPhrase(previous, line);
    });
    if (!matches.length) {
      insertByPosition(merged, { ...previous });
      continue;
    }
    // 原图的大框可能错误吞并相邻多行，不能让一次原图匹配删除另一条增强结果。
    if (matches.length === 1 && trustedMixedPhrase(previous, matches[0]!.line)) {
      const index = merged.indexOf(anchors[matches[0]!.index]!);
      if (index >= 0) merged[index] = { ...previous };
    }
  }
  return merged;
}

/**
 * 用彩色墨迹的高置信结果增强原图英文行，保持同位置替换和未覆盖黑色英文。
 * 原图与增强图坐标必须一致；新增行沿用提取门槛，替换已有行则要求更高置信度。
 */
export function mergeEnhancedEnglishOcrLines(
  original: readonly OcrTextLine[], enhanced: readonly OcrTextLine[], options: { preferEnhanced?: boolean } = {}
): OcrTextLine[] {
  // 同一蓝字通道内仍按置信度选图；跨原图与增强图时不比较不可比的分数。
  if (options.preferEnhanced && enhanced.length) return mergePreferredInkLines(original, enhanced);
  const merged = original.map(line => ({ ...line }));
  const used = new Set<number>();
  const additions: OcrTextLine[] = [];
  for (const line of enhanced) {
    let bestIndex = -1;
    let bestScore = -1;
    for (let index = 0; index < original.length; index += 1) {
      if (used.has(index)) continue;
      const score = matchScore(original[index]!.bbox, line.bbox);
      if (score > bestScore) { bestIndex = index; bestScore = score; }
    }
    const enhancedConfidence = line.confidence;
    // 缺失置信度不能被当成高质量识别；增强结果也不得凭低分覆盖原图。
    const usable = typeof enhancedConfidence === 'number' && Number.isFinite(enhancedConfidence) && enhancedConfidence >= 65;
    if (bestIndex >= 0) {
      used.add(bestIndex);
      const previous = original[bestIndex]!;
      const originalConfidence = previous.confidence ?? 80;
      // 中文释义偶尔会被识别为低置信拉丁乱码，不能用这些词扩大必须覆盖的英文范围。
      const reliableWords = previous.words?.filter(word => typeof word.confidence === 'number' && word.confidence >= 65);
      const originalSpan = reliableWords?.length ? unionBoxes(reliableWords) : previous.bbox;
      const coveredWidth = Math.max(0, Math.min(originalSpan.x1, line.bbox.x1) - Math.max(originalSpan.x0, line.bbox.x0));
      const coversOriginal = coveredWidth >= (originalSpan.x1 - originalSpan.x0) * 0.7;
      // 只识别到短语的彩色半段时仍算位置匹配，但不能覆盖或追加成重复残句。
      if (usable && enhancedConfidence > originalConfidence && coversOriginal) merged[bestIndex] = { ...line };
    } else if (typeof enhancedConfidence === 'number' && Number.isFinite(enhancedConfidence) && enhancedConfidence >= 45 && validBox(line.bbox)) {
      // 图片原来漏掉的低置信词仍保留供用户核对，避免如手写圈线中的 dog 被静默丢弃。
      if (!original.some(previous => isContainedFragment(previous.bbox, line.bbox))) additions.push({ ...line });
    }
  }
  for (const line of additions) insertByPosition(merged, line);
  return merged;
}
