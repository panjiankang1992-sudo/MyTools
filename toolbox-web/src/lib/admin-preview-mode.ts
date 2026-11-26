import type { WorksheetMode } from '../types';

/**
 * 草稿预览的**学科与题型**推导（F10）。
 *
 * 审查指出的问题：编辑器里写死了 `{ mode: 'chinese-pinyin', subject: 'chinese' }`，
 * 于是**英语教材的预览也走语文链路**——预览出来的东西与这本教材无关。
 *
 * 这里把"学科 → 可用题型"抽成纯函数，让调用点**没法再写死**，
 * 也让"英语只有英语题型、语文只有语文题型"这条性质可以被断言。
 */

/** 语文的两种题型（听写／拼音）。 */
const CHINESE_MODES: readonly WorksheetMode[] = ['chinese-dictation', 'chinese-pinyin'];
/** 英语的两种题型（听写／英译中）。 */
const ENGLISH_MODES: readonly WorksheetMode[] = ['english-dictation', 'english-meaning'];

/**
 * 取某学科可用的题型。
 *
 * @param subject 学科
 * @returns 该学科的题型清单，**顺序即界面上的顺序**
 */
export function previewModesFor(subject: string): readonly WorksheetMode[] {
  return subject === 'english' ? ENGLISH_MODES : CHINESE_MODES;
}

/**
 * 取某学科**默认**的预览题型。
 *
 * 默认取该学科的第一种（听写），而不是写死 `chinese-pinyin`——
 * 后者在英语教材上会得到一个语文题型。
 *
 * @param subject 学科
 * @returns 默认题型
 */
export function defaultPreviewMode(subject: string): WorksheetMode {
  return previewModesFor(subject)[0];
}

/**
 * 判断某题型是否属于某学科。
 *
 * 用于"切学科后把不匹配的题型纠回来"：用户可能在语文下选了拼音，
 * 然后切到英语——那个题型必须被替换成英语的。
 *
 * @param subject 学科
 * @param mode    待判断的题型
 * @returns 属于该学科时为 true
 */
export function modeBelongsToSubject(subject: string, mode: string): boolean {
  return previewModesFor(subject).includes(mode as WorksheetMode);
}

/**
 * 在校验过归属之后给出可用的题型；不属于当前学科时回落到默认。
 *
 * @param subject 学科
 * @param mode    期望的题型
 * @returns 一定属于该学科的题型
 */
export function coercePreviewMode(subject: string, mode: string | null | undefined): WorksheetMode {
  return mode && modeBelongsToSubject(subject, mode) ? (mode as WorksheetMode) : defaultPreviewMode(subject);
}

/**
 * 题型的**中文名**（设计 164 行：在侧面板选择对应两种题型）。
 *
 * 名称与公开端 `useWorksheetV2Session` 的 `MODES` **逐字一致**：
 * 管理端预览的是同一套字帖，名字不同会让"预览的是哪一种"变得无法沟通。
 * 公开端那份是组件内的局部常量，导出它会把管理端耦合到组件的模块图上，
 * 因此这里复写同一张表，并用测试锁住四个值。
 */
export const PREVIEW_MODE_LABELS: Readonly<Record<WorksheetMode, string>> = {
  'chinese-pinyin': '拼音字帖',
  'chinese-dictation': '汉字字帖',
  'english-dictation': '英语默写',
  'english-meaning': '中文释义练习'
};

/**
 * 取题型的可读名称。
 *
 * @param mode 题型
 * @returns 中文名；未收录时回落成原始值（**不隐藏**新题型）
 */
export function previewModeLabel(mode: WorksheetMode): string {
  return PREVIEW_MODE_LABELS[mode] ?? mode;
}

/**
 * 取某学科的选择项，供 `NSelect` 直接使用。
 *
 * @param subject 学科
 * @returns 该学科的两种题型，顺序与 `previewModesFor` 一致
 */
export function previewModeOptions(subject: string): Array<{ label: string; value: WorksheetMode }> {
  return previewModesFor(subject).map(mode => ({ label: previewModeLabel(mode), value: mode }));
}
