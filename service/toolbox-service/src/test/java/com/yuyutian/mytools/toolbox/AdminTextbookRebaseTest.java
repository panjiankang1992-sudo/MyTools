package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminIdempotencyService;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookService;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实事务回归：导入后草稿落后时，安全同步、幂等重试及连续发布均可完成。 */
@SpringBootTest(properties = "toolbox.curriculum.repository=database")
@ActiveProfiles("test")
class AdminTextbookRebaseTest {
    @Autowired private CurriculumFileImporter importer;
    @Autowired private AdminTextbookRepository repository;
    @Autowired private AdminTextbookService service;
    @Autowired private AdminIdempotencyService idempotency;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:draft-rebase;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void cleanIsolatedDatabase() {
        jdbc.update("DELETE FROM toolbox_textbook_version");
        jdbc.update("DELETE FROM toolbox_textbook_draft");
        jdbc.update("DELETE FROM toolbox_textbook");
        jdbc.update("DELETE FROM toolbox_curriculum_audit");
    }

    private ObjectNode book(String editionId, String version) throws Exception {
        return (ObjectNode) json.readTree("""
                {"edition":{"editionId":"%s","contentVersion":"%s","subject":"english",
                "grade":4,"semester":"first","publisher":"publisher","series":"series",
                "editionLabel":"2024","sourceNote":"manual input","coverageStatus":"partial",
                "regionCodes":["jiangsu"],"sourceIds":[]},
                "units":[{"unitId":"unit-one","title":"Unit 1","order":1}],"lessons":[],
                "entries":[{"entryId":"word-one","unitId":"unit-one","text":"dog","meaning":"dog meaning",
                "listTypes":["vocabulary"],"sourceRefs":[]},
                {"entryId":"word-two","unitId":"unit-one","text":"cat","meaning":"cat meaning",
                "listTypes":["vocabulary"],"sourceRefs":[]}]}
                """.formatted(editionId, version));
    }

    private void install(String editionId, String version, ObjectNode book) throws Exception {
        importer.importEdition(editionId, version, json.writeValueAsBytes(book), "hash-" + version,
                json.writeValueAsString(book.path("edition")), "test-admin");
    }

    private AdminTextbookDraft draft(ObjectNode value) throws Exception {
        return json.treeToValue(value, AdminTextbookDraft.class);
    }

    @Test
    void importSaveImportRebaseAndPublishTwicePreservesBothChanges() throws Exception {
        String edition = "rebase-success";
        ObjectNode initial = book(edition, "1.0.0");
        install(edition, "1.0.0", initial);
        var stored = service.findDraft(edition).orElseThrow();
        ObjectNode edited = json.valueToTree(stored.draft());
        ((ObjectNode) edited.path("entries").get(1)).put("meaning", "edited cat meaning");
        ObjectNode added = ((ObjectNode) edited.path("entries").get(0)).deepCopy();
        added.putNull("entryId").put("rowId", "new-row-fox").put("text", "fox").put("meaning", "fox meaning");
        ((ArrayNode) edited.path("entries")).add(added);
        long revision = service.saveDraft(edition, stored.revision(), draft(edited), "test-admin");
        ObjectNode latest = book(edition, "1.0.1");
        ((ObjectNode) latest.path("entries").get(0)).put("meaning", "latest dog meaning");
        install(edition, "1.0.1", latest);
        assertThatThrownBy(() -> service.publish(edition, "test-admin", revision))
                .isInstanceOf(BusinessException.class);
        var merged = service.rebaseDraft(edition, revision, "test-admin");
        assertThat(merged.baseContentVersion()).isEqualTo(edition + "@1.0.1");
        assertThat(merged.revision()).isEqualTo(revision + 1);
        assertThat(merged.draft().entries()).extracting(AdminTextbookDraft.DraftEntry::meaning)
                .containsExactly("latest dog meaning", "edited cat meaning", "fox meaning");
        assertThat(merged.draft().entries().get(2).entryId()).isNull();
        var first = service.publish(edition, "test-admin", merged.revision());
        var saved = service.findDraft(edition).orElseThrow();
        var second = service.publish(edition, "test-admin", saved.revision());
        assertThat(second.contentVersion()).isNotEqualTo(first.contentVersion());
        assertThat(service.findDraft(edition).orElseThrow().baseContentVersion()).isEqualTo(second.versionId());
    }

    @Test
    void conflictingChangesDoNotWriteAnyDraftOrAudit() throws Exception {
        String edition = "rebase-conflict";
        install(edition, "1.0.0", book(edition, "1.0.0"));
        var stored = service.findDraft(edition).orElseThrow();
        ObjectNode edited = json.valueToTree(stored.draft());
        ((ObjectNode) edited.path("entries").get(0)).put("meaning", "local different meaning");
        long revision = service.saveDraft(edition, stored.revision(), draft(edited), "test-admin");
        ObjectNode latest = book(edition, "1.0.1");
        ((ObjectNode) latest.path("entries").get(0)).put("meaning", "current different meaning");
        install(edition, "1.0.1", latest);
        var before = repository.findDraft("draft-" + edition).orElseThrow();
        assertThatThrownBy(() -> service.rebaseDraft(edition, revision, "test-admin"))
                .isInstanceOf(BusinessException.class).satisfies(failure ->
                        assertThat(((BusinessException) failure).details().get("reason")).isEqualTo("rebaseConflict"));
        assertThat(repository.findDraft("draft-" + edition).orElseThrow()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_curriculum_audit WHERE edition_id = ? AND action = ?",
                Integer.class, edition, "draft.rebase")).isZero();
    }

    @Test
    void rebaseReceiptReplaysAndStaleRevisionCannotOverwrite() throws Exception {
        String edition = "rebase-idempotency";
        install(edition, "1.0.0", book(edition, "1.0.0"));
        long revision = service.findDraft(edition).orElseThrow().revision();
        install(edition, "1.0.1", book(edition, "1.0.1"));
        var payload = Map.of("editionId", edition, "expectedRevision", revision);
        Map<?, ?> first = idempotency.execute("test-admin", "draft.rebase:" + edition, "rebase-key", payload,
                Map.class, () -> Map.of("revision", service.rebaseDraft(edition, revision, "test-admin").revision()));
        Map<?, ?> replay = idempotency.execute("test-admin", "draft.rebase:" + edition, "rebase-key", payload,
                Map.class, () -> { throw new AssertionError("must replay"); });
        assertThat(replay.get("revision").toString()).isEqualTo(first.get("revision").toString());
        assertThat(repository.findDraft("draft-" + edition).orElseThrow().revision()).isEqualTo(revision + 1);
        assertThatThrownBy(() -> service.rebaseDraft(edition, revision, "test-admin"))
                .isInstanceOf(BusinessException.class);
        assertThat(repository.rebaseDraft("draft-" + edition, revision + 1, edition + "@1.0.1",
                edition + "@1.0.0", "{}", null, "test-admin")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_curriculum_audit WHERE edition_id = ? AND action = ?",
                Integer.class, edition, "draft.rebase")).isEqualTo(1);
    }
}
