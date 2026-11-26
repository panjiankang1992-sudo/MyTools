#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { listPublishedBooks, loadCatalog, validateBook } from './curriculum-lib.mjs';

/**
 * 校验已发布内容包的 schema、引用、审核字段与哈希。
 * 用法：node scripts/validate-curriculum.mjs [--root content]
 */

const args = process.argv.slice(2);
const rootFlag = args.indexOf('--root');
const contentRoot = resolve(rootFlag >= 0 ? args[rootFlag + 1] : join(process.cwd(), 'content'));

const { root, requirements, published } = await loadCatalog(contentRoot);
const books = await listPublishedBooks(root, published);
const errors = [];
const warnings = [];

for (const item of books) {
  let raw;
  try {
    raw = await readFile(item.file);
  } catch {
    errors.push(`已发布清单中的 ${item.manifest.editionId}@${item.manifest.contentVersion} 缺少 book.json`);
    continue;
  }
  let book;
  try {
    book = JSON.parse(raw.toString('utf8'));
  } catch (cause) {
    errors.push(`${item.file} 不是合法 JSON：${cause.message}`);
    continue;
  }
  book.__manifestContentVersion = item.manifest.contentVersion;
  book.__manifestSha256 = item.manifest.contentSha256;
  const result = validateBook(book, requirements, raw);
  errors.push(...result.errors.map(message => `${item.manifest.editionId}: ${message}`));
  warnings.push(...result.warnings.map(message => `${item.manifest.editionId}: ${message}`));
}

const slots = requirements.slots ?? [];
const publishedSlots = new Set(books.map(item => `${item.manifest.editionId}`));
console.log(`内容根目录：${root}`);
console.log(`必需槽位：${slots.length}，已发布版本：${books.length}`);

if (warnings.length) {
  console.log(`\n警告（${warnings.length}）：`);
  for (const warning of warnings) console.log(`  - ${warning}`);
}
if (errors.length) {
  console.error(`\n校验失败（${errors.length}）：`);
  for (const error of errors) console.error(`  - ${error}`);
  process.exit(1);
}
console.log('\n校验通过：未发现结构、引用、校对或哈希问题。');
if (!publishedSlots.size) console.log('提示：当前没有任何已发布教材版本，覆盖校验会报告 0/20。');
