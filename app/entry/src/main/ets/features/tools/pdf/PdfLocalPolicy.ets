import { PdfErrorCode } from './common/PdfErrorCode';

export interface PdfBounds { left: number; right: number; top: number; bottom: number; }
export interface PdfPiece { page: number; side: number; }

/** 纯计算规则，和界面、设备 SDK 分离。 */
export class PdfLocalPolicy {
  /** 将一基页码范围转换为去重且升序的零基索引。 */
  static pages(value: string, count: number): number[] {
    if (!Number.isInteger(count) || count < 1) throw new Error(PdfErrorCode.INVALID);
    if (!value.trim()) return Array.from({ length: count }, (_v: Object, i: number) => i);
    const result: number[] = [];
    for (const part of value.split(',')) {
      if (!/^\s*\d+\s*(-\s*\d+\s*)?$/.test(part)) throw new Error(PdfErrorCode.INVALID);
      const pair = part.trim().split('-').map((v: string) => Number(v.trim()));
      const end = pair.length === 1 ? pair[0] : pair[1];
      // 拒绝越界、倒序与空范围，避免静默处理错误页面。
      if (pair[0] < 1 || end > count || end < pair[0]) throw new Error(PdfErrorCode.INVALID);
      for (let p = pair[0] - 1; p < end; p++) if (!result.includes(p)) result.push(p);
    }
    return result.sort((a: number, b: number) => a - b);
  }
  /** 生成实际输出身份；未选中页面默认保留。 */
  static pieces(count: number, selected: number[], grouped: boolean): PdfPiece[] {
    const result: PdfPiece[] = [];
    if (grouped) {
      for (let side = 0; side < 2; side++) selected.forEach((page: number) => result.push({ page, side }));
      for (let page = 0; page < count; page++) if (!selected.includes(page)) result.push({ page, side: -1 });
    } else {
      for (let page = 0; page < count; page++) {
        if (selected.includes(page)) { result.push({ page, side: 0 }); result.push({ page, side: 1 }); }
        else result.push({ page, side: -1 });
      }
    }
    return result;
  }
  /** 以视觉方向切分 PDF 底部原点坐标，包含四种旋转与非零边界。 */
  static cut(box: PdfBounds, rotation: number, vertical: boolean, ratio: number, side: number): PdfBounds {
    if (!Number.isFinite(ratio) || ratio < 0.05 || ratio > 0.95 || ![0,90,180,270].includes(rotation)) throw new Error(PdfErrorCode.INVALID);
    const out: PdfBounds = { left: box.left, right: box.right, top: box.top, bottom: box.bottom };
    const xAxis = vertical ? rotation % 180 === 0 : rotation % 180 !== 0;
    const lowFirst = vertical ? (rotation === 0 || rotation === 90) : (rotation === 90 || rotation === 180);
    const min = xAxis ? box.left : box.bottom;
    const max = xAxis ? box.right : box.top;
    if (max <= min) throw new Error(PdfErrorCode.INVALID);
    const split = lowFirst ? min + (max-min)*ratio : max - (max-min)*ratio;
    const low = side === 0 ? lowFirst : !lowFirst;
    // 只改变当前轴的可见边界；内容对象保持矢量和文字。
    if (xAxis) { if (low) out.right = split; else out.left = split; }
    else { if (low) out.top = split; else out.bottom = split; }
    return out;
  }
  /** 过滤文件名中的路径字符并保证扩展名一致。 */
  static outputName(value: string, extension: string): string {
    const stem = value.trim().replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').replace(/\.[^.]+$/, '').slice(0,100);
    return `${stem || 'result'}.${extension}`;
  }
}
