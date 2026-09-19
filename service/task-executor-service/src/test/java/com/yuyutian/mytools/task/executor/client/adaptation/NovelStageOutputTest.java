package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Phase;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Result;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NovelStageOutputTest {
    private final NovelStageOutput normalizer = new NovelStageOutput();

    @Test
    void preservesValidPlanAndCriticAsCanonicalJsonOnly() {
        for (Phase phase : List.of(Phase.PLAN, Phase.CRITIC)) {
            ObjectNode payload = phase == Phase.PLAN ? plan() : critic();
            var terminal = normalizer.normalize(phase, success(NovelProviderJson.encode(payload)));
            assertThat(terminal.status()).isEqualTo("SUCCEEDED");
            assertThat(terminal.outputText()).isNull();
            assertThat(terminal.structuredJson()).isEqualTo(NovelProviderJson.encode(payload));
            assertThat(terminal.toString()).doesNotContain("Ari", "preservedFacts", "checks");
        }
        ObjectNode conflict = plan().put("intentDisposition", "CONFLICT");
        assertThat(normalizer.normalize(Phase.PLAN, success(NovelProviderJson.encode(conflict))).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsUnknownFieldsAtEveryPlanObjectLevelBeforeConstructingSettlement() {
        for (String location : List.of("root", "preservedFacts", "entities", "events", "expansionPoints", "requiredEndingState")) {
            ObjectNode value = plan();
            ObjectNode target = location.equals("root") ? value : location.equals("requiredEndingState")
                    ? (ObjectNode) value.path(location) : (ObjectNode) value.path(location).get(0);
            target.put("reasoning", "hidden-fixture-reasoning");
            var terminal = normalizer.normalize(Phase.PLAN, success(NovelProviderJson.encode(value)));
            assertThat(terminal.errorCode()).as(location).isEqualTo("READER_041");
            assertThat(terminal.structuredJson()).isNull();
            assertThat(terminal.outputText()).isNull();
        }
    }

    @Test
    void rejectsCriticMissingChecksUnknownNestedFieldsAndInconsistentOutcome() {
        ObjectNode missing = critic(); missing.withArray("checks").remove(0);
        ObjectNode duplicate = critic(); duplicate.withArray("checks").set(0, duplicate.path("checks").get(1).deepCopy());
        ObjectNode extra = critic(); ((ObjectNode) extra.path("checks").get(0).path("evidence")).put("analysis", "hidden-reasoning");
        ObjectNode inconsistent = critic(); ((ObjectNode) inconsistent.path("checks").get(0)).put("verdict", "FAIL");
        ObjectNode unknown = critic(); ((ObjectNode) unknown.path("checks").get(0)).put("verdict", "UNKNOWN").putNull("evidence");
        for (ObjectNode value : List.of(missing, duplicate, extra, inconsistent, unknown)) {
            var result = normalizer.normalize(Phase.CRITIC, success(NovelProviderJson.encode(value)));
            assertThat(result.errorCode()).isEqualTo("READER_041");
            assertThat(result.structuredJson()).isNull();
        }
        unknown.put("outcome", "BLOCKED").withArray("issues").addObject().put("category", "FACTS").put("severity", "BLOCKED").put("summary", "Insufficient evidence.");
        assertThat(normalizer.normalize(Phase.CRITIC, success(NovelProviderJson.encode(unknown))).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsBadSpansEnumsDuplicateFactsAndOversizedStructuredPayloads() {
        ObjectNode badSpan = plan(); ((ObjectNode) badSpan.path("entities").get(0)).put("sourceEnd", 4);
        ObjectNode invented = plan(); ((ObjectNode) invented.path("expansionPoints").get(0)).put("additionType", "NEW_CHARACTER");
        ObjectNode duplicate = plan(); duplicate.withArray("preservedFacts").add(duplicate.path("preservedFacts").get(0).deepCopy());
        ObjectNode unknownFact = plan(); ((ObjectNode) unknownFact.path("events").get(0)).put("factId", "F99");
        for (ObjectNode value : List.of(badSpan, invented, duplicate, unknownFact)) {
            assertThat(normalizer.normalize(Phase.PLAN, success(NovelProviderJson.encode(value))).errorCode()).isEqualTo("READER_041");
        }
        assertThat(normalizer.normalize(Phase.PLAN, success(" ".repeat(65537))).errorCode()).isEqualTo("READER_041");
        assertThat(normalizer.normalize(Phase.PLAN, success("{} {} ")).errorCode()).isEqualTo("READER_041");
    }

    @Test
    void candidatesRemainPlainTextAndFailuresCannotCarryBodies() {
        for (Phase phase : List.of(Phase.GENERATE, Phase.REPAIR)) {
            var valid = normalizer.normalize(phase, success("Ari closed the door."));
            assertThat(valid.outputText()).isEqualTo("Ari closed the door.");
            assertThat(valid.structuredJson()).isNull();
            for (String body : List.of("  ```\nAri\n```", "# Title", "<HTML>bad</HTML>", "<!DOCTYPE html>", " ")) {
                var result = normalizer.normalize(phase, success(body));
                assertThat(result.errorCode()).isEqualTo("READER_041");
                assertThat(result.outputText()).isNull();
            }
        }
        var failure = normalizer.normalize(Phase.PLAN, new Result("FAILED", "untrusted-hidden-content", null, null, null, null,
                403, "READER_055", null));
        assertThat(failure.errorCode()).isEqualTo("READER_055");
        assertThat(failure.outputText()).isNull();
        assertThat(failure.structuredJson()).isNull();
    }

    @Test
    void rejectsWrongFactCategoryQuoteAndOverlappingEventsBeforeReaderSettlement() {
        ObjectNode category = plan(); ((ObjectNode) category.at("/preservedFacts/0")).put("category", "IDENTITY");
        ObjectNode quote = plan(); ((ObjectNode) quote.at("/events/0")).put("anchor", "x".repeat(15));
        ObjectNode outside = plan(); ((ObjectNode) outside.at("/requiredEndingState")).put("sourceStart", 5).put("sourceEnd", 20);
        ObjectNode overlapping = plan(); overlapping.withArray("events").add(overlapping.at("/events/0").deepCopy());
        for (ObjectNode value : List.of(category, quote, outside, overlapping)) {
            var result = normalizer.normalize(Phase.PLAN, success(NovelProviderJson.encode(value)));
            assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.errorCode()).isEqualTo("READER_041");
            assertThat(result.structuredJson()).isNull(); assertThat(result.outputText()).isNull();
        }
    }

    @Test
    void acceptsExistingStateAsAnOrderedPlotAnchorWithoutReclassifyingIt() {
        ObjectNode state = plan();
        ((ObjectNode) state.at("/preservedFacts/0")).put("category", "STATE");
        var result = normalizer.normalize(Phase.PLAN, success(NovelProviderJson.encode(state)));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(NovelProviderJson.parse(result.structuredJson()).at("/preservedFacts/0/category").asText()).isEqualTo("STATE");
    }

    @Test
    void rejectsOverflowingAndOutOfChapterEvidenceOffsets() {
        for (Phase phase : List.of(Phase.PLAN, Phase.CRITIC)) {
            ObjectNode value = phase == Phase.PLAN ? plan() : critic();
            ObjectNode span = (ObjectNode) value.at(phase == Phase.PLAN ? "/expansionPoints/0" : "/checks/0/evidence");
            String field = phase == Phase.PLAN ? "anchor" : "quote";
            span.put(field, "xx").put("sourceStart", Integer.MAX_VALUE).put("sourceEnd", Integer.MIN_VALUE + 1);
            assertThat(normalizer.normalize(phase, success(NovelProviderJson.encode(value))).errorCode()).isEqualTo("READER_041");
            span.put("sourceStart", 120000).put("sourceEnd", 120002);
            assertThat(normalizer.normalize(phase, success(NovelProviderJson.encode(value))).errorCode()).isEqualTo("READER_041");
        }
    }

    static ObjectNode plan() {
        ObjectNode node = NovelProviderJson.MAPPER.createObjectNode().put("schemaVersion", "adaptation-plan-v1")
                .put("originalSha256", NovelProviderJson.sha("Ari closed the door.".getBytes(StandardCharsets.UTF_8)))
                .put("intentDisposition", "COMPATIBLE").put("intentSummary", "Expand immediate sensory detail.").put("pointOfView", "THIRD_LIMITED");
        node.putArray("preservedFacts").addObject().put("id", "F1").put("category", "EVENT").put("statement", "Ari closes the door.")
                .put("sourceQuote", "closed the door.").put("sourceStart", 4).put("sourceEnd", 20);
        node.putArray("entities").addObject().put("name", "Ari").put("kind", "PERSON").put("sourceStart", 0).put("sourceEnd", 3);
        ObjectNode anchor = NovelProviderJson.MAPPER.createObjectNode().put("factId", "F1").put("anchor", "closed the door").put("sourceStart", 4).put("sourceEnd", 19);
        node.putArray("events").add(anchor.deepCopy()); node.set("requiredEndingState", anchor);
        node.putArray("expansionPoints").addObject().put("anchor", "closed the door").put("sourceStart", 4).put("sourceEnd", 19)
                .put("additionType", "SENSORY").put("purpose", "Describe the existing closing action.");
        node.putArray("forbiddenChanges").add("Do not reopen the door.");
        return node;
    }
    static ObjectNode critic() {
        ObjectNode node = NovelProviderJson.MAPPER.createObjectNode().put("schemaVersion", "adaptation-critic-v1")
                .put("candidateSha256", "0".repeat(64)).put("constraintSha256", "1".repeat(64)).put("outcome", "PASS");
        var checks = node.putArray("checks");
        for (String category : List.of("FACTS", "ENTITIES", "NUMBERS", "EVENT_ORDER", "ENTRY_STATE", "ENDING_STATE", "POINT_OF_VIEW", "WORLD_RULES", "INTENT", "SAFETY")) {
            checks.addObject().put("category", category).put("verdict", "PASS").putObject("evidence")
                    .put("quote", "Ari").put("sourceStart", 0).put("sourceEnd", 3);
        }
        node.putArray("issues"); return node;
    }
    private static Result success(String content) {
        return new Result("SUCCEEDED", content, "stop", "fixture-id", 100, 50, 200, null,
                NovelProviderJson.sha(content.getBytes(StandardCharsets.UTF_8)));
    }
}
