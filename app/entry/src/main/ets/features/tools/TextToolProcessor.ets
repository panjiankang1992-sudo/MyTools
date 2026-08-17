/**
 * 执行不依赖网络和设备权限的文本及开发转换。
 */
export class TextToolProcessor {
  /**
   * 处理有界文本并返回可复制结果。
   */
  process(input: string, operation: string): string {
    try {
      if (operation === 'json') return JSON.stringify(JSON.parse(input) as Object, null, 2);
      if (operation === 'urlEncode') return encodeURIComponent(input);
      if (operation === 'urlDecode') return decodeURIComponent(input);
      if (operation === 'timestampToDate') return this.timestampToDate(input);
      if (operation === 'dateToTimestamp') return this.dateToTimestamp(input);
      const lines = input.replace(/\r/g, '').split('\n');
      if (operation === 'unique') return Array.from(new Set<string>(lines)).join('\n');
      if (operation === 'sort') return lines.slice()
        .sort((left: string, right: string) => left.localeCompare(right)).join('\n');
      if (operation === 'compact') return lines.filter((line: string) => line.trim().length > 0).join('\n');
      if (operation === 'upper') return input.toUpperCase();
      if (operation === 'lower') return input.toLowerCase();
      if (operation === 'stats') {
        const words = input.trim().length === 0 ? 0 : input.trim().split(/\s+/).length;
        return `字符：${input.length}\n非空字符：${input.replace(/\s/g, '').length}\n` +
          `行数：${lines.length}\n词数：${words}`;
      }
      return '不支持的处理操作';
    } catch (_) {
      if (operation === 'json') return '输入不是有效的JSON';
      if (operation === 'urlDecode') return '输入不是有效的URL编码文本';
      if (operation === 'timestampToDate') return '请输入有效的秒或毫秒时间戳';
      if (operation === 'dateToTimestamp') return '请输入有效的ISO日期时间';
      return '处理失败';
    }
  }

  private timestampToDate(input: string): string {
    const parsed = Number(input.trim());
    if (!Number.isFinite(parsed)) throw new Error('invalid timestamp');
    const milliseconds = Math.abs(parsed) < 100000000000 ? parsed * 1000 : parsed;
    const date = new Date(milliseconds);
    if (!Number.isFinite(date.getTime())) throw new Error('invalid timestamp');
    return date.toISOString();
  }

  private dateToTimestamp(input: string): string {
    const milliseconds = Date.parse(input.trim());
    if (!Number.isFinite(milliseconds)) throw new Error('invalid date');
    return `秒：${Math.floor(milliseconds / 1000)}\n毫秒：${milliseconds}`;
  }
}
