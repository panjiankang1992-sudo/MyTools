interface JsonPathToken {
  kind: string;
  key: string;
  path: string[];
  index: number;
  sliceEnd: number;
  sliceStep: number;
  sliceStartSet: boolean;
  sliceEndSet: boolean;
  operator: string;
  valueKind: string;
  valueText: string;
  conditions: JsonPathCondition[];
  filterRoot: JsonPathFilterNode;
  selectors: JsonPathSelector[];
}

interface JsonPathSelector {
  kind: string;
  key: string;
  index: number;
}

interface JsonPathCondition {
  path: JsonPathFilterPathSegment[];
  operator: string;
  valueKind: string;
  valueText: string;
  regexTokens: string[];
  regexIgnoreCase: boolean;
  regexWork: number;
  pathWork: number;
}

interface JsonPathFilterPathSegment {
  kind: string;
  key: string;
  index: number;
  sliceEnd: number;
  sliceStep: number;
  sliceStartSet: boolean;
  sliceEndSet: boolean;
}

interface JsonPathComparison {
  index: number;
  operator: string;
}

interface JsonPathFilterNode {
  kind: string;
  condition: JsonPathCondition;
  children: JsonPathFilterNode[];
}

interface JsonPathFilterBudget {
  conditions: number;
}

interface JsonPathRecursiveEntry {
  value: Object;
  depth: number;
}

/**
 * 求值无需脚本执行的有界JSONPath子集。
 */
export class DeclarativeJsonPath {
  private static readonly MAX_TOKENS = 32;
  private static readonly MAX_RESULTS = 10000;
  private static readonly MAX_FILTER_INPUTS = 20000;
  private static readonly MAX_RECURSIVE_INPUTS = 20000;
  private static readonly MAX_RECURSIVE_DEPTH = 16;

  /**
   * 支持根节点、点属性、引号属性、数组下标、显式通配符和有界标量过滤。
   */
  evaluate(root: Object, rule: string): Object | undefined {
    const tokens = this.parse(rule);
    let values: Object[] = [root];
    for (let tokenIndex = 0; tokenIndex < tokens.length; tokenIndex++) {
      const token = tokens[tokenIndex];
      const next: Object[] = [];
      for (let valueIndex = 0; valueIndex < values.length; valueIndex++) {
        this.applyToken(values[valueIndex], token, next);
        if (next.length > DeclarativeJsonPath.MAX_RESULTS) throw new Error('JSONPath结果超过10000项');
      }
      values = next;
      if (values.length === 0) return undefined;
    }
    if (values.length === 1) return values[0];
    return values;
  }

  private parse(rule: string): JsonPathToken[] {
    const text = rule.trim();
    if (text.length === 0 || text === '$' || text === '@') return [];
    if (text.length > 4096) throw new Error('JSONPath长度超过4096字符');
    const tokens: JsonPathToken[] = [];
    let index = text[0] === '$' || text[0] === '@' ? 1 : 0;
    while (index < text.length) {
      if (tokens.length >= DeclarativeJsonPath.MAX_TOKENS) throw new Error('JSONPath层级超过32层');
      if (text.substring(index, index + 2) === '..') {
        index += 2;
        if (index >= text.length) throw new Error('JSONPath递归下降目标为空');
        if (text[index] === '*') {
          tokens.push(this.token('recursive_wildcard'));
          index++;
          continue;
        }
        const recursiveProperty = /^[A-Za-z_][\w:-]*/.exec(text.substring(index));
        if (recursiveProperty === null) throw new Error('JSONPath递归下降仅支持属性或通配符');
        const recursiveToken = this.token('recursive_property');
        recursiveToken.key = recursiveProperty[0];
        tokens.push(recursiveToken);
        index += recursiveProperty[0].length;
        continue;
      }
      if (text[index] === '.') {
        index++;
        if (index >= text.length) throw new Error('JSONPath属性为空');
        if (text[index] === '*') {
          tokens.push(this.token('wildcard'));
          index++;
          continue;
        }
      }
      if (text[index] === '[') {
        const closing = this.findBracketEnd(text, index);
        if (closing < 0) throw new Error('JSONPath方括号未闭合');
        const content = text.substring(index + 1, closing).trim();
        if (content === '*') {
          tokens.push(this.token('wildcard'));
        } else if (/^-?\d+$/.test(content)) {
          const indexToken = this.token('index');
          indexToken.index = Number.parseInt(content, 10);
          this.validateArrayIndex(indexToken.index, 'JSONPath数组下标');
          tokens.push(indexToken);
        } else if (/^-?\d*:-?\d*(?::-?\d+)?$/.test(content)) {
          tokens.push(this.parseSlice(content));
        } else if (content.startsWith('?(')) {
          tokens.push(this.parseFilter(content));
        } else {
          const quoted = /^(?:'([^']+)'|"([^"]+)")$/.exec(content);
          if (quoted !== null) {
            const propertyToken = this.token('property');
            propertyToken.key = quoted[1] ?? quoted[2];
            tokens.push(propertyToken);
          } else {
            const parts = this.splitSelectorUnion(content);
            if (parts.length < 2) {
              throw new Error('JSONPath方括号仅支持属性、有界下标、通配符、切片或联合选择器');
            }
            const unionToken = this.token('union');
            parts.forEach((part: string) => unionToken.selectors.push(this.parseSelector(part)));
            tokens.push(unionToken);
          }
        }
        index = closing + 1;
        continue;
      }
      const property = /^[A-Za-z_][\w:-]*/.exec(text.substring(index));
      if (property === null) throw new Error('JSONPath包含不支持的语法');
      const propertyToken = this.token('property');
      propertyToken.key = property[0];
      tokens.push(propertyToken);
      index += property[0].length;
    }
    return tokens;
  }

  private applyToken(value: Object, token: JsonPathToken, output: Object[]): void {
    if (token.kind === 'recursive_property' || token.kind === 'recursive_wildcard') {
      this.applyRecursiveToken(value, token, output);
      return;
    }
    if (token.kind === 'filter') {
      if (!Array.isArray(value)) return;
      if (value.length > DeclarativeJsonPath.MAX_FILTER_INPUTS) throw new Error('JSONPath过滤候选超过20000项');
      value.forEach((item: Object) => {
        if (this.matchesFilter(item, token)) output.push(item);
      });
      return;
    }
    if (token.kind === 'index') {
      if (!Array.isArray(value)) return;
      const resolved = this.resolveArrayIndex(token.index, value.length);
      if (resolved < 0 || resolved >= value.length) return;
      output.push(value[resolved] as Object);
      return;
    }
    if (token.kind === 'slice') {
      if (!Array.isArray(value)) return;
      this.applySlice(value, token, output);
      return;
    }
    if (token.kind === 'union') {
      token.selectors.forEach((selector: JsonPathSelector) => {
        if (selector.kind === 'index') {
          if (Array.isArray(value)) {
            const resolved = this.resolveArrayIndex(selector.index, value.length);
            if (resolved >= 0 && resolved < value.length) output.push(value[resolved] as Object);
          }
          return;
        }
        if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
          const child = (value as Record<string, Object>)[selector.key];
          if (child !== undefined && child !== null) output.push(child);
        }
      });
      return;
    }
    if (token.kind === 'wildcard') {
      if (Array.isArray(value)) {
        value.forEach((item: Object) => output.push(item));
      } else if (value !== null && typeof value === 'object') {
        Object.keys(value).forEach((key: string) => output.push((value as Record<string, Object>)[key]));
      }
      return;
    }
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return;
    const child = (value as Record<string, Object>)[token.key];
    if (child !== undefined && child !== null) output.push(child);
  }

  private applyRecursiveToken(value: Object, token: JsonPathToken, output: Object[]): void {
    const stack: JsonPathRecursiveEntry[] = [{ value: value, depth: 0 }];
    let visited = 0;
    while (stack.length > 0) {
      const entry = stack.pop();
      if (entry === undefined) break;
      const current = entry.value;
      if (current === null || typeof current !== 'object') continue;
      visited++;
      if (visited > DeclarativeJsonPath.MAX_RECURSIVE_INPUTS) {
        throw new Error('JSONPath递归下降候选超过20000项');
      }
      const children: Object[] = [];
      if (Array.isArray(current)) {
        current.forEach((child: Object) => {
          if (token.kind === 'recursive_wildcard') output.push(child);
          children.push(child);
        });
      } else {
        const record = current as Record<string, Object>;
        Object.keys(record).forEach((key: string) => {
          const child = record[key];
          if (token.kind === 'recursive_wildcard' || key === token.key) output.push(child);
          children.push(child);
        });
      }
      if (children.length > 0 && entry.depth >= DeclarativeJsonPath.MAX_RECURSIVE_DEPTH) {
        throw new Error('JSONPath递归下降深度超过16层');
      }
      for (let index = children.length - 1; index >= 0; index--) {
        stack.push({ value: children[index], depth: entry.depth + 1 });
      }
    }
  }

  private token(kind: string): JsonPathToken {
    return {
      kind: kind, key: '', path: [], index: 0, sliceEnd: 0, sliceStep: 1,
      sliceStartSet: false, sliceEndSet: false, operator: '', valueKind: '', valueText: '', conditions: [],
      filterRoot: this.filterNode('condition'), selectors: []
    };
  }

  private parseSlice(value: string): JsonPathToken {
    const parts = value.split(':');
    if (parts.length < 2 || parts.length > 3) throw new Error('JSONPath切片格式无效');
    const startSet = parts[0].length > 0;
    const endSet = parts[1].length > 0;
    const start = startSet ? Number.parseInt(parts[0], 10) : 0;
    const end = endSet ? Number.parseInt(parts[1], 10) : 0;
    const step = parts.length === 3 ? Number.parseInt(parts[2], 10) : 1;
    if (!Number.isSafeInteger(start) || Math.abs(start) > 1000000 ||
      !Number.isSafeInteger(end) || Math.abs(end) > 1000000 ||
      !Number.isSafeInteger(step) || step === 0 || Math.abs(step) > 1000000) {
      throw new Error('JSONPath切片边界超过1000000');
    }
    if (startSet && endSet && step > 0 && start >= 0 && end >= 0 && end - start > DeclarativeJsonPath.MAX_RESULTS) {
      throw new Error('JSONPath切片超过10000项');
    }
    const token = this.token('slice');
    token.index = start;
    token.sliceEnd = end;
    token.sliceStep = step;
    token.sliceStartSet = startSet;
    token.sliceEndSet = endSet;
    return token;
  }

  private applySlice(value: Object[], token: JsonPathToken, output: Object[]): void {
    const length = value.length;
    const step = token.sliceStep;
    let start = token.sliceStartSet ? this.normalizeSliceBoundary(token.index, length, step < 0) :
      (step > 0 ? 0 : length - 1);
    const end = token.sliceEndSet ? this.normalizeSliceBoundary(token.sliceEnd, length, step < 0) :
      (step > 0 ? length : -1);
    let count = 0;
    if (step > 0) {
      for (let index = start; index < end; index += step) {
        count++;
        if (count > DeclarativeJsonPath.MAX_RESULTS) throw new Error('JSONPath切片超过10000项');
        output.push(value[index] as Object);
      }
      return;
    }
    for (let index = start; index > end; index += step) {
      count++;
      if (count > DeclarativeJsonPath.MAX_RESULTS) throw new Error('JSONPath切片超过10000项');
      output.push(value[index] as Object);
    }
  }

  private normalizeSliceBoundary(value: number, length: number, reverse: boolean): number {
    const resolved = value < 0 ? length + value : value;
    if (reverse) return Math.max(-1, Math.min(resolved, length - 1));
    return Math.max(0, Math.min(resolved, length));
  }

  private resolveArrayIndex(value: number, length: number): number {
    return value < 0 ? length + value : value;
  }

  private validateArrayIndex(value: number, label: string): void {
    if (!Number.isSafeInteger(value) || Math.abs(value) > 1000000) {
      throw new Error(`${label}绝对值超过1000000`);
    }
  }

  private splitSelectorUnion(value: string): string[] {
    const parts: string[] = [];
    let quote = '';
    let start = 0;
    for (let index = 0; index < value.length; index++) {
      const char = value[index];
      if (quote.length > 0 && char === '\\') {
        index++;
        continue;
      }
      if ((char === '"' || char === "'") && (quote.length === 0 || quote === char)) {
        quote = quote.length === 0 ? char : '';
        continue;
      }
      if (quote.length === 0 && char === ',') {
        const part = value.substring(start, index).trim();
        if (part.length === 0) throw new Error('JSONPath联合选择器包含空项');
        parts.push(part);
        if (parts.length > 16) throw new Error('JSONPath联合选择器超过16项');
        start = index + 1;
      }
    }
    if (quote.length > 0) throw new Error('JSONPath联合选择器引号未闭合');
    const last = value.substring(start).trim();
    if (last.length === 0 && parts.length > 0) throw new Error('JSONPath联合选择器包含空项');
    if (last.length > 0) {
      parts.push(last);
      if (parts.length > 16) throw new Error('JSONPath联合选择器超过16项');
    }
    return parts;
  }

  private parseSelector(value: string): JsonPathSelector {
    if (/^-?\d+$/.test(value)) {
      const arrayIndex = Number.parseInt(value, 10);
      this.validateArrayIndex(arrayIndex, 'JSONPath联合数组下标');
      return { kind: 'index', key: '', index: arrayIndex };
    }
    const quoted = /^(?:'([^'\\]+)'|"([^"\\]+)")$/.exec(value);
    if (quoted === null) throw new Error('JSONPath联合选择器仅支持引号属性或有界下标');
    const key = quoted[1] ?? quoted[2];
    if (key.length > 128 || /[\u0000-\u001F\u007F]/.test(key)) {
      throw new Error('JSONPath联合属性名无效或超过128字符');
    }
    return { kind: 'property', key: key, index: 0 };
  }

  private parseFilter(content: string): JsonPathToken {
    if (!content.startsWith('?(') || !content.endsWith(')')) throw new Error('JSONPath过滤表达式未闭合');
    const body = content.substring(2, content.length - 1).trim();
    if (body.length === 0) throw new Error('JSONPath过滤表达式为空');
    const token = this.token('filter');
    token.filterRoot = this.parseFilterExpression(body, 0, { conditions: 0 });
    return token;
  }

  private filterNode(kind: string): JsonPathFilterNode {
    return {
      kind: kind,
      condition: { path: [], operator: '', valueKind: '', valueText: '', regexTokens: [], regexIgnoreCase: false,
        regexWork: 0, pathWork: 0 },
      children: []
    };
  }

  private parseFilterExpression(value: string, depth: number, budget: JsonPathFilterBudget): JsonPathFilterNode {
    if (depth > 4) throw new Error('JSONPath过滤分组超过4层');
    const text = value.trim();
    if (text.length === 0) throw new Error('JSONPath过滤分支为空');
    if (text.startsWith('(') && this.findFilterParenthesisEnd(text, 0) === text.length - 1) {
      return this.parseFilterExpression(text.substring(1, text.length - 1), depth + 1, budget);
    }
    const disjunction = this.splitFilterOperator(text, '||');
    if (disjunction.length > 1) {
      const node = this.filterNode('or');
      disjunction.forEach((part: string) => node.children.push(this.parseFilterExpression(part, depth + 1, budget)));
      return node;
    }
    const conjunction = this.splitFilterOperator(text, '&&');
    if (conjunction.length > 1) {
      const node = this.filterNode('and');
      conjunction.forEach((part: string) => node.children.push(this.parseFilterExpression(part, depth + 1, budget)));
      return node;
    }
    if (text[0] === '!' && text.substring(0, 2) !== '!=') {
      const node = this.filterNode('not');
      node.children.push(this.parseFilterExpression(text.substring(1), depth + 1, budget));
      return node;
    }
    budget.conditions++;
    if (budget.conditions > 8) throw new Error('JSONPath过滤条件超过8个');
    const node = this.filterNode('condition');
    node.condition = this.parseFilterCondition(text);
    return node;
  }

  private parseFilterCondition(body: string): JsonPathCondition {
    const comparison = this.findFilterComparison(body);
    const pathText = comparison.index < 0 ? body : body.substring(0, comparison.index).trim();
    const condition: JsonPathCondition = {
      path: this.parseFilterPath(pathText),
      operator: comparison.index < 0 ? 'exists' : comparison.operator,
      valueKind: '',
      valueText: '',
      regexTokens: [],
      regexIgnoreCase: false,
      regexWork: 0,
      pathWork: 0
    };
    if (condition.operator === 'exists') return condition;
    const literal = body.substring(comparison.index + comparison.operator.length).trim();
    if (condition.operator === '=~') {
      this.parseSafeRegex(literal, condition);
      return condition;
    }
    const quoted = /^(?:'([^'\\]*)'|"([^"\\]*)")$/.exec(literal);
    if (quoted !== null) {
      if (this.isRangeOperator(condition.operator)) throw new Error('JSONPath范围过滤仅支持有限数字');
      condition.valueKind = 'string';
      condition.valueText = quoted[1] ?? quoted[2];
      return condition;
    }
    if (literal === 'true' || literal === 'false') {
      if (this.isRangeOperator(condition.operator)) throw new Error('JSONPath范围过滤仅支持有限数字');
      condition.valueKind = 'boolean';
      condition.valueText = literal;
      return condition;
    }
    if (literal === 'null') {
      if (this.isRangeOperator(condition.operator)) throw new Error('JSONPath范围过滤仅支持有限数字');
      condition.valueKind = 'null';
      return condition;
    }
    if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(literal)) {
      condition.valueKind = 'number';
      condition.valueText = literal;
      return condition;
    }
    if (this.isRangeOperator(condition.operator)) {
      throw new Error('JSONPath范围过滤仅支持有限数字');
    }
    throw new Error('JSONPath过滤值仅支持无转义字符串、有限数字、布尔值或null');
  }

  private splitFilterOperator(value: string, operator: string): string[] {
    const values: string[] = [];
    let quote = '';
    let brackets = 0;
    let parentheses = 0;
    let start = 0;
    for (let index = 0; index < value.length; index++) {
      const char = value[index];
      if (quote.length > 0 && char === '\\') {
        index++;
        continue;
      }
      if ((char === '"' || char === "'") && (quote.length === 0 || quote === char)) {
        quote = quote.length === 0 ? char : '';
        continue;
      }
      if (quote.length > 0) continue;
      if (char === '[') brackets++;
      else if (char === ']') brackets--;
      else if (brackets === 0 && char === '(') parentheses++;
      else if (brackets === 0 && char === ')') parentheses--;
      if (brackets < 0 || parentheses < 0) throw new Error('JSONPath过滤括号不匹配');
      if (brackets === 0 && parentheses === 0 && value.substring(index, index + 2) === operator) {
        const expression = value.substring(start, index).trim();
        if (expression.length === 0) throw new Error('JSONPath过滤分支为空');
        values.push(expression);
        index++;
        start = index + 1;
      }
    }
    if (quote.length > 0 || brackets !== 0 || parentheses !== 0) throw new Error('JSONPath过滤表达式未闭合');
    const last = value.substring(start).trim();
    if (last.length === 0) throw new Error('JSONPath过滤分支为空');
    values.push(last);
    return values;
  }

  private findFilterParenthesisEnd(value: string, opening: number): number {
    let quote = '';
    let brackets = 0;
    let parentheses = 0;
    for (let index = opening; index < value.length; index++) {
      const char = value[index];
      if (quote.length > 0 && char === '\\') {
        index++;
        continue;
      }
      if ((char === '"' || char === "'") && (quote.length === 0 || quote === char)) {
        quote = quote.length === 0 ? char : '';
        continue;
      }
      if (quote.length > 0) continue;
      if (char === '[') brackets++;
      else if (char === ']') brackets--;
      else if (brackets === 0 && char === '(') parentheses++;
      else if (brackets === 0 && char === ')') {
        parentheses--;
        if (parentheses === 0) return index;
      }
      if (brackets < 0 || parentheses < 0) throw new Error('JSONPath过滤括号不匹配');
    }
    return -1;
  }

  private parseFilterPath(value: string): JsonPathFilterPathSegment[] {
    if (!value.startsWith('@')) throw new Error('JSONPath过滤路径必须从当前节点开始');
    const path: JsonPathFilterPathSegment[] = [];
    let index = 1;
    while (index < value.length) {
      if (path.length >= 4) throw new Error('JSONPath过滤属性路径超过4层');
      if (value.substring(index, index + 9) === '.length()') {
        if (index + 9 !== value.length) throw new Error('JSONPath过滤length函数必须位于路径末尾');
        path.push({ kind: 'length', key: '', index: 0, sliceEnd: 0, sliceStep: 1,
          sliceStartSet: false, sliceEndSet: false });
        index += 9;
        continue;
      }
      let key = '';
      if (value[index] === '.') {
        const property = /^[A-Za-z_][\w:-]*/.exec(value.substring(index + 1));
        if (property === null) throw new Error('JSONPath过滤点属性无效');
        key = property[0];
        index += key.length + 1;
      } else if (value[index] === '[') {
        const closing = this.findBracketEnd(value, index);
        if (closing < 0) throw new Error('JSONPath过滤属性方括号未闭合');
        const content = value.substring(index + 1, closing).trim();
        if (/^-?\d+$/.test(content)) {
          const arrayIndex = Number.parseInt(content, 10);
          this.validateArrayIndex(arrayIndex, 'JSONPath过滤数组下标');
          path.push({ kind: 'index', key: '', index: arrayIndex, sliceEnd: 0, sliceStep: 1,
            sliceStartSet: false, sliceEndSet: false });
          index = closing + 1;
          continue;
        }
        if (/^-?\d*:-?\d*(?::-?\d+)?$/.test(content)) {
          const token = this.parseSlice(content);
          path.push({ kind: 'slice', key: '', index: token.index, sliceEnd: token.sliceEnd,
            sliceStep: token.sliceStep, sliceStartSet: token.sliceStartSet, sliceEndSet: token.sliceEndSet });
          index = closing + 1;
          continue;
        }
        const quoted = /^(?:'([^'\\]+)'|"([^"\\]+)")$/.exec(content);
        if (quoted === null) throw new Error('JSONPath过滤方括号仅支持引号属性、有界下标或切片');
        key = quoted[1] ?? quoted[2];
        index = closing + 1;
      } else {
        throw new Error('JSONPath过滤路径包含不支持的语法');
      }
      if (key.length === 0 || key.length > 128 || /[\u0000-\u001F\u007F]/.test(key)) {
        throw new Error('JSONPath过滤属性名无效或超过128字符');
      }
      path.push({ kind: 'property', key: key, index: 0, sliceEnd: 0, sliceStep: 1,
        sliceStartSet: false, sliceEndSet: false });
    }
    if (path.length === 0) throw new Error('JSONPath过滤路径不能为空');
    return path;
  }

  private findFilterComparison(value: string): JsonPathComparison {
    let quote = '';
    let brackets = 0;
    for (let index = 0; index < value.length; index++) {
      const char = value[index];
      if (quote.length > 0 && char === '\\') {
        index++;
        continue;
      }
      if ((char === '"' || char === "'") && (quote.length === 0 || quote === char)) {
        quote = quote.length === 0 ? char : '';
        continue;
      }
      if (quote.length > 0) continue;
      if (char === '[') brackets++;
      else if (char === ']') brackets--;
      if (brackets < 0) throw new Error('JSONPath过滤方括号不匹配');
      if (brackets === 0) {
        const operator = value.substring(index, index + 2);
        if (operator === '==' || operator === '!=' || operator === '>=' || operator === '<=' || operator === '=~') {
          return { index: index, operator: operator };
        }
        if (char === '>' || char === '<') return { index: index, operator: char };
      }
    }
    if (quote.length > 0 || brackets !== 0) throw new Error('JSONPath过滤属性路径未闭合');
    return { index: -1, operator: '' };
  }

  private isRangeOperator(operator: string): boolean {
    return operator === '>' || operator === '>=' || operator === '<' || operator === '<=';
  }

  private parseSafeRegex(value: string, condition: JsonPathCondition): void {
    if (!value.startsWith('/')) throw new Error('JSONPath正则必须使用斜杠包围');
    let closing = -1;
    for (let index = value.length - 1; index > 0; index--) {
      if (value[index] !== '/') continue;
      let escapes = 0;
      for (let cursor = index - 1; cursor >= 0 && value[cursor] === '\\'; cursor--) escapes++;
      if (escapes % 2 === 0) {
        closing = index;
        break;
      }
    }
    if (closing < 1) throw new Error('JSONPath正则未闭合');
    const flags = value.substring(closing + 1);
    if (flags !== '' && flags !== 'i') throw new Error('JSONPath正则仅支持i标志');
    let pattern = value.substring(1, closing);
    if (pattern.length === 0 || pattern.length > 128 || /[\u0000-\u001F\u007F]/.test(pattern)) {
      throw new Error('JSONPath正则为空或超过128字符');
    }
    let anchoredStart = false;
    let anchoredEnd = false;
    if (pattern[0] === '^') {
      anchoredStart = true;
      pattern = pattern.substring(1);
    }
    if (pattern.length > 0 && pattern[pattern.length - 1] === '$' &&
      (pattern.length < 2 || pattern[pattern.length - 2] !== '\\')) {
      anchoredEnd = true;
      pattern = pattern.substring(0, pattern.length - 1);
    }
    const tokens: string[] = [];
    for (let index = 0; index < pattern.length; index++) {
      const char = pattern[index];
      if (char === '\\') {
        if (index + 1 >= pattern.length || !'.*^$/\\'.includes(pattern[index + 1])) {
          throw new Error('JSONPath正则转义不在安全子集内');
        }
        tokens.push(`literal:${pattern[index + 1]}`);
        index++;
        continue;
      }
      if (char === '.' && index + 1 < pattern.length && pattern[index + 1] === '*') {
        tokens.push('many');
        index++;
        continue;
      }
      if (char === '.') {
        tokens.push('any');
        continue;
      }
      if ('*+?()[]{}|^$'.includes(char)) throw new Error('JSONPath正则包含不安全或未支持的语法');
      tokens.push(`literal:${char}`);
    }
    if (!anchoredStart) tokens.unshift('many');
    if (!anchoredEnd) tokens.push('many');
    condition.valueKind = 'regex';
    condition.regexTokens = tokens;
    condition.regexIgnoreCase = flags === 'i';
  }

  private matchesSafeRegex(value: string, condition: JsonPathCondition): boolean {
    if (value.length > 4096) return false;
    const work = (value.length + 1) * (condition.regexTokens.length + 1);
    condition.regexWork += work;
    if (condition.regexWork > 2000000) throw new Error('JSONPath正则匹配工作量超过2000000');
    const text = condition.regexIgnoreCase ? value.toLocaleLowerCase() : value;
    let previous: boolean[] = new Array(text.length + 1).fill(false);
    previous[0] = true;
    for (let tokenIndex = 0; tokenIndex < condition.regexTokens.length; tokenIndex++) {
      const token = condition.regexTokens[tokenIndex];
      const current: boolean[] = new Array(text.length + 1).fill(false);
      if (token === 'many') {
        current[0] = previous[0];
        for (let index = 1; index <= text.length; index++) current[index] = previous[index] || current[index - 1];
      } else {
        const literal = token.startsWith('literal:') ? token.substring(8) : '';
        const expected = condition.regexIgnoreCase ? literal.toLocaleLowerCase() : literal;
        for (let index = 1; index <= text.length; index++) {
          current[index] = previous[index - 1] && (token === 'any' || text[index - 1] === expected);
        }
      }
      previous = current;
    }
    return previous[text.length];
  }

  private findBracketEnd(text: string, opening: number): number {
    let quote = '';
    let nested = 0;
    for (let index = opening + 1; index < text.length; index++) {
      const char = text[index];
      if (quote.length > 0 && char === '\\') {
        index++;
        continue;
      }
      if ((char === '"' || char === "'") && (quote.length === 0 || quote === char)) {
        quote = quote.length === 0 ? char : '';
        continue;
      }
      if (quote.length > 0) continue;
      if (char === '[') nested++;
      else if (char === ']') {
        if (nested === 0) return index;
        nested--;
      }
    }
    return -1;
  }

  private matchesFilter(value: Object, token: JsonPathToken): boolean {
    return this.matchesFilterNode(value, token.filterRoot);
  }

  private matchesFilterNode(value: Object, node: JsonPathFilterNode): boolean {
    if (node.kind === 'condition') return this.matchesCondition(value, node.condition);
    if (node.kind === 'and') {
      for (let index = 0; index < node.children.length; index++) {
        if (!this.matchesFilterNode(value, node.children[index])) return false;
      }
      return node.children.length > 0;
    }
    if (node.kind === 'or') {
      for (let index = 0; index < node.children.length; index++) {
        if (this.matchesFilterNode(value, node.children[index])) return true;
      }
    }
    if (node.kind === 'not' && node.children.length === 1) {
      return !this.matchesFilterNode(value, node.children[0]);
    }
    return false;
  }

  private matchesCondition(value: Object, condition: JsonPathCondition): boolean {
    let current: Object[] = [value];
    for (let index = 0; index < condition.path.length; index++) {
      const segment = condition.path[index];
      const next: Object[] = [];
      for (let candidateIndex = 0; candidateIndex < current.length; candidateIndex++) {
        condition.pathWork++;
        if (condition.pathWork > 200000) throw new Error('JSONPath过滤路径工作量超过200000');
        const candidate = current[candidateIndex];
        const previousLength = next.length;
        if (segment.kind === 'index') {
          if (!Array.isArray(candidate)) continue;
          const resolved = this.resolveArrayIndex(segment.index, candidate.length);
          if (resolved >= 0 && resolved < candidate.length) next.push(candidate[resolved] as Object);
        } else if (segment.kind === 'slice') {
          if (!Array.isArray(candidate)) continue;
          const token = this.token('slice');
          token.index = segment.index;
          token.sliceEnd = segment.sliceEnd;
          token.sliceStep = segment.sliceStep;
          token.sliceStartSet = segment.sliceStartSet;
          token.sliceEndSet = segment.sliceEndSet;
          this.applySlice(candidate, token, next);
        } else if (segment.kind === 'length') {
          if (Array.isArray(candidate) || typeof candidate === 'string') next.push(candidate.length);
        } else if (candidate !== null && typeof candidate === 'object' && !Array.isArray(candidate)) {
          const childValue = (candidate as Record<string, Object>)[segment.key];
          if (childValue !== undefined) next.push(childValue);
        }
        if (next.length > DeclarativeJsonPath.MAX_RESULTS) {
          throw new Error('JSONPath过滤路径投影超过10000项');
        }
        condition.pathWork += next.length - previousLength;
        if (condition.pathWork > 200000) throw new Error('JSONPath过滤路径工作量超过200000');
      }
      current = next;
      if (current.length === 0) return false;
    }
    if (condition.operator === 'exists') {
      return current.some((child: Object) => child !== undefined && child !== null);
    }
    for (let index = 0; index < current.length; index++) {
      condition.pathWork++;
      if (condition.pathWork > 200000) throw new Error('JSONPath过滤路径工作量超过200000');
      if (this.matchesFilterValue(current[index], condition)) return true;
    }
    return false;
  }

  private matchesFilterValue(child: Object, condition: JsonPathCondition): boolean {
    let matches = false;
    if (condition.valueKind === 'null') {
      matches = child === null;
    } else if (condition.valueKind === 'string') {
      matches = typeof child === 'string' && child === condition.valueText;
    } else if (condition.valueKind === 'boolean') {
      matches = typeof child === 'boolean' && child === (condition.valueText === 'true');
    } else if (condition.valueKind === 'number') {
      const expected = Number(condition.valueText);
      if (!Number.isFinite(expected) || typeof child !== 'number' || !Number.isFinite(child)) return false;
      if (condition.operator === '>') return child > expected;
      if (condition.operator === '>=') return child >= expected;
      if (condition.operator === '<') return child < expected;
      if (condition.operator === '<=') return child <= expected;
      matches = child === expected;
    } else if (condition.valueKind === 'regex') {
      matches = typeof child === 'string' && this.matchesSafeRegex(child, condition);
    }
    return condition.operator === '!=' ? !matches : matches;
  }
}
