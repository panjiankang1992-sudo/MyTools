/**
 * 展开无需脚本运行时的有界页码模板。
 */
export class DeclarativePageTemplate {
  private static readonly MAX_TEMPLATE_LENGTH = 64 * 1024;
  private static readonly MAX_OFFSET = 1000;
  private static readonly MAX_PAGE_VALUE = 1000000;

  /**
   * 支持page/searchPage及固定整数加减表达式。
   */
  expand(template: string, page: number): string {
    if (template.length > DeclarativePageTemplate.MAX_TEMPLATE_LENGTH) throw new Error('页码模板超过64 KB');
    if (!Number.isInteger(page) || page < 1 || page > DeclarativePageTemplate.MAX_PAGE_VALUE) {
      throw new Error('页码超出允许范围');
    }
    const expanded = template.replace(/\{\{\s*(?:page|searchPage)\s*(?:([+-])\s*(\d+))?\s*\}\}/gi,
      (_whole: string, operator: string | undefined, rawOffset: string | undefined) => {
        const offset = rawOffset === undefined ? 0 : Number.parseInt(rawOffset, 10);
        if (!Number.isInteger(offset) || offset > DeclarativePageTemplate.MAX_OFFSET) {
          throw new Error('页码模板偏移超过1000');
        }
        const value = operator === '-' ? page - offset : page + offset;
        if (value < 0 || value > DeclarativePageTemplate.MAX_PAGE_VALUE) throw new Error('页码模板结果超出范围');
        return `${value}`;
      });
    if (expanded.includes('{{') || expanded.includes('}}')) throw new Error('页码模板包含不支持的表达式');
    return expanded;
  }

  /**
   * 判断移除合法页码占位符后是否仍存在模板表达式。
   */
  hasUnsupportedExpression(template: string): boolean {
    const stripped = template.replace(/\{\{\s*(?:page|searchPage)\s*(?:[+-]\s*\d+)?\s*\}\}/gi, '');
    return stripped.includes('{{') || stripped.includes('}}');
  }
}
