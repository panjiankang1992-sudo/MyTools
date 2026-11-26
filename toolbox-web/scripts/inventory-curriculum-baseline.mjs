#!/usr/bin/env node
import { readFile, writeFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 步骤 1：只读数据盘点。
 *
 * 目的：为后续 DB 迁移建立「旧教材 bytes / ID / 计数」基线，迁移前后据此对账。
 * 本脚本**只读**，不修改任何内容文件。
 */

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const contentRoot = join(root, 'content');
const outFile = join(root, '..', 'docs', 'verification', 'toolbox-admin-textbooks-2026-10-05', 'curriculum-baseline.json');

/** 计算字节级 sha256。 */
function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

const published = JSON.parse(await readFile(join(contentRoot, 'catalog', 'published.json'), 'utf8'));
const requirements = JSON.parse(await readFile(join(contentRoot, 'catalog', 'requirements.json'), 'utf8'));

const editions = [];
for (const edition of published.editions) {
  const dir = join(contentRoot, 'textbooks', edition.editionId, edition.contentVersion);
  const bookPath = join(dir, 'book.json');
  const bytes = await readFile(bookPath);
  const book = JSON.parse(bytes.toString('utf8'));
  // book.json 是扁平结构：units / lessons / entries 为平级数组，条目通过 unitId、lessonId 引用归属。
  const units = book.units ?? [];
  const lessons = book.lessons ?? [];
  const entries = book.entries ?? [];
  const entriesWithoutLesson = entries.filter(entry => !entry.lessonId).length;
  const listTypes = {};
  for (const entry of entries) {
    for (const type of entry.listTypes ?? []) listTypes[type] = (listTypes[type] ?? 0) + 1;
  }
  editions.push({
    editionId: edition.editionId,
    contentVersion: edition.contentVersion,
    path: `content/textbooks/${edition.editionId}/${edition.contentVersion}/book.json`,
    bytes: bytes.byteLength,
    sha256: sha256(bytes),
    declaredContentSha256: book.edition?.contentSha256 ?? null,
    manifestContentSha256: edition.contentSha256 ?? null,
    counts: { units: units.length, lessons: lessons.length, entries: entries.length, entriesWithoutLesson },
    listTypes,
    idSamples: {
      unitIds: units.slice(0, 2).map(u => u.unitId),
      lessonIds: lessons.slice(0, 2).map(l => l.lessonId),
      entryIds: entries.slice(0, 3).map(e => e.entryId)
    },
    firstEntryId: entries[0]?.entryId ?? null,
    lastEntryId: entries[entries.length - 1]?.entryId ?? null
  });
}

const report = {
  schemaVersion: 1,
  generatedAt: new Date().toISOString(),
  purpose: '步骤 1 只读数据盘点：迁移前基线，用于迁移后对账（原 bytes、ID、计数、哈希）。',
  readOnly: true,
  catalog: {
    publishedSha256: sha256(await readFile(join(contentRoot, 'catalog', 'published.json'))),
    requirementsSha256: sha256(await readFile(join(contentRoot, 'catalog', 'requirements.json'))),
    catalogVersion: published.catalogVersion ?? null,
    editionCount: published.editions.length,
    requirementSlotCount: requirements.slots.length,
    sourceRecordFiles: (await readdir(join(contentRoot, 'sources'))).sort()
  },
  totals: {
    editions: editions.length,
    entries: editions.reduce((sum, e) => sum + e.counts.entries, 0),
    units: editions.reduce((sum, e) => sum + e.counts.units, 0),
    lessons: editions.reduce((sum, e) => sum + e.counts.lessons, 0)
  },
  editions
};

await writeFile(outFile, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
for (const e of editions) {
  console.log(`${e.editionId.padEnd(22)} ${e.contentVersion}  ${String(e.counts.entries).padStart(4)} 条  ${e.counts.units} 单元 ${e.counts.lessons} 课  sha256=${e.sha256.slice(0, 12)}…`);
}
console.log('');
console.log(`合计：${report.totals.editions} 册 / ${report.totals.entries} 条 / ${report.totals.units} 单元 / ${report.totals.lessons} 课`);
console.log(`catalogVersion=${report.catalog.catalogVersion}；需求槽位=${report.catalog.requirementSlotCount}`);
console.log(`报告：${outFile}`);
