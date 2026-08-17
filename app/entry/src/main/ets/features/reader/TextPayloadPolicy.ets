/**
 * TXT解码结果的内容安全策略，允许常见换行和制表符但拒绝二进制控制内容。
 */
export class TextPayloadPolicy {
  /** 检查解码文本是否具有可显示正文特征。 */
  error(text: string): string {
    if (text.length === 0) return 'TXT为空';
    const prefix = text.replace(/^\uFEFF?\s*/, '').substring(0, 512).toLowerCase();
    if (prefix.startsWith('<!doctype html') || prefix.startsWith('<html') || prefix.startsWith('<?xml') ||
      /^<(?:head|body)(?:\s|>)/.test(prefix)) return 'TXT响应疑似网页或XML文档';
    let visible = 0;
    let forbiddenControls = 0;
    for (let index = 0; index < text.length; index++) {
      const code = text.charCodeAt(index);
      if (code === 0 || (code < 0x20 && code !== 0x09 && code !== 0x0A && code !== 0x0D) || code === 0x7F) {
        forbiddenControls++;
      } else if (code > 0x20 && code !== 0xFEFF) {
        visible++;
      }
    }
    if (forbiddenControls > 0 && forbiddenControls * 10 > text.length) return 'TXT疑似二进制文件';
    if (visible === 0) return 'TXT中没有可显示的正文';
    return '';
  }
}
