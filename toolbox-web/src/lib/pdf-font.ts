/**
 * 本工程固定版本 fontkit 的 CFF 子集化补丁。
 *
 * 单独成模块，供浏览器渲染与构建脚本共用同一套字体嵌入逻辑；
 * 不放在 pdf-runtime.ts 里是因为后者依赖 pdfjs-dist，Node 脚本无法直接引用。
 */

import fontkit from '@pdf-lib/fontkit';
import type { Font, Subset } from '@pdf-lib/fontkit';

export interface CffDictionary {
  FontName?: unknown;
  Private?: { Subrs?: unknown; [key: string]: unknown };
  [key: string]: unknown;
}

export interface CffSubset extends Subset {
  cff: {
    length: number;
    offSize: number;
    isCIDFont: boolean;
    topDict: { FDArray: CffDictionary[] };
    fdForGlyph(id: number): number | undefined;
  };
  glyphs: number[];
  font: { getGlyph(id: number): { path: unknown; _usedSubrs: Record<string, boolean> } };
  subsetSubrs(subroutines: unknown, used: Record<string, boolean>): unknown;
  subsetFontdict(dictionary: CffDictionary): void;
  encode(stream: unknown): void;
}

/** 修正固定版本 fontkit 的 CFF 字典索引与头部长度问题，保证中文和声调完整打印。 */
export function createPdfFont(bytes: Uint8Array, postscriptName?: string): Font {
  const font = fontkit.create(bytes, postscriptName) as Font;
  const createSubset = font.createSubset.bind(font);
  font.createSubset = () => {
    const subset = createSubset() as CffSubset;
    if (!subset.cff) return subset;
    const encode = subset.encode;
    subset.encode = function (stream: unknown): void {
      // 1.1.1 将整个 CFF 表长度误写到单字节 offSize；只在编码期间改为合法偏移宽度。
      const length = this.cff.length;
      this.cff.length = this.cff.offSize || 4;
      try { encode.call(this, stream); } finally { this.cff.length = length; }
    };
    if (subset.cff.isCIDFont) {
      subset.subsetFontdict = function (dictionary: CffDictionary): void {
        const dictionaries: CffDictionary[] = [];
        const assignments: number[] = [];
        const indices = new Map<number, number>();
        const usedSubroutines: Record<string, boolean>[] = [];
        for (const id of this.glyphs) {
          const originalIndex = this.cff.fdForGlyph(id);
          if (originalIndex === undefined) throw new Error('FONT_UNAVAILABLE');
          let index = indices.get(originalIndex);
          if (index === undefined) {
            index = dictionaries.length;
            indices.set(originalIndex, index);
            dictionaries.push({ ...this.cff.topDict.FDArray[originalIndex] });
            usedSubroutines.push({});
          }
          // 再次遇到旧字典时使用其原映射，不错误套用最近新增的字典。
          assignments.push(index);
          const glyph = this.font.getGlyph(id);
          void glyph.path;
          Object.assign(usedSubroutines[index]!, glyph._usedSubrs);
        }
        dictionaries.forEach((entry, index) => {
          delete entry.FontName;
          if (entry.Private?.Subrs) {
            entry.Private = { ...entry.Private, Subrs: this.subsetSubrs(entry.Private.Subrs, usedSubroutines[index]!) };
          }
        });
        dictionary.FDArray = dictionaries;
        dictionary.FDSelect = { version: 0, fds: assignments };
      };
    }
    return subset;
  };
  return font;
}
