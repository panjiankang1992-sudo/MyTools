#!/usr/bin/env node
/**
 * 检查验收矩阵的**标题与正文是否一致**。
 *
 * 为什么需要它：第 95 轮发现 A09 的标题格式正确、但**正文是更早的一版**，
 * 而当时的 `regenerate-summary.mjs` 只比对标题，看不见这种情况。
 * 缺口在哪，守卫就只守到哪——所以这里补上"标题 vs 正文"的比对。
 *
 * 用法：node check-consistency.mjs
 * 退出码 1 表示存在不一致（便于接入自动化）。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, 'acceptance-matrix.md'), 'utf8');
const sections = [...source.matchAll(/^### ([ABCD]\d\d) (.*?) — \*\*(.+?)\*\*([\s\S]*?)(?=^### |^## |\Z)/gm)];

if (sections.length !== 33) {
  console.error(`条目数应为 33，实际 ${sections.length}`);
  process.exit(1);
}

const problems = [];
for (const [, code, title, status, body] of sections) {
  const hasGapMarker = /❌|⏸|⚠️/.test(body);
  // **限定标题**（如"通过（服务端）"）表示"在某范围内通过，范围外仍有缺口"，
  // 因此正文出现缺口标记是**预期**的，不算不一致。
  const qualified = /（.+）/.test(status);
  const pass = status.startsWith('通过');
  const partial = status.startsWith('部分');
  const uncovered = status.startsWith('未覆盖');

  if (pass && !qualified && hasGapMarker) {
    problems.push(`${code}（${title.trim()}）：标题为无限定的"通过"，但正文仍有 ❌／⏸／⚠️——`
      + `要么把标题限定为"通过（某范围）"，要么补齐正文缺口`);
  }
  if ((partial || uncovered) && !hasGapMarker) {
    problems.push(`${code}（${title.trim()}）：标题为"${status}"，但正文没有任何缺口标记`);
  }
  // 正文过短通常意味着"标题改了、正文没跟上"。
  // 阈值取 120 字符：一条真正的条目至少要能列出 1—2 个子条件。
  if (body.trim().length < 120) {
    problems.push(`${code}（${title.trim()}）：正文仅 ${body.trim().length} 字符，可能未同步更新`);
  }
}

if (problems.length) {
  console.error(`发现 ${problems.length} 处标题与正文不一致：`);
  for (const p of problems) console.error(' -', p);
  process.exit(1);
}
console.log('33 条标题与正文一致。');
