package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovelPlanReferencesTest {
    @Test
    void catalogReconstructsOriginalIncludingUnicodeAndSingleCodepointTail() {
        for (int size : new int[]{2, 119, 120, 121, 240, 241, 120000}) {
            String original = "\ud83d\ude80".repeat(size);
            var catalog = NovelPlanReferences.catalog(original);
            StringBuilder rebuilt = new StringBuilder();
            for (int index = 0; index < catalog.size(); index++) {
                var fragment = catalog.get(index);
                assertThat(fragment.path("sourceId").asInt()).isEqualTo(index + 1);
                String value = fragment.path("text").asText();
                assertThat(value.codePointCount(0, value.length())).isBetween(2, 120);
                rebuilt.append(value);
            }
            assertThat(rebuilt.toString()).isEqualTo(original);
        }
        assertThatThrownBy(() -> NovelPlanReferences.catalog("x")).isInstanceOf(NovelProviderException.class);
        assertThatThrownBy(() -> NovelPlanReferences.catalog("x".repeat(120001))).isInstanceOf(NovelProviderException.class);
    }

    @Test
    void bindsAllSpansAndEndingWithoutModelQuotesOffsetsOrFactIds() {
        String original = NovelInsertionProtocolTest.ORIGINAL + "\ud83d\ude80".repeat(150);
        var terminal = decode(wire(), original);
        assertThat(terminal.status()).isEqualTo("SUCCEEDED");
        var plan = NovelProviderJson.parse(terminal.structuredJson());
        assertThat(plan.path("originalSha256").asText()).isEqualTo(NovelProviderJson.sha(original.getBytes(StandardCharsets.UTF_8)));
        for (String array : List.of("preservedFacts", "events", "expansionPoints")) {
            for (var item : plan.get(array)) assertSpan(item, array.equals("preservedFacts") ? "sourceQuote" : "anchor", original);
        }
        assertSpan(plan.get("requiredEndingState"), "anchor", original);
        assertThat(plan.at("/requiredEndingState/sourceEnd").asInt()).isEqualTo(original.codePointCount(0, original.length()));
        assertThat(plan.at("/preservedFacts/0/statement").asText()).isEqualTo("Keep the original facts unchanged.");
        assertThat(plan.at("/preservedFacts/0/category").asText()).isEqualTo("KNOWLEDGE");
    }

    @Test
    void retainsConflictAndMultipleFactStatementsSharingOneSourceId() {
        var wire = wire().put("intentDisposition", "CONFLICT");
        wire.withArray("preservedFacts").addObject().put("sourceId", 1).put("category", "EVENT").put("statement", "A distinct factual judgment.");
        var result = decode(wire, NovelInsertionProtocolTest.ORIGINAL);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        var plan = NovelProviderJson.parse(result.structuredJson());
        assertThat(plan.path("intentDisposition").asText()).isEqualTo("CONFLICT");
        assertThat(plan.get("preservedFacts")).hasSize(2);
        assertThat(plan.get("events")).hasSize(1);
    }

    @Test
    void repeatedFragmentsHaveExactCoordinatesWithoutFalseDuplicateEventOrder() {
        String original = "Ari waited. ".repeat(40);
        var wire = wire();
        ((ObjectNode) wire.at("/preservedFacts/0")).put("sourceId", 3);
        var result = decode(wire, original);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        var plan = NovelProviderJson.parse(result.structuredJson());
        assertThat(plan.at("/preservedFacts/0/sourceStart").asInt()).isEqualTo(240);
        assertThat(plan.get("events")).hasSize(1);
        assertThat(plan.at("/events/0/sourceStart").asInt()).isZero();
    }

    @Test
    void rejectsUnknownReferencesAndOldQuoteFieldsRatherThanGuessing() {
        for (JsonNode bad : List.of(NovelProviderJson.MAPPER.getNodeFactory().numberNode(0), NovelProviderJson.MAPPER.getNodeFactory().numberNode(99),
                NovelProviderJson.MAPPER.getNodeFactory().numberNode(1.0), NovelProviderJson.MAPPER.getNodeFactory().textNode("1"),
                NovelProviderJson.MAPPER.getNodeFactory().textNode("S0"), NovelProviderJson.MAPPER.getNodeFactory().textNode("s1"))) {
            var wire = wire(); ((ObjectNode) wire.at("/preservedFacts/0")).set("sourceId", bad); assertFailed(decode(wire, NovelInsertionProtocolTest.ORIGINAL));
        }
        var extra = wire(); ((ObjectNode) extra.at("/preservedFacts/0")).put("sourceQuote", "A fictional quote."); assertFailed(decode(extra, NovelInsertionProtocolTest.ORIGINAL));
        var missing = wire(); missing.remove("forbiddenChanges"); assertFailed(decode(missing, NovelInsertionProtocolTest.ORIGINAL));
        assertFailed(decode(NovelProviderQuoteProtocolTest.plan(), NovelInsertionProtocolTest.ORIGINAL));
        var alias = wire(); ((ObjectNode) alias.at("/preservedFacts/0")).put("sourceId", "S0001");
        assertThat(decode(alias, NovelInsertionProtocolTest.ORIGINAL).structuredJson()).isEqualTo(decode(wire(), NovelInsertionProtocolTest.ORIGINAL).structuredJson());
    }

    @Test
    void oldMisquotedPlanStillFailsWhileReferenceContractNeedsNoCopiedQuote() {
        String original = "Ari closed the door.";
        var legacy = NovelProviderQuoteProtocolTest.plan();
        ((ObjectNode) legacy.at("/preservedFacts/0")).put("sourceQuote", "Ari closed the door!");
        var raw = new NovelProviderModels.Result("SUCCEEDED", NovelProviderJson.encode(legacy), "stop", "fixture", 1, 1, 200, null, "1".repeat(64));
        assertThat(NovelProviderContentCodec.decodeQuotes(NovelProviderModels.Phase.PLAN, raw, original, null).status()).isEqualTo("FAILED");
        assertThat(decode(wire(), original).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void resultingPlanSupportsInsertionAndActualOptimizeBase() {
        String original = NovelInsertionProtocolTest.ORIGINAL;
        var plan = NovelProviderJson.parse(decode(wire(), original).structuredJson());
        var input = NovelInsertionProtocolTest.input();
        ((ObjectNode) input.get("constraints")).set("supplement", plan);
        var scope = NovelInsertionProtocol.prepare(input);
        int slot = input.withArray("insertionSlots").get(input.withArray("insertionSlots").size() - 1).path("slotId").asInt();
        String previous = scope.assemble(NovelInsertionProtocolTest.wire(slot, "Cool air lingered."));
        input.put("baseInput", previous);
        var nextScope = NovelInsertionProtocol.prepare(input);
        String next = nextScope.assemble(NovelInsertionProtocolTest.wire(slot, "The air felt cool."));
        assertThat(next.replace("\nThe air felt cool.\n", "")).isEqualTo(original);
        assertThat(next).doesNotContain("Cool air lingered.");
    }

    @Test
    void planPromptIsOptInAndUsesOnlyOriginalForReferenceAuthority() {
        String original = NovelInsertionProtocolTest.ORIGINAL;
        var context = NovelAdaptationPromptsTest.context("OPTIMIZE", original, "An unrelated previous wording.");
        var legacy = new NovelAdaptationPrompts().plan(context).quoteProtocol();
        assertThat(NovelProviderJson.parse(legacy.user()).has("sourceEvidence")).isFalse();
        var prompt = legacy.insertionProtocol();
        var input = NovelProviderJson.parse(prompt.user());
        assertThat(input.path("sourceEvidence")).isEqualTo(NovelPlanReferences.catalog(original));
        assertThat(input.has("baseInput")).isFalse();
        assertThat(input.path("providerWireVersion").asText()).isEqualTo("adaptation-plan-refs-v1");
        assertThat(prompt.system()).contains("CONFLICT", "insertion-only", "Read ALL original");
    }

    static ObjectNode wire() {
        var result = NovelProviderJson.MAPPER.createObjectNode().put("intentDisposition", "COMPATIBLE")
                .put("intentSummary", "Enrich the atmosphere without changing facts.").put("pointOfView", "THIRD_LIMITED");
        result.putArray("preservedFacts").addObject().put("sourceId", 1).put("category", "KNOWLEDGE").put("statement", "Keep the original facts unchanged.");
        result.putArray("expansionPoints").addObject().put("sourceId", 1).put("additionType", "SENSORY").put("purpose", "Enrich only grounded sensory detail.");
        result.putArray("forbiddenChanges").add("Do not change the original plot or ending.");
        return result;
    }

    private static NovelStageOutput.Terminal decode(JsonNode wire, String original) {
        var raw = new NovelProviderModels.Result("SUCCEEDED", "<think>fixture</think>\n" + NovelProviderJson.encode(wire), "stop", "fixture", 1, 1, 200, null, "1".repeat(64));
        return new NovelStageOutput().normalize(NovelProviderModels.Phase.PLAN,
                NovelProviderContentCodec.decodeBounded(NovelProviderModels.Phase.PLAN, raw, original, null, null));
    }
    private static void assertSpan(JsonNode node, String field, String original) {
        assertThat(original.substring(original.offsetByCodePoints(0, node.path("sourceStart").asInt()), original.offsetByCodePoints(0, node.path("sourceEnd").asInt())))
                .isEqualTo(node.path(field).asText());
    }
    private static void assertFailed(NovelStageOutput.Terminal result) {
        assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.structuredJson()).isNull();
    }
}
