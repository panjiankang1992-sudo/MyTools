package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Phase;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NovelProviderQuoteProtocolTest {
    private static final String SOURCE = "Ari closed the door.";

    @Test
    void derivesCoordinatesAndDigestWithoutReclassifyingFacts() {
        for (String category : List.of("IDENTITY", "RELATIONSHIP", "STATE", "WORLD", "KNOWLEDGE", "EVENT")) {
            var wire = plan();
            ((ObjectNode) wire.at("/preservedFacts/0")).put("category", category);
            var result = decode(Phase.PLAN, wire, "\ud83d\ude80 " + SOURCE, null);
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            var canonical = NovelProviderJson.parse(result.structuredJson());
            assertThat(canonical.path("schemaVersion").asText()).isEqualTo("adaptation-plan-v2");
            assertThat(canonical.path("originalSha256").asText()).isEqualTo(sha("\ud83d\ude80 " + SOURCE));
            assertThat(canonical.at("/entities/0/sourceStart").intValue()).isEqualTo(2);
            assertThat(canonical.at("/preservedFacts/0/category").asText()).isEqualTo(category);
            assertThat(canonical.at("/events/0/sourceStart").intValue()).isEqualTo(6);
        }
    }

    @Test
    void rejectsInventedOrAmbiguousQuotesAndUsesExplicitOccurrenceOnly() {
        var wire = plan();
        assertFailed(decode(Phase.PLAN, wire, SOURCE + " " + SOURCE, null));
        ((ObjectNode) wire.at("/preservedFacts/0")).put("occurrence", 2);
        ((ObjectNode) wire.at("/expansionPoints/0")).put("occurrence", 2);
        var result = decode(Phase.PLAN, wire, SOURCE + " " + SOURCE, null);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(NovelProviderJson.parse(result.structuredJson()).at("/events/0/sourceStart").intValue()).isEqualTo(25);
        assertThat(result.structuredJson()).doesNotContain("occurrence");
        ((ObjectNode) wire.at("/preservedFacts/0")).put("sourceQuote", "opened the door.");
        assertFailed(decode(Phase.PLAN, wire, SOURCE, null));
    }

    @Test
    void rejectsUnknownFieldsCallerSuppliedHashesAndLegacyCoordinates() {
        for (String path : List.of("", "/preservedFacts/0", "/entities/0", "/events/0", "/requiredEndingState", "/expansionPoints/0")) {
            var wire = plan();
            ((ObjectNode) wire.at(path)).put("sourceStart", 0);
            assertFailed(decode(Phase.PLAN, wire, SOURCE, null));
        }
        assertFailed(decode(Phase.PLAN, plan().put("originalSha256", sha(SOURCE)), SOURCE, null));
        assertFailed(decode(Phase.PLAN, plan().put("schemaVersion", "adaptation-plan-v1"), SOURCE, null));
        var category = plan(); ((ObjectNode) category.at("/preservedFacts/0")).put("category", "DIALOGUE");
        assertFailed(decode(Phase.PLAN, category, SOURCE, null));
    }

    @Test
    void rejectsInvalidOccurrenceTypesBoundsAndNonexistentReferences() {
        for (JsonNode occurrence : List.of(NovelProviderJson.MAPPER.getNodeFactory().textNode("1"),
                NovelProviderJson.MAPPER.getNodeFactory().numberNode(0), NovelProviderJson.MAPPER.getNodeFactory().numberNode(2),
                NovelProviderJson.MAPPER.getNodeFactory().numberNode(Long.MAX_VALUE))) {
            var wire = plan(); ((ObjectNode) wire.at("/preservedFacts/0")).set("occurrence", occurrence);
            assertFailed(decode(Phase.PLAN, wire, SOURCE, null));
        }
        var wire = plan(); ((ObjectNode) wire.at("/events/0")).put("factId", "F999");
        assertFailed(decode(Phase.PLAN, wire, SOURCE, null));
        wire = plan(); wire.withArray("events").add(wire.at("/events/0").deepCopy());
        assertFailed(decode(Phase.PLAN, wire, SOURCE, null));
    }

    @Test
    void bindsCriticToActualCandidateAndKeepsAllJudgments() {
        var critic = critic();
        var result = decode(Phase.CRITIC, critic, "Ari waited.", "1".repeat(64));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        var canonical = NovelProviderJson.parse(result.structuredJson());
        assertThat(canonical.path("candidateSha256").asText()).isEqualTo(sha("Ari waited."));
        assertThat(canonical.path("constraintSha256").asText()).isEqualTo("1".repeat(64));
        assertFailed(decode(Phase.CRITIC, critic, "Ari moved.", "1".repeat(64)));
        assertFailed(decode(Phase.CRITIC, critic, "Ari waited.", null));
        ((ObjectNode) critic.at("/checks/0")).put("verdict", "UNKNOWN").putNull("evidence");
        critic.put("outcome", "BLOCKED").withArray("issues").addObject().put("category", "FACTS")
                .put("severity", "BLOCKED").put("summary", "Insufficient evidence.");
        result = decode(Phase.CRITIC, critic, "Ari waited.", "1".repeat(64));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(NovelProviderJson.parse(result.structuredJson()).path("outcome").asText()).isEqualTo("BLOCKED");
        critic.put("outcome", "PASS");
        assertFailed(decode(Phase.CRITIC, critic, "Ari waited.", "1".repeat(64)));
    }

    @Test
    void neverFallsBackFromMalformedQuoteProtocolToLegacySuccess() {
        var legacy = NovelStageOutputTest.plan();
        assertFailed(decode(Phase.PLAN, legacy, SOURCE, null));
        var failure = new NovelProviderModels.Result("CALL_OUTCOME_UNKNOWN", null, null, null, null, null, null, "READER_054", null);
        assertThat(NovelProviderContentCodec.decodeQuotes(Phase.PLAN, failure, SOURCE, null)).isSameAs(failure);
    }

    static ObjectNode plan() {
        var node = NovelStageOutputTest.plan();
        node.put("schemaVersion", "adaptation-plan-quotes-v1").remove("originalSha256");
        stripCoordinates(node);
        return node;
    }

    static ObjectNode critic() {
        var node = NovelStageOutputTest.critic();
        node.put("schemaVersion", "adaptation-critic-quotes-v1").remove(List.of("candidateSha256", "constraintSha256"));
        stripCoordinates(node);
        for (JsonNode check : node.withArray("checks")) ((ObjectNode) check.get("evidence")).put("quote", "Ari waited.");
        return node;
    }

    private static void stripCoordinates(JsonNode node) {
        if (node.isObject()) ((ObjectNode) node).remove(List.of("sourceStart", "sourceEnd"));
        node.forEach(NovelProviderQuoteProtocolTest::stripCoordinates);
    }

    private static NovelStageOutput.Terminal decode(Phase phase, JsonNode wire, String source, String constraintSha) {
        String raw = "<think>fixture</think>\n```json\n" + NovelProviderJson.encode(wire) + "\n```";
        var decoded = NovelProviderContentCodec.decodeQuotes(phase,
                new NovelProviderModels.Result("SUCCEEDED", raw, "stop", "fixture", 1, 1, 200, null, sha(raw)), source, constraintSha);
        return new NovelStageOutput().normalize(phase, decoded);
    }

    private static void assertFailed(NovelStageOutput.Terminal result) {
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("READER_041");
        assertThat(result.structuredJson()).isNull();
        assertThat(result.outputText()).isNull();
    }

    private static String sha(String value) { return NovelProviderJson.sha(value.getBytes(StandardCharsets.UTF_8)); }
}
