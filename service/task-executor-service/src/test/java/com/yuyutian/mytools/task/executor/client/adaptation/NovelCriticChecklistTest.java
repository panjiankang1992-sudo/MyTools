package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NovelCriticChecklistTest {
    @Test
    void acceptsObservedFlatRootWithoutChangingAnyJudgmentOrEvidence() {
        var wrapped = wire();
        assertThat(decode(wrapped.get("checks")).structuredJson()).isEqualTo(decode(wrapped).structuredJson());
        for (String verdict : new String[]{"FAIL", "UNKNOWN"}) {
            ((ObjectNode) wrapped.at("/checks/FACTS")).put("verdict", verdict)
                    .putObject("issue").put("severity", "BLOCKED").put("summary", "Insufficient factual support.");
            assertThat(decode(wrapped.get("checks")).structuredJson()).isEqualTo(decode(wrapped).structuredJson());
            assertThat(NovelProviderJson.parse(decode(wrapped.get("checks")).structuredJson()).path("outcome").asText()).isEqualTo("BLOCKED");
        }
    }

    @Test
    void rejectsIncompleteMixedOrAugmentedFlatRoots() {
        var missing = (ObjectNode) wire().get("checks"); missing.remove("FACTS"); assertFailed(decode(missing));
        var extra = (ObjectNode) wire().get("checks"); extra.put("outcome", "PASS"); assertFailed(decode(extra));
        var mixed = (ObjectNode) wire().get("checks"); mixed.set("checks", wire().get("checks")); assertFailed(decode(mixed));
        var invalid = (ObjectNode) wire().get("checks"); ((ObjectNode) invalid.get("FACTS")).put("evidenceId", 99); assertFailed(decode(invalid));
        var issue = (ObjectNode) wire().get("checks"); ((ObjectNode) issue.get("FACTS")).put("verdict", "FAIL"); assertFailed(decode(issue));
    }

    @Test
    void acceptsNoModelLabelWithoutChangingAnyJudgment() {
        var unlabelled = wire(); unlabelled.remove("schemaVersion");
        var labelled = wire(); labelled.put("schemaVersion", "critic-checklist-v1");
        assertThat(decode(unlabelled).structuredJson()).isEqualTo(decode(labelled).structuredJson());
        labelled.put("schemaVersion", "x".repeat(65)); assertFailed(decode(labelled));
    }

    @Test
    void aggregatesTenExplicitPassesAndBindsEvidenceToCandidate() {
        var result = decode(wire());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        JsonNode canonical = NovelProviderJson.parse(result.structuredJson());
        assertThat(canonical.path("outcome").asText()).isEqualTo("PASS");
        assertThat(canonical.path("checks")).hasSize(10);
        assertThat(canonical.at("/checks/0/evidence/quote").asText()).isEqualTo("Ari waited.");
    }

    @Test
    void preservesFailuresUnknownsAndSafetyBlocksWithoutGlobalModelOutcome() {
        for (String category : new String[]{"FACTS", "SAFETY"}) {
            var wire = wire();
            var check = (ObjectNode) wire.at("/checks/" + category);
            check.put("verdict", "FAIL").putObject("issue").put("severity", "REPAIRABLE").put("summary", "A fact was changed.");
            var result = decode(wire);
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(NovelProviderJson.parse(result.structuredJson()).path("outcome").asText())
                    .isEqualTo(category.equals("SAFETY") ? "BLOCKED" : "REPAIRABLE");
        }
        var wire = wire();
        ((ObjectNode) wire.at("/checks/FACTS")).put("verdict", "UNKNOWN").putNull("evidenceId")
                .putObject("issue").put("severity", "BLOCKED").put("summary", "Insufficient evidence.");
        assertThat(NovelProviderJson.parse(decode(wire).structuredJson()).path("outcome").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void acceptsOnlyExplicitIntegerOrEReferenceAliases() {
        for (String id : new String[]{"E1", "E01", "E0001"}) {
            var wire = wire(); ((ObjectNode) wire.at("/checks/FACTS")).put("evidenceId", id);
            assertThat(decode(wire).status()).isEqualTo("SUCCEEDED");
        }
        for (String id : new String[]{"e1", "1", "E0000", "E2", "E00001", " E1"}) {
            var wire = wire(); ((ObjectNode) wire.at("/checks/FACTS")).put("evidenceId", id);
            assertFailed(decode(wire));
        }
        var wire = wire(); ((ObjectNode) wire.at("/checks/FACTS")).put("evidenceId", 1.0);
        assertFailed(decode(wire));
    }

    @Test
    void rejectsMissingChecksGlobalOutcomeAndContradictoryIssues() {
        var missing = wire(); ((ObjectNode) missing.get("checks")).remove("FACTS"); assertFailed(decode(missing));
        var global = wire(); global.put("outcome", "PASS"); assertFailed(decode(global));
        var passIssue = wire(); ((ObjectNode) passIssue.at("/checks/FACTS")).putObject("issue").put("severity", "BLOCKED").put("summary", "Rejected.");
        assertFailed(decode(passIssue));
        var failMissing = wire(); ((ObjectNode) failMissing.at("/checks/FACTS")).put("verdict", "FAIL"); assertFailed(decode(failMissing));
        var nullEvidence = wire(); ((ObjectNode) nullEvidence.at("/checks/FACTS")).putNull("evidenceId"); assertFailed(decode(nullEvidence));
        assertFailed(decode(NovelCandidateEvidenceTest.critic()));
    }

    static ObjectNode wire() {
        var result = NovelProviderJson.MAPPER.createObjectNode().put("schemaVersion", "adaptation-critic-checklist-v1");
        var checks = result.putObject("checks");
        for (String category : NovelCriticChecklist.CATEGORIES) checks.putObject(category).put("verdict", "PASS").put("evidenceId", 1).putNull("issue");
        return result;
    }

    private static NovelStageOutput.Terminal decode(JsonNode wire) {
        var raw = new NovelProviderModels.Result("SUCCEEDED", NovelProviderJson.encode(wire), "stop", "fixture", 1, 1, 200, null, "1".repeat(64));
        var decoded = NovelProviderContentCodec.decodeBounded(NovelProviderModels.Phase.CRITIC, raw, "Ari waited.", "1".repeat(64), null);
        return new NovelStageOutput().normalize(NovelProviderModels.Phase.CRITIC, decoded);
    }
    private static void assertFailed(NovelStageOutput.Terminal result) {
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.outputText()).isNull(); assertThat(result.structuredJson()).isNull();
    }
}
