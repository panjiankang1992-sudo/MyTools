interface HtmlElement {
  tag: string;
  attributes: Record<string, string>;
  innerHtml: string;
  outerHtml: string;
  start: number;
}

interface HtmlSelector {
  tag: string;
  id: string;
  classNames: string[];
  attributes: HtmlAttributeFilter[];
  containsText: string;
  exactText: string;
  positionMode: string;
  positionIndex: number;
  negations: HtmlNegation[];
}

interface HtmlAttributeFilter {
  name: string;
  value: string;
  operator: string;
}

interface HtmlNegation {
  kind: string;
  value: string;
  attribute: HtmlAttributeFilter;
}

interface HtmlExtraction {
  selectors: string[];
  extractor: string;
}

interface HtmlClosingTag {
  start: number;
  end: number;
}

/**
 * 解析无需脚本执行的常见HTML书源规则子集。
 */
export class BookSourceHtmlParser {
  private static readonly MAX_RESULTS = 10000;

  /**
   * 按规则返回重复元素的原始HTML片段。
   */
  selectList(html: string, rule: string): string[] {
    const normalizedRule = this.normalizeRule(rule);
    if (normalizedRule.length === 0) return [];
    const parts = this.selectorParts(this.ruleParts(normalizedRule));
    if (parts.length === 0) return [];
    let contexts: string[] = [html];
    parts.forEach((part: string) => {
      const next: string[] = [];
      contexts.forEach((context: string) => {
        this.selectElements(context, part).forEach((element: HtmlElement) => {
          next.push(element.outerHtml);
          if (next.length > BookSourceHtmlParser.MAX_RESULTS) throw new Error('HTML规则结果超过10000项');
        });
      });
      contexts = next;
    });
    return contexts;
  }

  /**
   * 按规则读取首个元素的正文、HTML或属性。
   */
  selectText(html: string, rule: string): string {
    const values = this.selectTexts(html, rule);
    return values.length === 0 ? '' : values[0];
  }

  /**
   * 按规则读取全部匹配元素的正文、HTML或属性，最多返回10000项。
   */
  selectTexts(html: string, rule: string): string[] {
    if (rule.trim().length === 0) return [];
    const normalizedRule = this.normalizeRule(rule);
    if (normalizedRule.length === 0) return [];
    const parsed = this.parseExtraction(normalizedRule);
    if (parsed.selectors.length === 0) {
      return [parsed.extractor === 'html' ? html.trim() : this.htmlToText(html)];
    }
    let contexts: string[] = [html];
    this.selectorParts(parsed.selectors).forEach((selector: string) => {
      const next: string[] = [];
      contexts.forEach((context: string) => {
        this.selectElements(context, selector).forEach((element: HtmlElement) => {
          next.push(element.outerHtml);
          if (next.length > BookSourceHtmlParser.MAX_RESULTS) throw new Error('HTML规则结果超过10000项');
        });
      });
      contexts = next;
    });
    const values: string[] = [];
    contexts.forEach((context: string) => {
      const element = this.elementFromOuterHtml(context);
      if (element === undefined) return;
      if (parsed.extractor === 'html') values.push(element.innerHtml.trim());
      else if (parsed.extractor === 'text' || parsed.extractor.length === 0) {
        values.push(this.htmlToText(element.innerHtml));
      } else {
        values.push((element.attributes[parsed.extractor.toLowerCase()] ?? '').trim());
      }
    });
    return values;
  }

  /**
   * 将HTML正文安全转换为阅读文本。
   */
  htmlToText(value: string): string {
    return value.replace(/<script\b[\s\S]*?<\/script\s*>/gi, '').replace(/<style\b[\s\S]*?<\/style\s*>/gi, '')
      .replace(/<(br\s*\/?|\/p|\/div|\/h[1-6]|\/li|\/section)>/gi, '\n').replace(/<[^>]+>/g, '')
      .replace(/&nbsp;|&#160;/gi, ' ').replace(/&amp;/gi, '&').replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
      .replace(/&quot;/gi, '"').replace(/&#39;|&apos;/gi, "'").replace(/&#(\d+);/g, (_: string, code: string) => {
        const value = Number.parseInt(code, 10);
        return Number.isFinite(value) && value > 0 && value <= 0xFFFF ? String.fromCharCode(value) : '';
      }).replace(/\r/g, '').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  }

  /**
   * 判断规则是否属于当前可审计的CSS或XPath子集。
   */
  supportsRule(rule: string): boolean {
    const text = rule.trim();
    const normalized = /^@?xpath:/i.test(text) ? this.xpathToCss(text) : text;
    if (normalized.length === 0) return false;
    const extraction = this.parseExtraction(normalized);
    const selectors = this.selectorParts(extraction.selectors);
    return selectors.length > 0 && selectors.every((selector: string) =>
      this.splitSelectorAlternatives(selector).every((alternative: string) =>
        this.parseSelector(alternative) !== undefined));
  }

  private normalizeRule(rule: string): string {
    const text = rule.trim();
    return /^@?xpath:/i.test(text) ? this.xpathToCss(text) : text;
  }

  private xpathToCss(rule: string): string {
    let expression = rule.trim().replace(/^@?xpath:/i, '').trim();
    let extractor = '';
    const textExtraction = /\/text\(\)\s*$/.exec(expression);
    const attributeExtraction = /\/@([\w:-]+)\s*$/.exec(expression);
    if (textExtraction !== null) {
      extractor = 'text';
      expression = expression.substring(0, textExtraction.index);
    } else if (attributeExtraction !== null) {
      const attribute = attributeExtraction[1].toLowerCase();
      if (!['href', 'src', 'content', 'value', 'title', 'data-src', 'data-original', 'data-lazy-src',
        'data-url'].includes(attribute)) return '';
      extractor = attribute;
      expression = expression.substring(0, attributeExtraction.index);
    }
    if (expression.includes('|') || expression.includes('::') || expression.includes('..')) return '';
    const steps = this.splitXPathSteps(expression);
    if (steps.length === 0 || steps.length > 32) return '';
    const selectors: string[] = [];
    for (let index = 0; index < steps.length; index++) {
      const selector = this.xpathStepToCss(steps[index]);
      if (selector.length === 0) return '';
      selectors.push(selector);
    }
    return `${selectors.join(' ')}${extractor.length > 0 ? `@${extractor}` : ''}`;
  }

  private splitXPathSteps(expression: string): string[] {
    const values: string[] = [];
    let bracketDepth = 0;
    let quote = '';
    let start = 0;
    for (let index = 0; index < expression.length; index++) {
      const char = expression[index];
      if ((char === '"' || char === "'") && (quote.length === 0 || quote === char)) {
        quote = quote.length === 0 ? char : '';
      } else if (quote.length === 0) {
        if (char === '[') bracketDepth++;
        else if (char === ']') bracketDepth--;
        else if (char === '/' && bracketDepth === 0) {
          const step = expression.substring(start, index).trim();
          if (step.length > 0 && step !== '.') values.push(step);
          while (index + 1 < expression.length && expression[index + 1] === '/') index++;
          start = index + 1;
        }
      }
      if (bracketDepth < 0) return [];
    }
    if (quote.length > 0 || bracketDepth !== 0) return [];
    const last = expression.substring(start).trim();
    if (last.length > 0 && last !== '.') values.push(last);
    return values;
  }

  private xpathStepToCss(step: string): string {
    const node = /^(\*|[a-zA-Z][\w:-]*)(.*)$/.exec(step);
    if (node === null) return '';
    const baseSelector = node[1] === '*' ? '' : node[1].toLowerCase();
    let selectors: string[] = [baseSelector];
    let positionSelector = '';
    const suffix = node[2];
    let consumed = '';
    const predicate = /\[([^\[\]]+)\]/g;
    let match = predicate.exec(suffix);
    while (match !== null) {
      consumed += match[0];
      const converted = this.xpathPredicateToCss(match[1].trim());
      if (converted.length === 0) return '';
      if (converted.startsWith(':eq(') || converted === ':last') {
        if (positionSelector.length > 0 || selectors.length > 1) return '';
        positionSelector = converted;
      } else {
        const alternatives = this.splitSelectorAlternatives(converted);
        if (alternatives.length === 0) return '';
        const expanded: string[] = [];
        selectors.forEach((selector: string) => {
          alternatives.forEach((alternative: string) => {
            expanded.push(`${selector}${alternative}`);
          });
        });
        if (expanded.length > 8) return '';
        selectors = expanded;
      }
      match = predicate.exec(suffix);
    }
    if (consumed !== suffix) return '';
    return selectors.map((selector: string) => `${selector.length === 0 ? '*' : selector}${positionSelector}`).join(',');
  }

  private xpathPredicateToCss(predicate: string): string {
    const disjunction = this.splitXPathDisjunction(predicate);
    if (disjunction.length === 0) return '';
    if (disjunction.length > 1) {
      const converted: string[] = [];
      for (let index = 0; index < disjunction.length; index++) {
        const value = this.xpathPredicateToCss(disjunction[index]);
        if (value.length === 0 || value.includes(',') || value.startsWith(':eq(') || value === ':last') return '';
        converted.push(value);
      }
      return converted.join(',');
    }
    const conjunction = this.splitXPathConjunction(predicate);
    if (conjunction.length === 0) return '';
    if (conjunction.length > 1) {
      const converted: string[] = [];
      for (let index = 0; index < conjunction.length; index++) {
        const value = this.xpathPredicateToCss(conjunction[index]);
        if (value.length === 0 || value.startsWith(':eq(') || value === ':last') return '';
        converted.push(value);
      }
      return converted.join('');
    }
    if (/^\d+$/.test(predicate)) {
      const position = Number.parseInt(predicate, 10);
      return position <= 0 ? '' : `:eq(${position - 1})`;
    }
    if (/^last\(\)$/i.test(predicate)) return ':last';
    const lastOffset = /^last\(\)\s*-\s*(\d+)$/i.exec(predicate);
    if (lastOffset !== null) {
      const offset = Number.parseInt(lastOffset[1], 10);
      return offset < 0 ? '' : `:eq(${-offset - 1})`;
    }
    const positionEquals = /^position\(\)\s*=\s*(\d+)$/i.exec(predicate);
    if (positionEquals !== null) {
      const position = Number.parseInt(positionEquals[1], 10);
      return position <= 0 ? '' : `:eq(${position - 1})`;
    }
    const positionRange = /^position\(\)\s*(<=|>=|<|>)\s*(\d+)$/i.exec(predicate);
    if (positionRange !== null) {
      const position = Number.parseInt(positionRange[2], 10);
      if (!Number.isSafeInteger(position) || position > 1000000) return '';
      if (positionRange[1] === '<') return `:xlt(${position - 1})`;
      if (positionRange[1] === '<=') return `:xlt(${position})`;
      if (positionRange[1] === '>') return `:xgt(${position - 1})`;
      return `:xgt(${position - 2})`;
    }
    const exists = /^@([\w:-]+)$/.exec(predicate);
    if (exists !== null) return `[${exists[1]}]`;
    const notExists = /^not\(\s*@([\w:-]+)\s*\)$/i.exec(predicate);
    if (notExists !== null) return `:not([${notExists[1]}])`;
    const equals = /^@([\w:-]+)\s*=\s*(["'])([^"']*)\2$/.exec(predicate);
    if (equals !== null && !/[\]\["']/.test(equals[3])) return `[${equals[1]}="${equals[3]}"]`;
    const notEquals = /^not\(\s*@([\w:-]+)\s*=\s*(["'])([^"']*)\2\s*\)$/i.exec(predicate);
    if (notEquals !== null && !/[\]\["']/.test(notEquals[3])) {
      return `:not([${notEquals[1]}="${notEquals[3]}"])`;
    }
    const attributeContains = /^contains\(\s*@([\w:-]+)\s*,\s*(["'])([^"']+)\2\s*\)$/i.exec(predicate);
    if (attributeContains !== null && !/[\]\["']/.test(attributeContains[3])) {
      return `[${attributeContains[1]}*="${attributeContains[3]}"]`;
    }
    const attributeStarts = /^starts-with\(\s*@([\w:-]+)\s*,\s*(["'])([^"']+)\2\s*\)$/i.exec(predicate);
    if (attributeStarts !== null && !/[\]\["']/.test(attributeStarts[3])) {
      return `[${attributeStarts[1]}^="${attributeStarts[3]}"]`;
    }
    const attributeEnds = /^ends-with\(\s*@([\w:-]+)\s*,\s*(["'])([^"']+)\2\s*\)$/i.exec(predicate);
    if (attributeEnds !== null && !/[\]\["']/.test(attributeEnds[3])) {
      return `[${attributeEnds[1]}$="${attributeEnds[3]}"]`;
    }
    const textContains = /^contains\(\s*(?:text\(\)|\.)\s*,\s*(["'])([^"')]+)\1\s*\)$/i.exec(predicate);
    if (textContains !== null) return `:contains(${textContains[2]})`;
    const textEquals = /^(?:text\(\)|normalize-space\(\s*(?:text\(\)|\.)\s*\))\s*=\s*(["'])([^"']*)\1$/i
      .exec(predicate);
    if (textEquals !== null && !/[()]/.test(textEquals[2])) return `:textequals(${textEquals[2]})`;
    return '';
  }

  private splitXPathConjunction(predicate: string): string[] {
    return this.splitXPathLogicalOperator(predicate, 'and');
  }

  private splitXPathDisjunction(predicate: string): string[] {
    return this.splitXPathLogicalOperator(predicate, 'or');
  }

  private splitXPathLogicalOperator(predicate: string, operator: string): string[] {
    const values: string[] = [];
    let quote = '';
    let parentheses = 0;
    let start = 0;
    for (let index = 0; index < predicate.length; index++) {
      const char = predicate[index];
      if ((char === '"' || char === "'") && (quote.length === 0 || quote === char)) {
        quote = quote.length === 0 ? char : '';
        continue;
      }
      if (quote.length > 0) continue;
      if (char === '(') parentheses++;
      else if (char === ')') parentheses--;
      if (parentheses < 0) return [];
      const separatorPattern = new RegExp(`^\\s+${operator}\\s+`, 'i');
      if (parentheses === 0 && separatorPattern.test(predicate.substring(index))) {
        const separator = separatorPattern.exec(predicate.substring(index));
        if (separator === null) return [];
        const value = predicate.substring(start, index).trim();
        if (value.length === 0) return [];
        values.push(value);
        if (values.length >= 8) return [];
        index += separator[0].length - 1;
        start = index + 1;
      }
    }
    if (quote.length > 0 || parentheses !== 0) return [];
    const last = predicate.substring(start).trim();
    if (last.length === 0) return [];
    values.push(last);
    return values;
  }

  private parseExtraction(rule: string): HtmlExtraction {
    const parts = this.ruleParts(rule);
    if (parts.length === 0) return { selectors: [], extractor: 'text' };
    const last = parts[parts.length - 1].toLowerCase();
    if (last === 'text' || last === 'textnodes' || last === 'owntext' || last === 'html' || last === 'href' ||
      last === 'src' || last === 'content' || last === 'value' || last === 'title' || last === 'data-src' ||
      last === 'data-original' || last === 'data-lazy-src' || last === 'data-url') {
      parts.pop();
      return { selectors: parts, extractor: last === 'textnodes' || last === 'owntext' ? 'text' : last };
    }
    return { selectors: parts, extractor: 'text' };
  }

  private ruleParts(rule: string): string[] {
    let text = rule.trim().replace(/^@css:/i, '').replace(/^css:/i, '');
    if (text.startsWith('&&')) text = text.substring(2);
    return text.split('@').map((part: string) => part.trim()).filter((part: string) => part.length > 0);
  }

  private selectorParts(parts: string[]): string[] {
    const selectors: string[] = [];
    parts.forEach((part: string) => {
      this.splitSelectorChain(part.replace(/\s*,\s*/g, ',').replace(/\s*>\s*/g, ' '))
        .forEach((value: string) => selectors.push(value));
    });
    return selectors;
  }

  private selectElements(html: string, selectorText: string): HtmlElement[] {
    const alternatives = this.splitSelectorAlternatives(selectorText);
    const values: HtmlElement[] = [];
    alternatives.forEach((alternative: string) => {
      this.selectSingleElements(html, alternative).forEach((element: HtmlElement) => {
        if (!values.some((existing: HtmlElement) => existing.start === element.start &&
          existing.outerHtml === element.outerHtml)) values.push(element);
      });
    });
    return values.sort((left: HtmlElement, right: HtmlElement) => left.start - right.start);
  }

  private selectSingleElements(html: string, selectorText: string): HtmlElement[] {
    const selector = this.parseSelector(selectorText);
    if (selector === undefined) return [];
    const tagPattern = selector.tag.length === 0 ? '[a-zA-Z][\\w:-]*' : this.escapeRegex(selector.tag);
    const opening = new RegExp(`<(${tagPattern})(\\s[^<>]*?)?\\s*/?>`, 'gi');
    const values: HtmlElement[] = [];
    let match = opening.exec(html);
    while (match !== null) {
      const tag = match[1].toLowerCase();
      const openingText = match[0];
      const attributes = this.parseAttributes(match[2] ?? '');
      const start = match.index;
      const openingEnd = opening.lastIndex;
      let end = openingEnd;
      let inner = '';
      if (!openingText.endsWith('/>') && !this.isVoidTag(tag)) {
        const closing = this.findClosingTag(html, tag, openingEnd);
        if (closing === undefined) {
          match = opening.exec(html);
          continue;
        }
        inner = html.substring(openingEnd, closing.start);
        end = closing.end;
      }
      if (this.matches(tag, attributes, inner, selector)) {
        values.push({ tag: tag, attributes: attributes, innerHtml: inner, outerHtml: html.substring(start, end),
          start: start });
        if (values.length > BookSourceHtmlParser.MAX_RESULTS) throw new Error('HTML选择器结果超过10000项');
      }
      match = opening.exec(html);
    }
    if (selector.positionMode === 'first') return values.length === 0 ? [] : [values[0]];
    if (selector.positionMode === 'last') return values.length === 0 ? [] : [values[values.length - 1]];
    if (selector.positionMode === 'eq') {
      const position = selector.positionIndex < 0 ? values.length + selector.positionIndex : selector.positionIndex;
      return position < 0 || position >= values.length ? [] : [values[position]];
    }
    if (selector.positionMode === 'lt' || selector.positionMode === 'gt' || selector.positionMode === 'xlt' ||
      selector.positionMode === 'xgt') {
      const threshold = (selector.positionMode === 'lt' || selector.positionMode === 'gt') &&
        selector.positionIndex < 0 ? values.length + selector.positionIndex : selector.positionIndex;
      return values.filter((_value: HtmlElement, index: number) =>
        selector.positionMode === 'lt' || selector.positionMode === 'xlt' ? index < threshold : index > threshold);
    }
    return values;
  }

  private findClosingTag(html: string, tag: string, from: number): HtmlClosingTag | undefined {
    const token = new RegExp(`<\\/?${this.escapeRegex(tag)}(?:\\s[^<>]*?)?\\s*/?>`, 'gi');
    token.lastIndex = from;
    let depth = 1;
    let match = token.exec(html);
    while (match !== null) {
      if (/^<\//.test(match[0])) {
        depth--;
        if (depth === 0) return { start: match.index, end: token.lastIndex };
      } else if (!match[0].endsWith('/>')) {
        depth++;
      }
      match = token.exec(html);
    }
    return undefined;
  }

  private elementFromOuterHtml(html: string): HtmlElement | undefined {
    const opening = /^<([a-zA-Z][\w:-]*)(\s[^<>]*?)?\s*\/?>/i.exec(html);
    if (opening === null) return undefined;
    const tag = opening[1].toLowerCase();
    const close = new RegExp(`<\/${this.escapeRegex(tag)}\s*>\s*$`, 'i');
    const inner = html.substring(opening[0].length).replace(close, '');
    return { tag: tag, attributes: this.parseAttributes(opening[2] ?? ''), innerHtml: inner, outerHtml: html, start: 0 };
  }

  private parseSelector(value: string): HtmlSelector | undefined {
    let text = value.trim();
    text = text.replace(/^class\./i, '.').replace(/^id\./i, '#').replace(/^tag\./i, '');
    if (text.includes('+') || text.includes('~')) {
      return undefined;
    }
    const negations: HtmlNegation[] = [];
    let invalidNegation = false;
    const negationPattern = /:not\(([^()]*)\)/gi;
    text = text.replace(negationPattern, (_whole: string, body: string) => {
      const negation = this.parseNegation(body.trim());
      if (negation === undefined) invalidNegation = true;
      else negations.push(negation);
      return '';
    });
    if (invalidNegation || negations.length > 4 || text.toLowerCase().includes(':not(')) return undefined;
    let positionMode = '';
    let positionIndex = 0;
    const position = /:(first|last)(?:-child)?$|:(eq|lt|gt|xlt|xgt|nth-child)\(\s*(-?\d+)\s*\)$/i.exec(text);
    if (position !== null) {
      positionMode = position[1] ?? position[2] ?? 'eq';
      positionMode = positionMode.toLowerCase();
      positionIndex = position[3] === undefined ? 0 : Number.parseInt(position[3], 10);
      if (!Number.isSafeInteger(positionIndex) || Math.abs(positionIndex) > 1000000) return undefined;
      if (/nth-child/i.test(position[0])) {
        positionMode = 'eq';
        positionIndex--;
      }
      text = text.substring(0, position.index);
    }
    let containsText = '';
    const contains = /:contains\(\s*["']?([^\)"']+)["']?\s*\)$/i.exec(text);
    if (contains !== null) {
      containsText = contains[1].trim();
      text = text.substring(0, contains.index);
    }
    let exactText = '';
    const textEquals = /:textequals\(\s*([^)]+)\s*\)$/i.exec(text);
    if (textEquals !== null) {
      exactText = textEquals[1].trim();
      text = text.substring(0, textEquals.index);
    }
    const attributes: HtmlAttributeFilter[] = [];
    const attributePattern = /\[([\w:-]+)(?:\s*(\^=|\$=|\*=|~=|=)\s*(?:"([^"]*)"|'([^']*)'|([^\]\s]+)))?\]/g;
    text = text.replace(attributePattern, (_whole: string, name: string, operator: string | undefined,
      doubleQuoted: string | undefined, singleQuoted: string | undefined, bare: string | undefined) => {
      attributes.push({
        name: name.toLowerCase(),
        value: (doubleQuoted ?? singleQuoted ?? bare ?? '').trim(),
        operator: operator ?? ''
      });
      return '';
    });
    // 不允许把未识别的属性表达式或其他CSS语法静默降级为宽泛匹配。
    if (text.includes('[') || text.includes(']')) return undefined;
    const idMatch = /#([\w-]+)/.exec(text);
    const classNames: string[] = [];
    const classRegex = /\.([\w-]+)/g;
    let classMatch = classRegex.exec(text);
    while (classMatch !== null) {
      classNames.push(classMatch[1]);
      classMatch = classRegex.exec(text);
    }
    const tagMatch = /^([a-zA-Z][\w:-]*)/.exec(text);
    if (text !== '*' && !/^(?:[a-zA-Z][\w:-]*)?(?:#[\w-]+)?(?:\.[\w-]+)*$/.test(text)) return undefined;
    return {
      tag: tagMatch === null ? '' : tagMatch[1].toLowerCase(),
      id: idMatch === null ? '' : idMatch[1],
      classNames: classNames,
      attributes: attributes,
      containsText: containsText,
      exactText: exactText,
      positionMode: positionMode,
      positionIndex: positionIndex,
      negations: negations
    };
  }

  private parseNegation(value: string): HtmlNegation | undefined {
    const emptyAttribute: HtmlAttributeFilter = { name: '', value: '', operator: '' };
    const className = /^\.([\w-]+)$/.exec(value);
    if (className !== null) return { kind: 'class', value: className[1], attribute: emptyAttribute };
    const id = /^#([\w-]+)$/.exec(value);
    if (id !== null) return { kind: 'id', value: id[1], attribute: emptyAttribute };
    const tag = /^([a-zA-Z][\w:-]*)$/.exec(value);
    if (tag !== null) return { kind: 'tag', value: tag[1].toLowerCase(), attribute: emptyAttribute };
    const attribute = /^\[([\w:-]+)(?:\s*(\^=|\$=|\*=|~=|=)\s*(?:"([^"]*)"|'([^']*)'|([^\]\s]+)))?\]$/.exec(value);
    if (attribute === null) return undefined;
    return {
      kind: 'attribute',
      value: '',
      attribute: {
        name: attribute[1].toLowerCase(),
        operator: attribute[2] ?? '',
        value: (attribute[3] ?? attribute[4] ?? attribute[5] ?? '').trim()
      }
    };
  }

  private parseAttributes(raw: string): Record<string, string> {
    const values: Record<string, string> = {};
    const regex = /([:\w-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
    let match = regex.exec(raw);
    while (match !== null) {
      values[match[1].toLowerCase()] = match[2] ?? match[3] ?? match[4] ?? '';
      match = regex.exec(raw);
    }
    return values;
  }

  private matches(tag: string, attributes: Record<string, string>, innerHtml: string, selector: HtmlSelector): boolean {
    if (selector.id.length > 0 && attributes['id'] !== selector.id) return false;
    const classes = (attributes['class'] ?? '').split(/\s+/).filter((value: string) => value.length > 0);
    if (selector.classNames.some((value: string) => !classes.includes(value))) return false;
    for (let index = 0; index < selector.attributes.length; index++) {
      const filter = selector.attributes[index];
      if (attributes[filter.name] === undefined) return false;
      const attributeValue = attributes[filter.name] ?? '';
      if (filter.operator === '=' && attributeValue !== filter.value) return false;
      if (filter.operator === '^=' && !attributeValue.startsWith(filter.value)) return false;
      if (filter.operator === '$=' && !attributeValue.endsWith(filter.value)) return false;
      if (filter.operator === '*=' && !attributeValue.includes(filter.value)) return false;
      if (filter.operator === '~=' && !attributeValue.split(/\s+/).includes(filter.value)) return false;
    }
    for (let index = 0; index < selector.negations.length; index++) {
      const negation = selector.negations[index];
      if (negation.kind === 'id' && attributes['id'] === negation.value) return false;
      if (negation.kind === 'class' && classes.includes(negation.value)) return false;
      if (negation.kind === 'tag' && tag === negation.value) return false;
      if (negation.kind === 'attribute' && this.matchesAttribute(attributes, negation.attribute)) return false;
    }
    if (selector.containsText.length > 0 && !this.htmlToText(innerHtml).includes(selector.containsText)) return false;
    if (selector.exactText.length > 0 && this.htmlToText(innerHtml).replace(/\s+/g, ' ').trim() !==
      selector.exactText.replace(/\s+/g, ' ').trim()) return false;
    return true;
  }

  private matchesAttribute(attributes: Record<string, string>, filter: HtmlAttributeFilter): boolean {
    if (attributes[filter.name] === undefined) return false;
    if (filter.operator.length === 0) return true;
    const value = attributes[filter.name] ?? '';
    if (filter.operator === '=') return value === filter.value;
    if (filter.operator === '^=') return value.startsWith(filter.value);
    if (filter.operator === '$=') return value.endsWith(filter.value);
    if (filter.operator === '*=') return value.includes(filter.value);
    if (filter.operator === '~=') return value.split(/\s+/).includes(filter.value);
    return false;
  }

  private splitSelectorChain(value: string): string[] {
    const values: string[] = [];
    let bracketDepth = 0;
    let parenthesisDepth = 0;
    let start = 0;
    for (let index = 0; index < value.length; index++) {
      if (value[index] === '[') bracketDepth++;
      if (value[index] === ']') bracketDepth--;
      if (value[index] === '(') parenthesisDepth++;
      if (value[index] === ')') parenthesisDepth--;
      if (/\s/.test(value[index]) && bracketDepth === 0 && parenthesisDepth === 0) {
        const item = value.substring(start, index).trim();
        if (item.length > 0) values.push(item);
        start = index + 1;
      }
    }
    const last = value.substring(start).trim();
    if (last.length > 0) values.push(last);
    return values;
  }

  private splitSelectorAlternatives(value: string): string[] {
    const values: string[] = [];
    let bracketDepth = 0;
    let parenthesisDepth = 0;
    let start = 0;
    for (let index = 0; index < value.length; index++) {
      if (value[index] === '[') bracketDepth++;
      if (value[index] === ']') bracketDepth--;
      if (value[index] === '(') parenthesisDepth++;
      if (value[index] === ')') parenthesisDepth--;
      if (value[index] === ',' && bracketDepth === 0 && parenthesisDepth === 0) {
        values.push(value.substring(start, index).trim());
        start = index + 1;
      }
    }
    values.push(value.substring(start).trim());
    return values.filter((item: string) => item.length > 0);
  }

  private isVoidTag(tag: string): boolean {
    return ['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'source', 'track', 'wbr']
      .includes(tag);
  }

  private escapeRegex(value: string): string {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
}
