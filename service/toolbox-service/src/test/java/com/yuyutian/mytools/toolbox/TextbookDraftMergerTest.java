package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.DraftToBookMapper;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.TextbookDraftMerger;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 三方同步必须保留管理员内容及最新教材修正；模糊身份和相同字段冲突不能生成可写入结果。 */
class TextbookDraftMergerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TextbookDraftMerger merger = new TextbookDraftMerger(JSON);

    @Test
    void preservesIndependentCorrectionsAdditionsAndLocalDeletion() {
        ObjectNode base = book(entry("e1", "apple", "fruit"), entry("e2", "dog", "animal"),
                entry("e3", "old", "remove"));
        ObjectNode current = base.deepCopy();
        row(current, "e1").put("meaning", "fresh fruit");
        rows(current).add(entry("e4", "cat", "new published animal"));
        ObjectNode local = base.deepCopy();
        row(local, "e2").put("meaning", "pet");
        rows(local).remove(2);
        rows(local).add(entry("e5", "sun", "local addition"));

        var result = merge(base, current, local);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.draft().entries()).extracting(AdminTextbookDraft.DraftEntry::entryId)
                .containsExactlyInAnyOrder("e1", "e2", "e4", "e5");
        assertThat(find(result.draft(), "e1").meaning()).isEqualTo("fresh fruit");
        assertThat(find(result.draft(), "e2").meaning()).isEqualTo("pet");
        assertThat(find(result.draft(), "e4").meaning()).isEqualTo("new published animal");
        assertThat(find(result.draft(), "e5").meaning()).isEqualTo("local addition");
        // 输入快照本身不应被原地改写，调用方才能安全重试或展示冲突前的内容。
        assertThat(row(base, "e1").path("meaning").asText()).isEqualTo("fruit");
        assertThat(rows(local)).hasSize(3);
    }

    @Test
    void rejectsConcurrentChangesToTheSameFieldWithoutReturningPartialDraft() {
        ObjectNode base = book(entry("e1", "apple", "fruit"));
        ObjectNode current = base.deepCopy();
        ObjectNode local = base.deepCopy();
        row(current, "e1").put("meaning", "published correction");
        row(local, "e1").put("meaning", "my correction");

        var result = merge(base, current, local);
        assertThat(result.draft()).isNull();
        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.entityId()).isEqualTo("e1");
            assertThat(conflict.field()).isEqualTo("meaning");
            assertThat(conflict.reason()).isEqualTo("differentChanges");
        });
        assertThat(row(local, "e1").path("meaning").asText()).isEqualTo("my correction");
    }

    @Test
    void rejectsPublishedDeletionOfLocallyChangedContent() {
        ObjectNode base = book(entry("e1", "apple", "fruit"));
        ObjectNode current = base.deepCopy();
        rows(current).removeAll();
        ObjectNode local = base.deepCopy();
        row(local, "e1").put("meaning", "my correction");

        var result = merge(base, current, local);
        assertThat(result.draft()).isNull();
        assertThat(result.conflicts()).anyMatch(conflict -> "deleteModify".equals(conflict.reason()));
    }

    @Test
    void rejectsDeletedParentWithNewLocalChildren() {
        ObjectNode base = book();
        ObjectNode current = base.deepCopy();
        ((ArrayNode) current.path("units")).removeAll();
        ObjectNode local = base.deepCopy();
        rows(local).add(entry("e-new", "apple", "local addition"));

        var result = merge(base, current, local);
        assertThat(result.draft()).isNull();
        assertThat(result.conflicts()).anyMatch(conflict -> "missingParent".equals(conflict.reason()));
    }

    @Test
    void preservesMetadataSourcesAndLegacyOpaqueSourceReferences() {
        ObjectNode base = book(entry("e1", "apple", "fruit"));
        ObjectNode original = row(base, "e1");
        var source = JSON.createObjectNode().put("sourceId", "source-a").put("locator", "page 8").put("page", 8);
        original.withObject("/preserved").set("sourceRefs", JSON.createArrayNode().add(source));
        original.withObject("/preserved").put("senseId", "sense-a");
        original.withObject("/preserved").put("context", "original context");
        original.withObject("/preserved").set("pinyinSegments", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("grapheme", "a").put("syllable", "a")));
        ObjectNode current = base.deepCopy();
        current.put("isbn", "published-isbn");
        row(current, "e1").withObject("/preserved").put("context", "published context");
        ObjectNode local = base.deepCopy();
        local.put("sourceNote", "my source note");
        row(local, "e1").withObject("/preserved").put("senseId", "my-sense");

        var result = merge(base, current, local);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.draft().isbn()).isEqualTo("published-isbn");
        assertThat(result.draft().sourceNote()).isEqualTo("my source note");
        assertThat(result.draft().sourceIds()).containsExactly("source-a");
        var merged = find(result.draft(), "e1");
        assertThat(merged.preserved()).containsEntry("context", "published context")
                .containsEntry("senseId", "my-sense").containsKey("pinyinSegments");
        assertThat(merged.sourceRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.sourceId()).isEqualTo("source-a");
            assertThat(ref.page()).isEqualTo(8);
        });
    }

    @Test
    void preservesExistingClientUuidAndResolvesItsPublishedIdentity() {
        ObjectNode base = book(entry("e1", "apple", "fruit"));
        ObjectNode current = base.deepCopy();
        row(current, "e1").withObject("/preserved").put("context", "published context");
        ObjectNode local = base.deepCopy();
        row(local, "e1").putNull("entryId").put("rowId", "my-client-uuid").put("meaning", "my meaning");
        local.withObject("/idMapping").put("e:my-client-uuid", "e1");

        var result = merge(base, current, local);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.draft().entries()).hasSize(1);
        var merged = find(result.draft(), "e1");
        assertThat(merged.rowId()).isEqualTo("my-client-uuid");
        assertThat(merged.meaning()).isEqualTo("my meaning");
        assertThat(merged.preserved()).containsEntry("context", "published context");
        assertThat(result.draft().idMapping()).containsEntry("e:my-client-uuid", "e1");
    }

    @Test
    void keepsPublishedSegmentCorrectionWhenVisibleTextWasNotChangedLocally() {
        ObjectNode base = book(entry("e1", "\u884c", "walk").put("pinyin", "x\u00edng"));
        row(base, "e1").withObject("/preserved").set("pinyinSegments", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("grapheme", "\u884c").put("syllable", "xing")));
        ObjectNode current = base.deepCopy();
        ArrayNode corrected = JSON.createArrayNode()
                .add(JSON.createObjectNode().put("grapheme", "\u884c").put("syllable", "x\u00edng"));
        row(current, "e1").withObject("/preserved").set("pinyinSegments", corrected);
        ObjectNode local = base.deepCopy();
        row(local, "e1").withObject("/preserved").put("context", "my context");

        var result = merge(base, current, local);
        assertThat(result.conflicts()).isEmpty();
        var merged = find(result.draft(), "e1");
        assertThat(merged.preserved()).containsEntry("context", "my context");
        JsonNode actual = JSON.valueToTree(merged.preserved().get("pinyinSegments"));
        assertThat(actual).isEqualTo(corrected);
    }

    @Test
    void keepsNewClientOnlyHierarchyReadyForNormalIdAllocation() {
        ObjectNode base = book(entry("e1", "apple", "fruit"));
        ObjectNode current = base.deepCopy();
        current.put("sourceNote", "published source");
        ObjectNode local = base.deepCopy();
        ((ArrayNode) local.path("units")).add(JSON.createObjectNode().putNull("unitId")
                .put("clientUnitId", "new-unit-client").put("title", "New unit").put("order", 2));
        ((ArrayNode) local.path("lessons")).add(JSON.createObjectNode().putNull("lessonId")
                .put("clientLessonId", "new-lesson-client").putNull("unitId")
                .put("clientUnitId", "new-unit-client").put("title", "New lesson").put("order", 1));
        rows(local).add(entry("new-row-client", "sun", "local addition").putNull("entryId")
                .putNull("unitId").put("clientUnitId", "new-unit-client")
                .putNull("lessonId").put("clientLessonId", "new-lesson-client"));

        var result = merge(base, current, local);
        assertThat(result.conflicts()).isEmpty();
        var added = result.draft().entries().stream().filter(item -> "sun".equals(item.text())).findFirst().orElseThrow();
        assertThat(added.entryId()).isNull();
        assertThat(added.rowId()).isEqualTo("new-row-client");
        assertThat(added.unitId()).isNull();
        assertThat(added.clientUnitId()).isEqualTo("new-unit-client");
        assertThat(added.lessonId()).isNull();
        assertThat(added.clientLessonId()).isEqualTo("new-lesson-client");
        assertThat(result.draft().units()).anySatisfy(unit -> {
            assertThat(unit.unitId()).isNull();
            assertThat(unit.clientUnitId()).isEqualTo("new-unit-client");
        });
        var published = new DraftToBookMapper().map("test-book", result.draft(), "1.0.6");
        assertThat(published.entries()).hasSize(2);
        assertThat(published.entries().stream().filter(item -> "sun".equals(item.text())).findFirst().orElseThrow().entryId())
                .startsWith("test-book-e").isNotEqualTo("new-row-client");
    }

    @Test
    void remapsRenumberedIdsWithoutReusingOldAliasForAnotherWord() {
        ObjectNode base = book(entry("e1", "apple", "fruit"), entry("e2", "dog", "animal"));
        ObjectNode current = book(entry("e1", "dog", "animal"), entry("e2", "apple", "fruit"));
        ObjectNode local = base.deepCopy();
        row(local, "e1").put("meaning", "my fruit");

        var result = merge(base, current, local);
        assertThat(result.conflicts()).isEmpty();
        assertThat(find(result.draft(), "e2").text()).isEqualTo("apple");
        assertThat(find(result.draft(), "e2").meaning()).isEqualTo("my fruit");
        assertThat(find(result.draft(), "e1").text()).isEqualTo("dog");
        assertThat(find(result.draft(), "e1").meaning()).isEqualTo("animal");
        // 每个保留的客户端行别名必须指向它实际所在的行，不能因编号复用串词。
        for (var entry : result.draft().entries()) {
            assertThat(result.draft().idMapping()).containsEntry("e:" + entry.rowId(), entry.entryId());
        }
        assertThat(result.draft().entries()).extracting(AdminTextbookDraft.DraftEntry::rowId).doesNotHaveDuplicates();
    }

    @Test
    void rejectsAmbiguousSemanticMatchesAfterRenumbering() {
        ObjectNode base = book(entry("e1", "apple", "fruit"), entry("e2", "dog", "animal"));
        ObjectNode current = book(entry("e1", "dog", "animal"), entry("e2", "apple", "fruit"),
                entry("e3", "apple", "different sense"));

        var result = merge(base, current, base.deepCopy());
        assertThat(result.draft()).isNull();
        assertThat(result.conflicts()).anyMatch(conflict -> "ambiguousIdentity".equals(conflict.reason()));
    }

    @Test
    void neverAppliesOldMeaningToDifferentWordWhenOnlyOneIdentityWasReused() {
        ObjectNode base = book(entry("e1", "basketball", "ball"));
        ObjectNode current = book(entry("e1", "football", "ball"), entry("e2", "basketball", "ball"));
        ObjectNode local = base.deepCopy();
        row(local, "e1").put("meaning", "my basketball meaning");

        var result = merge(base, current, local);
        // 只有一处复用也不能跨词应用改动；严格阻断或唯一对齐均可，但不能静默串词。
        if (result.conflicts().isEmpty()) {
            assertThat(find(result.draft(), "e1").text()).isEqualTo("football");
            assertThat(find(result.draft(), "e1").meaning()).isEqualTo("ball");
            assertThat(find(result.draft(), "e2").text()).isEqualTo("basketball");
            assertThat(find(result.draft(), "e2").meaning()).isEqualTo("my basketball meaning");
        } else {
            assertThat(result.draft()).isNull();
            assertThat(result.conflicts()).anyMatch(conflict -> "identity".equals(conflict.field()));
        }
    }

    @Test
    void joinsAlreadyAppliedAdditionButKeepsLiteralLocalMeaning() {
        ObjectNode base = book(entry("e1", "apple", "fruit"), entry("e2", "dog", "animal"));
        ObjectNode current = book(entry("e1", "dog", "animal"), entry("e2", "apple", "fruit"),
                entry("e3", "It's OK.", "\u6ca1\u5173\u7cfb\u3002"));
        row(current, "e3").withObject("/preserved").put("senseId", "published-sense");
        ObjectNode local = base.deepCopy();
        ObjectNode added = entry("my-added-row", "It's OK.", "\u6ca1\u5173\u7cfb").putNull("entryId");
        added.withObject("/preserved").put("context", "my context");
        rows(local).add(added);

        var result = merge(base, current, local);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.draft().entries()).hasSize(3);
        assertThat(find(result.draft(), "e3").meaning()).isEqualTo("\u6ca1\u5173\u7cfb");
        assertThat(find(result.draft(), "e3").rowId()).isEqualTo("my-added-row");
        assertThat(find(result.draft(), "e3").preserved()).containsEntry("senseId", "published-sense")
                .containsEntry("context", "my context");
    }

    @Test
    void doesNotTreatQuestionMarkAsEquivalentMeaning() {
        ObjectNode base = book(entry("e1", "apple", "fruit"), entry("e2", "dog", "animal"));
        ObjectNode current = book(entry("e1", "dog", "animal"), entry("e2", "apple", "fruit"),
                entry("e3", "It's OK.", "\u6ca1\u5173\u7cfb\uff1f"));
        ObjectNode local = base.deepCopy();
        rows(local).add(entry("my-added-row", "It's OK.", "\u6ca1\u5173\u7cfb").putNull("entryId"));

        var result = merge(base, current, local);
        assertThat(result.draft()).isNull();
        assertThat(result.conflicts()).isNotEmpty();
    }

    @Test
    void refusesMalformedOrIncompletePublishedSnapshots() throws Exception {
        AdminTextbookDraft draft = draft(book(entry("e1", "apple", "fruit")));
        for (String invalid : List.of("{", "{}", "[]", "{\"edition\":{},\"entries\":[]}")) {
            byte[] bytes = invalid.getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> merger.merge("test-book", bytes, bytes, draft))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private TextbookDraftMerger.MergeResult merge(ObjectNode base, ObjectNode current, ObjectNode local) {
        return merger.merge(draft(base), draft(current), draft(local));
    }

    private static AdminTextbookDraft draft(ObjectNode node) {
        try {
            return JSON.treeToValue(node, AdminTextbookDraft.class);
        } catch (Exception failure) {
            throw new IllegalStateException("invalid test draft", failure);
        }
    }

    private static ObjectNode book(ObjectNode... entries) {
        ObjectNode book = JSON.createObjectNode().put("editionId", "test-book").put("subject", "english")
                .put("grade", 4).put("semester", "first").put("publisher", "Test publisher")
                .put("series", "Test series").put("editionLabel", "2024")
                .put("sourceNote", "original source").put("coverageDeclaration", "partial");
        book.set("units", JSON.createArrayNode().add(JSON.createObjectNode().put("unitId", "u1")
                .put("clientUnitId", "u1").put("title", "Unit 1").put("order", 1)));
        book.set("lessons", JSON.createArrayNode());
        book.set("entries", JSON.createArrayNode());
        book.set("sourceIds", JSON.createArrayNode().add("source-a"));
        ObjectNode mapping = JSON.createObjectNode().put("u:u1", "u1");
        for (ObjectNode entry : entries) {
            rows(book).add(entry);
            mapping.put("e:" + entry.path("rowId").asText(), entry.path("entryId").asText());
        }
        book.set("idMapping", mapping);
        return book;
    }

    private static ObjectNode entry(String id, String text, String meaning) {
        ObjectNode row = JSON.createObjectNode().put("rowId", id).put("entryId", id)
                .put("unitId", "u1").put("clientUnitId", "u1").put("text", text)
                .putNull("pinyin").put("meaning", meaning).put("confirmed", true)
                .put("automaticInitialized", true);
        row.set("listTypes", JSON.createArrayNode().add("vocabulary"));
        row.set("preserved", JSON.createObjectNode());
        row.set("sourceRefs", JSON.createArrayNode());
        return row;
    }

    private static ArrayNode rows(ObjectNode book) {
        return (ArrayNode) book.path("entries");
    }

    private static ObjectNode row(ObjectNode book, String id) {
        for (var row : rows(book)) {
            if (id.equals(row.path("entryId").asText())) {
                return (ObjectNode) row;
            }
        }
        throw new IllegalArgumentException("test row missing");
    }

    private static AdminTextbookDraft.DraftEntry find(AdminTextbookDraft draft, String id) {
        return draft.entries().stream().filter(entry -> id.equals(entry.entryId())).findFirst().orElseThrow();
    }
}
