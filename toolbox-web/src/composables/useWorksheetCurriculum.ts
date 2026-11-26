import { computed, ref } from 'vue';
import { gradesForSubject, isUsableEdition, type CurriculumEntry, type ListType, type Semester, type Subject, type TextbookCatalog, type TextbookVersion } from '../domain/curriculum';
import { PublicApiError } from '../services/public-api';
import { fetchTextbookCatalog, fetchTextbookVersion, loadAllEntries } from '../services/curriculum-api';

/** 教材选择只产生待导入内容，不接触字帖全局题型或现有原文。 */
export interface WorksheetCurriculumApi {
  catalog: typeof fetchTextbookCatalog;
  version: typeof fetchTextbookVersion;
  entries: typeof loadAllEntries;
}

/** 完整教材身份及选中的条目，供统一输入流程追加导入。 */
export interface WorksheetCurriculumImport {
  entries: CurriculumEntry[];
  editionId: string;
  contentVersion: string;
  label: string;
  grade: number;
  semester: Semester;
}

/** 使用真实目录名称；缺名或误把内部标识当名称时仅降级为序号。 */
export function curriculumScopeLabel(title: string | undefined, id: string, order: number, kind: 'unit' | 'lesson'): string {
  const value = title?.trim();
  if (!value || value === id || /^(?:cn|en|zh)-[a-z0-9-]+$/i.test(value)) return `第 ${order} ${kind === 'unit' ? '单元' : '课'}`;
  return value;
}

/** 组合教材名、年级和册次；界面不展示内容版本的内部标识。 */
export function curriculumEditionLabel(edition: TextbookVersion['edition']): string {
  const names = ['', '一', '二', '三', '四', '五', '六'];
  const gradeName = names[edition.grade] ?? String(edition.grade);
  const originalLabel = edition.editionLabel.trim();
  // 已发布元数据损坏时只保留可证实的年份版次前缀，不猜测损坏的修订说明。
  const readableLabel = originalLabel.includes('\uFFFD')
    ? originalLabel.match(/^(\d{4}\s*年(?:第\s*\d+\s*)?版)/u)?.[1] ?? ''
    : originalLabel === edition.editionId || /^(?:cn|en|zh)-[a-z0-9-]+$/i.test(originalLabel) ? '' : originalLabel;
  return [edition.series, `${gradeName}年级${edition.semester === 'first' ? '上册' : '下册'}`, readableLabel].filter(Boolean).join(' · ');
}

/** 创建可独立测试的教材范围选择状态；接口仅访问已发布目录。 */
export function useWorksheetCurriculum(subject: Subject, api: WorksheetCurriculumApi = {
  catalog: fetchTextbookCatalog, version: fetchTextbookVersion, entries: loadAllEntries
}) {
  const catalog = ref<TextbookCatalog | null>(null);
  const grade = ref(gradesForSubject(subject)[0]!);
  const semester = ref<Semester>('first');
  const editionId = ref<string | null>(null);
  const version = ref<TextbookVersion | null>(null);
  const entries = ref<CurriculumEntry[]>([]);
  const listTypes = ref<ListType[]>([]);
  const scopes = ref<string[]>([]);
  const loading = ref(false);
  const catalogLoading = ref(false);
  const error = ref('');
  let requestId = 0;
  let catalogRequestId = 0;

  const gradeOptions = gradesForSubject(subject).map(value => ({ label: `${value} 年级`, value }));
  const editions = computed(() => (catalog.value?.editions ?? []).filter(edition =>
    edition.subject === subject && edition.grade === grade.value && edition.semester === semester.value && isUsableEdition(edition)));
  const coverage = computed(() => catalog.value?.coverage.find(slot =>
    slot.subject === subject && slot.grade === grade.value && slot.semester === semester.value) ?? null);
  const selectedEdition = computed(() => editions.value.find(edition => edition.editionId === editionId.value) ?? null);
  const scopeTree = computed(() => (version.value?.units ?? []).slice().sort((a, b) => a.order - b.order).map(unit => ({
    key: `unit:${unit.unitId}`, label: curriculumScopeLabel(unit.title, unit.unitId, unit.order, 'unit'),
    // 英语单词表按单元选择；仅语文继续显示课文层级。
    children: subject === 'english' ? undefined : (version.value?.lessons ?? []).filter(lesson => lesson.unitId === unit.unitId)
      .sort((a, b) => a.order - b.order).map(lesson => ({ key: `lesson:${lesson.lessonId}`, label: curriculumScopeLabel(lesson.title, lesson.lessonId, lesson.order, 'lesson') }))
  })));
  const selectedEntries = computed(() => {
    const scopeSet = new Set(scopes.value);
    // 英语教材来源固定为原书单词表，接口混入的表外内容不能导入。
    const kinds = new Set<ListType>(subject === 'english' ? ['vocabulary'] : listTypes.value);
    const unitOrder = new Map((version.value?.units ?? []).map(unit => [unit.unitId, unit.order]));
    const lessonOrder = new Map((version.value?.lessons ?? []).map(lesson => [lesson.lessonId, lesson.order]));
    // 发布发生在教材版本层；该已发布版本内的条目允许已校对或已发布状态。
    return entries.value.filter(entry => (entry.reviewStatus === 'reviewed' || entry.reviewStatus === 'published') && !entry.blockingIssues.length
      && entry.listTypes.some(kind => kinds.has(kind))
      && (scopeSet.has(`unit:${entry.unitId}`) || (entry.lessonId !== null && scopeSet.has(`lesson:${entry.lessonId}`))))
      .sort((a, b) => (unitOrder.get(a.unitId) ?? 0) - (unitOrder.get(b.unitId) ?? 0)
        || (lessonOrder.get(a.lessonId ?? '') ?? 0) - (lessonOrder.get(b.lessonId ?? '') ?? 0)
        || a.order - b.order || a.entryId.localeCompare(b.entryId));
  });

  /** 切换或清除版本时立即作废旧请求及其可导入内容。 */
  function resetVersion(): number {
    requestId += 1;
    version.value = null;
    entries.value = [];
    listTypes.value = [];
    scopes.value = [];
    loading.value = false;
    error.value = '';
    return requestId;
  }

  /** 加载指定版本，迟到响应不得改变当前版本或可导入条目。 */
  async function loadEdition(value: string | null, refreshAllowed = true): Promise<void> {
    const ticket = resetVersion();
    editionId.value = value;
    const edition = editions.value.find(item => item.editionId === value);
    if (!edition) return;
    loading.value = true;
    try {
      const [detail, loaded] = await Promise.all([
        api.version(edition.editionId, edition.contentVersion),
        api.entries(edition.editionId, edition.contentVersion, subject === 'english' ? { listTypes: ['vocabulary'] } : {})
      ]);
      if (ticket !== requestId) return;
      // 身份必须与目录一致，不能把缓存中的另一版本错误标成当前教材。
      if (detail.edition.editionId !== edition.editionId || detail.edition.contentVersion !== edition.contentVersion
        || loaded.contentVersion !== edition.contentVersion || loaded.entries.some(entry => entry.editionId !== edition.editionId)) {
        throw new Error('CONTENT_VERSION_CHANGED');
      }
      version.value = detail;
      entries.value = loaded.entries;
      listTypes.value = subject === 'english' ? ['vocabulary'] : [...detail.listTypes];
      // 仅加载可选目录，练习范围由用户主动勾选或全选。
      scopes.value = [];
    } catch (cause) {
      if (ticket !== requestId) return;
      // 页面打开后教材可能已重新发布；仅版本失效时更新目录并最多重试一次。
      const staleVersion = (cause instanceof PublicApiError && cause.code === 'TOOLBOX_003')
        || (cause instanceof Error && cause.message === 'CONTENT_VERSION_CHANGED');
      if (refreshAllowed && staleVersion) {
        try {
          const refreshed = await api.catalog({ subject });
          if (ticket !== requestId) return;
          catalog.value = refreshed;
          const replacement = editions.value.find(item => item.editionId === value);
          if (replacement && replacement.contentVersion !== edition.contentVersion) {
            await loadEdition(value, false);
            return;
          }
        } catch {
          // 刷新失败时继续显示可重试错误，不能复用此前的可导入内容。
        }
      }
      if (ticket === requestId) error.value = '\u6559\u6750\u5185\u5bb9\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002\u73b0\u6709\u539f\u6587\u5df2\u4fdd\u7559\u3002';
    } finally {
      if (ticket === requestId) loading.value = false;
    }
  }

  /** 选择教材时允许一次目录更新恢复，事件参数不参与内部重试控制。 */
  async function chooseEdition(value: string | null): Promise<void> {
    await loadEdition(value);
  }

  /** 切换年级后选择该册第一个已发布版本；缺失册次保持空态。 */
  async function chooseGrade(value: number): Promise<void> {
    grade.value = value;
    await chooseEdition(editions.value[0]?.editionId ?? null);
  }

  /** 切换上下册，不沿用旧册的范围或条目。 */
  async function chooseSemester(value: Semester): Promise<void> {
    semester.value = value;
    await chooseEdition(editions.value[0]?.editionId ?? null);
  }

  /** 加载学科目录，不假设一年级或三年级一定有可用内容。 */
  async function loadCatalog(): Promise<void> {
    const ticket = ++catalogRequestId;
    catalogLoading.value = true;
    error.value = '';
    try {
      const result = await api.catalog({ subject });
      if (ticket !== catalogRequestId) return;
      catalog.value = result;
      // 重试保留当前教材选择，只有当前教材已移除时才选择该册首个可用版本。
      await chooseEdition(editions.value.find(item => item.editionId === editionId.value)?.editionId ?? editions.value[0]?.editionId ?? null);
    } catch {
      if (ticket === catalogRequestId) error.value = '教材目录加载失败，可重试或直接输入内容。';
    } finally {
      if (ticket === catalogRequestId) catalogLoading.value = false;
    }
  }

  /** 全选当前已加载教材范围；字表选择独立保留。 */
  function selectAllScopes(): void {
    scopes.value = (version.value?.units ?? []).map(unit => `unit:${unit.unitId}`);
  }

  /** 取得同步快照；缺失范围、版本或加载中的内容不能导入。 */
  function getImport(): WorksheetCurriculumImport | null {
    const edition = selectedEdition.value;
    if (loading.value || !edition || !version.value || !selectedEntries.value.length) return null;
    return {
      entries: [...selectedEntries.value], editionId: edition.editionId, contentVersion: edition.contentVersion,
      label: curriculumEditionLabel(edition),
      grade: edition.grade, semester: edition.semester
    };
  }

  return { grade, semester, editionId, version, listTypes, scopes, loading, catalogLoading, error, catalog,
    gradeOptions, editions, coverage, selectedEdition, scopeTree, selectedEntries,
    chooseGrade, chooseSemester, chooseEdition, loadCatalog, selectAllScopes, getImport };
}
