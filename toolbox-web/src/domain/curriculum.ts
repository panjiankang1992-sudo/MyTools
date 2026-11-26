/**
 * 教材数据模型，字段与实施契约 3.1 节一一对应。
 * 这些类型既用于接口响应，也用于发布产物的本地校验。
 */

/** 学科。 */
export type Subject = 'chinese' | 'english';

/** 册次。 */
export type Semester = 'first' | 'second';

/** 内容校对状态。 */
export type ReviewStatus = 'draft' | 'reviewed' | 'published';

/** 覆盖状态，missing 表示该册尚未发布任何内容。 */
export type CoverageStatus = 'missing' | 'partial' | 'complete';

/** 条目形态。 */
export type EntryForm = 'character' | 'word' | 'phrase';

/** 原教材清单类别。 */
export type ListType = 'recognition' | 'writing' | 'vocabulary' | 'expression';

/** 教材版本元数据。 */
export interface TextbookEdition {
  editionId: string;
  subject: Subject;
  grade: number;
  semester: Semester;
  regionCodes: string[];
  publisher: string;
  series: string;
  editionLabel: string;
  curriculumYear: number | null;
  isbn: string | null;
  sourceIds: string[];
  schemaVersion: 1;
  contentVersion: string;
  contentSha256: string;
  reviewStatus: ReviewStatus;
  coverageStatus: CoverageStatus;
  verifiedAt: string;
  publishedAt: string | null;
  availableEntryCount: number;
  expectedEntryCount: number | null;
  missingScopes: string[];
}

/** 单元。 */
export interface CurriculumUnit {
  unitId: string;
  editionId: string;
  order: number;
  title: string;
}

/** 课文。 */
export interface CurriculumLesson {
  lessonId: string;
  unitId: string;
  order: number;
  title: string;
}

/** 条目来源定位。 */
export interface EntrySourceRef {
  sourceId: string;
  locator: string;
  page: number | null;
}

/** 逐字拼音对齐片段。 */
export interface PinyinSegment {
  grapheme: string;
  syllable: string;
}

/** 教材条目。 */
export interface CurriculumEntry {
  entryId: string;
  entryVersion: string;
  editionId: string;
  unitId: string;
  lessonId: string | null;
  order: number;
  form: EntryForm;
  listTypes: ListType[];
  text: string;
  pinyinDisplay: string | null;
  pinyinSegments: PinyinSegment[];
  meaning: string | null;
  partOfSpeech: string | null;
  senseId: string;
  context: string | null;
  promptContext: string | null;
  acceptableAnswers: string[];
  sourceRefs: EntrySourceRef[];
  reviewStatus: ReviewStatus;
  blockingIssues: string[];
}

/** 20 个必需槽位中的一个。 */
export interface CoverageSlot {
  subject: Subject;
  grade: number;
  semester: Semester;
  coverageStatus: CoverageStatus;
  editionIds: string[];
}

/** 教材目录接口响应。 */
export interface TextbookCatalog {
  catalogVersion: string;
  editions: TextbookEdition[];
  coverage: CoverageSlot[];
}

/** 指定版本的完整内容。 */
export interface TextbookVersion {
  edition: TextbookEdition;
  units: CurriculumUnit[];
  lessons: CurriculumLesson[];
  listTypes: ListType[];
  entryCount: number;
  sha256: string;
}

/** 条目分页响应。 */
export interface TextbookEntriesPage {
  entries: CurriculumEntry[];
  nextCursor: string | null;
  total: number;
  contentVersion: string;
}

/** 语文年级范围。 */
export const CHINESE_GRADES = [1, 2, 3, 4, 5, 6] as const;

/** 英语年级范围。 */
export const ENGLISH_GRADES = [3, 4, 5, 6] as const;

/** 按学科返回合法年级列表。 */
export function gradesForSubject(subject: Subject): number[] {
  return subject === 'chinese' ? [...CHINESE_GRADES] : [...ENGLISH_GRADES];
}

/** 判断条目是否存在阻断发布的校对问题。 */
export function hasBlockingIssues(entry: CurriculumEntry): boolean {
  return entry.blockingIssues.length > 0;
}

/** 生成稳定的单元排序键，不依赖接口返回顺序。 */
export function compareUnits(left: CurriculumUnit, right: CurriculumUnit): number {
  return left.order - right.order || left.unitId.localeCompare(right.unitId);
}

/** 生成稳定的课文排序键。 */
export function compareLessons(left: CurriculumLesson, right: CurriculumLesson): number {
  return left.unitId.localeCompare(right.unitId) || left.order - right.order || left.lessonId.localeCompare(right.lessonId);
}

/** 生成稳定的条目排序键：单元、课文、条目顺序、条目 ID。 */
export function compareEntries(left: CurriculumEntry, right: CurriculumEntry): number {
  return left.unitId.localeCompare(right.unitId)
    || (left.lessonId ?? '').localeCompare(right.lessonId ?? '')
    || left.order - right.order
    || left.entryId.localeCompare(right.entryId);
}

/** 将覆盖槽位整理为“学科-年级-册次”的稳定键。 */
export function coverageKey(subject: Subject, grade: number, semester: Semester): string {
  return `${subject}:${grade}:${semester}`;
}

/** 判断一册是否可用于生成练习。 */
export function isUsableEdition(edition: TextbookEdition): boolean {
  return edition.reviewStatus === 'published' && edition.coverageStatus !== 'missing' && edition.availableEntryCount > 0;
}
