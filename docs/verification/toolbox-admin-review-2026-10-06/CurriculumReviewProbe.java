import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftEntry;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftUnit;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftLesson;
import com.yuyutian.mytools.toolbox.service.admin.validation.CurriculumValidator;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.DraftToBookMapper;
import java.util.List;

/** 只调用实际编译代码的只读验收探针；不访问网络或持久化数据。 */
public class CurriculumReviewProbe {
    private static final CurriculumValidator VALIDATOR = new CurriculumValidator();
    private static final DraftToBookMapper MAPPER = new DraftToBookMapper();

    private static DraftEntry entry(String row, String text, String pinyin, String unit, String lesson) {
        return new DraftEntry(row, null, null, unit, null, lesson, text, pinyin, null,
                List.of("writing"), false, null, true);
    }

    private static AdminTextbookDraft draft(List<DraftUnit> units, List<DraftLesson> lessons,
                                           List<DraftEntry> entries) {
        return new AdminTextbookDraft("audit-book", "chinese", 1, "first", "publisher", "series", "edition",
                "paper textbook appendix", "partial", units, lessons, entries);
    }

    private static void report(String name, AdminTextbookDraft draft) {
        var report = VALIDATOR.validate(draft, CurriculumValidator.Level.PUBLISH);
        System.out.println(name + ": publishable=" + report.publishable() + "; issues=" + report.issues());
    }

    /** 输出安全边界实际判断；所有样例都应被发布校验拒绝。 */
    public static void main(String[] args) {
        var u1 = new DraftUnit(null, "u1", "Unit 1", 1);
        var u2 = new DraftUnit(null, "u2", "Unit 2", 2);
        var row = entry("r1", "山", "shān", "u1", null);
        report("UNCONFIRMED_PARTIAL_ENTRY", draft(List.of(u1), List.of(), List.of(row)));
        report("INVALID_PINYIN", draft(List.of(u1), List.of(),
                List.of(entry("r1", "山", "123", "u1", null))));
        report("LESSON_UNIT_MISMATCH", draft(List.of(u1, u2),
                List.of(new DraftLesson(null, "l1", null, "u2", "Lesson", 1)),
                List.of(entry("r1", "山", "shān", "u1", "l1"))));
        report("DUPLICATE_LESSON_ORDER", draft(List.of(u1),
                List.of(new DraftLesson(null, "l1", null, "u1", "One", 1),
                        new DraftLesson(null, "l2", null, "u1", "Two", 1)), List.of(row)));
        var first = MAPPER.map("audit-book", draft(List.of(u1), List.of(), List.of(row)), "1.0.0");
        var next = draft(List.of(u1), List.of(),
                List.of(entry("r-new", "水", "shuǐ", "u1", null), row)).withIdMapping(first.persistedMapping());
        var mapped = MAPPER.map("audit-book", next, "1.0.1");
        System.out.println("INSERT_BEFORE_PERSISTED_ROW: ids=" + mapped.entries().stream().map(e -> e.entryId()).toList());
        System.out.println("INSERT_BEFORE_PERSISTED_ROW: uniqueIds="
                + mapped.entries().stream().map(e -> e.entryId()).distinct().count() + "; entries=" + mapped.entries().size());
        System.out.println("UNCONFIRMED_PUBLIC_REVIEW_STATUS=" + first.entries().get(0).reviewStatus());
        System.out.println("PUBLISHED_SOURCE_REFS=" + first.entries().get(0).sourceRefs());
    }
}
