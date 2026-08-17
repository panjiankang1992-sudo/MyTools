import { ReaderBlock } from './ReaderModels';

/** 把当前可读文本净化并切成系统TTS可稳定接收的有界片段。 */
export class ReaderSpeechPolicy {
  static readonly MAX_INPUT: number = 200000;
  static readonly MAX_CHUNKS: number = 400;
  static readonly MAX_CHUNK: number = 500;

  /** 提取非图片块正文；没有结构块时使用纯文本回退。 */
  readableText(blocks: ReaderBlock[], fallbackText: string): string {
    const text = blocks.length > 0 ? blocks.filter((block: ReaderBlock) => block.kind !== 'image')
      .map((block: ReaderBlock) => block.text).join('\n') : fallbackText;
    return text.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, ' ')
      .replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim()
      .substring(0, ReaderSpeechPolicy.MAX_INPUT);
  }

  /** 在句末附近切分，避免截断UTF-16代理对。 */
  chunks(text: string): string[] {
    const normalized = text.trim().substring(0, ReaderSpeechPolicy.MAX_INPUT);
    const result: string[] = [];
    let start = 0;
    while (start < normalized.length && result.length < ReaderSpeechPolicy.MAX_CHUNKS) {
      let end = Math.min(normalized.length, start + ReaderSpeechPolicy.MAX_CHUNK);
      if (end < normalized.length && this.isLowSurrogate(normalized.charCodeAt(end))) end--;
      const minimum = Math.min(end, start + 250);
      let boundary = end;
      while (boundary > minimum && !/[。！？；.!?;\n]/.test(normalized.charAt(boundary - 1))) boundary--;
      if (boundary > minimum) end = boundary;
      if (end <= start) end = Math.min(normalized.length, start + ReaderSpeechPolicy.MAX_CHUNK);
      const chunk = normalized.substring(start, end).trim();
      if (chunk.length > 0) result.push(chunk);
      start = end;
    }
    return result;
  }

  private isLowSurrogate(value: number): boolean {
    return value >= 0xDC00 && value <= 0xDFFF;
  }
}
