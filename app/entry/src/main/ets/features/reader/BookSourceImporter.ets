import { BookSource, BookSourceImportResult } from './ReaderModels';

/**
 * 兼容开源阅读书源 JSON 的安全导入器。
 */
export class BookSourceImporter {
  private static readonly MAX_SOURCES: number = 500;
  private static readonly MAX_SOURCE_BYTES: number = 512 * 1024;
  private static readonly MAX_RULE_FIELDS: number = 64;
  private static readonly MAX_RULE_BYTES: number = 512 * 1024;
  private static readonly MAX_RULE_VALUE_LENGTH: number = 64 * 1024;
  private static readonly MAX_ROUTE_LENGTH: number = 512 * 1024;
  /**
   * 解析单个书源或书源数组，并按书源地址去重。
   */
  importJson(content: string): BookSourceImportResult {
    if (content.length === 0 || content.length > 5 * 1024 * 1024) {
      throw new Error('书源文件为空或超过 5 MB');
    }
    let parsed: Object;
    try {
      parsed = JSON.parse(content) as Object;
    } catch (_) {
      throw new Error('书源 JSON 格式不正确');
    }
    const allValues = Array.isArray(parsed) ? parsed as Array<Object> : [parsed];
    const values = allValues.slice(0, BookSourceImporter.MAX_SOURCES);
    const sourceMap = new Map<string, BookSource>();
    let rejectedCount = Math.max(0, allValues.length - values.length);
    let scriptedCount = 0;
    values.forEach((value: Object) => {
      if (typeof value !== 'object' || value === null) {
        rejectedCount++;
        return;
      }
      const record = value as Record<string, Object>;
      const serialized = JSON.stringify(record);
      if (this.utf8Length(serialized) > BookSourceImporter.MAX_SOURCE_BYTES) {
        rejectedCount++;
        return;
      }
      const url = this.stringValue(record, 'bookSourceUrl').trim();
      const name = this.stringValue(record, 'bookSourceName').trim();
      if (!this.isHttpUrl(url) || url.length > 4096 || name.length === 0 || name.length > 200 ||
        /[\u0000-\u001F\u007F]/.test(url)) {
        rejectedCount++;
        return;
      }
      try {
        const group = this.limitedString(record, 'bookSourceGroup', 500);
        const searchUrl = this.limitedString(record, 'searchUrl', BookSourceImporter.MAX_ROUTE_LENGTH);
        const exploreUrl = this.limitedString(record, 'exploreUrl', BookSourceImporter.MAX_ROUTE_LENGTH);
        this.rejectInlineSensitiveHeaders(searchUrl);
        this.rejectInlineSensitiveHeaders(exploreUrl);
        const header = this.safeHeader(record);
        const requiresScriptRuntime = this.containsScript(record);
        const ruleSearch = this.recordValue(record, 'ruleSearch');
        const ruleExplore = this.recordValue(record, 'ruleExplore');
        const ruleBookInfo = this.recordValue(record, 'ruleBookInfo');
        const ruleToc = this.recordValue(record, 'ruleToc');
        const ruleContent = this.recordValue(record, 'ruleContent');
        const source: BookSource = {
          bookSourceUrl: url,
          bookSourceName: name,
          bookSourceGroup: group,
          bookSourceType: this.sourceTypeValue(record),
          enabled: this.booleanValue(record, 'enabled', true),
          enabledExplore: this.booleanValue(record, 'enabledExplore', true),
          searchUrl: searchUrl,
          exploreUrl: exploreUrl,
          header: header,
          ruleSearch: ruleSearch,
          ruleExplore: ruleExplore,
          ruleBookInfo: ruleBookInfo,
          ruleToc: ruleToc,
          ruleContent: ruleContent,
          requiresScriptRuntime: requiresScriptRuntime,
          healthStatus: 'unknown',
          healthLatencyMs: 0,
          healthCheckedAt: 0,
          healthMessage: '',
          healthHistory: [],
          healthSuccessRate: 0,
          healthAverageLatencyMs: 0,
          healthConsecutiveFailures: 0,
          healthDegraded: false
        };
        sourceMap.set(url, source);
        if (requiresScriptRuntime) scriptedCount++;
      } catch (_) {
        rejectedCount++;
      }
    });
    return { sources: Array.from(sourceMap.values()), rejectedCount, scriptedCount };
  }

  private containsScript(record: Record<string, Object>): boolean {
    const text = JSON.stringify(record).toLowerCase();
    return text.includes('@js:') || text.includes('<js>') || text.includes('javascript:') ||
      this.stringValue(record, 'jsLib').length > 0 || this.stringValue(record, 'loginCheckJs').length > 0;
  }

  private utf8Length(value: string): number {
    let bytes = 0;
    for (let index = 0; index < value.length; index++) {
      const code = value.charCodeAt(index);
      if (code <= 0x7F) bytes++;
      else if (code <= 0x7FF) bytes += 2;
      else if (code >= 0xD800 && code <= 0xDBFF && index + 1 < value.length) {
        const low = value.charCodeAt(index + 1);
        if (low >= 0xDC00 && low <= 0xDFFF) {
          bytes += 4;
          index++;
        } else {
          bytes += 3;
        }
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }

  private isHttpUrl(value: string): boolean {
    return (value.startsWith('https://') || value.startsWith('http://')) && !/\s/.test(value) &&
      !/^https?:\/\/[^/?#]*@/i.test(value);
  }

  private safeHeader(record: Record<string, Object>): string {
    const raw = this.limitedString(record, 'header', 32 * 1024);
    if (raw.trim().length === 0) return '';
    let parsed: Record<string, Object>;
    // 兼容开源阅读书源常见的零宽字符，以及省略最外层花括号的请求头片段。
    const normalizedRaw = raw.replace(/\uFEFF|\u200B/g, '').trim();
    try {
      parsed = JSON.parse(normalizedRaw) as Record<string, Object>;
    } catch (_) {
      try {
        parsed = JSON.parse(`{${normalizedRaw}}`) as Record<string, Object>;
      } catch (_) {
        throw new Error('书源请求头必须是JSON对象');
      }
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('书源请求头必须是JSON对象');
    }
    const keys = Object.keys(parsed);
    if (keys.length > 32) throw new Error('书源请求头字段过多');
    const normalized: Record<string, string> = {};
    keys.forEach((key: string) => {
      const lower = key.trim().toLowerCase();
      if (!/^[!#$%&'*+.^_`|~a-z0-9-]{1,64}$/.test(lower)) throw new Error('书源请求头名称无效');
      if (['authorization', 'cookie', 'proxy-authorization', 'x-api-key'].includes(lower)) {
        throw new Error('书源敏感请求头需要安全凭据存储');
      }
      const value = parsed[key];
      if (typeof value !== 'string' || value.length > 4096 || /[\r\n]/.test(value)) {
        throw new Error('书源请求头内容无效');
      }
      normalized[key.trim()] = value;
    });
    return JSON.stringify(normalized);
  }

  private rejectInlineSensitiveHeaders(value: string): void {
    const marker = value.indexOf(',{');
    if (marker < 0) return;
    let config: Record<string, Object>;
    try {
      config = JSON.parse(value.substring(marker + 1)) as Record<string, Object>;
    } catch (_) {
      return;
    }
    const headers = config['headers'];
    if (typeof headers !== 'object' || headers === null || Array.isArray(headers)) return;
    const hasSensitive = Object.keys(headers as Record<string, Object>).some((key: string) => {
      const lower = key.trim().toLowerCase();
      return lower === 'authorization' || lower === 'cookie' || lower === 'proxy-authorization' ||
        lower === 'x-api-key';
    });
    if (hasSensitive) throw new Error('书源内联敏感请求头需要安全凭据存储');
  }

  private stringValue(record: Record<string, Object>, key: string): string {
    const value = record[key];
    return typeof value === 'string' ? value : '';
  }

  private limitedString(record: Record<string, Object>, key: string, maxLength: number): string {
    const value = this.stringValue(record, key);
    if (value.length > maxLength) throw new Error(`书源字段过长：${key}`);
    return value;
  }

  private sourceTypeValue(record: Record<string, Object>): number {
    const value = record['bookSourceType'];
    if (value === undefined) return 0;
    if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > 4) {
      throw new Error('书源类型必须为0至4的整数');
    }
    return value;
  }

  private booleanValue(record: Record<string, Object>, key: string, fallback: boolean): boolean {
    const value = record[key];
    return typeof value === 'boolean' ? value : fallback;
  }

  private recordValue(record: Record<string, Object>, key: string): Record<string, Object> {
    const value = record[key];
    if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
      const source = value as Record<string, Object>;
      const keys = Object.keys(source);
      if (keys.length > BookSourceImporter.MAX_RULE_FIELDS) throw new Error(`书源规则字段过多：${key}`);
      const result: Record<string, Object> = {};
      let totalLength = 0;
      keys.forEach((field: string) => {
        if (field.length === 0 || field.length > 100 || !/^[\w.-]+$/.test(field)) {
          throw new Error(`书源规则字段名无效：${key}`);
        }
        const fieldValue = source[field];
        if (typeof fieldValue === 'string') {
          if (fieldValue.length > BookSourceImporter.MAX_RULE_VALUE_LENGTH) {
            throw new Error(`书源规则过长：${key}.${field}`);
          }
          totalLength += field.length + fieldValue.length;
          result[field] = fieldValue;
        } else if (typeof fieldValue === 'number' || typeof fieldValue === 'boolean') {
          totalLength += field.length + 16;
          result[field] = fieldValue;
        } else if (fieldValue !== undefined && fieldValue !== null) {
          throw new Error(`书源规则字段类型无效：${key}.${field}`);
        }
      });
      if (totalLength > BookSourceImporter.MAX_RULE_BYTES) throw new Error(`书源规则对象过大：${key}`);
      return result;
    }
    return {};
  }
}
