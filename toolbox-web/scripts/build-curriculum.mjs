#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { discoverBooks, loadCatalog, readJson, sha256 } from './curriculum-lib.mjs';

/**
 * 依据 textbooks 目录重建 published.json 的内容哈希。
 * 已发布且内容变化的版本必须提升 contentVersion，禁止原地覆盖。
 * 用法：node scripts/build-curriculum.mjs [--root content]
 */

const args = process.argv.slice(2);
const rootFlag = args.indexOf('--root');
const contentRoot = resolve(rootFlag >= 0 ? args[rootFlag + 1] : join(process.cwd(), 'content'));

const { root, requirements, published } = await loadCatalog(contentRoot);
const allDiscovered = await discoverBooks(root);

/** 版本号比较：仅按数字段比较，保证 1.0.10 > 1.0.9。 */
function compareVersions(left, right) {
  const a = String(left).split('.').map(Number);
  const b = String(right).split('.').map(Number);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    const diff = (a[index] ?? 0) - (b[index] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

// 同一版本号只发布最新的 contentVersion；旧版本目录保留作为审计产物，但不进发布清单。
const latestByEdition = new Map();
for (const item of allDiscovered) {
  const current = latestByEdition.get(item.editionId);
  if (!current || compareVersions(item.contentVersion, current.contentVersion) > 0) latestByEdition.set(item.editionId, item);
}
const discovered = [...latestByEdition.values()];
const previous = new Map((published.editions ?? []).map(item => [`${item.editionId}@${item.contentVersion}`, item]));
const editions = [];
const problems = [];

for (const item of discovered) {
  const raw = await readFile(item.file);
  const book = JSON.parse(raw.toString('utf8'));
  const digest = sha256(raw);
  const existing = previous.get(`${item.editionId}@${item.contentVersion}`);
  if (existing && existing.contentSha256 !== digest) {
    problems.push(`${item.editionId}@${item.contentVersion} 已发布但内容发生变化，请提升 contentVersion 后重新构建`);
    continue;
  }
  if (book.edition?.contentVersion !== item.contentVersion) {
    problems.push(`${item.editionId}/${item.contentVersion} 的 book.json 与目录版本号不一致`);
    continue;
  }
  if (book.edition?.reviewStatus !== 'published') {
    problems.push(`${item.editionId}@${item.contentVersion} 的 reviewStatus 不是 published，不能发布`);
    continue;
  }
  // editions 的元素是完整 TextbookEdition，正文哈希以本次构建结果为准。
  editions.push({ ...book.edition, contentSha256: digest });
}

if (problems.length) {
  console.error('构建失败：');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

const repositorySources = await readJson(join(root, 'sources', 'catalog-sources.json')).catch(() => ({ sources: [] }));
const unchanged = editions.length === (published.editions ?? []).length
  && editions.every(item => (published.editions ?? []).some(previous => previous.editionId === item.editionId && previous.contentSha256 === item.contentSha256));
const manifest = {
  schemaVersion: published.schemaVersion ?? 1,
  catalogVersion: published.catalogVersion ?? '0.0.0',
  // 内容未变化时保留原发布时间，避免无意义地改动清单。
  publishedAt: unchanged && published.publishedAt ? published.publishedAt : new Date().toISOString(),
  note: published.note,
  sourceCount: repositorySources.sources?.length ?? 0,
  editions: editions.sort((left, right) => left.editionId.localeCompare(right.editionId))
};
await writeFile(join(root, 'catalog', 'published.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
console.log(`已写入 ${join(root, 'catalog', 'published.json')}，共 ${manifest.editions.length} 个已发布版本。`);
if (!manifest.editions.length) {
  console.log('提示：未发现可发布的内容包，清单为空是预期结果。');
}
