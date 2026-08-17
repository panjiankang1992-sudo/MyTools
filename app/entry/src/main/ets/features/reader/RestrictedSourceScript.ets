export interface RestrictedSourceRule {
  selector: string;
  script: string;
  replacementPattern: string;
  replacementText: string;
}

interface RestrictedSourceCall {
  name: string;
  arguments: string;
  length: number;
}

interface RestrictedReplacementPattern {
  tokens: string[];
  anchoredStart: boolean;
  anchoredEnd: boolean;
}

interface RestrictedReplacementRule {
  selector: string;
  pattern: string;
  text: string;
}

/**
 * 执行书源规则中确定性、无副作用的字符串后处理子集。
 */
export class RestrictedSourceScript {
  private static readonly MAX_SCRIPT_LENGTH: number = 4096;
  private static readonly MAX_VALUE_LENGTH: number = 5 * 1024 * 1024;
  private static readonly MAX_CALLS: number = 16;
  private static readonly MAX_REPLACEMENT_PATTERN_LENGTH: number = 256;
  private static readonly MAX_REPLACEMENT_TEXT_LENGTH: number = 4096;
  private static readonly MAX_REPLACEMENT_WORK: number = 8 * 1024 * 1024;

  /**
   * 分离声明式选择器和受限脚本，并在返回前完整校验脚本语法。
   */
  parse(rule: string): RestrictedSourceRule {
    const text = rule.trim();
    if (text.length === 0) return { selector: '', script: '', replacementPattern: '', replacementText: '' };
    if (text.length > 16 * 1024 || /javascript:/i.test(text)) {
      throw new Error('书源规则包含不支持的脚本语法');
    }
    const marker = text.toLowerCase().indexOf('@js:');
    const blockStart = text.toLowerCase().indexOf('<js>');
    let selectorAndReplacement = text;
    let script = '';
    if (marker >= 0 && blockStart >= 0) throw new Error('书源规则不能混用脚本标记');
    if (marker >= 0) {
      selectorAndReplacement = text.substring(0, marker).trim();
      script = text.substring(marker + 4).trim();
    } else if (blockStart >= 0) {
      const blockEnd = text.toLowerCase().indexOf('</js>', blockStart + 4);
      if (blockEnd < 0 || text.substring(blockEnd + 5).trim().length > 0) {
        throw new Error('书源脚本块未正确闭合');
      }
      selectorAndReplacement = text.substring(0, blockStart).trim();
      script = text.substring(blockStart + 4, blockEnd).trim();
    }
    const replacement = this.parseReplacement(selectorAndReplacement);
    const selector = replacement.selector;
    if (script.length > 0) {
      if (selector.length === 0) throw new Error('受限脚本必须跟在声明式选择器之后');
      this.evaluate(script, '');
    }
    return {
      selector,
      script,
      replacementPattern: replacement.pattern,
      replacementText: replacement.text
    };
  }

  /**
   * 对已经由声明式规则选出的字符串执行受限后处理。
   */
  apply(rule: string, value: string): string {
    const parsed = this.parse(rule);
    if (value.length > RestrictedSourceScript.MAX_VALUE_LENGTH) throw new Error('书源后处理输入超过5 MB');
    let result = value;
    if (parsed.replacementPattern.length > 0) {
      result = this.replacePattern(result, parsed.replacementPattern, parsed.replacementText);
    }
    return parsed.script.length === 0 ? result : this.evaluate(parsed.script, result);
  }

  private parseReplacement(value: string): RestrictedReplacementRule {
    if (!value.includes('##')) return { selector: value.trim(), pattern: '', text: '' };
    const segments = value.split('##');
    if (segments.length !== 3) throw new Error('书源替换规则必须使用选择器##模式##替换文本');
    const selector = segments[0].trim();
    const pattern = segments[1];
    const text = segments[2];
    if (selector.length === 0 || pattern.length === 0 ||
      pattern.length > RestrictedSourceScript.MAX_REPLACEMENT_PATTERN_LENGTH ||
      text.length > RestrictedSourceScript.MAX_REPLACEMENT_TEXT_LENGTH ||
      /[\u0000-\u001F\u007F]/.test(pattern) || /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(text)) {
      throw new Error('书源替换规则为空、包含控制字符或超过配额');
    }
    if (/\$(?:\d|\{)/.test(text)) throw new Error('书源替换文本不支持捕获组引用');
    this.compileReplacementPattern(pattern);
    return { selector, pattern, text };
  }

  private compileReplacementPattern(source: string): RestrictedReplacementPattern {
    let pattern = source;
    let anchoredStart = false;
    let anchoredEnd = false;
    if (pattern.startsWith('^')) {
      anchoredStart = true;
      pattern = pattern.substring(1);
    }
    if (pattern.endsWith('$') && (pattern.length < 2 || pattern[pattern.length - 2] !== '\\')) {
      anchoredEnd = true;
      pattern = pattern.substring(0, pattern.length - 1);
    }
    const tokens: string[] = [];
    for (let index = 0; index < pattern.length; index++) {
      const character = pattern[index];
      if (character === '\\') {
        if (index + 1 >= pattern.length || !'.*^$\\'.includes(pattern[index + 1])) {
          throw new Error('书源替换模式转义不在安全子集内');
        }
        tokens.push(`literal:${pattern[++index]}`);
      } else if (character === '.' && index + 1 < pattern.length && pattern[index + 1] === '*') {
        tokens.push('many');
        index++;
      } else if (character === '.') {
        tokens.push('any');
      } else {
        if ('*+?()[]{}|^$'.includes(character)) throw new Error('书源替换模式包含不安全或未支持的语法');
        tokens.push(`literal:${character}`);
      }
    }
    if (tokens.length === 0 || tokens.every((token: string) => token === 'many')) {
      throw new Error('书源替换模式不能只匹配空字符串');
    }
    return { tokens, anchoredStart, anchoredEnd };
  }

  private replacePattern(value: string, source: string, replacement: string): string {
    const pattern = this.compileReplacementPattern(source);
    let cursor = 0;
    let output = '';
    let work = 0;
    while (cursor < value.length) {
      const startLimit = pattern.anchoredStart ? Math.min(1, value.length) : value.length;
      let matchedStart = -1;
      let matchedEnd = -1;
      for (let start = cursor; start < startLimit; start++) {
        const end = this.longestMatch(value, start, pattern, work);
        work += (value.length - start + 1) * (pattern.tokens.length + 1);
        if (work > RestrictedSourceScript.MAX_REPLACEMENT_WORK) {
          throw new Error('书源替换模式工作量超过限制');
        }
        if (end >= 0) {
          matchedStart = start;
          matchedEnd = end;
          break;
        }
      }
      if (matchedStart < 0) break;
      output += value.substring(cursor, matchedStart) + replacement;
      if (output.length > RestrictedSourceScript.MAX_VALUE_LENGTH) throw new Error('书源后处理输出超过5 MB');
      cursor = matchedEnd;
    }
    const result = output + value.substring(cursor);
    if (result.length > RestrictedSourceScript.MAX_VALUE_LENGTH) throw new Error('书源后处理输出超过5 MB');
    return result;
  }

  private longestMatch(value: string, start: number, pattern: RestrictedReplacementPattern, previousWork: number): number {
    const width = value.length - start;
    const projectedWork = previousWork + (width + 1) * (pattern.tokens.length + 1);
    if (projectedWork > RestrictedSourceScript.MAX_REPLACEMENT_WORK) {
      throw new Error('书源替换模式工作量超过限制');
    }
    let previous: boolean[] = new Array(width + 1).fill(false);
    previous[0] = true;
    for (let tokenIndex = 0; tokenIndex < pattern.tokens.length; tokenIndex++) {
      const token = pattern.tokens[tokenIndex];
      const current: boolean[] = new Array(width + 1).fill(false);
      if (token === 'many') {
        current[0] = previous[0];
        for (let index = 1; index <= width; index++) current[index] = previous[index] || current[index - 1];
      } else {
        const literal = token.startsWith('literal:') ? token.substring(8) : '';
        for (let index = 1; index <= width; index++) {
          current[index] = previous[index - 1] && (token === 'any' || value[start + index - 1] === literal);
        }
      }
      previous = current;
    }
    for (let length = width; length > 0; length--) {
      if (previous[length] && (!pattern.anchoredEnd || start + length === value.length)) return start + length;
    }
    return -1;
  }

  private evaluate(script: string, input: string): string {
    if (script.length === 0 || script.length > RestrictedSourceScript.MAX_SCRIPT_LENGTH) {
      throw new Error('书源受限脚本包含禁止语法');
    }
    let expression = script.trim();
    if (expression.startsWith('return ')) expression = expression.substring(7).trim();
    if (expression.endsWith(';')) expression = expression.substring(0, expression.length - 1).trim();
    if (!expression.startsWith('result')) throw new Error('书源受限脚本只能返回result的确定性变换');
    let cursor = 'result'.length;
    let value = input;
    let calls = 0;
    while (cursor < expression.length) {
      if (++calls > RestrictedSourceScript.MAX_CALLS) throw new Error('书源受限脚本调用次数超过16次');
      const remaining = expression.substring(cursor);
      if (remaining.startsWith('.trim()')) {
        value = value.trim();
        cursor += 7;
      } else if (remaining.startsWith('.toLowerCase()')) {
        value = value.toLowerCase();
        cursor += 14;
      } else if (remaining.startsWith('.toUpperCase()')) {
        value = value.toUpperCase();
        cursor += 14;
      } else {
        const call = this.readCall(remaining);
        if (call.name === 'substring') value = this.substring(value, call.arguments);
        else if (call.name === 'slice') value = this.slice(value, call.arguments);
        else if (call.name === 'replace') value = this.replace(value, call.arguments, false);
        else if (call.name === 'replaceAll') value = this.replace(value, call.arguments, true);
        else if (call.name === 'concat') value = this.concat(value, call.arguments);
        else throw new Error('书源受限脚本调用了未允许的方法');
        cursor += call.length;
      }
      if (value.length > RestrictedSourceScript.MAX_VALUE_LENGTH) throw new Error('书源脚本输出超过5 MB');
    }
    return value;
  }

  private readCall(value: string): RestrictedSourceCall {
    const match = value.match(/^\.([A-Za-z]+)\(/);
    if (match === null) throw new Error('书源受限脚本语法无效');
    let quoted = false;
    let escaped = false;
    for (let index = match[0].length; index < value.length; index++) {
      const character = value.charAt(index);
      if (escaped) escaped = false;
      else if (character === '\\' && quoted) escaped = true;
      else if (character === '"') quoted = !quoted;
      else if (character === ')' && !quoted) {
        return { name: match[1], arguments: value.substring(match[0].length, index), length: index + 1 };
      }
    }
    throw new Error('书源受限脚本调用未闭合');
  }

  private numericArguments(raw: string): number[] {
    if (!/^-?\d{1,7}(?:,-?\d{1,7})?$/.test(raw.replace(/\s/g, ''))) {
      throw new Error('书源受限脚本切片参数无效');
    }
    const values = raw.replace(/\s/g, '').split(',').map((value: string) => Number(value));
    if (values.some((value: number) => !Number.isSafeInteger(value) || Math.abs(value) > 1000000)) {
      throw new Error('书源受限脚本切片参数越界');
    }
    return values;
  }

  private substring(value: string, raw: string): string {
    const args = this.numericArguments(raw);
    if (args.some((item: number) => item < 0)) throw new Error('substring参数不能为负数');
    return args.length === 1 ? value.substring(args[0]) : value.substring(args[0], args[1]);
  }

  private slice(value: string, raw: string): string {
    const args = this.numericArguments(raw);
    return args.length === 1 ? value.slice(args[0]) : value.slice(args[0], args[1]);
  }

  private stringArguments(raw: string, expected: number): string[] {
    let parsed: Object;
    try {
      parsed = JSON.parse(`[${raw}]`) as Object;
    } catch (_) {
      throw new Error('书源受限脚本字符串参数无效');
    }
    if (!Array.isArray(parsed) || parsed.length !== expected ||
      parsed.some((item: Object) => typeof item !== 'string' || item.length > 4096)) {
      throw new Error('书源受限脚本字符串参数无效');
    }
    return parsed as string[];
  }

  private replace(value: string, raw: string, all: boolean): string {
    const args = this.stringArguments(raw, 2);
    if (args[0].length === 0) throw new Error('书源受限脚本不允许空替换目标');
    return all ? value.split(args[0]).join(args[1]) : value.replace(args[0], args[1]);
  }

  private concat(value: string, raw: string): string {
    return value + this.stringArguments(raw, 1)[0];
  }
}
