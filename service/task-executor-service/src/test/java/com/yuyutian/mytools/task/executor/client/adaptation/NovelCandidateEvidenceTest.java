package com.yuyutian.mytools.task.executor.client.adaptation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Phase;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovelCandidateEvidenceTest {
    @Test
    void catalogPreservesEveryCodepointWithBoundedWindowsAndNoSingleCharacterTail() {
        for (int length : List.of(2, 159, 160, 161, 162, 319, 320, 321, 120000)) {
            String candidate = "\ud83d\ude80".repeat(length);
            var catalog = NovelCandidateEvidence.catalog(candidate);
            StringBuilder restored = new StringBuilder();
            for (JsonNode entry : catalog) {
                String quote = entry.get("quote").textValue();
                assertThat(quote.codePointCount(0, quote.length())).isBetween(2, 161);
                restored.append(quote);
            }
            assertThat(restored.toString()).isEqualTo(candidate);
            assertThat(catalog.size()).isLessThanOrEqualTo(750);
        }
        assertThatThrownBy(() -> NovelCandidateEvidence.catalog("x")).isInstanceOf(NovelProviderException.class);
        assertThatThrownBy(() -> NovelCandidateEvidence.catalog("x".repeat(120001))).isInstanceOf(NovelProviderException.class);
    }

    @Test
    void explicitReferenceBindsRepeatedEvidenceToExactCurrentCandidateWindow() {
        String candidate = "\ud83d\ude80".repeat(321);
        var wire = critic();
        ((ObjectNode) wire.at("/checks/0")).put("evidenceId", "E0002");
        var terminal = decode(wire, candidate);
        assertThat(terminal.status()).isEqualTo("SUCCEEDED");
        JsonNode evidence = NovelProviderJson.parse(terminal.structuredJson()).at("/checks/0/evidence");
        assertThat(evidence.get("quote").textValue()).isEqualTo("\ud83d\ude80".repeat(161));
        assertThat(evidence.get("sourceStart").intValue()).isEqualTo(160);
        assertThat(evidence.get("sourceEnd").intValue()).isEqualTo(321);
        assertThat(NovelProviderJson.parse(terminal.structuredJson()).path("candidateSha256").asText()).isEqualTo(sha(candidate));
        assertThat(NovelProviderJson.parse(terminal.structuredJson()).path("constraintSha256").asText()).isEqualTo("1".repeat(64));
        assertThat(decode(wire, "short candidate").status()).isEqualTo("FAILED");
    }

    @Test
    void rejectsUnknownIdsTypesHashesAndLegacyQuoteFallback() {
        for (String id : List.of("E0000", "E0002", "E1", "e0001", " E0001", "E0001\n", "invented quote")) {
            var wire = critic();
            ((ObjectNode) wire.at("/checks/0")).put("evidenceId", id);
            assertFailed(decode(wire, "Ari waited."));
        }
        var wire = critic(); ((ObjectNode) wire.at("/checks/0")).put("evidenceId", 1);
        assertFailed(decode(wire, "Ari waited."));
        wire = critic(); wire.put("candidateSha256", "1".repeat(64));
        assertFailed(decode(wire, "Ari waited."));
        assertFailed(decode(NovelProviderQuoteProtocolTest.critic(), "Ari waited."));
        assertFailed(decode(NovelStageOutputTest.critic(), "Ari waited."));
    }

    @Test
    void retainsUnknownAndBlockedVerdictsAndRequiresConsistentIssues() {
        var wire = critic();
        ((ObjectNode) wire.at("/checks/0")).put("verdict", "UNKNOWN").putNull("evidenceId");
        assertFailed(decode(wire, "Ari waited."));
        wire.put("outcome", "BLOCKED").withArray("issues").addObject().put("category", "FACTS")
                .put("severity", "BLOCKED").put("summary", "Evidence is insufficient.");
        var result = decode(wire, "Ari waited.");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(NovelProviderJson.parse(result.structuredJson()).path("outcome").asText()).isEqualTo("BLOCKED");
        wire.put("outcome", "PASS");
        assertFailed(decode(wire, "Ari waited."));
    }

    @Test
    void doesNotChangeProviderFailuresOrAutomaticallyRetry() {
        var failed = new NovelProviderModels.Result("CALL_OUTCOME_UNKNOWN", null, null, null, null, null, null, "READER_054", null);
        assertThat(NovelProviderContentCodec.decodeReferences(failed, null, null)).isSameAs(failed);
    }

    @Test
    void diagnosticsContainOnlyFixedCategoriesAndNeverRawContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(NovelProviderProtocolDiagnostics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            String privateValue = "private-provider-payload-do-not-log";
            var raw = new NovelProviderModels.Result("SUCCEEDED", privateValue, "stop", privateValue, 1, 1, 200, null, privateValue);
            assertThat(NovelProviderContentCodec.decodeReferences(raw, "Ari waited.", "1".repeat(64)).status()).isEqualTo("FAILED");
            var wire = critic(); ((ObjectNode) wire.at("/checks/0")).put("evidenceId", privateValue);
            assertFailed(decode(wire, "Ari waited."));
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(privateValue, "Ari waited.");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list.get(0).getFormattedMessage()).contains("layer=JSON_PARSE", "diagnosticSha256=unavailable");
            assertThat(appender.list.get(1).getFormattedMessage()).contains("layer=REFERENCE_BINDING", "reason=INVALID_REFERENCE");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    static ObjectNode critic() {
        var node = NovelProviderQuoteProtocolTest.critic();
        node.put("schemaVersion", "adaptation-critic-refs-v1");
        for (JsonNode check : node.get("checks")) {
            ((ObjectNode) check).remove("evidence");
            ((ObjectNode) check).put("evidenceId", "E0001");
        }
        return node;
    }

    private static NovelStageOutput.Terminal decode(JsonNode wire, String candidate) {
        String raw = "<think>private fixture</think>\n```json\n" + NovelProviderJson.encode(wire) + "\n```";
        var result = new NovelProviderModels.Result("SUCCEEDED", raw, "stop", "fixture", 1, 1, 200, null, sha(raw));
        return new NovelStageOutput().normalize(Phase.CRITIC, NovelProviderContentCodec.decodeReferences(result, candidate, "1".repeat(64)));
    }

    private static void assertFailed(NovelStageOutput.Terminal result) {
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("READER_041");
        assertThat(result.structuredJson()).isNull();
        assertThat(result.outputText()).isNull();
    }

    private static String sha(String value) { return NovelProviderJson.sha(value.getBytes(StandardCharsets.UTF_8)); }
}
