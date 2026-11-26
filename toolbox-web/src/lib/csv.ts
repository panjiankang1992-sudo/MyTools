/**
 * 支持引号、逗号与换行的分隔文本解析。
 * 用户可粘贴 TSV 或标准 CSV，解析失败时给出明确的行号。
 */

/** 解析结果。 */
export interface ParsedTable {
  rows: string[][];
  /** 出错行的从 1 开始的行号，解析成功时为 null。 */
  errorLine: number | null;
}

/** 判断文本是否更像用竖线分隔的简易格式。 */
export function detectDelimiter(value: string): '\t' | ',' | '|' {
  const sample = value.split(/\r?\n/u).slice(0, 5).join('\n');
  if (sample.includes('\t')) return '\t';
  if (sample.includes(',')) return ',';
  return '|';
}

/**
 * 解析带引号的分隔文本。
 * 双引号内的分隔符与换行按字面处理，两个连续双引号表示一个双引号字符。
 */
export function parseTable(value: string, delimiter: '\t' | ',' | '|'): ParsedTable {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  let line = 1;
  let errorLine: number | null = null;
  const text = value.replace(/\r\n?/gu, '\n');
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index]!;
    if (quoted) {
      if (character === '"') {
        if (text[index + 1] === '"') {
          field += '"';
          index += 1;
        } else {
          quoted = false;
        }
      } else {
        if (character === '\n') line += 1;
        field += character;
      }
      continue;
    }
    if (character === '"' && field === '') {
      quoted = true;
    } else if (character === delimiter) {
      row.push(field);
      field = '';
    } else if (character === '\n') {
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
      line += 1;
    } else {
      field += character;
    }
  }
  if (quoted && errorLine === null) errorLine = line;
  row.push(field);
  rows.push(row);
  const cleaned = rows
    .map(cells => cells.map(cell => cell.trim()))
    .filter(cells => cells.some(cell => cell.length > 0));
  return { rows: cleaned, errorLine };
}

/** 解析两列词条文本，返回规范化后的英文与中文/拼音对。 */
export function parseEntryRows(value: string): { pairs: Array<[string, string]>; invalidLine: number | null; unterminatedQuote: boolean } {
  const { rows, errorLine } = parseTable(value, detectDelimiter(value));
  const pairs: Array<[string, string]> = [];
  let invalidLine: number | null = null;
  rows.forEach((cells, index) => {
    if (cells.length !== 2 || !cells[0] || !cells[1]) {
      if (invalidLine === null) invalidLine = index + 1;
      return;
    }
    pairs.push([cells[0]!.normalize('NFC'), cells[1]!.normalize('NFC')]);
  });
  return { pairs, invalidLine, unterminatedQuote: errorLine !== null };
}
