#!/usr/bin/env node
import { join, resolve } from 'node:path';
import { loadCatalog } from './curriculum-lib.mjs';

/**
 * 检查 20 个必需槽位是否全部达到 complete。
 * 默认在存在缺失或部分可用槽位时以非零退出，发布门禁必须使用默认行为。
 * 用法：node scripts/verify-curriculum-coverage.mjs [--root content] [--allow-partial]
 */

const args = process.argv.slice(2);
const allowPartial = args.includes('--allow-partial');
const rootFlag = args.indexOf('--root');
const contentRoot = resolve(rootFlag >= 0 ? args[rootFlag + 1] : join(process.cwd(), 'content'));

const { requirements, published } = await loadCatalog(contentRoot);
// 覆盖状态以已发布版本的 coverageStatus 为准，与公开接口的口径一致。
const editions = published.editions ?? [];

const rows = [];
let complete = 0;
let partial = 0;
let missing = 0;
for (const slot of requirements.slots ?? []) {
  const matches = editions.filter(item => item.subject === slot.subject && item.grade === slot.grade && item.semester === slot.semester);
  const status = matches.length
    ? (matches.some(item => item.coverageStatus === 'complete') ? 'complete' : 'partial')
    : 'missing';
  if (status === 'complete') complete += 1;
  else if (status === 'partial') partial += 1;
  else missing += 1;
  rows.push({
    slot: slot.slotId ?? `${slot.subject}-${slot.grade}-${slot.semester}`,
    subject: slot.subject,
    grade: slot.grade,
    semester: slot.semester,
    status,
    editionIds: matches.map(item => item.editionId)
  });
}

const report = {
  generatedAt: new Date().toISOString(),
  catalogVersion: published.catalogVersion ?? null,
  totalSlots: rows.length,
  complete,
  partial,
  missing,
  slots: rows
};
console.log(JSON.stringify(report, null, 2));

if (!allowPartial && complete !== rows.length) {
  console.error(`\n覆盖失败：${complete}/${rows.length} 册完成，缺失 ${missing} 册，部分可用 ${partial} 册。`);
  console.error('教材词库未完成核验时不得宣布全学段覆盖完成；开发阶段可使用 --allow-partial 输出报告。');
  process.exit(1);
}
if (complete !== rows.length) {
  console.log(`\n开发报告：${complete}/${rows.length} 册完成（存在缺失与部分可用，未达到发布门禁）。`);
} else {
  console.log(`\n覆盖通过：${complete}/${rows.length} 册完成。`);
}
