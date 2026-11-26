package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftEntry;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftLesson;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftUnit;
import com.yuyutian.mytools.toolbox.service.admin.validation.CurriculumValidator;
import com.yuyutian.mytools.toolbox.service.admin.validation.CurriculumValidator.Level;
import com.yuyutian.mytools.toolbox.service.admin.validation.ValidationIssue;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 统一校验器的问题码与定位（设计文档 §5.4、验收项 C01／B04）。
 * 重点：发布比草稿严格；问题必须带字段路径与 rowId，能直接定位到行。
 */
class CurriculumValidatorTest {

    private final CurriculumValidator validator = new CurriculumValidator();

    /** 构造一份可通过发布校验的语文草稿。 */
    private static AdminTextbookDraftBuilderHolder validChinese() {
        return new AdminTextbookDraftBuilderHolder();
    }

    /** 便于逐步构造草稿的小工具。 */
    private static final class AdminTextbookDraftBuilderHolder {
        private String subject = "chinese";
        private int grade = 1;
        private String semester = "first";
        private String publisher = "人民教育出版社";
        private String series = "统编语文";
        private String editionLabel = "2024 年修订版";
        private String sourceNote = "纸质教材附录词语表，第 78—82 页";
        private String coverageDeclaration = "partial";
        private List<DraftUnit> units = List.of(new DraftUnit(null, "cu1", "第一单元", 1));
        private List<DraftLesson> lessons = List.of(new DraftLesson(null, "cl1", null, "cu1", "天地人", 1));
        private List<DraftEntry> entries = List.of(
                new DraftEntry("row-1", null, null, "cu1", null, "cl1", "山", "shān", null, List.of("writing"), true));

        AdminTextbookDraftBuilderHolder subject(String value) { this.subject = value; return this; }
        AdminTextbookDraftBuilderHolder grade(int value) { this.grade = value; return this; }
        AdminTextbookDraftBuilderHolder entries(List<DraftEntry> value) { this.entries = value; return this; }
        AdminTextbookDraftBuilderHolder sourceNote(String value) { this.sourceNote = value; return this; }
        AdminTextbookDraftBuilderHolder publisher(String value) { this.publisher = value; return this; }
        AdminTextbookDraftBuilderHolder editionLabel(String value) { this.editionLabel = value; return this; }
        AdminTextbookDraftBuilderHolder series(String value) { this.series = value; return this; }

        AdminTextbookDraft build() {
            return new AdminTextbookDraft("book-1", subject, grade, semester, publisher, series, editionLabel,
                    sourceNote, coverageDeclaration, units, lessons, entries);
        }
    }

    /** 找出指定问题码。 */
    private static List<ValidationIssue> byCode(CurriculumValidator.ValidationReport report, String code) {
        return report.issues().stream().filter(issue -> issue.code().equals(code)).toList();
    }

    @Test
    @DisplayName("完整草稿在发布级别下可发布，无阻止项")
    void completeDraftIsPublishable() {
        var report = validator.validate(validChinese().build(), Level.PUBLISH);
        assertThat(report.publishable()).isTrue();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    @DisplayName("人工录入不要求出版社和版次，缺省也不产生无效提示")
    void publisherAndEditionLabelAreOptional() {
        for (Level level : Level.values()) {
            var report = validator.validate(validChinese().publisher(null).editionLabel(" ").build(), level);
            assertThat(report.publishable()).isTrue();
            assertThat(report.issues()).isEmpty();
        }
    }

    @Test
    @DisplayName("简化元数据仍要求教材名称，可选信息已填写时仍检查损坏与长度")
    void simplifiedMetadataKeepsNecessaryChecks() {
        var missingTitle = validator.validate(validChinese().series(null).build(), Level.PUBLISH);
        assertThat(byCode(missingTitle, "NAME_REQUIRED")).singleElement()
                .extracting(ValidationIssue::fieldPath).isEqualTo("series");
        var damagedOptional = validator.validate(validChinese().publisher("\uFFFD")
                .editionLabel("a".repeat(121)).build(), Level.PUBLISH);
        assertThat(byCode(damagedOptional, "NAME_REPLACEMENT_CHAR")).hasSize(1);
        assertThat(byCode(damagedOptional, "NAME_TOO_LONG")).hasSize(1);
    }

    @Test
    @DisplayName("缺来源说明：草稿只提示，发布阻止（C01）")
    void sourceRequiredOnlyAtPublish() {
        assertThat(validator.validate(validChinese().sourceNote(null).build(), Level.DRAFT).publishable()).isTrue();
        var publish = validator.validate(validChinese().sourceNote(null).build(), Level.PUBLISH);
        assertThat(publish.publishable()).isFalse();
        assertThat(byCode(publish, "SOURCE_REQUIRED")).hasSize(1);
    }

    @Test
    @DisplayName("拼音音节数与字数不符时阻止发布，并定位到 rowId（C01）")
    void pinyinCountMismatchIsBlocking() {
        var draft = validChinese().entries(List.of(
                new DraftEntry("row-9", null, null, "cu1", null, "cl1", "山水", "shān", null, List.of("writing"), true)))
                .build();
        var report = validator.validate(draft, Level.PUBLISH);
        assertThat(report.publishable()).isFalse();
        var issues = byCode(report, "PINYIN_COUNT_MISMATCH");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).rowId()).isEqualTo("row-9");
        assertThat(issues.get(0).fieldPath()).isEqualTo("entries[0].pinyin");
    }

    @Test
    @DisplayName("英语年级下限为 3：二年级英语判为越界")
    void englishGradeLowerBound() {
        var report = validator.validate(validChinese().subject("english").grade(2).build(), Level.PUBLISH);
        assertThat(byCode(report, "GRADE_OUT_OF_RANGE")).hasSize(1);
    }

    @Test
    @DisplayName("重复的 rowId 被拒绝")
    void duplicateRowIdRejected() {
        var entry = new DraftEntry("row-dup", null, null, "cu1", null, "cl1", "山", "shān", null,
                List.of("writing"), true);
        var report = validator.validate(validChinese().entries(List.of(entry, entry)).build(), Level.PUBLISH);
        assertThat(byCode(report, "ROW_ID_DUPLICATE")).hasSize(1);
    }

    @Test
    @DisplayName("名称含 U+FFFD 替换字符时阻止发布（乱码拦截）")
    void replacementCharacterRejected() {
        var draft = validChinese().build();
        var broken = new AdminTextbookDraft(draft.editionId(), draft.subject(), draft.grade(), draft.semester(),
                "人民教育出版\uFFFD社", draft.series(), draft.editionLabel(), draft.sourceNote(),
                draft.coverageDeclaration(), draft.units(), draft.lessons(), draft.entries());
        var report = validator.validate(broken, Level.PUBLISH);
        assertThat(byCode(report, "NAME_REPLACEMENT_CHAR")).hasSize(1);
    }

    @Test
    @DisplayName("条目归属到不存在的课文时阻止发布并给出 unitId／lessonId")
    void entryLessonMustExist() {
        var draft = validChinese().entries(List.of(
                new DraftEntry("row-x", null, null, "cu1", null, "cl-missing", "山", "shān", null,
                        List.of("writing"), true))).build();
        var report = validator.validate(draft, Level.PUBLISH);
        var issues = byCode(report, "ENTRY_LESSON_UNKNOWN");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).lessonId()).isEqualTo("cl-missing");
    }

    @Test
    @DisplayName("空白条目：草稿提示、发布阻止，不被当作条目发布")
    void blankEntryBlocksPublish() {
        var draft = validChinese().entries(List.of(
                new DraftEntry("row-blank", null, null, "cu1", null, "cl1", "  ", null, null,
                        List.of("writing"), false))).build();
        assertThat(validator.validate(draft, Level.DRAFT).publishable()).isTrue();
        var report = validator.validate(draft, Level.PUBLISH);
        assertThat(report.publishable()).isFalse();
        assertThat(byCode(report, "TEXT_REQUIRED")).hasSize(1);
    }

    @Test
    @DisplayName("声明完整收录但存在未核对条目时阻止发布（C07）")
    void completeCoverageRequiresVerifiedEntries() {
        // 完整收录必须有核对依据；这里一条已核对、一条未核对。
        var verified = new DraftEntry("row-1", null, null, "cu1", null, "cl1", "山", "shān", null,
                List.of("writing"), true, "digest-1");
        var unverified = new DraftEntry("row-2", null, null, "cu1", null, "cl1", "水", "shuǐ", null,
                List.of("writing"), false, null);
        var draft = new AdminTextbookDraft("book-1", "chinese", 1, "first", "人民教育出版社", "统编语文",
                "2024 年修订版", "纸质教材附录", "complete",
                List.of(new DraftUnit(null, "cu1", "第一单元", 1)),
                List.of(new DraftLesson(null, "cl1", null, "cu1", "天地人", 1)),
                List.of(verified, unverified));
        var report = validator.validate(draft, Level.PUBLISH);
        assertThat(report.publishable()).isFalse();
        var issues = byCode(report, "COVERAGE_COMPLETE_NEEDS_EVIDENCE");
        assertThat(issues).hasSize(1);
        // 必须给出可操作的细节：还剩几条没核对。
        assertThat(issues.get(0).detail()).contains("1 条");
    }

    @Test
    @DisplayName("全部条目都已核对时允许声明完整收录（C07）")
    void completeCoverageAllowedWhenAllVerified() {
        var verified = new DraftEntry("row-1", null, null, "cu1", null, "cl1", "山", "shān", null,
                List.of("writing"), true, "digest-1");
        var draft = new AdminTextbookDraft("book-1", "chinese", 1, "first", "人民教育出版社", "统编语文",
                "2024 年修订版", "纸质教材附录", "complete",
                List.of(new DraftUnit(null, "cu1", "第一单元", 1)),
                List.of(new DraftLesson(null, "cl1", null, "cu1", "天地人", 1)),
                List.of(verified));
        var report = validator.validate(draft, Level.PUBLISH);
        assertThat(byCode(report, "COVERAGE_COMPLETE_NEEDS_EVIDENCE")).isEmpty();
        assertThat(report.publishable()).isTrue();
    }

    @Test
    @DisplayName("声明部分收录时不受该规则约束（C07）")
    void partialCoverageIsNotSubjectToTheRule() {
        var unverified = new DraftEntry("row-1", null, null, "cu1", null, "cl1", "山", "shān", null,
                List.of("writing"), false, null);
        var draft = new AdminTextbookDraft("book-1", "chinese", 1, "first", "人民教育出版社", "统编语文",
                "2024 年修订版", "纸质教材附录", "partial",
                List.of(new DraftUnit(null, "cu1", "第一单元", 1)),
                List.of(new DraftLesson(null, "cl1", null, "cu1", "天地人", 1)),
                List.of(unverified));
        var report = validator.validate(draft, Level.PUBLISH);
        // 部分收录允许有未核对内容——这正是它存在的意义。
        assertThat(byCode(report, "COVERAGE_COMPLETE_NEEDS_EVIDENCE")).isEmpty();
    }

    @Test
    @DisplayName("条目指向不存在的单元时阻止发布（完整性：悬空引用）")
    void entryUnitMustExist() {
        // 这正是"删除单元后条目仍指向它"会产生的形态——必须被发布校验拦下。
        var draft = validChinese().entries(List.of(
                new DraftEntry("row-1", null, null, "cu-missing", null, null, "山", "shān", null,
                        List.of("writing"), true))).build();
        var report = validator.validate(draft, Level.PUBLISH);
        var issues = byCode(report, "ENTRY_UNIT_UNKNOWN");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).rowId()).isEqualTo("row-1");
    }

    @Test
    @DisplayName("课文指向不存在的单元时阻止发布")
    void lessonUnitMustExist() {
        var draft = new AdminTextbookDraft("book-1", "chinese", 1, "first", "人民教育出版社", "统编语文",
                "2024 年修订版", "纸质教材附录", "partial",
                List.of(new DraftUnit(null, "cu1", "第一单元", 1)),
                List.of(new DraftLesson(null, "cl1", null, "cu-missing", "天地人", 1)),
                List.of());
        var issues = byCode(validator.validate(draft, Level.PUBLISH), "LESSON_UNIT_UNKNOWN");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).fieldPath()).contains("unitId");
    }

    @Test
    @DisplayName("单元标识重复时阻止发布")
    void duplicateUnitIdRejected() {
        var draft = new AdminTextbookDraft("book-1", "chinese", 1, "first", "人民教育出版社", "统编语文",
                "2024 年修订版", "纸质教材附录", "partial",
                List.of(new DraftUnit("u1", "cu1", "第一单元", 1),
                        new DraftUnit("u1", "cu2", "第二单元", 2)),
                List.of(), List.of());
        assertThat(byCode(validator.validate(draft, Level.PUBLISH), "UNIT_ID_DUPLICATE")).hasSize(1);
    }

    @Test
    @DisplayName("条目缺少稳定行标识时阻止发布")
    void rowIdIsMandatory() {
        var draft = validChinese().entries(List.of(
                new DraftEntry("  ", null, null, "cu1", null, null, "山", "shān", null,
                        List.of("writing"), true))).build();
        assertThat(byCode(validator.validate(draft, Level.PUBLISH), "ROW_ID_MISSING")).hasSize(1);
    }

    @Test
    @DisplayName("正文或释义含替换字符时阻止发布（乱码不得进入已发布内容）")
    void replacementCharactersBlockPublish() {
        var textBroken = validChinese().entries(List.of(
                new DraftEntry("row-1", null, null, "cu1", null, null, "\uFFFD", "shān", null,
                        List.of("writing"), true))).build();
        assertThat(byCode(validator.validate(textBroken, Level.PUBLISH), "TEXT_REPLACEMENT_CHAR")).hasSize(1);

        var meaningBroken = validChinese().subject("english").grade(3).entries(List.of(
                new DraftEntry("row-1", null, null, "cu1", null, null, "apple", null, "苹\uFFFD果",
                        List.of("vocabulary"), true))).build();
        assertThat(byCode(validator.validate(meaningBroken, Level.PUBLISH), "MEANING_REPLACEMENT_CHAR")).hasSize(1);
    }

    @Test
    @DisplayName("缺少学科／册次，或取值非法时阻止发布")
    void subjectAndSemesterAreValidated() {
        var noSubject = new AdminTextbookDraft("book-1", null, 1, "first", "人民教育出版社", "统编语文",
                "2024 年修订版", "来源", "partial", List.of(), List.of(), List.of());
        assertThat(byCode(validator.validate(noSubject, Level.PUBLISH), "SUBJECT_REQUIRED")).hasSize(1);

        var badSemester = new AdminTextbookDraft("book-1", "chinese", 1, "third", "人民教育出版社", "统编语文",
                "2024 年修订版", "来源", "partial", List.of(), List.of(), List.of());
        assertThat(byCode(validator.validate(badSemester, Level.PUBLISH), "SEMESTER_INVALID")).hasSize(1);
    }

    @Test
    @DisplayName("英语条目缺中文释义时发布阻止")
    void englishNeedsMeaning() {
        var draft = validChinese().subject("english").grade(3).entries(List.of(
                new DraftEntry("row-en", null, null, "cu1", null, "cl1", "apple", null, null,
                        List.of("vocabulary"), true))).build();
        var report = validator.validate(draft, Level.PUBLISH);
        assertThat(byCode(report, "MEANING_REQUIRED")).hasSize(1);
    }
}
