package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Phase;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Result;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NovelProviderContentCodecTest {
    private static final String ORIGINAL = "Ari closed the door.";

    @Test
    void decodesKnownEnvelopeAndOnlyStructuralPunctuation() {
        var plan = NovelStageOutputTest.plan();
        String purpose = "Keep \"quoted\" commas \uff0c colons \uff1a and backslashes \\ unchanged.";
        ((ObjectNode) plan.at("/expansionPoints/0")).put("purpose", purpose);
        String input = "<think>private fixture reasoning</think>\n```json\n" + fullwidth(NovelProviderJson.encode(plan)) + "\n```";
        var result = decode(Phase.PLAN, input, ORIGINAL);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.content()).isEqualTo(NovelProviderJson.encode(plan)).doesNotContain("private fixture reasoning", "<think>");
        assertThat(NovelProviderJson.parse(result.content()).at("/expansionPoints/0/purpose").textValue()).isEqualTo(purpose);
        assertThat(new NovelStageOutput().normalize(Phase.PLAN, result).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void derivesCodepointOffsetsFromExactQuotesIncludingSupplementaryCharacters() {
        String source = "\ud83d\ude80 " + ORIGINAL;
        var plan = NovelStageOutputTest.plan().put("originalSha256", sha(source));
        for (String path : List.of("/preservedFacts/0", "/entities/0", "/events/0", "/requiredEndingState", "/expansionPoints/0")) {
            ((ObjectNode) plan.at(path)).put("sourceStart", 100).put("sourceEnd", 102);
        }
        var result = decode(Phase.PLAN, NovelProviderJson.encode(plan), source);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        var aligned = NovelProviderJson.parse(result.content());
        assertThat(aligned.at("/entities/0/sourceStart").intValue()).isEqualTo(2);
        assertThat(aligned.at("/preservedFacts/0/sourceStart").intValue()).isEqualTo(6);
        assertThat(aligned.at("/preservedFacts/0/sourceEnd").intValue()).isEqualTo(22);
        assertThat(aligned.at("/events/0/sourceEnd").intValue()).isEqualTo(21);
        assertThat(new NovelStageOutput().normalize(Phase.PLAN, result).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void neverInventsQuotesOrRepairsAmbiguousFactEvidence() {
        var invented = NovelStageOutputTest.plan();
        ((ObjectNode) invented.at("/preservedFacts/0")).put("sourceQuote", "opened the door.");
        assertFailed(decode(Phase.PLAN, NovelProviderJson.encode(invented), ORIGINAL));
        String repeated = ORIGINAL + " " + ORIGINAL;
        var ambiguous = NovelStageOutputTest.plan().put("originalSha256", sha(repeated));
        ((ObjectNode) ambiguous.at("/preservedFacts/0")).put("sourceStart", 100).put("sourceEnd", 102);
        assertFailed(decode(Phase.PLAN, NovelProviderJson.encode(ambiguous), repeated));
        // 已正确定位的重复引文仍然合法，解码器不能擅自移到第一次出现。
        ((ObjectNode) ambiguous.at("/preservedFacts/0")).put("sourceStart", 25).put("sourceEnd", 41);
        ((ObjectNode) ambiguous.at("/events/0")).put("sourceStart", 25).put("sourceEnd", 40);
        ((ObjectNode) ambiguous.at("/requiredEndingState")).put("sourceStart", 25).put("sourceEnd", 40);
        var result = decode(Phase.PLAN, NovelProviderJson.encode(ambiguous), repeated);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(NovelProviderJson.parse(result.content()).at("/preservedFacts/0/sourceStart").intValue()).isEqualTo(25);
    }

    @Test
    void doesNotRepairWrongTypesOverflowDigestOrFactRelations() {
        for (String field : List.of("sourceStart", "sourceEnd")) {
            var typed = NovelStageOutputTest.plan();
            ((ObjectNode) typed.at("/entities/0")).put(field, "3");
            assertFailed(decode(Phase.PLAN, NovelProviderJson.encode(typed), ORIGINAL));
            ((ObjectNode) typed.at("/entities/0")).put(field, Long.MAX_VALUE);
            assertFailed(decode(Phase.PLAN, NovelProviderJson.encode(typed), ORIGINAL));
        }
        var wrongDigest = NovelStageOutputTest.plan().put("originalSha256", "0".repeat(64));
        assertFailed(decode(Phase.PLAN, NovelProviderJson.encode(wrongDigest), ORIGINAL));
        var wrongFact = NovelStageOutputTest.plan();
        ((ObjectNode) wrongFact.at("/events/0")).put("factId", "F99");
        assertFailed(decode(Phase.PLAN, NovelProviderJson.encode(wrongFact), ORIGINAL));
    }

    @Test
    void keepsUnknownFieldsAndJudgmentsForIndependentStrictValidation() {
        var plan = NovelStageOutputTest.plan().put("unexpected", "private-data");
        var result = decode(Phase.PLAN, NovelProviderJson.encode(plan), ORIGINAL);
        assertThat(new NovelStageOutput().normalize(Phase.PLAN, result).errorCode()).isEqualTo("READER_041");
        var category = NovelStageOutputTest.plan();
        ((ObjectNode) category.at("/preservedFacts/0")).put("category", "IDENTITY");
        assertThat(new NovelStageOutput().normalize(Phase.PLAN, decode(Phase.PLAN, NovelProviderJson.encode(category), ORIGINAL)).errorCode()).isEqualTo("READER_041");
        String duplicate = NovelProviderJson.encode(NovelStageOutputTest.plan()).replaceFirst("\\{", "{\"pointOfView\":\"FIRST\",");
        assertFailed(decode(Phase.PLAN, duplicate, ORIGINAL));
    }

    @Test
    void criticOffsetsUseCurrentCandidateAndNeverChangeVerdicts() {
        String candidate = "Ari waited. The door stayed shut.";
        var critic = NovelProviderJson.MAPPER.createObjectNode().put("schemaVersion", "adaptation-critic-v1")
                .put("candidateSha256", sha(candidate)).put("constraintSha256", "1".repeat(64)).put("outcome", "BLOCKED");
        var checks = critic.putArray("checks");
        for (String category : List.of("FACTS", "ENTITIES", "NUMBERS", "EVENT_ORDER", "ENTRY_STATE", "ENDING_STATE", "POINT_OF_VIEW", "WORLD_RULES", "INTENT", "SAFETY")) {
            var check = checks.addObject().put("category", category).put("verdict", category.equals("SAFETY") ? "UNKNOWN" : "PASS");
            if (category.equals("SAFETY")) check.putNull("evidence");
            else check.putObject("evidence").put("quote", "The door stayed shut.").put("sourceStart", 1).put("sourceEnd", 3);
        }
        critic.putArray("issues").addObject().put("category", "SAFETY").put("severity", "BLOCKED").put("summary", "Insufficient evidence.");
        var result = decode(Phase.CRITIC, NovelProviderJson.encode(critic), candidate);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(NovelProviderJson.parse(result.content()).at("/checks/0/evidence/sourceStart").intValue()).isEqualTo(12);
        assertThat(NovelProviderJson.parse(result.content()).path("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(new NovelStageOutput().normalize(Phase.CRITIC, result).status()).isEqualTo("SUCCEEDED");
        assertFailed(decode(Phase.CRITIC, NovelProviderJson.encode(critic), ORIGINAL));
    }

    @Test
    void rejectsUnclosedNestedOversizedOrAmbiguousWrappersWithoutGuessing() {
        for (String body : List.of("<think>unfinished", "<think>a<think>b</think>{}", "<think>a</think>{}</think>",
                "prefix<think>a</think>{}", "<think>" + "x".repeat(32769) + "</think>{}", "```json\n{}\n``` trailing", "{} {}")) {
            assertFailed(decode(Phase.PLAN, body, ORIGINAL));
        }
    }

    @Test
    void proseIsNotRewrittenAndUnknownFailuresRemainBodyless() {
        String prose = "  Ari said: \"Wait, please.\"\n\nThe door stayed shut.\n";
        assertThat(decode(Phase.GENERATE, prose, null).content()).isEqualTo(prose);
        var result = decode(Phase.GENERATE, "<think>private fixture</think>\n" + prose, null);
        assertThat(result.content()).isEqualTo(prose.stripLeading());
        var failure = new Result("CALL_OUTCOME_UNKNOWN", null, null, null, null, null, null, "READER_054", null);
        assertThat(NovelProviderContentCodec.decode(Phase.PLAN, failure, ORIGINAL)).isSameAs(failure);
    }

    private static Result decode(Phase phase, String value, String source) {
        return NovelProviderContentCodec.decode(phase, new Result("SUCCEEDED", value, "stop", "fixture", 1, 1, 200, null, sha(value)), source);
    }

    private static void assertFailed(Result result) {
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("READER_041");
        assertThat(result.content()).isNull();
    }

    private static String sha(String value) { return NovelProviderJson.sha(value.getBytes(StandardCharsets.UTF_8)); }

    private static String fullwidth(String json) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (char value : json.toCharArray()) {
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else {
                if (value == '"') quoted = true;
                if (value == ':') value = '\uff1a';
                else if (value == ',') value = '\uff0c';
            }
            out.append(value);
        }
        return out.toString();
    }
}
