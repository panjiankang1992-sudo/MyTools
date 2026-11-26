import { createHash } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import { join, resolve } from 'node:path';

/** 教材内容包的读取与校验共享逻辑。 */

/** 按 UTF-8 计算字节的 SHA-256 十六进制摘要。 */
export function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

/** 读取并解析 JSON 文件，失败时抛出带路径的错误。 */
export async function readJson(path) {
  const raw = await readFile(path, 'utf8');
  try {
    return JSON.parse(raw);
  } catch (cause) {
    throw new Error(`${path} 不是合法 JSON：${cause.message}`);
  }
}

/** 统计字符串的 Unicode 码点数量，不按 UTF-16 长度切分。 */
export function codePoints(value) {
  return Array.from(value ?? '');
}

/** 读取并校验目录清单与已发布清单。 */
export async function loadCatalog(contentRoot) {
  const root = resolve(contentRoot);
  const requirements = await readJson(join(root, 'catalog', 'requirements.json'));
  const published = await readJson(join(root, 'catalog', 'published.json'));
  return { root, requirements, published };
}

/** 列出已发布版本的书籍目录与入口文件。 */
export async function listPublishedBooks(root, published) {
  const books = [];
  for (const item of published.editions ?? []) {
    const file = join(root, 'textbooks', item.editionId, item.contentVersion, 'book.json');
    books.push({ manifest: item, file });
  }
  return books;
}

/** 校验单个内容包，返回错误与警告列表。 */
export function validateBook(book, requirements, rawBuffer) {
  const errors = [];
  const warnings = [];
  const edition = book.edition ?? {};
  const slots = requirements.slots ?? [];
  const slot = slots.find(item => item.subject === edition.subject && item.grade === edition.grade && item.semester === edition.semester);

  // 1. 必须在必需槽位内，且年级符合学科规则。
  if (!slot) errors.push(`edition ${edition.editionId} 的学科/年级/册次不在 20 个必需槽位内`);
  if (edition.subject === 'chinese' && !(edition.grade >= 1 && edition.grade <= 6)) errors.push('语文年级必须为 1—6');
  if (edition.subject === 'english' && !(edition.grade >= 3 && edition.grade <= 6)) errors.push('英语年级必须为 3—6');
  for (const field of ['editionId', 'contentVersion', 'publisher', 'series', 'editionLabel', 'reviewStatus', 'coverageStatus']) {
    if (!edition[field]) errors.push(`edition 缺少字段 ${field}`);
  }
  if (edition.schemaVersion !== 1) errors.push('edition.schemaVersion 必须为 1');
  // 时间字段必须是带 Z 的 ISO 8601 UTC，后端按 Instant 解析。
  for (const field of ['verifiedAt', 'publishedAt']) {
    const value = edition[field];
    if (value === null || value === undefined) continue;
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/u.test(value)) {
      errors.push(`edition.${field} 必须是带 Z 的 ISO 8601 UTC 时间，实际为 ${value}`);
    }
  }
  if (edition.publishedAt && edition.reviewStatus !== 'published') errors.push('publishedAt 存在时 reviewStatus 必须为 published');

  // 2. 清单里的版本号与内容哈希必须与文件一致。
  if (edition.contentVersion !== book.__manifestContentVersion) errors.push('book.json 的 contentVersion 与 published.json 不一致');
  if (rawBuffer && sha256(rawBuffer) !== book.__manifestSha256) errors.push('book.json 的内容哈希与 published.json 不一致，请运行 build-curriculum 重新生成清单');

  const units = book.units ?? [];
  const lessons = book.lessons ?? [];
  const entries = book.entries ?? [];

  // 3. ID 唯一。
  const unitIds = new Set();
  for (const unit of units) {
    if (unitIds.has(unit.unitId)) errors.push(`单元 ID 重复：${unit.unitId}`);
    unitIds.add(unit.unitId);
    if (unit.editionId !== edition.editionId) errors.push(`单元 ${unit.unitId} 不属于当前版本`);
  }
  const lessonIds = new Set();
  for (const lesson of lessons) {
    if (lessonIds.has(lesson.lessonId)) errors.push(`课文 ID 重复：${lesson.lessonId}`);
    lessonIds.add(lesson.lessonId);
    if (!unitIds.has(lesson.unitId)) errors.push(`课文 ${lesson.lessonId} 引用了不存在的单元 ${lesson.unitId}`);
  }
  const entryIds = new Set();
  for (const item of entries) {
    if (entryIds.has(item.entryId)) errors.push(`条目 ID 重复：${item.entryId}`);
    entryIds.add(item.entryId);
    if (item.editionId !== edition.editionId) errors.push(`条目 ${item.entryId} 不属于当前版本`);
    if (!unitIds.has(item.unitId)) errors.push(`条目 ${item.entryId} 引用了不存在的单元 ${item.unitId}`);
    if (item.lessonId && !lessonIds.has(item.lessonId)) errors.push(`条目 ${item.entryId} 引用了不存在的课文 ${item.lessonId}`);
    if (!['character', 'word', 'phrase'].includes(item.form)) errors.push(`条目 ${item.entryId} 的 form 非法`);
    if (!Array.isArray(item.listTypes) || !item.listTypes.length) errors.push(`条目 ${item.entryId} 缺少 listTypes`);
    if (!codePoints(item.text).length) errors.push(`条目 ${item.entryId} 的 text 为空`);
    // 拼音片段必须与字素逐字对齐。
    const graphemes = codePoints(item.text);
    if (Array.isArray(item.pinyinSegments) && item.pinyinSegments.length) {
      if (item.pinyinSegments.length !== graphemes.length) errors.push(`条目 ${item.entryId} 的拼音片段数与汉字数不一致`);
      item.pinyinSegments.forEach((segment, index) => {
        if (segment.grapheme !== graphemes[index]) errors.push(`条目 ${item.entryId} 第 ${index + 1} 个拼音片段未与对应汉字对齐`);
        if (!segment.syllable) errors.push(`条目 ${item.entryId} 第 ${index + 1} 个拼音片段缺少读音`);
      });
    } else if (item.form === 'character' && edition.subject === 'chinese') {
      warnings.push(`条目 ${item.entryId} 是单字但没有逐字拼音片段`);
    }
    // 5. 发布状态必须有来源与审核记录，且不得存在阻断问题。
    if (edition.reviewStatus === 'published') {
      if (!Array.isArray(item.sourceRefs) || !item.sourceRefs.length) errors.push(`条目 ${item.entryId} 已发布但缺少来源定位`);
      if (Array.isArray(item.blockingIssues) && item.blockingIssues.length) errors.push(`条目 ${item.entryId} 存在阻断问题，不能发布`);
      if (item.reviewStatus !== 'reviewed' && item.reviewStatus !== 'published') errors.push(`条目 ${item.entryId} 未通过人工校对`);
    }
  }

  // 4. 排序必须稳定：同一父级下 order 不能重复。
  const orderKeys = new Set();
  for (const unit of units) {
    const key = `unit:${unit.order}`;
    if (orderKeys.has(key)) errors.push(`单元 order 重复：${unit.order}`);
    orderKeys.add(key);
  }
  const lessonOrders = new Set();
  for (const lesson of lessons) {
    const key = `${lesson.unitId}:${lesson.order}`;
    if (lessonOrders.has(key)) errors.push(`课文 order 在同一单元内重复：${lesson.unitId}/${lesson.order}`);
    lessonOrders.add(key);
  }

  if ((edition.coverageStatus === 'complete') && edition.expectedEntryCount !== null && entries.length !== edition.expectedEntryCount) {
    errors.push('coverageStatus=complete 时条目数量必须等于 expectedEntryCount');
  }
  if (edition.availableEntryCount !== entries.length) {
    errors.push(`availableEntryCount(${edition.availableEntryCount}) 与实际条目数量(${entries.length}) 不一致`);
  }
  return { errors, warnings };
}

/** 递归收集目录下的所有书籍文件路径，用于构建脚本。 */
export async function discoverBooks(root) {
  const base = join(root, 'textbooks');
  const results = [];
  let editionDirs = [];
  try {
    editionDirs = await readdir(base);
  } catch {
    return results;
  }
  for (const editionId of editionDirs) {
    const editionPath = join(base, editionId);
    if (!(await stat(editionPath)).isDirectory()) continue;
    for (const contentVersion of await readdir(editionPath)) {
      const file = join(editionPath, contentVersion, 'book.json');
      try {
        if ((await stat(file)).isFile()) results.push({ editionId, contentVersion, file });
      } catch { /* 不是完整的内容包，跳过。 */ }
    }
  }
  return results;
}
