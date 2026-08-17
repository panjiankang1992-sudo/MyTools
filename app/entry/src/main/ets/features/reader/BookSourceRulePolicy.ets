import { BookSourceHtmlParser } from './BookSourceHtmlParser';
import { RestrictedSourceScript } from './RestrictedSourceScript';

/**
 * 按响应格式校验并返回无需脚本执行的书源规则。
 */
export class BookSourceRulePolicy {
  private readonly htmlParser = new BookSourceHtmlParser();
  private readonly script = new RestrictedSourceScript();

  /**
   * 返回JSON规则；具体JSONPath语法由有界求值器校验。
   */
  jsonRule(record: Record<string, Object>, key: string): string {
    return this.declarativeRule(record, key);
  }

  /**
   * 返回已通过当前HTML/CSS/XPath能力校验的规则。
   */
  htmlRule(record: Record<string, Object>, key: string): string {
    const rule = this.declarativeRule(record, key);
    if (rule.length > 0 && !this.htmlParser.supportsRule(rule)) {
      throw new Error(`HTML规则使用了当前不支持的表达式：${key}`);
    }
    return rule;
  }

  /**
   * 对标量选择结果执行已经通过语法校验的受限字符串后处理。
   */
  transform(record: Record<string, Object>, key: string, value: string): string {
    const raw = this.rawRule(record, key);
    return raw.length === 0 ? value : this.script.apply(raw, value);
  }

  private declarativeRule(record: Record<string, Object>, key: string): string {
    const text = this.rawRule(record, key);
    const parsed = this.script.parse(text);
    if ((key === 'bookList' || key === 'chapterList') &&
      (parsed.script.length > 0 || parsed.replacementPattern.length > 0)) {
      throw new Error(`列表规则不允许字符串后处理：${key}`);
    }
    return parsed.selector;
  }

  private rawRule(record: Record<string, Object>, key: string): string {
    const value = record[key];
    return typeof value === 'string' ? value.trim() : '';
  }
}
