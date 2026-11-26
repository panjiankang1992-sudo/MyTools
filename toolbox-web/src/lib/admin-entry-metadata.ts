import type { ListType } from '../domain/curriculum';
import type { DraftEntry } from './admin-textbook-draft';

/**
 * 条目的**分类与来源**元数据（设计 115 / 297 行）。
 *
 * 两件事都在这里做成纯逻辑，模板只做接线：
 * 1. 设计 115 行：「语文右侧有识字表、写字表、词语表筛选；**同一条可属于多个表类别**。
 *    类别只表达教材来源，不自动决定字帖题型」；
 * 2. 设计 297 行：「保留现有 `sourceRefs={sourceId,locator,page}`。编辑器可给当前范围
 *    设置默认来源和页码，再对少数条目调整；发布展开为明确引用」。
 *
 * 来源记录**不新增后端字段**：服务端 `AdminTextbookDraft.DraftEntry` 已有
 * `preserved`（不透明保留映射），`DraftToBookMapper.preservedSourceRefs`
 * 会把 `preserved.sourceRefs` 展开成正式的 `sourceRefs`。
 * 因此前端把来源写进 `preserved.sourceRefs` 即可走通发布链路。
 */

/** 界面上类别的固定顺序。 */
export const ADMIN_LIST_TYPE_ORDER: readonly ListType[] = ['recognition', 'writing', 'vocabulary', 'expression'];

/** 判断一个字符串是否是合法的表类别。 */
export function isListType(value: string): value is ListType {
  return (ADMIN_LIST_TYPE_ORDER as readonly string[]).includes(value);
}

/**
 * 取类别的中文名。
 *
 * 名称与公开端 `WorksheetCurriculumPicker` 的 `listLabels` 对齐
 * （`vocabulary` 在英语里叫"单词表"），避免同一个类别在两端叫不同名字。
 *
 * @param type    类别
 * @param subject 学科
 * @returns 中文名；未收录时回落成原始值
 */
export function listTypeLabel(type: ListType | string, subject: string): string {
  switch (type) {
    case 'recognition': return '识字表';
    case 'writing': return '写字表';
    case 'vocabulary': return subject === 'english' ? '单词表' : '词语表';
    case 'expression': return '表达与短语';
    default: return String(type);
  }
}

/** 过滤掉非法值并按固定顺序归一化；去重。 */
export function normalizeListTypes(values: readonly string[]): ListType[] {
  const present = new Set(values.filter(isListType));
  return ADMIN_LIST_TYPE_ORDER.filter(type => present.has(type));
}

/**
 * 勾选／取消一个类别。
 *
 * @param current 当前类别
 * @param type    要切换的类别
 * @returns 归一化后的新类别集合
 */
export function toggleListType(current: readonly string[], type: ListType): ListType[] {
  const has = current.includes(type);
  return normalizeListTypes(has ? current.filter(item => item !== type) : [...current, type]);
}

/**
 * 判断条目是否命中类别筛选。
 *
 * **空筛选 = 不过滤**（设计 115 行的"筛选"是视图动作，默认显示全部）。
 * 命中的语义是"该条**属于其中任意一个**所选类别"——同一条可属于多个表类别。
 *
 * @param entry  条目
 * @param filter 选中的类别；空数组表示不筛选
 * @returns 是否应显示
 */
export function matchesListTypeFilter(entry: DraftEntry, filter: readonly ListType[]): boolean {
  if (!filter.length) return true;
  const types = entry.listTypes ?? [];
  return filter.some(type => types.includes(type));
}

/** 把一批行设为同一组类别（"给当前范围设置默认类别"）。 */
export function setListTypesForRows(
  entries: readonly DraftEntry[],
  rowIds: readonly string[],
  listTypes: readonly string[]
): DraftEntry[] {
  const target = new Set(rowIds);
  const normalized = normalizeListTypes(listTypes);
  return entries.map(entry => (target.has(entry.rowId)
    ? { ...entry, listTypes: [...normalized] }
    : entry));
}

/** 切换单行的某一个类别，其余行不动。 */
export function toggleListTypeForRow(
  entries: readonly DraftEntry[],
  rowId: string,
  type: ListType
): DraftEntry[] {
  return entries.map(entry => (entry.rowId === rowId
    ? { ...entry, listTypes: toggleListType(entry.listTypes ?? [], type) }
    : entry));
}

/** 条目级来源定位（与服务端 `EntrySourceRef` 同形）。 */
export interface EntrySourceRef {
  sourceId: string;
  locator: string;
  page: number | null;
}

/** 空来源。 */
export const EMPTY_SOURCE_REF: EntrySourceRef = { sourceId: '', locator: '', page: null };

/** 来源是否为空（三项都空 = 没有来源信息）。 */
export function isSourceRefEmpty(ref: EntrySourceRef): boolean {
  return !ref.sourceId.trim() && !ref.locator.trim() && ref.page === null;
}

/** 从保留映射里读原始 `preserved`；返回浅拷贝，避免原地改动。 */
function preservedOf(entry: DraftEntry): Record<string, unknown> {
  return entry.preserved ? { ...entry.preserved } : {};
}

/** 写回保留映射；空映射时**删除该键**，不发送无意义的空对象。 */
function withPreserved(entry: DraftEntry, preserved: Record<string, unknown>): DraftEntry {
  const next: DraftEntry = { ...entry };
  if (Object.keys(preserved).length) {
    next.preserved = preserved;
  } else {
    delete next.preserved;
  }
  return next;
}

/** 把一条原始记录读成来源；结构不符时返回 null（**不猜测**）。 */
function readSourceRef(value: unknown): EntrySourceRef | null {
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  const sourceId = typeof record.sourceId === 'string' ? record.sourceId : '';
  const locator = typeof record.locator === 'string' ? record.locator : '';
  const page = typeof record.page === 'number' && Number.isFinite(record.page) ? Math.trunc(record.page) : null;
  return { sourceId, locator, page };
}

/**
 * 读取条目的全部来源记录。
 *
 * 只读不写：`preserved.sourceRefs` 里可能还有**界面上没有编辑入口**的其它字段，
 * 因此这里不能整包替换 `preserved`。
 *
 * @param entry 条目
 * @returns 来源记录；缺失或结构不符时返回空数组
 */
export function sourceRefsOf(entry: DraftEntry): EntrySourceRef[] {
  const raw = entry.preserved?.sourceRefs;
  if (!Array.isArray(raw)) return [];
  return raw.map(readSourceRef).filter((ref): ref is EntrySourceRef => ref !== null);
}

/**
 * 把来源记录写回 `preserved.sourceRefs`，**保留 `preserved` 里的其他字段**。
 *
 * @param entry 条目
 * @param refs  新的来源记录；空数组表示删除来源
 * @returns 新条目
 */
export function withSourceRefs(entry: DraftEntry, refs: readonly EntrySourceRef[]): DraftEntry {
  const preserved = preservedOf(entry);
  if (refs.length) {
    preserved.sourceRefs = refs.map(ref => ({
      sourceId: ref.sourceId.trim() || null,
      locator: ref.locator.trim() || null,
      page: ref.page
    }));
  } else {
    delete preserved.sourceRefs;
  }
  return withPreserved(entry, preserved);
}

/**
 * 取条目的"主来源"（第一条记录），供界面录入。
 *
 * 界面只维护**一条**主来源；若已有来源不止一条（来自导入的已发布教材），
 * 多出来的记录会被原样保留——这里只暴露第一条，不替用户丢弃数据。
 *
 * @param entry 条目
 * @returns 主来源；没有时返回空来源
 */
export function primarySourceRefOf(entry: DraftEntry): EntrySourceRef {
  return sourceRefsOf(entry)[0] ?? { ...EMPTY_SOURCE_REF };
}

/**
 * 设置条目的主来源。
 *
 * 语义边界（写清楚是为了不做出"静默丢数据"的行为）：
 * - 主来源填了内容 → 替换第一条，**其余记录保留**；
 * - 主来源被清空 → 删除第一条，其余记录原样前移。
 *
 * @param entry 条目
 * @param ref   主来源
 * @returns 新条目
 */
export function setPrimarySourceRef(entry: DraftEntry, ref: EntrySourceRef): DraftEntry {
  const rest = sourceRefsOf(entry).slice(1);
  return withSourceRefs(entry, isSourceRefEmpty(ref) ? rest : [{ ...ref }, ...rest]);
}

/** 给指定范围的行设置同一来源（设计 297 行："给当前范围设置默认来源和页码"）。 */
export function applySourceRefToRows(
  entries: readonly DraftEntry[],
  rowIds: readonly string[],
  ref: EntrySourceRef
): DraftEntry[] {
  const target = new Set(rowIds);
  return entries.map(entry => (target.has(entry.rowId) ? setPrimarySourceRef(entry, ref) : entry));
}

/**
 * 生成来源的可读说明，用于列表与预览提示。
 *
 * @param ref 来源
 * @returns 中文说明；空来源时返回"未填写来源"
 */
export function sourceRefLabel(ref: EntrySourceRef): string {
  if (isSourceRefEmpty(ref)) return '未填写来源';
  const parts: string[] = [];
  if (ref.sourceId.trim()) parts.push(ref.sourceId.trim());
  if (ref.page !== null) parts.push(`第 ${ref.page} 页`);
  if (ref.locator.trim()) parts.push(ref.locator.trim());
  return parts.join(' · ');
}
