#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * W37：米字格与四线三格的几何验证。
 *
 * 直接解压 PDF 内容流并提取线段，判断格线结构是否符合契约：
 * 米字格 = 外框 + 横向中线 + 纵向中线 + 两条对角线；四线三格 = 四条水平线、相邻 3 mm。
 * 只报告**实测坐标**，不做「看起来像」的判断。
 */

const here = dirname(fileURLToPath(import.meta.url));
const samples = join(here, '..', '..', 'docs', 'verification', 'worksheet-workflow-v2', 'samples');

/** 从 PDF 中解压出全部内容流文本。 */
function extractStreams(file) {
  const buffer = readFileSync(file);
  const latin = buffer.toString('latin1');
  const streams = [];
  const pattern = /stream\r?\n/g;
  let match = pattern.exec(latin);
  while (match) {
    const start = match.index + match[0].length;
    const end = latin.indexOf('endstream', start);
    if (end === -1) break;
    const raw = buffer.subarray(start, end);
    try {
      streams.push(inflateSync(raw).toString('latin1'));
    } catch {
      streams.push(raw.toString('latin1'));
    }
    match = pattern.exec(latin);
  }
  return streams.join('\n');
}

/** 提取线段：记录起点与终点。 */
function extractSegments(content) {
  const segments = [];
  // 匹配「x y m ... x y l」这一类的路径构造。
  const tokens = content.split(/\s+/);
  let current = null;
  const numbers = [];
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (/^-?[\d.]+$/.test(token)) {
      numbers.push(Number(token));
      continue;
    }
    if (token === 'm' && numbers.length >= 2) {
      current = { x: numbers[numbers.length - 2], y: numbers[numbers.length - 1] };
    } else if (token === 'l' && numbers.length >= 2 && current) {
      const end = { x: numbers[numbers.length - 2], y: numbers[numbers.length - 1] };
      segments.push({ from: current, to: end });
      current = end;
    } else if (token === 're' && numbers.length >= 4) {
      // 矩形记为四条边。
      const [x, y, w, h] = numbers.slice(-4);
      const corners = [{ x, y }, { x: x + w, y }, { x: x + w, y: y + h }, { x, y: y + h }];
      for (let corner = 0; corner < 4; corner += 1) {
        segments.push({ from: corners[corner], to: corners[(corner + 1) % 4] });
      }
    } else if (!/^-?[\d.]+$/.test(token)) {
      numbers.length = 0;
      if (token !== 'm' && token !== 'l') current = null;
    }
  }
  return segments;
}

/** 把线段归类为水平 / 垂直 / 对角，并给出长度。 */
function classify(segment) {
  const dx = segment.to.x - segment.from.x;
  const dy = segment.to.y - segment.from.y;
  const length = Math.hypot(dx, dy);
  const tolerance = 0.3;
  if (Math.abs(dy) <= tolerance && Math.abs(dx) > tolerance) return { kind: 'horizontal', length, dx, dy };
  if (Math.abs(dx) <= tolerance && Math.abs(dy) > tolerance) return { kind: 'vertical', length, dx, dy };
  if (Math.abs(Math.abs(dx) - Math.abs(dy)) <= 2) {
    return { kind: 'diagonal', length, dx, dy, direction: dx * dy > 0 ? 'down-right' : 'up-right' };
  }
  return { kind: 'other', length, dx, dy };
}

/** 在给定格单元内统计各类线。 */
function analyzeCell(segments, originX, originY, size) {
  const inside = segments.filter(segment => {
    const minX = Math.min(segment.from.x, segment.to.x);
    const maxX = Math.max(segment.from.x, segment.to.x);
    const minY = Math.min(segment.from.y, segment.to.y);
    const maxY = Math.max(segment.from.y, segment.to.y);
    return minX >= originX - 0.5 && maxX <= originX + size + 0.5
      && minY >= originY - 0.5 && maxY <= originY + size + 0.5;
  });
  const classified = inside.map(segment => ({ ...segment, ...classify(segment) }));
  const near = (value, target, tolerance = 0.5) => Math.abs(value - target) <= tolerance;
  return {
    total: classified.length,
    // 外框：长边位于四条边界上。
    frame: classified.filter(item => item.kind !== 'diagonal' && near(item.length, size, 1.5)).length,
    // 中线：长度约为格宽一半且位于中线位置。
    center: classified.filter(item => item.kind !== 'diagonal'
      && near(item.length, size / 2, 1.5)
      && (near(Math.min(item.from.x, item.to.x), originX)
        || near(Math.min(item.from.y, item.to.y), originY))).length,
    diagonal: classified.filter(item => item.kind === 'diagonal').length,
    diagonals: classified.filter(item => item.kind === 'diagonal')
      .map(item => item.direction).sort()
  };
}

const report = { generatedAt: new Date().toISOString(), files: [] };

for (const name of ['page-chinese-dictation-student', 'page-chinese-pinyin-student', 'page-english-dictation-student', 'page-english-meaning-student', 'page-mi-grid-10mm', 'page-mi-grid-14mm']) {
  const file = join(samples, `${name}.pdf`);
  let content;
  try {
    content = extractStreams(file);
  } catch (error) {
    report.files.push({ file: `${name}.pdf`, error: String(error.message) });
    continue;
  }
  const segments = extractSegments(content);
  const classified = segments.map(segment => classify(segment));
  const counts = classified.reduce((acc, item) => { acc[item.kind] = (acc[item.kind] ?? 0) + 1; return acc; }, {});
  const diagonals = classified.filter(item => item.kind === 'diagonal');
  // 米字格的对角线应当成对出现且方向相反。
  const directions = diagonals.reduce((acc, item) => { acc[item.direction] = (acc[item.direction] ?? 0) + 1; return acc; }, {});
  // 找第一个完整的米字格单元并做结构判定。
  const firstCell = segments.length
    ? analyzeCell(segments, Math.min(...segments.flatMap(s => [s.from.x, s.to.x])), Math.min(...segments.flatMap(s => [s.from.y, s.to.y])), 12)
    : null;
  // 英语字帖：把水平线按 y 聚簇，簇内 4 条且间距均为 3mm 记为四线区，单条记为汉义横线。
  const horizontalYs = [...new Set(segments
    .filter(segment => Math.abs(segment.to.y - segment.from.y) < 0.3 && Math.abs(segment.to.x - segment.from.x) > 0.3)
    .map(segment => Number(segment.from.y.toFixed(2))))].sort((a, b) => a - b);
  const clusters = [];
  for (const y of horizontalYs) {
    const last = clusters[clusters.length - 1];
    if (last && Math.abs(y - last[last.length - 1]) < 12) last.push(y);
    else clusters.push([y]);
  }
  const threeMm = 8.5039;
  const fourLineClusters = clusters.filter(c => c.length === 4 && c.slice(1).every((y, i) => Math.abs(y - c[i] - threeMm) < 0.5));
  const singleLineClusters = clusters.filter(c => c.length === 1);
  // 米字格对角线长度 = 格宽 × √2；据此反推格宽并与配置值比较（契约 §6.2 误差 ≤0.5pt）。
  const MM_TO_PT = 72 / 25.4;
  const diagonalLengths = diagonals.map(item => Number(item.length.toFixed(4)));
  const impliedGridMm = diagonalLengths.length
    ? diagonalLengths.map(length => length / Math.SQRT2 / MM_TO_PT)
    : [];
  const configuredMm = name.endsWith('10mm') ? 10 : name.endsWith('14mm') ? 14 : 12;
  const gridWidthCheck = impliedGridMm.length
    ? {
      configuredMm,
      sampleImpliedMm: [...new Set(impliedGridMm.map(v => Number(v.toFixed(4))))].slice(0, 3),
      maxErrorPt: Number(Math.max(...impliedGridMm.map(v => Math.abs(v - configuredMm) * MM_TO_PT)).toFixed(4))
    }
    : null;
  report.files.push({
    file: `${name}.pdf`,
    gridWidthCheck,
    lineClusters: clusters.map(c => ({ lines: c.length, gapsPt: c.slice(1).map((y, i) => Number((y - c[i]).toFixed(3))) })),
    fourLineAreas: fourLineClusters.length,
    singleLines: singleLineClusters.length,
    segmentCount: segments.length,
    kinds: counts,
    diagonalDirections: directions,
    diagonalPairBalanced: (directions['down-right'] ?? 0) === (directions['up-right'] ?? 0),
    hasDiagonals: diagonals.length > 0,
    minX: segments.length ? Math.min(...segments.map(s => Math.min(s.from.x, s.to.x))) : null,
    maxX: segments.length ? Math.max(...segments.map(s => Math.max(s.from.x, s.to.x))) : null,
    firstCell
  });
}

writeFileSync(join(samples, 'grid-geometry.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
for (const item of report.files) {
  console.log(`${item.file}: 线段 ${item.segmentCount} 条 | ${JSON.stringify(item.kinds)}`);
  console.log(`  对角线：${item.hasDiagonals ? `有（${JSON.stringify(item.diagonalDirections)}），成对=${item.diagonalPairBalanced}` : '无'}`);
}
console.log(`报告：${join(samples, 'grid-geometry.json')}`);
