package com.yuyutian.mytools.toolbox.service.admin.validation;

import com.yuyutian.mytools.toolbox.service.admin.validation.ValidationIssue.Severity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 教材草稿的统一校验器。
 *
 * 设计文档 §5.4 要求：由后端给出**问题码、字段路径、rowId、unitId、lessonId 与严重程度**，
 * 前端即时校验只用于体验，**能否发布由后端重新校验决定**。
 *
 * 两级严格度：
 * - `DRAFT`：允许暂缺并返回定位，便于边录边存；
 * - `PUBLISH`：必填完整，否则阻止发布。
 */
@Service
public class CurriculumValidator {

    /** 名称中不允许出现的替换字符，出现即说明来源编码已损坏。 */
    private static final char REPLACEMENT_CHARACTER = '\uFFFD';
    /** 单栏内容上限（Unicode 码点）。 */
    private static final int MAX_TEXT_CODE_POINTS = 512;
    /** 名称上限（Unicode 码点）。 */
    private static final int MAX_NAME_CODE_POINTS = 120;

    /**
     * 按指定严格度校验草稿。
     *
     * @param draft 草稿
     * @param level 严格度
     * @return 校验报告
     */
    public ValidationReport validate(AdminTextbookDraft draft, Level level) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (draft == null) {
            issues.add(ValidationIssue.blocking("DRAFT_MISSING", "draft", null, "草稿为空"));
            return new ValidationReport(List.copyOf(issues));
        }
        validateMetadata(draft, level, issues);
        validateStructure(draft, level, issues);
        validateEntries(draft, level, issues);
        return new ValidationReport(List.copyOf(issues));
    }

    /** 校验教材元数据。 */
    private void validateMetadata(AdminTextbookDraft draft, Level level, List<ValidationIssue> issues) {
        boolean strict = level == Level.PUBLISH;
        if (isBlank(draft.subject())) {
            issues.add(level == Level.PUBLISH
                    ? ValidationIssue.blocking("SUBJECT_REQUIRED", "subject", null, "缺少学科")
                    : new ValidationIssue(Severity.WARNING, "SUBJECT_REQUIRED", "subject", null, null, null, "缺少学科"));
        } else if (!"chinese".equals(draft.subject()) && !"english".equals(draft.subject())) {
            issues.add(ValidationIssue.blocking("SUBJECT_INVALID", "subject", null, draft.subject()));
        }
        if (isBlank(draft.semester())) {
            issues.add(severity(level, "SEMESTER_REQUIRED", "semester", "缺少册次"));
        } else if (!"first".equals(draft.semester()) && !"second".equals(draft.semester())) {
            issues.add(ValidationIssue.blocking("SEMESTER_INVALID", "semester", null, draft.semester()));
        }
        // 语文 1—6 年级，英语 3—6 年级（契约对英语年级有下限）。
        int minimumGrade = "english".equals(draft.subject()) ? 3 : 1;
        if (draft.grade() < minimumGrade || draft.grade() > 6) {
            issues.add(ValidationIssue.blocking("GRADE_OUT_OF_RANGE", "grade", null,
                    "grade=" + draft.grade() + ", subject=" + draft.subject()));
        }
        // 出版社与版次是已有教材的可选信息，不作为人工录入的门槛。
        validateOptionalName(draft.publisher(), "publisher", issues);
        requireName(draft.series(), "series", strict, issues);
        validateOptionalName(draft.editionLabel(), "editionLabel", issues);
        if (strict && isBlank(draft.sourceNote())) {
            issues.add(ValidationIssue.blocking("SOURCE_REQUIRED", "sourceNote", null, "发布必须填写来源说明"));
        }
        if (strict && isBlank(draft.coverageDeclaration())) {
            issues.add(ValidationIssue.blocking("COVERAGE_REQUIRED", "coverageDeclaration", null, "缺少收录范围声明"));
        }
        if (strict) {
            // F08：**未核对的条目一律不允许发布**，与"收录是否完整"无关。
            //
            // 此前这条检查只挂在 `coverageDeclaration == "complete"` 之下，
            // 于是 `partial` 被当成了"可以不逐条核对"——那是**语义混淆**：
            // partial 说的是"这册书没收录全"，**不是**"这些内容没人核对过"。
            //
            // 后果很直接：管理员录入一批新内容、一条都没核对，照样能发布出去。
            long unverified = (draft.entries() == null ? List.<AdminTextbookDraft.DraftEntry>of() : draft.entries())
                    .stream()
                    .filter(entry -> entry.text() != null && !entry.text().isBlank())
                    // **只看 `confirmed`，不看摘要**。
                    //
                    // 设计 B11 的契约是：客户端提交**确认意图**，
                    // **服务端**重算摘要并记录核对人与时间。
                    // 因此校验阶段摘要本来就可能还是空的——那是服务端的产物，
                    // 拿它当门槛会把"正常的确认流程"判成未核对。
                    // （我第一版正是这样写的，被既有测试 `completeDraftIsPublishable` 挡了下来。）
                    .filter(entry -> !entry.confirmed())
                    .count();
            if (unverified > 0) {
                issues.add(ValidationIssue.blocking("UNCONFIRMED_ENTRIES", "entries", null,
                        "有 " + unverified + " 条内容尚未人工核对，不能发布"));
            }
            if ("complete".equals(draft.coverageDeclaration()) && unverified > 0) {
                // 完整收录另有一条更严的要求：声明"收全了"必须有核对依据。
                issues.add(ValidationIssue.blocking("COVERAGE_COMPLETE_NEEDS_EVIDENCE", "coverageDeclaration",
                        null, "完整收录需要全部条目已核对，当前有 " + unverified + " 条未核对"));
            }
        }
    }

    /**
     * 合法拼音的**字符级**模式：字母（含带调拉丁字母）、空格、隔音撇号。
     *
     * Java 的 `\p{IsAlphabetic}` 覆盖带声调符号的拉丁字母（如 ā á ǎ à），
     * 因此不需要枚举调号。
     */
    private static final java.util.regex.Pattern PINYIN_PATTERN =
            java.util.regex.Pattern.compile("[\\p{IsAlphabetic} ']+");

    /** 校验单元与课文的结构与顺序。 */
    private void validateStructure(AdminTextbookDraft draft, Level level, List<ValidationIssue> issues) {
        boolean strict = level == Level.PUBLISH;
        Set<String> unitKeys = new HashSet<>();
        Set<String> unitOrders = new HashSet<>();
        List<AdminTextbookDraft.DraftUnit> units = draft.units() == null ? List.of() : draft.units();
        for (int index = 0; index < units.size(); index += 1) {
            AdminTextbookDraft.DraftUnit unit = units.get(index);
            String path = "units[" + index + "]";
            String key = identity(unit.unitId(), unit.clientUnitId());
            if (key == null) {
                issues.add(ValidationIssue.blocking("UNIT_ID_MISSING", path, null, "单元缺少标识"));
            } else if (!unitKeys.add(key)) {
                issues.add(ValidationIssue.blocking("UNIT_ID_DUPLICATE", path, null, key));
            }
            if (!unitOrders.add(String.valueOf(unit.order()))) {
                issues.add(ValidationIssue.blocking("UNIT_ORDER_DUPLICATE", path + ".order", null,
                        "order=" + unit.order()));
            }
            requireName(unit.title(), path + ".title", strict, issues);
        }
        Set<String> lessonKeys = new HashSet<>();
        Set<String> lessonOrders = new HashSet<>();
        List<AdminTextbookDraft.DraftLesson> lessons = draft.lessons() == null ? List.of() : draft.lessons();
        for (int index = 0; index < lessons.size(); index += 1) {
            AdminTextbookDraft.DraftLesson lesson = lessons.get(index);
            String path = "lessons[" + index + "]";
            String key = identity(lesson.lessonId(), lesson.clientLessonId());
            if (key == null) {
                issues.add(ValidationIssue.blocking("LESSON_ID_MISSING", path, null, "课文缺少标识"));
            } else if (!lessonKeys.add(key)) {
                issues.add(ValidationIssue.blocking("LESSON_ID_DUPLICATE", path, null, key));
            }
            String parent = identity(lesson.unitId(), lesson.clientUnitId());
            if (parent == null || !unitKeys.contains(parent)) {
                issues.add(ValidationIssue.blocking("LESSON_UNIT_UNKNOWN", path + ".unitId", null, String.valueOf(parent)));
            }
            requireName(lesson.title(), path + ".title", strict, issues);
            // F08：课文顺序必须**在同一父节点内**唯一。
            // 此前完全不查，于是同一单元下两条课文都排第 1 位也能发布——
            // 读者看到的顺序将取决于数据库返回顺序，**不可复现**。
            if (parent != null && !lessonOrders.add(parent + "#" + lesson.order())) {
                issues.add(ValidationIssue.blocking("LESSON_ORDER_DUPLICATE", path + ".order", null,
                        "同一单元内 order=" + lesson.order() + " 重复"));
            }
        }
    }

    /** 校验条目内容、归属与配对。 */
    private void validateEntries(AdminTextbookDraft draft, Level level, List<ValidationIssue> issues) {
        boolean strict = level == Level.PUBLISH;
        boolean chinese = "chinese".equals(draft.subject());
        Set<String> rowIds = new HashSet<>();
        Set<String> unitKeys = new HashSet<>();
        for (AdminTextbookDraft.DraftUnit unit : draft.units() == null ? List.<AdminTextbookDraft.DraftUnit>of() : draft.units()) {
            String key = identity(unit.unitId(), unit.clientUnitId());
            if (key != null) {
                unitKeys.add(key);
            }
        }
        Set<String> lessonKeys = new HashSet<>();
        // 课文 → 所属单元。用于校验"条目说自己属于 u1、却挂了 u2 的课文"这种自相矛盾的归属。
        Map<String, String> lessonParents = new HashMap<>();
        for (AdminTextbookDraft.DraftLesson lesson : draft.lessons() == null ? List.<AdminTextbookDraft.DraftLesson>of() : draft.lessons()) {
            String key = identity(lesson.lessonId(), lesson.clientLessonId());
            if (key != null) {
                lessonKeys.add(key);
                String parent = identity(lesson.unitId(), lesson.clientUnitId());
                if (parent != null) {
                    lessonParents.put(key, parent);
                }
            }
        }
        List<AdminTextbookDraft.DraftEntry> entries = draft.entries() == null ? List.of() : draft.entries();
        for (int index = 0; index < entries.size(); index += 1) {
            AdminTextbookDraft.DraftEntry entry = entries.get(index);
            String path = "entries[" + index + "]";
            String rowId = entry.rowId();
            if (isBlank(rowId)) {
                issues.add(ValidationIssue.blocking("ROW_ID_MISSING", path + ".rowId", null, "条目缺少稳定行标识"));
            } else if (!rowIds.add(rowId)) {
                issues.add(new ValidationIssue(Severity.BLOCKING, "ROW_ID_DUPLICATE", path + ".rowId", rowId,
                        null, null, "重复的行标识"));
            }
            String owner = identity(entry.unitId(), entry.clientUnitId());
            if (owner == null || !unitKeys.contains(owner)) {
                issues.add(new ValidationIssue(Severity.BLOCKING, "ENTRY_UNIT_UNKNOWN", path + ".unitId", rowId,
                        entry.unitId(), entry.lessonId(), "条目所属单元不存在"));
            }
            String lessonOwner = identity(entry.lessonId(), entry.clientLessonId());
            if (lessonOwner != null && !lessonKeys.contains(lessonOwner)) {
                // 定位信息用生效标识：草稿阶段常只有 clientLessonId，报 null 会让前端无法定位到行。
                issues.add(new ValidationIssue(Severity.BLOCKING, "ENTRY_LESSON_UNKNOWN", path + ".lessonId", rowId,
                        owner, lessonOwner, "条目所属课文不存在"));
            }
            // F08：条目的单元与它所属课文的单元**必须一致**。
            // 此前只查"课文是否存在"，不查它属于谁——于是
            // "条目说自己在 u1、课文 l1 实际属于 u2"这种矛盾能发布出去，
            // 公开目录里这条内容会出现在一个它不属于的单元下。
            if (lessonOwner != null && owner != null) {
                String lessonUnit = lessonParents.get(lessonOwner);
                if (lessonUnit != null && !lessonUnit.equals(owner)) {
                    issues.add(new ValidationIssue(Severity.BLOCKING, "ENTRY_LESSON_UNIT_MISMATCH",
                            path + ".lessonId", rowId, owner, lessonOwner,
                            "条目所属单元与课文所属单元不一致"));
                }
            }
            boolean emptyText = isBlank(entry.text());
            if (emptyText) {
                // 空白行在草稿阶段允许存在，但发布时不得作为条目输出。
                issues.add(severity(level, "TEXT_REQUIRED", path + ".text", rowId, "内容为空"));
                continue;
            }
            if (containsReplacementCharacter(entry.text())) {
                issues.add(new ValidationIssue(Severity.BLOCKING, "TEXT_REPLACEMENT_CHAR", path + ".text", rowId,
                        entry.unitId(), entry.lessonId(), "内容含替换字符 U+FFFD"));
            }
            if (codePointLength(entry.text()) > MAX_TEXT_CODE_POINTS) {
                issues.add(new ValidationIssue(Severity.BLOCKING, "TEXT_TOO_LONG", path + ".text", rowId,
                        entry.unitId(), entry.lessonId(), "长度=" + codePointLength(entry.text())));
            }
            if (chinese) {
                validateChineseEntry(entry, path, level, issues);
            } else {
                validateEnglishEntry(entry, path, level, issues);
            }
        }
    }

    /** 语文条目：汉字 + 逐字拼音，且音节数与字数必须对齐。 */
    private void validateChineseEntry(AdminTextbookDraft.DraftEntry entry, String path, Level level,
                                      List<ValidationIssue> issues) {
        String pinyin = entry.pinyin();
        if (isBlank(pinyin)) {
            issues.add(severity(level, "PINYIN_REQUIRED", path + ".pinyin", entry.rowId(), "缺少拼音"));
            return;
        }
        // F08：拼音必须**只由字母构成**（含带声调符号的拉丁字母），可含空格与隔音撇号。
        //
        // 此前完全没有这一层：`山 / 123` 这种明显不是拼音的内容能一路发布出去，
        // 然后在字帖里渲染成错误的东西。
        //
        // **范围说明**：这是**字符级**校验（拒绝数字与标点），
        // **不是**音节词典校验——`shān` 与 `zzz` 都会被它放过。
        // 完整的音节表在前端（`worksheet-processing.isValidPinyinSyllable`）有一份，
        // 把它搬到服务端是另一件事；这里先把"明显非法"挡住，
        // 并把这条限制写清楚，免得被当成"拼音已经全校验了"。
        if (!PINYIN_PATTERN.matcher(pinyin.trim()).matches()) {
            issues.add(new ValidationIssue(Severity.BLOCKING, "PINYIN_INVALID_CHARACTERS", path + ".pinyin",
                    entry.rowId(), null, null, "拼音只能由字母、空格与隔音撇号组成"));
        }
        int syllables = pinyin.trim().split("\\s+").length;
        int characters = codePointLength(entry.text());
        if (syllables != characters) {
            issues.add(new ValidationIssue(Severity.BLOCKING, "PINYIN_COUNT_MISMATCH", path + ".pinyin",
                    entry.rowId(), entry.unitId(), entry.lessonId(),
                    "音节数=" + syllables + ", 字数=" + characters));
        }
    }

    /** 英语条目：英文 + 中文释义。 */
    private void validateEnglishEntry(AdminTextbookDraft.DraftEntry entry, String path, Level level,
                                      List<ValidationIssue> issues) {
        if (isBlank(entry.meaning())) {
            issues.add(severity(level, "MEANING_REQUIRED", path + ".meaning", entry.rowId(), "缺中文释义"));
            return;
        }
        if (containsReplacementCharacter(entry.meaning())) {
            issues.add(new ValidationIssue(Severity.BLOCKING, "MEANING_REPLACEMENT_CHAR", path + ".meaning",
                    entry.rowId(), entry.unitId(), entry.lessonId(), "释义含替换字符 U+FFFD"));
        }
    }

    /** 可选名称仅在已填写时检查长度与乱码。 */
    private void validateOptionalName(String value, String fieldPath, List<ValidationIssue> issues) {
        if (!isBlank(value)) {
            requireName(value, fieldPath, true, issues);
        }
    }

    /** 名称必填与长度、乱码检查。 */
    private void requireName(String value, String fieldPath, boolean strict, List<ValidationIssue> issues) {
        if (isBlank(value)) {
            issues.add(strict
                    ? ValidationIssue.blocking("NAME_REQUIRED", fieldPath, null, "缺少必填名称")
                    : new ValidationIssue(Severity.WARNING, "NAME_REQUIRED", fieldPath, null, null, null, "缺少名称"));
            return;
        }
        if (containsReplacementCharacter(value)) {
            issues.add(ValidationIssue.blocking("NAME_REPLACEMENT_CHAR", fieldPath, null, "名称含替换字符 U+FFFD"));
        }
        if (codePointLength(value) > MAX_NAME_CODE_POINTS) {
            issues.add(ValidationIssue.blocking("NAME_TOO_LONG", fieldPath, null,
                    "长度=" + codePointLength(value)));
        }
    }

    /** 按严格度构造问题：发布时阻止，草稿时仅提示。 */
    private static ValidationIssue severity(Level level, String code, String fieldPath, String detail) {
        return severity(level, code, fieldPath, null, detail);
    }

    /** 带 rowId 的分级问题。 */
    private static ValidationIssue severity(Level level, String code, String fieldPath, String rowId, String detail) {
        return level == Level.PUBLISH
                ? ValidationIssue.blocking(code, fieldPath, rowId, detail)
                : new ValidationIssue(Severity.WARNING, code, fieldPath, rowId, null, null, detail);
    }

    /** 正式标识与临时标识二选一，返回生效的那个。 */
    private static String identity(String officialId, String clientId) {
        if (!isBlank(officialId)) {
            return officialId;
        }
        return isBlank(clientId) ? null : clientId;
    }

    /** 是否为空或纯空白。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 是否含替换字符。 */
    private static boolean containsReplacementCharacter(String value) {
        return value != null && value.indexOf(REPLACEMENT_CHARACTER) >= 0;
    }

    /** 按 Unicode 码点计数，避免用 UTF-16 长度误判补充平面字符。 */
    private static int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /** 校验严格度。 */
    public enum Level {
        /** 草稿保存：允许暂缺，问题仅提示。 */
        DRAFT,
        /** 发布：必填完整，问题阻止发布。 */
        PUBLISH
    }

    /**
     * 校验报告。
     *
     * @param issues 问题清单
     */
    public record ValidationReport(List<ValidationIssue> issues) {

        /**
         * 是否存在阻止发布的问题。
         *
         * @return 存在时为 true
         */
        public boolean hasBlocking() {
            return issues.stream().anyMatch(issue -> issue.severity() == Severity.BLOCKING);
        }

        /**
         * 是否可发布。
         *
         * @return 无阻止项时为 true
         */
        public boolean publishable() {
            return !hasBlocking();
        }
    }
}
