import type { CurriculumEntry, ListType, Semester, Subject, TextbookCatalog, TextbookEntriesPage, TextbookVersion } from '../domain/curriculum';
import { publicRequest } from './public-api';

/** 教材只读接口客户端。未发布的版本一律按不存在处理，不回退到示例数据。 */

/** 目录查询条件。 */
export interface CatalogQuery {
  subject?: Subject;
  grade?: number;
  semester?: Semester;
  region?: string;
}

/** 条目查询条件。 */
export interface EntryQuery {
  unitIds?: readonly string[];
  lessonIds?: readonly string[];
  listTypes?: readonly ListType[];
  cursor?: string | null;
  limit?: number;
}

const PAGE_LIMIT_MAX = 500;
const PAGE_SAFETY_LIMIT = 200;

/** 将数组参数编码为逗号分隔的查询串。 */
function joinParam(values: readonly string[] | undefined): string | null {
  if (!values?.length) return null;
  return values.join(',');
}

/** 读取已发布教材目录与 20 槽位覆盖状态。 */
export async function fetchTextbookCatalog(query: CatalogQuery = {}): Promise<TextbookCatalog> {
  const params = new URLSearchParams();
  if (query.subject) params.set('subject', query.subject);
  if (query.grade) params.set('grade', String(query.grade));
  if (query.semester) params.set('semester', query.semester);
  if (query.region) params.set('region', query.region);
  const suffix = params.size ? `?${params.toString()}` : '';
  // 目录决定后续版本地址，直接读取新响应，避免历史错误 ETag 使旧目录永久命中。
  return publicRequest<TextbookCatalog>(`/textbooks${suffix}`, { cache: 'no-store' });
}

/** 读取指定教材版本的元数据、单元与课文。 */
export async function fetchTextbookVersion(editionId: string, version: string): Promise<TextbookVersion> {
  return publicRequest<TextbookVersion>(`/textbooks/${encodeURIComponent(editionId)}/versions/${encodeURIComponent(version)}`);
}

/** 读取一页条目。 */
export async function fetchTextbookEntries(editionId: string, version: string, query: EntryQuery = {}): Promise<TextbookEntriesPage> {
  const params = new URLSearchParams();
  const units = joinParam(query.unitIds);
  const lessons = joinParam(query.lessonIds);
  const lists = joinParam(query.listTypes);
  if (units) params.set('unitIds', units);
  if (lessons) params.set('lessonIds', lessons);
  if (lists) params.set('listTypes', lists);
  if (query.cursor) params.set('cursor', query.cursor);
  params.set('limit', String(Math.min(query.limit ?? PAGE_LIMIT_MAX, PAGE_LIMIT_MAX)));
  return publicRequest<TextbookEntriesPage>(`/textbooks/${encodeURIComponent(editionId)}/versions/${encodeURIComponent(version)}/entries?${params.toString()}`);
}

/** 加载某个范围的全部条目；全册选择必须取完全部分页并核对版本一致。 */
export async function loadAllEntries(editionId: string, version: string, query: EntryQuery = {}): Promise<{ entries: CurriculumEntry[]; total: number; contentVersion: string }> {
  const entries: CurriculumEntry[] = [];
  let cursor: string | null = query.cursor ?? null;
  let total = 0;
  let contentVersion = version;
  for (let page = 0; page < PAGE_SAFETY_LIMIT; page += 1) {
    const result = await fetchTextbookEntries(editionId, version, { ...query, cursor });
    if (result.contentVersion !== version) throw new Error('CONTENT_VERSION_CHANGED');
    total = result.total;
    contentVersion = result.contentVersion;
    entries.push(...result.entries);
    cursor = result.nextCursor;
    if (!cursor) break;
  }
  if (cursor) throw new Error('PAGINATION_INCOMPLETE');
  if (entries.length !== total) throw new Error('PAGINATION_MISMATCH');
  return { entries, total, contentVersion };
}
