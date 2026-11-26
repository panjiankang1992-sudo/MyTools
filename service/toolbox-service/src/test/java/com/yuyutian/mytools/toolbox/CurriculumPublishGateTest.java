package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftEntry;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftLesson;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftUnit;
import com.yuyutian.mytools.toolbox.service.admin.validation.CurriculumValidator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F08：**校验与人工核对必须构成发布门槛**。
 *
 * 审查探针 `curriculum-probe-output.txt` 复现的四个反例，
 * 在修复前**全部** `publishable=true`：
 *
 * ```
 * UNCONFIRMED_PARTIAL_ENTRY: publishable=true; issues=[]
 * INVALID_PINYIN:            publishable=true; issues=[]
 * LESSON_UNIT_MISMATCH:      publishable=true; issues=[]
 * DUPLICATE_LESSON_ORDER:    publishable=true; issues=[]
 * ```
 *
 * 也就是说：**未核对的条目、非法拼音、跨单元的课文归属、同一父节点下重复的课文顺序，
 * 都能被发布出去。** 这些不是"宽松"，是**前置门禁缺失**。
 */
class CurriculumPublishGateTest {

    private static final CurriculumValidator VALIDATOR = new CurriculumValidator();

    /** 与审查探针一致：未核对、无摘要。 */
    private static DraftEntry entry(String row, String text, String pinyin, String unit, String lesson) {
        return new DraftEntry(row, null, null, unit, null, lesson, text, pinyin, (String) null,
                List.of("writing"), false, null);
    }

    /** 已人工核对（带摘要）的条目——用来做"修好之后应该通过"的反向断言。 */
    private static DraftEntry confirmedEntry(String row, String text, String pinyin, String unit, String lesson) {
        return new DraftEntry(row, null, null, unit, null, lesson, text, pinyin, (String) null,
                List.of("writing"), true, "digest-" + row);
    }

    private static AdminTextbookDraft draft(List<DraftUnit> units, List<DraftLesson> lessons,
                                            List<DraftEntry> entries) {
        return new AdminTextbookDraft("gate-book", "chinese", 1, "first", "人民教育出版社",
                "统编语文", "2024 年修订版", "附录", "partial", units, lessons, entries);
    }

    private static boolean publishable(AdminTextbookDraft draft) {
        return VALIDATOR.validate(draft, CurriculumValidator.Level.PUBLISH).publishable();
    }

    @Test
    @DisplayName("未核对的条目必须挡住发布——partial 只是收录不全，不是核对不全")
    void unconfirmedEntryBlocksPublish() {
        var u1 = new DraftUnit(null, "u1", "Unit 1", 1);
        var row = entry("r1", "山", "shān", "u1", null);
        // 这是审查的第一个反例。partial 的正确含义是"这册书没收录全"，
        // **不能**被解释成"可以不逐条核对"。
        assertThat(publishable(draft(List.of(u1), List.of(), List.of(row))))
                .as("未核对的条目不应可发布").isFalse();
    }

    @Test
    @DisplayName("全部核对之后就应当可以发布（门槛不能变成永久拒绝）")
    void confirmedEntriesAllowPublish() {
        var u1 = new DraftUnit(null, "u1", "Unit 1", 1);
        var row = confirmedEntry("r1", "山", "shān", "u1", null);
        // 反向断言：修复不能把发布变成永远做不到。
        assertThat(publishable(draft(List.of(u1), List.of(), List.of(row))))
                .as("全部核对后应当可发布").isTrue();
    }

    @Test
    @DisplayName("非法拼音必须挡住发布")
    void invalidPinyinBlocksPublish() {
        var u1 = new DraftUnit(null, "u1", "Unit 1", 1);
        var row = confirmedEntry("r1", "山", "123", "u1", null);
        // 数字不是合法拼音音节；发布出去会让字帖渲染出错内容。
        assertThat(publishable(draft(List.of(u1), List.of(), List.of(row))))
                .as("非法拼音不应可发布").isFalse();
    }

    @Test
    @DisplayName("条目挂到不属于自己单元的课文时必须挡住发布")
    void lessonUnitMismatchBlocksPublish() {
        var u1 = new DraftUnit(null, "u1", "Unit 1", 1);
        var u2 = new DraftUnit(null, "u2", "Unit 2", 2);
        // 课文 l1 属于 u2，但条目 r1 说自己属于 u1 且挂 l1——归属自相矛盾。
        var lessons = List.of(new DraftLesson(null, "l1", null, "u2", "Lesson", 1));
        var row = confirmedEntry("r1", "山", "shān", "u1", "l1");
        assertThat(publishable(draft(List.of(u1, u2), lessons, List.of(row))))
                .as("跨单元归属不应可发布").isFalse();
    }

    @Test
    @DisplayName("同一父节点下重复的课文顺序必须挡住发布")
    void duplicateLessonOrderBlocksPublish() {
        var u1 = new DraftUnit(null, "u1", "Unit 1", 1);
        var lessons = List.of(new DraftLesson(null, "l1", null, "u1", "One", 1),
                new DraftLesson(null, "l2", null, "u1", "Two", 1));
        var row = confirmedEntry("r1", "山", "shān", "u1", "l1");
        // 两条课文都排第 1 位——读者看到的顺序将取决于数据库返回顺序，不可复现。
        assertThat(publishable(draft(List.of(u1), lessons, List.of(row))))
                .as("重复课文顺序不应可发布").isFalse();
    }
}
