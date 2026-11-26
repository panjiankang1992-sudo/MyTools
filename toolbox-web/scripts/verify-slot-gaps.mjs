#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
/** W43：原需求 20 册槽位的缺失/partial 展示核对。 */
const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const outDir = join(root, '..', 'docs', 'verification', 'worksheet-workflow-v2', 'samples');
const requirements = JSON.parse(await readFile(join(root, 'content', 'catalog', 'requirements.json'), 'utf8'));
const published = JSON.parse(await readFile(join(root, 'content', 'catalog', 'published.json'), 'utf8'));
// 与后端 CurriculumCatalog#coverageOf 一致：**按 subject/grade/semester 匹配已发布版本**
// 实时推导状态，而不是读 requirements.json 里那个从不更新的 status/editionId 字段。
const rows = requirements.slots.map(slot => {
  const matched = published.editions.filter(e => e.subject === slot.subject && e.grade === slot.grade && e.semester === slot.semester);
  const complete = matched.some(e => e.coverageStatus === 'complete');
  const actualCoverage = matched.length === 0 ? 'missing' : complete ? 'complete' : 'partial';
  return {
    slotId: slot.slotId, subject: slot.subject, grade: slot.grade, semester: slot.semester,
    storedStatusInRequirements: slot.status,
    editionIds: matched.map(e => e.editionId),
    actualCoverage,
    entryCount: matched.reduce((sum, e) => sum + (e.availableEntryCount ?? 0), 0)
  };
});
const summary = rows.reduce((acc, row) => { acc[row.actualCoverage] = (acc[row.actualCoverage] ?? 0) + 1; return acc; }, {});
const report = {
  generatedAt: new Date().toISOString(),
  requirement: '契约 W43③：检查原需求 20 册槽位的缺失/partial 展示，缺失如实展示、不回退样例或虚称全册',
  method: '与后端 CurriculumCatalog#coverageOf 一致，按 subject/grade/semester 实时推导（不读 requirements.json 的 status 字段）',
  slotCount: rows.length,
  summary,
  rows
};
await writeFile(join(outDir, 'slot-gaps.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
for (const row of rows) {
  console.log(`${row.slotId.padEnd(20)} 推导=${row.actualCoverage.padEnd(8)} 条目=${String(row.entryCount).padStart(4)} 版本=${row.editionIds.join(',') || '-'}`);
}
console.log('');
console.log(`槽位 ${rows.length} 个：${JSON.stringify(summary)}`);
console.log(`报告已写入：${join(outDir, 'slot-gaps.json')}`);
process.exit(rows.length === 20 ? 0 : 1);
