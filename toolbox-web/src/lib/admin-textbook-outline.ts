/**
 * 教材目录（单元／课文）的纯逻辑（设计文档 §3.4）。
 *
 * 抽成模块的原因与列表页相同：项目没有组件挂载测试工具，
 * 而"新增／重命名／排序／删除时如何维护归属"是正确性关键，且完全可纯函数验证。
 *
 * 一条硬规则：**删除单元必须把其下的课文与条目一起处理**，
 * 否则会留下指向不存在单元的悬空引用——发布时会被校验拒绝，但用户看不懂为什么。
 */

/** 目录中的单元。 */
export interface OutlineUnit {
  /** 正式标识；新建时为 null。 */
  unitId: string | null;
  /** 客户端临时标识；与 unitId 互斥。 */
  clientUnitId: string;
  title: string;
  order: number;
}

/** 目录中的课文。 */
export interface OutlineLesson {
  lessonId: string | null;
  clientLessonId: string;
  clientUnitId: string;
  title: string;
  order: number;
}

/** 目录树。 */
export interface Outline {
  units: OutlineUnit[];
  lessons: OutlineLesson[];
}

/** 未分单元的课文的归属常量；它不是一个真实单元。 */
export const NO_UNIT = '';

/**
 * 生成不与既有标识冲突的临时标识。
 *
 * @param existing 既有全部临时标识
 * @param prefix   前缀，如 `u` 或 `l`
 * @returns 新的临时标识
 */
export function nextClientId(existing: readonly string[], prefix: string): string {
  let index = existing.length + 1;
  const taken = new Set(existing);
  let candidate = `${prefix}${index}`;
  // 删除过节点后长度会减少，因此必须显式避让已有标识，不能只靠长度。
  while (taken.has(candidate)) {
    index += 1;
    candidate = `${prefix}${index}`;
  }
  return candidate;
}

/**
 * 新增单元，追加到末尾。
 *
 * @param outline 当前目录
 * @param title   单元名称
 * @returns 新目录
 */
export function addUnit(outline: Outline, title: string): Outline {
  const clientIds = outline.units.map(unit => unit.clientUnitId);
  const unit: OutlineUnit = {
    unitId: null,
    clientUnitId: nextClientId(clientIds, 'u'),
    title,
    order: outline.units.length + 1
  };
  return { units: [...outline.units, unit], lessons: outline.lessons };
}

/**
 * 在指定单元下新增课文。
 *
 * @param outline      当前目录
 * @param clientUnitId 所属单元；`NO_UNIT` 表示未分单元
 * @param title        课文标题
 * @returns 新目录
 */
export function addLesson(outline: Outline, clientUnitId: string, title: string): Outline {
  const clientIds = outline.lessons.map(lesson => lesson.clientLessonId);
  const siblings = outline.lessons.filter(lesson => lesson.clientUnitId === clientUnitId);
  const lesson: OutlineLesson = {
    lessonId: null,
    clientLessonId: nextClientId(clientIds, 'l'),
    clientUnitId,
    title,
    order: siblings.length + 1
  };
  return { units: outline.units, lessons: [...outline.lessons, lesson] };
}

/**
 * 重命名单元。
 *
 * @param outline      当前目录
 * @param clientUnitId 单元标识
 * @param title        新名称
 * @returns 新目录
 */
export function renameUnit(outline: Outline, clientUnitId: string, title: string): Outline {
  return {
    units: outline.units.map(unit => (unit.clientUnitId === clientUnitId ? { ...unit, title } : unit)),
    lessons: outline.lessons
  };
}

/**
 * 重命名课文。
 *
 * @param outline        当前目录
 * @param clientLessonId 课文标识
 * @param title          新标题
 * @returns 新目录
 */
export function renameLesson(outline: Outline, clientLessonId: string, title: string): Outline {
  return {
    units: outline.units,
    lessons: outline.lessons.map(lesson => (lesson.clientLessonId === clientLessonId ? { ...lesson, title } : lesson))
  };
}

/**
 * 删除单元，**连同其下的课文一起删除**。
 * 同时返回被移除的课文标识，供调用方清理条目归属。
 *
 * @param outline      当前目录
 * @param clientUnitId 单元标识
 * @returns 新目录与被移除的课文标识
 */
export function removeUnit(outline: Outline, clientUnitId: string): { outline: Outline; removedLessonIds: string[] } {
  const removed = outline.lessons.filter(lesson => lesson.clientUnitId === clientUnitId);
  const units = outline.units
    .filter(unit => unit.clientUnitId !== clientUnitId)
    .map((unit, index) => ({ ...unit, order: index + 1 }));
  return {
    outline: { units, lessons: outline.lessons.filter(lesson => lesson.clientUnitId !== clientUnitId) },
    removedLessonIds: removed.map(lesson => lesson.clientLessonId)
  };
}

/**
 * 删除课文。
 *
 * @param outline        当前目录
 * @param clientLessonId 课文标识
 * @returns 新目录
 */
export function removeLesson(outline: Outline, clientLessonId: string): Outline {
  return { units: outline.units, lessons: outline.lessons.filter(lesson => lesson.clientLessonId !== clientLessonId) };
}

/**
 * 在同级内上移或下移一个节点。
 *
 * @param outline 当前目录
 * @param kind    `unit` 或 `lesson`
 * @param id      节点标识
 * @param delta   -1 上移，1 下移
 * @returns 新目录
 */
export function moveNode(outline: Outline, kind: 'unit' | 'lesson', id: string, delta: number): Outline {
  if (kind === 'unit') {
    return { units: swap(outline.units, item => item.clientUnitId, id, delta), lessons: outline.lessons };
  }
  // 课文只在同一单元内移动，避免"上移"把课文挪到别的单元下。
  const target = outline.lessons.find(lesson => lesson.clientLessonId === id);
  if (!target) return outline;
  const others = outline.lessons.filter(lesson => lesson.clientUnitId !== target.clientUnitId);
  const siblings = swap(outline.lessons.filter(lesson => lesson.clientUnitId === target.clientUnitId),
    item => item.clientLessonId, id, delta);
  return { units: outline.units, lessons: [...others, ...siblings] };
}

/**
 * 在数组中交换相邻元素并重排 order。
 *
 * @param items  数组
 * @param keyOf 取标识
 * @param id     目标标识
 * @param delta  位移
 * @returns 重排后的数组
 */
function swap<T>(items: readonly T[], keyOf: (item: T) => string, id: string, delta: number): T[] {
  const list = [...items];
  const index = list.findIndex(item => keyOf(item) === id);
  const target = index + delta;
  if (index < 0 || target < 0 || target >= list.length) return list;
  const current = list[index] as T;
  list[index] = list[target] as T;
  list[target] = current;
  return list.map((item, position) => ({ ...item, order: position + 1 }));
}

/**
 * 按 order 排序后的树，供直接渲染。
 *
 * @param outline 当前目录
 * @returns 已排序的单元与其下课文
 */
export function renderTree(outline: Outline): Array<{ unit: OutlineUnit | null; lessons: OutlineLesson[] }> {
  const units = [...outline.units].sort((left, right) => left.order - right.order);
  const groups: Array<{ unit: OutlineUnit | null; lessons: OutlineLesson[] }> = units.map(unit => ({
    unit,
    lessons: outline.lessons
      .filter(lesson => lesson.clientUnitId === unit.clientUnitId)
      .sort((left, right) => left.order - right.order)
  }));
  const orphans = outline.lessons.filter(lesson => lesson.clientUnitId === NO_UNIT);
  if (orphans.length) {
    groups.push({ unit: null, lessons: [...orphans].sort((left, right) => left.order - right.order) });
  }
  return groups;
}

/**
 * 删除单元后，把其下条目的课文归属清空，单元归属交给调用方重新指定。
 *
 * @param entries         草稿条目
 * @param removedLessonIds 被移除的课文标识
 * @returns 清理后的条目
 */
export function detachRemovedLessons<T extends { clientLessonId: string | null }>(
  entries: readonly T[],
  removedLessonIds: readonly string[]
): T[] {
  const removed = new Set(removedLessonIds);
  return entries.map(entry => (entry.clientLessonId && removed.has(entry.clientLessonId)
    ? { ...entry, clientLessonId: null, lessonId: null }
    : entry));
}

/** 粘贴目标节点。 */
export interface ResolvedPasteTarget {
  clientUnitId: string;
  clientLessonId: string | null;
}

/**
 * 解析批量粘贴应写入的目标节点。
 *
 * **课文所属单元必须从目录里查，不能从"已有条目"里查**：
 * 一篇还没有任何条目的课文查不到单元，会退化成"未分单元"，
 * 于是新粘贴的内容被挂到一个**不属于该课文**的单元下——数据自相矛盾，
 * 而且要到发布校验时才以难懂的错误暴露。
 *
 * @param outline  当前目录
 * @param selected 当前选中的节点
 * @returns 粘贴目标
 */
export function resolvePasteTarget(
  outline: Outline,
  selected: { kind: 'all' | 'unit' | 'lesson'; id: string }
): ResolvedPasteTarget {
  if (selected.kind === 'unit') {
    return { clientUnitId: selected.id, clientLessonId: null };
  }
  if (selected.kind === 'lesson') {
    const lesson = outline.lessons.find(item => item.clientLessonId === selected.id);
    return { clientUnitId: lesson?.clientUnitId ?? '', clientLessonId: selected.id };
  }
  return { clientUnitId: '', clientLessonId: null };
}

/**
 * 删除单元后，把**该单元下所有条目**解绑到"未分单元"。
 *
 * 只清课文归属是不够的：条目会继续指向一个**已被删除的单元**，
 * 形成悬空引用——发布校验会以 `ENTRY_UNIT_UNKNOWN` 拒绝，而用户看不出为什么。
 *
 * 选择"移到未分单元"而不是"删除条目"，是因为**删除单元不该顺带毁掉用户已录入的内容**；
 * 解绑后内容仍在列表中可见、可重新指定归属。
 *
 * @param entries      全部条目
 * @param clientUnitId 被删除的单元标识
 * @returns 解绑后的条目
 */
export function detachRemovedUnit<T extends { clientUnitId: string | null; clientLessonId: string | null }>(
  entries: readonly T[],
  clientUnitId: string
): T[] {
  return entries.map(entry => (entry.clientUnitId === clientUnitId
    ? { ...entry, clientUnitId: '', clientLessonId: null }
    : entry));
}
