/**
 * 校验问题码 → 用户可读提示。
 *
 * **为什么需要它**：服务端的 `ValidationIssue` 并不都带 `detail`
 * （19 条 blocking 规则里有 8 条只有 code 与 fieldPath）。界面此前用
 * `issue.detail || issue.code` 兜底，于是用户在界面上直接看到
 * `LESSON_UNIT_UNKNOWN（lessons[0].unitId）` 这样的英文标识符——
 * **既看不懂，也不知道该改什么**。
 *
 * 这里的映射只负责"把码翻译成话"；**未收录的码仍然原样显示**，
 * 不静默换成"未知错误"——那会把新加规则的问题藏起来。
 */

/** 校验问题码对应的中文提示。 */
export const VALIDATION_MESSAGES: Readonly<Record<string, string>> = {
  // 教材与声明
  DRAFT_MISSING: '草稿不存在，可能已被其他管理员删除。',
  SUBJECT_REQUIRED: '请选择学科，例如语文或英语。',
  SUBJECT_INVALID: '学科取值无效，请重新选择。',
  GRADE_OUT_OF_RANGE: '年级超出该学科的支持范围。',
  SEMESTER_INVALID: '册次取值无效，请重新选择。',
  SOURCE_REQUIRED: '发布前必须填写来源说明。',
  COVERAGE_REQUIRED: '请选择收录范围（部分内容或完整收录）。',
  COVERAGE_COMPLETE_NEEDS_EVIDENCE:
    '声明"完整收录"时，所有条目都必须先核对；请核对剩余条目，或改为"部分内容"。',
  // 名称
  NAME_REQUIRED: '名称不能为空。',
  NAME_TOO_LONG: '名称过长，请缩短。',
  NAME_REPLACEMENT_CHAR: '名称含有乱码字符（U+FFFD），请修正。',
  // 单元
  UNIT_ID_MISSING: '单元缺少稳定的系统标识，请重新添加该单元。',
  UNIT_ID_DUPLICATE: '存在重复的单元标识，请删除重复项。',
  UNIT_ORDER_DUPLICATE: '同一层级中单元顺序重复，请调整顺序。',
  // 课文
  LESSON_ID_MISSING: '课文缺少稳定的系统标识，请重新添加该课文。',
  LESSON_ID_DUPLICATE: '存在重复的课文标识，请删除重复项。',
  LESSON_UNIT_UNKNOWN: '课文所属的单元不存在，请重新指定单元或删除该课文。',
  // 条目
  ROW_ID_MISSING: '有内容行缺少稳定标识，请删除该行后重新输入。',
  ROW_ID_DUPLICATE: '存在重复的内容行标识，请删除重复项。',
  ENTRY_UNIT_UNKNOWN: '该内容所属的单元不存在，请重新指定单元。',
  ENTRY_LESSON_UNKNOWN: '该内容所属的课文不存在，请重新指定课文。',
  TEXT_TOO_LONG: '正文过长，请拆分或缩短。',
  TEXT_REPLACEMENT_CHAR: '正文含有乱码字符（U+FFFD），请修正后再发布。',
  MEANING_REPLACEMENT_CHAR: '释义含有乱码字符（U+FFFD），请修正后再发布。',
  PINYIN_REQUIRED: '缺少拼音。',
  PINYIN_COUNT_MISMATCH: '拼音音节数与汉字数不一致，请逐字核对。',
  MEANING_REQUIRED: '英语条目缺少中文释义。'
};

/**
 * 取某个校验问题码的用户可读提示。
 *
 * @param code 服务端返回的问题码
 * @returns 中文提示；未收录时返回原码（**不隐藏**）
 */
export function validationMessage(code: string | null | undefined): string {
  if (!code) {
    return '存在未说明的校验问题。';
  }
  return VALIDATION_MESSAGES[code] ?? code;
}

/**
 * 组装一条问题的展示文本。
 *
 * 保留 `detail` 优先：服务端给了具体说明（如"还剩 3 条未核对"）时，
 * 它比通用提示更有用。
 *
 * @param issue 服务端返回的问题
 * @returns 展示文本
 */
export function formatIssue(issue: { code?: string | null; detail?: string | null; fieldPath?: string | null }): string {
  const text = issue.detail?.trim() ? issue.detail.trim() : validationMessage(issue.code);
  return issue.fieldPath ? `${text}（${issue.fieldPath}）` : text;
}
