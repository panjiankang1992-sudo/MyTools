/**
 * 教材列表的纯展示与查询逻辑。
 *
 * 抽成模块的原因：项目没有组件挂载测试工具，界面渲染无法自动验证，
 * 但"查询串如何拼"与"元数据如何展示"是页面正确性的关键且可纯函数验证。
 */

/** 列表查询条件。 */
export interface TextbookListQueryInput {
  keyword?: string;
  subject?: string;
  grade?: number;
  semester?: string;
  status?: string;
  page?: number;
  pageSize?: number;
}

/**
 * 把查询条件拼成查询串，忽略空值。
 * 空字符串不应产生 `keyword=` 这样的空参数，否则后端会把空串当作有效筛选。
 *
 * @param query 查询条件
 * @returns 查询串
 */
export function buildTextbookQueryString(query: TextbookListQueryInput): string {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.subject) params.set('subject', query.subject);
  if (query.grade) params.set('grade', String(query.grade));
  if (query.semester) params.set('semester', query.semester);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page ?? 1));
  params.set('pageSize', String(query.pageSize ?? 20));
  return params.toString();
}

/** 列表项展示信息。 */
export interface TextbookDisplay {
  /** 主标题：教材名称（系列）。 */
  title: string;
  /** 副标题：出版社 · 版本 · 年级册次。 */
  subtitle: string;
  /** 内容规模。 */
  counts: string;
}

/** 当前版本声明元数据的相关字段。 */
interface EditionMetadata {
  series?: string;
  publisher?: string;
  editionLabel?: string;
  grade?: number;
  semester?: string;
  availableEntryCount?: number;
  expectedEntryCount?: number;
  /**
   * 收录范围。**注意字段名**：草稿里叫 `coverageDeclaration`（枚举 `partial`/`complete`），
   * 而**发布出去**的元数据里叫 `coverageStatus`（后端把它转成了 `CoverageStatus`）——
   * 两者不是同一个名字。列表读的是**已发布/草稿正文**里的那一份，所以用后者。
   */
  coverageStatus?: string;
}

const GRADE_NAMES = ['一', '二', '三', '四', '五', '六'];

/**
 * 由列表项生成展示文案。
 * 元数据缺失或不可解析时**退回显示教材标识**，绝不隐藏记录——
 * 列表漏掉一条已存在的教材比显示得难看严重得多。
 *
 * @param editionId    教材标识
 * @param metadataJson 当前版本元数据；草稿为 null
 * @returns 展示文案
 */
export function describeTextbook(editionId: string, metadataJson: string | null): TextbookDisplay {
  if (!metadataJson) {
    return { title: editionId, subtitle: '草稿（尚未发布）', counts: '—' };
  }
  let metadata: EditionMetadata;
  try {
    metadata = JSON.parse(metadataJson) as EditionMetadata;
  } catch {
    return { title: editionId, subtitle: '元数据不可解析', counts: '—' };
  }
  const gradeName = metadata.grade === undefined ? '' : (GRADE_NAMES[metadata.grade - 1] ?? String(metadata.grade));
  const semesterName = metadata.semester === 'second' ? '下册' : '上册';
  const countText = metadata.availableEntryCount === undefined
    ? '—'
    : `${metadata.availableEntryCount} 条${metadata.expectedEntryCount ? ` / 应收 ${metadata.expectedEntryCount}` : ''}`;
  // 收录范围：元数据里一直有 `coverageDeclaration`，但**界面此前从未渲染它**——
  // 于是"这册教材只收了一部分"这件事，管理员在列表上看不出来。
  const coverageText = metadata.coverageStatus === 'partial'
    ? '部分收录'
    : metadata.coverageStatus === 'complete' ? '完整收录' : '';
  const counts = [countText, coverageText].filter(Boolean).join(' · ');
  return {
    title: metadata.series ?? editionId,
    subtitle: [metadata.publisher, metadata.editionLabel, gradeName ? `${gradeName}年级 · ${semesterName}` : null]
      .filter(Boolean).join(' · '),
    counts
  };
}

/**
 * 英语只提供 3—6 年级。
 * 切换学科时要据此清理无效的年级筛选，避免出现"英语 1 年级"这种组合。
 *
 * @param subject 学科
 * @returns 可选年级
 */
export function gradesForSubject(subject: string | null | undefined): number[] {
  return subject === 'english' ? [3, 4, 5, 6] : [1, 2, 3, 4, 5, 6];
}
