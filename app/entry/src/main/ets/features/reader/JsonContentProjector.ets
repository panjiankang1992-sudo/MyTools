/**
 * 将JSON正文规则结果投影为有界阅读文本。
 */
export class JsonContentProjector {
  private static readonly MAX_SEGMENTS = 10000;
  private static readonly MAX_CHARACTERS = 5 * 1024 * 1024;

  /**
   * 接受单个标量或标量数组，数组按段落换行拼接。
   */
  multiline(value: Object | undefined): string {
    if (value === undefined || value === null) return '';
    if (!Array.isArray(value)) return this.scalar(value).trim();
    if (value.length > JsonContentProjector.MAX_SEGMENTS) {
      throw new Error('JSON正文分段超过10000项');
    }
    const segments: string[] = [];
    let characters = 0;
    for (let index = 0; index < value.length; index++) {
      const item = value[index] as Object;
      if (item === null || item === undefined) continue;
      const segment = this.scalar(item).trim();
      if (segment.length === 0) continue;
      characters += segment.length + (segments.length > 0 ? 1 : 0);
      if (characters > JsonContentProjector.MAX_CHARACTERS) {
        throw new Error('JSON正文投影超过5 MB字符预算');
      }
      segments.push(segment);
    }
    return segments.join('\n');
  }

  private scalar(value: Object): string {
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return `${value}`;
    throw new Error('JSON正文仅支持标量或标量数组');
  }
}
