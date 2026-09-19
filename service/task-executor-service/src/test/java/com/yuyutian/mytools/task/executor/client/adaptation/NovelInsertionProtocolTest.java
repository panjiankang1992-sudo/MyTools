package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovelInsertionProtocolTest {
    static final String ORIGINAL = "Ari waited at the old gate. Mira held the key. The door stayed shut. Ari waited for dawn.";

    @Test
    void protocolAuthorityComesFromTheTicketNotTheOptionalModelLabel() {
        var scope = NovelInsertionProtocol.prepare(input());
        var unlabelled = wire(2, "Mist cooled the stone."); unlabelled.remove("schemaVersion");
        String expected = scope.assemble(unlabelled);
        var labelled = wire(2, "Mist cooled the stone."); labelled.put("schemaVersion", "insert-v1");
        assertThat(scope.assemble(labelled)).isEqualTo(expected);
        labelled.putObject("schemaVersion").put("instruction", "invalid");
        assertThatThrownBy(() -> scope.assemble(labelled)).isInstanceOf(NovelProviderException.class);
        var legacy = NovelProviderQuoteProtocolTest.plan();
        assertThatThrownBy(() -> scope.assemble(legacy)).isInstanceOf(NovelProviderException.class);
    }

    @Test
    void preservesOriginalAndEndingWhileEnforcingTheComputedBudget() {
        var input = input();
        var scope = NovelInsertionProtocol.prepare(input);
        String addition = "Cool mist brushed the stone.";
        String body = scope.assemble(wire(2, addition));
        assertThat(body.replace("\n" + addition + "\n", "")).isEqualTo(ORIGINAL);
        assertThat(body).endsWith("Ari waited for dawn.");
        assertThat(body.length()).isLessThanOrEqualTo(input.at("/insertionBudget/maximumFinalCodepoints").intValue());
        assertThat(scope.toString()).doesNotContain("Ari", addition);
        assertThat(input.at("/insertionSlots/1/slotId").intValue()).isEqualTo(2);
    }

    @Test
    void optimizeAndRepairRecoverOnlyExistingAddedWording() {
        String previous = NovelInsertionProtocol.prepare(input()).assemble(wire(2, "Cool mist brushed the stone."));
        for (String key : new String[]{"baseInput", "candidate"}) {
            var next = input(); next.put(key, previous);
            var scope = NovelInsertionProtocol.prepare(next);
            assertThat(next.at("/insertionSlots/1/currentAddition").textValue()).isEqualTo("Cool mist brushed the stone.");
            String changed = scope.assemble(wire(2, "Mist cooled the stone."));
            assertThat(changed).doesNotContain("brushed").contains("Mist cooled the stone.");
            assertThat(changed.replace("\nMist cooled the stone.\n", "")).isEqualTo(ORIGINAL);
        }
    }

    @Test
    void originalSlotIdentitySurvivesAChangedPlanWithoutUnlockingProtectedSpans() {
        String previous = NovelInsertionProtocol.prepare(input()).assemble(wire(2, "Cool mist brushed the stone."));
        var next = input(); next.put("baseInput", previous);
        ((ObjectNode) next.at("/constraints/supplement")).putArray("events").addObject().put("sourceStart", 2).put("sourceEnd", 45);
        var scope = NovelInsertionProtocol.prepare(next);
        assertThat(next.get("insertionSlots")).allSatisfy(slot -> assertThat(slot.path("slotId").asInt()).isNotEqualTo(2));
        assertThatThrownBy(() -> scope.assemble(wire(2, "Mist cooled the stone."))).isInstanceOf(NovelProviderException.class);
        assertThat(scope.assemble(wire(1, "Mist lingered."))).endsWith(ORIGINAL);
    }

    @Test
    void rejectsOverlongDuplicateUnknownAndEmptyAdditionsWithoutTruncation() {
        var scope = NovelInsertionProtocol.prepare(input());
        assertThatThrownBy(() -> scope.assemble(wire(2, "x".repeat(121)))).isInstanceOf(NovelProviderException.class);
        assertThatThrownBy(() -> scope.assemble(wire(999, "Mist."))).isInstanceOf(NovelProviderException.class);
        var duplicate = wire(2, "Mist."); duplicate.withArray("additions").add(duplicate.at("/additions/0").deepCopy());
        assertThatThrownBy(() -> scope.assemble(duplicate)).isInstanceOf(NovelProviderException.class);
        var extra = wire(2, "Mist."); extra.put("original", "overwrite");
        assertThatThrownBy(() -> scope.assemble(extra)).isInstanceOf(NovelProviderException.class);
        var empty = wire(2, "Mist."); empty.withArray("additions").removeAll();
        assertThatThrownBy(() -> scope.assemble(empty)).isInstanceOf(NovelProviderException.class);
        assertThatThrownBy(() -> scope.assemble(wire(2, "   "))).isInstanceOf(NovelProviderException.class);
    }

    @Test
    void refusesLegacyRewrittenBaseAndUntrustedTrailingContent() {
        var legacy = input(); legacy.put("baseInput", ORIGINAL.replace("old gate", "new gate"));
        assertThatThrownBy(() -> NovelInsertionProtocol.prepare(legacy)).isInstanceOf(NovelProviderException.class);
        var trailing = input(); trailing.put("candidate", ORIGINAL + "\nA new event.");
        assertThatThrownBy(() -> NovelInsertionProtocol.prepare(trailing)).isInstanceOf(NovelProviderException.class);
    }

    @Test
    void handlesUnicodeAndSingleSentenceWithoutSplittingSurrogates() {
        var input = input(); input.put("original", "\ud83d\ude80".repeat(120) + "\u3002");
        ((ObjectNode) input.at("/constraints/supplement/requiredEndingState")).put("sourceStart", 110);
        var scope = NovelInsertionProtocol.prepare(input);
        String addition = "\u96fe\u6c14\u5fae\u51c9\u3002";
        String result = scope.assemble(wire(1, addition));
        assertThat(result).isEqualTo("\n" + addition + "\n" + input.get("original").textValue());
        NovelProviderJson.text(result, 120000);
        var full = input(); full.put("original", "x".repeat(120000));
        assertThatThrownBy(() -> NovelInsertionProtocol.prepare(full)).isInstanceOf(NovelProviderException.class);
    }

    static ObjectNode input() {
        var context = NovelAdaptationPromptsTest.context("INITIAL", ORIGINAL, null);
        ObjectNode input = NovelProviderJson.MAPPER.createObjectNode().put("original", ORIGINAL);
        ObjectNode constraints = (ObjectNode) NovelProviderJson.parse(NovelAdaptationPromptsTest.constraints(context));
        ((ObjectNode) constraints.get("deterministic")).put("minimumLengthPermille", 750).put("maximumLengthPermille", 2500);
        var plan = (ObjectNode) constraints.get("supplement");
        plan.putArray("events"); plan.putArray("entities");
        plan.putObject("requiredEndingState").put("sourceStart", ORIGINAL.length() - 20);
        input.set("constraints", constraints);
        return input;
    }

    static ObjectNode wire(int slot, String text) {
        var result = NovelProviderJson.MAPPER.createObjectNode().put("schemaVersion", "adaptation-insert-v1");
        result.putArray("additions").addObject().put("slotId", slot).put("text", text);
        return result;
    }
}
