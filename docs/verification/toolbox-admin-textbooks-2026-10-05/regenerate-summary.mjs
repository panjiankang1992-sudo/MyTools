#!/usr/bin/env node
/**
 * 从正文机械地重建验收矩阵的汇总表。
 *
 * 为什么需要它：汇总表此前是**手工维护**的，随逐轮更新逐渐漂移——
 * 曾出现"写着 11 项通过、却列了 10 个编号"这种自相矛盾。
 * 让汇总由正文推导，可以让"两者不一致"在结构上不可能发生。
 *
 * 用法：node regenerate-summary.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const file = join(here, 'acceptance-matrix.md');
const source = readFileSync(file, 'utf8');

const items = [...source.matchAll(/^### ([ABCD]\d\d) .*? — \*\*(.+?)\*\*/gm)]
  .map(match => ({ code: match[1], status: match[2] }));
if (items.length !== 33) {
  throw new Error(`条目数应为 33，实际 ${items.length}：汇总不重建，避免掩盖缺漏`);
}

const pass = items.filter(i => i.status.startsWith('通过')).map(i => i.code);
const partial = items.filter(i => i.status.startsWith('部分')).map(i => i.code);
const rest = items.filter(i => !i.status.startsWith('通过') && !i.status.startsWith('部分')).map(i => i.code);
const fmt = list => (list.length ? list.join('、') : '（无）');

const summary = `| 状态 | 数量 | 编号 |
| --- | --- | --- |
| 通过 | ${pass.length} | ${fmt(pass)} |
| 部分 | ${partial.length} | ${fmt(partial)} |
| 未覆盖 | ${rest.length} | ${fmt(rest)} |

**结论：33 项中 ${pass.length} 项通过，${partial.length} 项部分通过，${rest.length} 项未覆盖。${pass.length === 33 ? '全部通过。' : '矩阵尚未完成，不能声称已验收。'}**
`;

const start = source.indexOf('| 状态 | 数量 | 编号 |');
const conclusion = source.indexOf('**结论：', start);
const end = source.indexOf('\n', conclusion) + 1;
writeFileSync(file, source.slice(0, start) + summary + source.slice(end));
console.log(summary);
