package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.curriculum.DraftToBookMapper;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftEntry;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F09：正式 ID **不能按数组位置生成**，否则前插会碰撞。
 *
 * 审查探针复现的形态（`curriculum-probe-output.txt`）：
 *
 * ```
 * INSERT_BEFORE_PERSISTED_ROW: ids=[audit-book-e0001, audit-book-e0001]
 * INSERT_BEFORE_PERSISTED_ROW: uniqueIds=1; entries=2
 * ```
 *
 * **两条内容拿到同一个正式 ID**——它们在数据库里是同一行，
 * 其中一条会覆盖另一条，而用户完全看不出来。
 */
class DraftToBookIdCollisionTest {

    private final DraftToBookMapper mapper = new DraftToBookMapper();

    /** 已持久化的映射：row-1 原本是 e0001。 */
    private static final Map<String, String> PERSISTED = Map.of("e:row-1", "audit-book-e0001");

    private static AdminTextbookDraft draftWith(List<DraftEntry> entries) {
        return new AdminTextbookDraft("audit-book", "chinese", 1, "first", "人民教育出版社",
                "统编语文", "2024 年修订版", "附录", "partial",
                List.of(new DraftUnit("audit-book-u01", "cu1", "第一单元", 1)),
                List.of(), entries, PERSISTED);
    }

    private static DraftEntry entry(String rowId, String entryId, String text) {
        return new DraftEntry(rowId, entryId, null, "cu1", null, null,
                text, null, null, List.of("writing"), false);
    }

    @Test
    @DisplayName("在既有条目**前面**插入新行时，两个正式 ID 必须不同")
    void insertBeforePersistedRowDoesNotCollide() {
        // 新行在前（下标 0），既有行在后（下标 1）。
        // 旧实现给新行按位置生成 e0001，而既有行的正式 ID 恰好也是 e0001 → 碰撞。
        var mapped = mapper.map("audit-book", draftWith(List.of(
                entry("row-new", null, "新"),
                entry("row-1", "audit-book-e0001", "山"))), "1.0.0");

        assertThat(mapped.entries()).hasSize(2);
        List<String> ids = mapped.entries().stream().map(e -> e.entryId()).toList();
        assertThat(ids).as("两个条目不能拿到同一个正式 ID：%s", ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("既有条目的正式 ID 必须原样沿用（不能因为前插就漂移）")
    void persistedIdentityIsPreserved() {
        var mapped = mapper.map("audit-book", draftWith(List.of(
                entry("row-new", null, "新"),
                entry("row-1", "audit-book-e0001", "山"))), "1.0.0");

        // 前插不应改变既有内容的身份——否则"改一个字"会变成"删一条、加一条"。
        assertThat(mapped.entries().stream().map(e -> e.entryId()).toList())
                .contains("audit-book-e0001");
    }

    @Test
    @DisplayName("同一次映射里连续分配的多行也不能互相碰撞")
    void multipleNewRowsDoNotCollide() {
        // 既有 e0001 在前，后面跟两个全新行——它们都不能落到已占用的 ID 上。
        var mapped = mapper.map("audit-book", draftWith(List.of(
                entry("row-1", "audit-book-e0001", "山"),
                entry("row-a", null, "水"),
                entry("row-b", null, "火"))), "1.0.0");

        List<String> ids = mapped.entries().stream().map(e -> e.entryId()).toList();
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        assertThat(ids).contains("audit-book-e0001");
    }

    @Test
    @DisplayName("单元与课文的前插同样不碰撞")
    void unitAndLessonAllocationIsCollisionFree() {
        // 旧实现里单元／课文走的是同一套"按位置生成 + 复用旧映射"的逻辑，
        // 因此有相同的碰撞风险——这里一并锁定。
        var unitNew = new AdminTextbookDraft.DraftUnit(null, "cu-new", "新单元", 0);
        var unitOld = new AdminTextbookDraft.DraftUnit("audit-book-u01", "cu1", "第一单元", 1);
        var mapped = mapper.map("audit-book", new AdminTextbookDraft("audit-book", "chinese", 1, "first",
                "人民教育出版社", "统编语文", "2024 年修订版", "附录", "partial",
                List.of(unitNew, unitOld), List.of(), List.of(), Map.of("u:cu1", "audit-book-u01")), "1.0.0");

        List<String> ids = mapped.units().stream().map(u -> u.unitId()).toList();
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(ids).contains("audit-book-u01");
    }
}
