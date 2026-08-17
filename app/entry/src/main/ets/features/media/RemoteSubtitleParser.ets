export interface RemoteSubtitleCue {
  startMs: number;
  endMs: number;
  text: string;
}

/**
 * 解析有界SRT与WebVTT字幕，不执行样式、脚本或外部资源。
 */
export class RemoteSubtitleParser {
  private static readonly MAX_TEXT_LENGTH: number = 1024 * 1024;
  private static readonly MAX_CUES: number = 5000;
  private static readonly MAX_CUE_TEXT: number = 2000;

  /**
   * 将严格UTF-8解码后的字幕文本转换为按开始时间排序的提示集合。
   */
  parse(value: string): RemoteSubtitleCue[] {
    if (value.length === 0 || value.length > RemoteSubtitleParser.MAX_TEXT_LENGTH) {
      throw new Error('远程字幕为空或超过1 MB限制');
    }
    let text = value.charCodeAt(0) === 0xFEFF ? value.substring(1) : value;
    text = text.replace(/\r\n?/g, '\n');
    if (/^WEBVTT(?:\s.*)?$/i.test(text.split('\n')[0].trim())) text = text.substring(text.indexOf('\n') + 1);
    const cues: RemoteSubtitleCue[] = [];
    const blocks = text.split(/\n{2,}/);
    for (let blockIndex = 0; blockIndex < blocks.length; blockIndex++) {
      const lines = blocks[blockIndex].split('\n').map((line: string) => line.trim());
      while (lines.length > 0 && lines[0].length === 0) lines.shift();
      if (lines.length === 0 || /^NOTE(?:\s|$)/i.test(lines[0]) || /^STYLE(?:\s|$)/i.test(lines[0]) ||
        /^REGION(?:\s|$)/i.test(lines[0])) continue;
      let timingIndex = lines.findIndex((line: string) => line.includes('-->'));
      if (timingIndex < 0 || timingIndex > 1) continue;
      const timing = /^([^\s]+)\s+-->\s+([^\s]+)(?:\s+.*)?$/.exec(lines[timingIndex]);
      if (timing === null) continue;
      const startMs = this.timestamp(timing[1]);
      const endMs = this.timestamp(timing[2]);
      const cueText = this.cleanText(lines.slice(timingIndex + 1).join('\n'));
      if (startMs < 0 || endMs <= startMs || endMs - startMs > 24 * 60 * 60 * 1000 || cueText.length === 0) continue;
      cues.push({ startMs, endMs, text: cueText });
      if (cues.length > RemoteSubtitleParser.MAX_CUES) throw new Error('远程字幕超过5000条限制');
    }
    if (cues.length === 0) throw new Error('远程字幕没有可用时间轴');
    return cues.sort((left: RemoteSubtitleCue, right: RemoteSubtitleCue) =>
      left.startMs === right.startMs ? left.endMs - right.endMs : left.startMs - right.startMs);
  }

  /**
   * 返回给定播放时刻最多三条重叠字幕的安全纯文本。
   */
  textAt(cues: RemoteSubtitleCue[], timeMs: number): string {
    if (!Number.isFinite(timeMs) || timeMs < 0 || cues.length === 0) return '';
    let low = 0;
    let high = cues.length;
    while (low < high) {
      const middle = Math.floor((low + high) / 2);
      if (cues[middle].startMs <= timeMs) low = middle + 1;
      else high = middle;
    }
    const active: string[] = [];
    for (let index = low - 1; index >= 0 && active.length < 3; index--) {
      const cue = cues[index];
      if (timeMs - cue.startMs > 24 * 60 * 60 * 1000) break;
      if (timeMs < cue.endMs) active.unshift(cue.text);
    }
    return active.join('\n');
  }

  private timestamp(value: string): number {
    const match = /^(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})$/.exec(value);
    if (match === null) return -1;
    const hours = Number.parseInt(match[1] ?? '0', 10);
    const minutes = Number.parseInt(match[2], 10);
    const seconds = Number.parseInt(match[3], 10);
    const millis = Number.parseInt(match[4], 10);
    if (hours > 99 || minutes > 59 || seconds > 59) return -1;
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis;
  }

  private cleanText(value: string): string {
    const text = value.replace(/<br\s*\/?\s*>/gi, '\n').replace(/<[^>]{1,256}>/g, '')
      .replace(/&nbsp;|&#160;/gi, ' ').replace(/&amp;/gi, '&').replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
      .replace(/&quot;/gi, '"').replace(/&#39;|&apos;/gi, "'")
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').trim();
    if (text.length > RemoteSubtitleParser.MAX_CUE_TEXT) throw new Error('单条远程字幕超过2000字符限制');
    return text;
  }
}
