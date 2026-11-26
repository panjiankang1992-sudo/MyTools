package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.model.EntryForm;
import com.yuyutian.mytools.toolbox.model.ListType;
import com.yuyutian.mytools.toolbox.model.ReviewStatus;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.DraftToBookMapper;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftEntry;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftLesson;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 草稿到公开结构的显式映射（设计文档 §5.3）。
 * 重点：公开结构不携带管理字段；临时 ID 有确定映射；拼音不硬拆。
 */
class DraftToBookMapperTest {

    private final DraftToBookMapper mapper = new DraftToBookMapper();

    /** 构造草稿。 */
    private static AdminTextbookDraft draft(List<DraftUnit> units, List<DraftLesson> lessons, List<DraftEntry> entries) {
        return new AdminTextbookDraft("book-m", "chinese", 1, "first", "人民教育出版社", "统编语文",
                "2024 年修订版", "纸质教材附录，第 78 页", "partial", units, lessons, entries);
    }

    @Test
    @DisplayName("临时 ID 被分配为确定且可重复的正式 ID，并返回映射")
    void assignsStableIds() {
        AdminTextbookDraft input = draft(
                List.of(new DraftUnit(null, "cu1", "第一单元", 1)),
                List.of(new DraftLesson(null, "cl1", null, "cu1", "天地人", 1)),
                List.of(new DraftEntry("row-1", null, null, "cu1", null, "cl1", "山", "shān", null,
                        List.of("writing"), true)));

        DraftToBookMapper.MappedBook first = mapper.map("book-m", input, "1.0.0");
        DraftToBookMapper.MappedBook second = mapper.map("book-m", input, "1.0.0");
        assertThat(first.units().get(0).unitId()).isEqualTo("book-m-u01");
        assertThat(first.lessons().get(0).lessonId()).isEqualTo("book-m-l01");
        assertThat(first.entries().get(0).entryId()).isEqualTo("book-m-e0001");
        // 同一输入两次映射必须得到同样的 ID，避免刷新后身份漂移
        assertThat(second.entries().get(0).entryId()).isEqualTo(first.entries().get(0).entryId());
        assertThat(first.unitIdMapping()).containsEntry("cu1", "book-m-u01");
        assertThat(first.lessonIdMapping()).containsEntry("cl1", "book-m-l01");
        assertThat(first.entryIdMapping()).containsEntry("row-1", "book-m-e0001");
    }

    @Test
    @DisplayName("归属引用被解析为正式 ID：条目的 unitId 与 lessonId 都指向已映射节点")
    void resolvesOwnership() {
        AdminTextbookDraft input = draft(
                List.of(new DraftUnit(null, "cu1", "第一单元", 1)),
                List.of(new DraftLesson(null, "cl1", null, "cu1", "天地人", 1)),
                List.of(new DraftEntry("row-1", null, null, "cu1", null, "cl1", "山", "shān", null,
                        List.of("writing"), true)));
        DraftToBookMapper.MappedBook mapped = mapper.map("book-m", input, "1.0.0");
        var entry = mapped.entries().get(0);
        assertThat(entry.unitId()).isEqualTo("book-m-u01");
        assertThat(entry.lessonId()).isEqualTo("book-m-l01");
        assertThat(mapped.lessons().get(0).unitId()).isEqualTo("book-m-u01");
    }

    @Test
    @DisplayName("拼音按字对齐生成音节段；数量不符时留空而不硬拆")
    void pinyinSegmentsAlignOrStayEmpty() {
        AdminTextbookDraft aligned = draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(new DraftEntry("r1", null, null, "cu1", null, null, "山水", "shān shuǐ", null,
                        List.of("writing"), true)));
        var segments = mapper.map("book-m", aligned, "1.0.0").entries().get(0).pinyinSegments();
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).grapheme()).isEqualTo("山");
        assertThat(segments.get(1).syllable()).isEqualTo("shuǐ");

        AdminTextbookDraft mismatched = draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(new DraftEntry("r1", null, null, "cu1", null, null, "山水", "shān", null,
                        List.of("writing"), true)));
        assertThat(mapper.map("book-m", mismatched, "1.0.0").entries().get(0).pinyinSegments()).isEmpty();
    }

    @Test
    @DisplayName("单个 ASCII 字母是 word 而不是 character（英语册的 I 与 a 曾被标成单字）")
    void singleAsciiLetterIsWordNotCharacter() {
        // 真实 140 条英语册上复现：`formOf` 只看文本、不看学科，
        // 于是 `"I"` 与 `"a"` 这两个**单码点**被推成 CHARACTER ✗ ——
        // 而生产同一册的对应条目是 WORD（2026-10-07 只读实测）。
        // 汉字单个码点仍必须是 CHARACTER，所以这两条必须一起断言。
        AdminTextbookDraft input = draft(List.of(new DraftUnit(null, "cu1", "U1", 1)), List.of(),
                List.of(new DraftEntry("r1", null, null, "cu1", null, null, "I", null, "我",
                                List.of(), true),
                        new DraftEntry("r2", null, null, "cu1", null, null, "a", null, "一个",
                                List.of(), true),
                        new DraftEntry("r3", null, null, "cu1", null, null, "山", "shān", null,
                                List.of(), true)));
        var entries = mapper.map("book-letter", input, "1.0.0").entries();
        assertThat(entries.get(0).form()).as("英文单词 I").isEqualTo(EntryForm.WORD);
        assertThat(entries.get(1).form()).as("英文单词 a").isEqualTo(EntryForm.WORD);
        assertThat(entries.get(2).form()).as("汉字 山 仍须是单字").isEqualTo(EntryForm.CHARACTER);
    }

    @Test
    @DisplayName("形态推断：单字为 character、含空格为 phrase、其余为 word")
    void infersEntryForm() {
        AdminTextbookDraft input = draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(new DraftEntry("r1", null, null, "cu1", null, null, "山", "shān", null, List.of(), true),
                        new DraftEntry("r2", null, null, "cu1", null, null, "ice cream", null, "冰淇淋",
                                List.of(), true),
                        new DraftEntry("r3", null, null, "cu1", null, null, "朋友", "péng yǒu", null,
                                List.of(), true)));
        var entries = mapper.map("book-m", input, "1.0.0").entries();
        assertThat(entries.get(0).form()).isEqualTo(EntryForm.CHARACTER);
        assertThat(entries.get(1).form()).isEqualTo(EntryForm.PHRASE);
        assertThat(entries.get(2).form()).isEqualTo(EntryForm.WORD);
    }

    @Test
    @DisplayName("未人工核对的条目不得映射为已核对；列表类别按契约枚举转换")
    void confirmationAndListTypes() {
        // 核对可信要求"confirmed 为真**且**摘要匹配"（设计文档 B11），因此这里给出正确摘要。
        AdminTextbookDraft.DraftEntry confirmedEntry = new AdminTextbookDraft.DraftEntry("r1", null, null, "cu1",
                null, null, "山", "shān", null, List.of("writing", "recognition"), true,
                DraftToBookMapper.contentDigest(new AdminTextbookDraft.DraftEntry("r1", null, null, "cu1",
                        null, null, "山", "shān", null, List.of("writing", "recognition"), true)));
        AdminTextbookDraft input = draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(confirmedEntry,
                        new DraftEntry("r2", null, null, "cu1", null, null, "水", "shuǐ", null,
                                List.of("vocabulary"), false)));
        var entries = mapper.map("book-m", input, "1.0.0").entries();
        assertThat(entries.get(0).listTypes()).containsExactly(ListType.WRITING, ListType.RECOGNITION);
        assertThat(entries.get(0).reviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        // 未核对条目不能因为"写进了正文"就变成已审事实
        assertThat(entries.get(1).reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
    }

    @Test
    @DisplayName("已持久化的映射被沿用以至插入新节点后既有 ID 不变（ID 稳定性）")
    void persistedMappingKeepsIdenticalIds() {
        // 首次映射：两个单元
        AdminTextbookDraft first = draft(
                List.of(new DraftUnit(null, "cu1", "第一单元", 1),
                        new DraftUnit(null, "cu2", "第二单元", 2)),
                List.of(),
                List.of(new DraftEntry("row-1", null, null, "cu1", null, null, "山", "shān", null,
                        List.of("writing"), true)));
        DraftToBookMapper.MappedBook initial = mapper.map("book-m", first, "1.0.0");
        assertThat(initial.units().get(1).unitId()).isEqualTo("book-m-u02");

        // 在中间插入一个新单元，并把首次映射写回草稿
        AdminTextbookDraft inserted = draft(
                List.of(new DraftUnit(null, "cu-new", "新单元", 2),
                        new DraftUnit(null, "cu2", "第二单元", 3)),
                List.of(),
                List.of()).withIdMapping(initial.persistedMapping());
        DraftToBookMapper.MappedBook afterInsert = mapper.map("book-m", inserted, "1.0.1");

        // 既有 cu2 必须沿用 book-m-u02，而不是因为位置后移变成 u03
        assertThat(afterInsert.unitIdMapping()).containsEntry("cu2", "book-m-u02");
        // 新节点获得一个不同的 ID
        assertThat(afterInsert.unitIdMapping().get("cu-new")).isNotEqualTo("book-m-u02");
    }

    @Test
    @DisplayName("映射可往返：写回草稿后再次映射得到同一批 ID")
    void mappingRoundTripIsStable() {
        AdminTextbookDraft input = draft(
                List.of(new DraftUnit(null, "cu1", "第一单元", 1)),
                List.of(new DraftLesson(null, "cl1", null, "cu1", "天地人", 1)),
                List.of(new DraftEntry("row-1", null, null, "cu1", null, "cl1", "山", "shān", null,
                        List.of("writing"), true)));
        DraftToBookMapper.MappedBook first = mapper.map("book-m", input, "1.0.0");
        DraftToBookMapper.MappedBook second = mapper.map("book-m",
                input.withIdMapping(first.persistedMapping()), "1.0.0");
        assertThat(second.units().get(0).unitId()).isEqualTo(first.units().get(0).unitId());
        assertThat(second.lessons().get(0).lessonId()).isEqualTo(first.lessons().get(0).lessonId());
        assertThat(second.entries().get(0).entryId()).isEqualTo(first.entries().get(0).entryId());
    }

    @Test
    @DisplayName("伪造的核对标记不被采信：confirmed=true 但无摘要时仍为未核对（B11）")
    void forgedConfirmationWithoutDigestIsRejected() {
        // 客户端直接把 confirmed 置真、不给任何摘要——服务端必须不认。
        AdminTextbookDraft input = draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(new DraftEntry("r1", null, null, "cu1", null, null, "山", "shān", null,
                        List.of("writing"), true)));
        var entry = mapper.map("book-m", input, "1.0.0").entries().get(0);
        assertThat(entry.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
    }

    @Test
    @DisplayName("确认后内容被改动时，旧摘要不再匹配，核对状态被撤销（B11）")
    void changedContentRevokesConfirmation() {
        // 先按内容算出正确摘要并确认。
        AdminTextbookDraft.DraftEntry verified = new AdminTextbookDraft.DraftEntry("r1", null, null, "cu1",
                null, null, "山", "shān", null, List.of("writing"), true,
                DraftToBookMapper.contentDigest(new AdminTextbookDraft.DraftEntry("r1", null, null, "cu1",
                        null, null, "山", "shān", null, List.of("writing"), true)));
        AdminTextbookDraft confirmed = draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(verified));
        assertThat(mapper.map("book-m", confirmed, "1.0.0").entries().get(0).reviewStatus())
                .isEqualTo(ReviewStatus.PUBLISHED);

        // 内容改成"水"，摘要却仍是旧的——服务端必须撤销核对状态。
        AdminTextbookDraft.DraftEntry tampered = new AdminTextbookDraft.DraftEntry("r1", null, null, "cu1",
                null, null, "水", "shān", null, List.of("writing"), true, verified.confirmedDigest());
        AdminTextbookDraft changed = draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(tampered));
        assertThat(mapper.map("book-m", changed, "1.0.0").entries().get(0).reviewStatus())
                .isEqualTo(ReviewStatus.DRAFT);
    }

    @Test
    @DisplayName("摘要只覆盖影响含义的字段：类别变化不影响核对可信度（B11）")
    void digestIgnoresListTypes() {
        AdminTextbookDraft.DraftEntry base = new AdminTextbookDraft.DraftEntry("r1", null, null, "cu1",
                null, null, "山", "shān", null, List.of("writing"), true);
        String digest = DraftToBookMapper.contentDigest(base);
        // 仅类别不同：摘要必须相同，因此核对仍可信（类别不影响含义）。
        AdminTextbookDraft.DraftEntry otherTypes = new AdminTextbookDraft.DraftEntry("r1", null, null, "cu1",
                null, null, "山", "shān", null, List.of("recognition", "vocabulary"), true, digest);
        assertThat(DraftToBookMapper.contentDigest(otherTypes)).isEqualTo(digest);
        var mapped = mapper.map("book-m", draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(otherTypes)), "1.0.0");
        assertThat(mapped.entries().get(0).reviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
    }

    @Test
    @DisplayName("条目顺序按草稿顺序连续编号，无课文时 lessonId 为空")
    void orderingAndOptionalLesson() {
        AdminTextbookDraft input = draft(List.of(new DraftUnit(null, "cu1", "一", 1)), List.of(),
                List.of(new DraftEntry("r1", null, null, "cu1", null, null, "a", null, "甲", List.of(), true),
                        new DraftEntry("r2", null, null, "cu1", null, null, "b", null, "乙", List.of(), true)));
        var entries = mapper.map("book-m", input, "1.0.0").entries();
        assertThat(entries.get(0).order()).as("0 基（用户 2026-10-07 拍板，与生产内容包一致）").isEqualTo(0);
        assertThat(entries.get(1).order()).isEqualTo(1);
        assertThat(entries.get(0).lessonId()).isNull();
    }
}
