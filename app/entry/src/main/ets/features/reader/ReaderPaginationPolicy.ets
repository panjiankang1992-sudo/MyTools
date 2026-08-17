import { ReaderBlock, ReaderSettings } from './ReaderModels';
import { ReaderViewport } from './ReaderViewportPolicy';

/** 把结构化文本章节投影为有界阅读页，同时保留块类型和图片。 */
export class ReaderPaginationPolicy {
  /** 根据当前排版设置生成最多20000页的稳定分页。 */
  paginate(blocks: ReaderBlock[], fallbackText: string, settings: ReaderSettings,
    viewport: ReaderViewport = { width: 360, height: 700 }, fontScale: number = 1): ReaderBlock[][] {
    const capacity = this.capacity(settings, viewport, fontScale);
    const source = blocks.length > 0 ? blocks : [this.block('paragraph', fallbackText, '')];
    const pages: ReaderBlock[][] = [];
    let page: ReaderBlock[] = [];
    let used = 0;
    source.forEach((block: ReaderBlock) => {
      if (pages.length >= 20000) return;
      if (block.kind === 'image') {
        if (page.length > 0) pages.push(page);
        pages.push([this.copy(block, block.text)]);
        page = [];
        used = 0;
        return;
      }
      const weight = this.weight(block.kind);
      const chunkSize = Math.max(40, Math.floor(capacity / weight));
      const chunks = this.split(block.text, chunkSize);
      chunks.forEach((text: string) => {
        const cost = Math.max(1, Math.ceil(text.length * weight));
        if (page.length > 0 && used + cost > capacity) {
          pages.push(page);
          page = [];
          used = 0;
        }
        if (pages.length < 20000) {
          page.push(this.copy(block, text));
          used += cost;
        }
      });
    });
    if (page.length > 0 && pages.length < 20000) pages.push(page);
    return pages.length > 0 ? pages : [[this.block('paragraph', '', '')]];
  }

  private capacity(settings: ReaderSettings, viewport: ReaderViewport, fontScale: number): number {
    const contentWidth = Math.max(160, viewport.width - settings.horizontalPadding * 2);
    const contentHeight = Math.max(240, viewport.height - 122);
    const widthFactor = Math.max(0.45, Math.min(3.5, contentWidth / 312));
    const heightFactor = Math.max(0.45, Math.min(3.5, contentHeight / 578));
    const spacingFactor = Math.max(0.65, Math.min(1.15, 14 / Math.max(8, settings.paragraphSpacing)));
    const fontFactor = settings.fontFamily === 'monospace' ? 0.82 : (settings.fontFamily === 'serif' ? 0.93 : 1);
    const safeFontScale = Number.isFinite(fontScale) ? Math.max(0.8, Math.min(3.2, fontScale)) : 1;
    const value = 1200 * (19 / (settings.fontSize * safeFontScale)) * (1.8 / settings.lineHeight) *
      widthFactor * heightFactor * spacingFactor * fontFactor;
    return Math.max(300, Math.min(2400, Math.floor(value)));
  }

  private weight(kind: string): number {
    if (kind === 'heading') return 1.8;
    if (kind === 'code' || kind === 'math' || kind === 'diagram') return 1.45;
    if (kind === 'quote' || kind === 'footnote' || kind === 'list') return 1.25;
    return 1;
  }

  private split(text: string, limit: number): string[] {
    if (text.length === 0) return [''];
    const result: string[] = [];
    let start = 0;
    while (start < text.length && result.length < 20000) {
      let end = Math.min(text.length, start + limit);
      if (end < text.length && end > start && this.lowSurrogate(text.charCodeAt(end))) end--;
      let boundary = end;
      const minimum = Math.min(end, start + Math.floor(limit * 0.65));
      while (boundary > minimum && !/[\s，。！？；：,.!?;:]/.test(text.charAt(boundary - 1))) boundary--;
      if (boundary > minimum) end = boundary;
      if (end <= start) end = Math.min(text.length, start + limit);
      result.push(text.substring(start, end));
      start = end;
    }
    return result;
  }

  private lowSurrogate(value: number): boolean {
    return value >= 0xDC00 && value <= 0xDFFF;
  }

  private copy(block: ReaderBlock, text: string): ReaderBlock {
    return { kind: block.kind, text, uri: block.uri, level: block.level };
  }

  private block(kind: 'paragraph', text: string, uri: string): ReaderBlock {
    return { kind, text, uri };
  }
}
