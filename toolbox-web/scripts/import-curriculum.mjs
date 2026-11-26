#!/usr/bin/env node
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { loadCatalog, readJson, sha256 } from './curriculum-lib.mjs';
import { parseTable, detectDelimiter } from '../src/lib/csv.ts';

/**
 * 把已获授权的词表 CSV 导入为不可变内容包。
 *
 * 只有在提供来源记录（sourceId + locator）与审核信息后才会写入发布清单；
 * 脚本不会自行判定校对通过，也不会覆盖已发布的同名版本。
 *
 * 用法：
 *   node scripts/import-curriculum.mjs \
 *     --edition <editionId> --version <contentVersion> \
 *     --subject chinese --grade 1 --semester first \
 *     --publisher <出版社> --series <系列> --edition-label <版次> \
 *     --source <sourceId> --reviewed-by <审核人> --reviewed-at <YYYY-MM-DD> \
 *     --coverage partial --missing-scopes "第六至八单元识字表缺失|写字表少 5 字" \
 *     --input <词表.csv>
 */

const args = process.argv.slice(2);

/** 读取 --key value 形式的参数。 */
function option(name, required = true) {
  const index = args.indexOf(`--${name}`);
  const value = index >= 0 ? args[index + 1] : undefined;
  if (required && (!value || value.startsWith('--'))) {
    console.error(`缺少参数 --${name}`);
    process.exit(2);
  }
  return value;
}

const editionId = option('edition');
const contentVersion = option('version');
const subject = option('subject');
const grade = Number(option('grade'));
const semester = option('semester');
const input = option('input');
const rootFlag = args.indexOf('--root');
const contentRoot = resolve(rootFlag >= 0 ? args[rootFlag + 1] : join(process.cwd(), 'content'));

if (!['chinese', 'english'].includes(subject)) { console.error('--subject 只能是 chinese 或 english'); process.exit(2); }
if (subject === 'chinese' && !(grade >= 1 && grade <= 6)) { console.error('语文年级必须是 1—6'); process.exit(2); }
if (subject === 'english' && !(grade >= 3 && grade <= 6)) { console.error('英语年级必须是 3—6'); process.exit(2); }
if (!['first', 'second'].includes(semester)) { console.error('--semester 只能是 first 或 second'); process.exit(2); }

const { root, requirements } = await loadCatalog(contentRoot);
const slot = (requirements.slots ?? []).find(item => item.subject === subject && item.grade === grade && item.semester === semester);
if (!slot) { console.error('该学科/年级/册次不在 20 个必需槽位内'); process.exit(1); }

/** 把 YYYY-MM-DD 或完整 ISO 时间统一为契约要求的 UTC 瞬时字符串。 */
function toInstant(value) {
  if (/^\d{4}-\d{2}-\d{2}$/u.test(value)) return `${value}T00:00:00Z`;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    console.error(`--reviewed-at 不是合法日期：${value}`);
    process.exit(2);
  }
  return parsed.toISOString().replace(/\.\d{3}Z$/u, 'Z');
}

const reviewedAt = toInstant(option('reviewed-at'));
const raw = await readFile(input, 'utf8');
const rows = parseTable(raw, detectDelimiter(raw)).rows;
if (rows.length < 2) { console.error('CSV 至少需要表头与一行数据'); process.exit(1); }

const header = rows[0].map(cell => cell.trim().toLowerCase());
const column = (name) => {
  const index = header.indexOf(name);
  if (index < 0) { console.error(`CSV 缺少列 ${name}；实际列为 ${header.join(', ')}`); process.exit(1); }
  return index;
};
const unitColumn = column('unit');
const lessonColumn = header.indexOf('lesson');
const formColumn = column('form');
const listColumn = column('listtypes');
const textColumn = column('text');
const pinyinColumn = header.indexOf('pinyin');
const meaningColumn = header.indexOf('meaning');
const senseColumn = header.indexOf('senseid');
const posColumn = header.indexOf('partofspeech');

const units = [];
const lessons = [];
const entries = [];
const seenEntries = new Set();

rows.slice(1).forEach((cells, index) => {
  if (!cells.some(cell => cell.length)) return;
  const unitTitle = cells[unitColumn] ?? '';
  const lessonTitle = lessonColumn >= 0 ? (cells[lessonColumn] ?? '') : '';
  const text = (cells[textColumn] ?? '').normalize('NFC').trim();
  if (!unitTitle || !text) return;
  const unitId = `${editionId}-u${String(units.length + 1).padStart(2, '0')}`;
  let unit = units.find(item => item.title === unitTitle);
  if (!unit) {
    unit = { unitId, editionId, order: units.length + 1, title: unitTitle };
    units.push(unit);
  }
  let lessonId = null;
  if (lessonTitle) {
    const existing = lessons.find(item => item.unitId === unit.unitId && item.title === lessonTitle);
    if (existing) lessonId = existing.lessonId;
    else {
      lessonId = `${unit.unitId}-l${String(lessons.filter(item => item.unitId === unit.unitId).length + 1).padStart(2, '0')}`;
      lessons.push({ lessonId, unitId: unit.unitId, order: lessons.filter(item => item.unitId === unit.unitId).length + 1, title: lessonTitle });
    }
  }
  // 去重键必须包含单元、课文与清单类别：同一个字出现在不同课文或不同清单里
  // 是教材的两个条目，合并会丢失课次归属，也会让「按写字表筛选」漏字。
  const dedupeKey = JSON.stringify([text, cells[pinyinColumn] ?? '', cells[meaningColumn] ?? '',
    unit.unitId, lessonId ?? '', cells[listColumn] ?? '', cells[senseColumn] ?? '']);
  if (seenEntries.has(dedupeKey)) return;
  seenEntries.add(dedupeKey);
  const graphemes = Array.from(text);
  const syllables = (cells[pinyinColumn] ?? '').split(/\s+/u).filter(Boolean);
  entries.push({
    entryId: `${editionId}-${String(entries.length + 1).padStart(4, '0')}`,
    entryVersion: contentVersion,
    editionId,
    unitId: unit.unitId,
    lessonId,
    order: index,
    form: (cells[formColumn] ?? 'word').trim() || 'word',
    listTypes: (cells[listColumn] ?? 'vocabulary').split(/[；;|]/u).map(value => value.trim()).filter(Boolean),
    text,
    pinyinDisplay: pinyinColumn >= 0 && cells[pinyinColumn] ? cells[pinyinColumn].trim() : null,
    // 逐字对齐只在音节数与汉字数一致时生成，避免把不确定的读音硬拆。
    pinyinSegments: syllables.length === graphemes.length
      ? graphemes.map((grapheme, position) => ({ grapheme, syllable: syllables[position] }))
      : [],
    meaning: meaningColumn >= 0 && cells[meaningColumn] ? cells[meaningColumn].trim() : null,
    partOfSpeech: posColumn >= 0 && cells[posColumn] ? cells[posColumn].trim() : null,
    senseId: senseColumn >= 0 && cells[senseColumn] ? cells[senseColumn].trim() : `${editionId}-${index + 1}`,
    context: null,
    promptContext: null,
    acceptableAnswers: [],
    sourceRefs: [{ sourceId: option('source'), locator: `row ${index + 2}`, page: null }],
    reviewStatus: 'reviewed',
    blockingIssues: []
  });
});

if (!entries.length) { console.error('未解析出任何条目'); process.exit(1); }

const book = {
  edition: {
    editionId,
    subject,
    grade,
    semester,
    regionCodes: subject === 'chinese' ? ['jiangsu'] : ['jiangsu'],
    publisher: option('publisher'),
    series: option('series'),
    editionLabel: option('edition-label'),
    curriculumYear: null,
    isbn: null,
    sourceIds: [option('source')],
    schemaVersion: 1,
    contentVersion,
    contentSha256: '',
    reviewStatus: 'published',
    coverageStatus: option('coverage') ?? 'partial',
    // 已知缺口必须显式写入清单，界面与接口据此提示「部分可用」。
    missingScopes: (option('missing-scopes', false) ?? '').split('|').map(v => v.trim()).filter(Boolean),
    verifiedAt: reviewedAt,
    publishedAt: new Date().toISOString(),
    availableEntryCount: entries.length,
    expectedEntryCount: entries.length
  },
  units,
  lessons,
  entries
};

const targetDir = join(root, 'textbooks', editionId, contentVersion);
const targetFile = join(targetDir, 'book.json');
try {
  await readFile(targetFile);
  console.error(`目标版本已存在且不可覆盖：${targetFile}\n请提升 --version 后重试。`);
  process.exit(1);
} catch { /* 目录不存在，继续创建。 */ }

const body = `${JSON.stringify(book, null, 2)}\n`;
await mkdir(dirname(targetFile), { recursive: true });
await writeFile(targetFile, body, 'utf8');
console.log(`已写入 ${targetFile}`);
console.log(`单元 ${units.length}，课文 ${lessons.length}，条目 ${entries.length}`);
console.log(`正文 SHA-256：${sha256(Buffer.from(body, 'utf8'))}`);
console.log('下一步：运行 npm run build:curriculum 生成发布清单，再运行 npm run test:curriculum 复核。');
