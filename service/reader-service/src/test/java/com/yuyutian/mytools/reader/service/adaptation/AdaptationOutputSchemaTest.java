package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Kind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Terminal;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 所有结构攻击都经过真实入库载荷入口，只有合法结果才能产生可持久摘要。 */
class AdaptationOutputSchemaTest {
    private static final String ORIGINAL = "The traveler leaves at dawn. The gate remains closed.";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AdaptationAttemptPayload payloads = new AdaptationAttemptPayload(mapper);

    @Test
    void shouldAcceptCompletePlanAndThreeConsistentCriticOutcomesWithoutGrantingSelection() throws Exception {
        assertThat(check(Kind.PLAN, plan()).candidate()).isFalse();
        assertThat(check(Kind.PLAN, plan().put("intentDisposition", "CONFLICT")).candidate()).isFalse();
        for (String category : new String[]{null, "EVENT_ORDER", "SAFETY"}) {
            var result = payloads.check(Kind.CRITIC, success(StoryFixtures.critic(ORIGINAL, "a".repeat(64), category, false)));
            assertThat(result.value().status()).isEqualTo("SUCCEEDED");
            assertThat(result.candidate()).isFalse(); assertThat(result.outputSha256()).isNull();
        }
        assertThat(payloads.check(Kind.CRITIC, success(StoryFixtures.critic(ORIGINAL, "a".repeat(64), null, true))).candidate()).isFalse();
    }

    @TestFactory
    Stream<DynamicTest> shouldRejectInvalidPlanBeforeCanonicalizationOrStorage() {
        return List.of(
                mutation("top-level extra", n -> n.put("reasoning", "private sentinel")),
                mutation("missing field", n -> n.remove("forbiddenChanges")),
                mutation("unknown version", n -> n.put("schemaVersion", "plan-v2")),
                mutation("bad hash", n -> n.put("originalSha256", "A".repeat(64))),
                mutation("unknown intent", n -> n.put("intentDisposition", "IGNORE_ORIGINAL")),
                mutation("empty summary", n -> n.put("intentSummary", "  ")),
                mutation("long summary", n -> n.put("intentSummary", "x".repeat(1001))),
                mutation("numeric summary", n -> n.put("intentSummary", 1)),
                mutation("wrong viewpoint", n -> n.put("pointOfView", "SECOND")),
                mutation("no facts", n -> n.putArray("preservedFacts")),
                mutation("too many facts", n -> fill(n, "preservedFacts", 65)),
                mutation("duplicate fact", n -> array(n, "preservedFacts").add(n.at("/preservedFacts/0").deepCopy())),
                mutation("invalid fact identity", n -> object(n, "/preservedFacts/0").put("id", "F0")),
                mutation("fact extra", n -> object(n, "/preservedFacts/0").put("analysis", "private sentinel")),
                mutation("fact category", n -> object(n, "/preservedFacts/0").put("category", "GUESS")),
                mutation("broken surrogate", n -> object(n, "/preservedFacts/0").put("statement", "\uD800")),
                mutation("fractional offset", n -> object(n, "/events/0").put("sourceStart", 1.5)),
                mutation("negative offset", n -> object(n, "/events/0").put("sourceStart", -1)),
                mutation("overflowing offset", n -> object(n, "/events/0").put("sourceEnd", 2147483648L)),
                mutation("overflowing subtraction", n -> object(n, "/expansionPoints/0").put("sourceStart", Integer.MAX_VALUE)
                        .put("sourceEnd", Integer.MIN_VALUE + 1).put("anchor", "xx")),
                mutation("out of chapter range", n -> object(n, "/expansionPoints/0").put("sourceStart", 120000).put("sourceEnd", 120002).put("anchor", "xx")),
                mutation("wrong span length", n -> object(n, "/events/0").put("sourceEnd", 2)),
                mutation("unknown fact reference", n -> object(n, "/events/0").put("factId", "F999")),
                mutation("wrong fact category reference", n -> object(n, "/preservedFacts/0").put("category", "IDENTITY")),
                mutation("anchor differs from fact quote", n -> object(n, "/events/0").put("anchor", "x".repeat("traveler leaves".length()))),
                mutation("event overlap", n -> array(n, "events").add(n.at("/events/0").deepCopy())),
                mutation("ending extra", n -> object(n, "/requiredEndingState").put("instructions", "private sentinel")),
                mutation("unsupported addition", n -> object(n, "/expansionPoints/0").put("additionType", "NEW_PLOT")),
                mutation("empty expansion", n -> n.putArray("expansionPoints")),
                mutation("forbidden object", n -> array(n, "forbiddenChanges").addObject().put("text", "invalid")),
                mutation("unknown entity kind", n -> object(n, "/entities/0").put("kind", "NEW_ACTOR")),
                mutation("duplicate entity", n -> array(n, "entities").add(n.at("/entities/0").deepCopy())))
                .stream().map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
                    ObjectNode node = plan(); mutation.change().accept(node); rejected(Kind.PLAN, node);
                }));
    }

    @Test
    void shouldAcceptAQuotedStateAsAnOrderedPlotAnchor() throws Exception {
        ObjectNode node = plan();
        node.withArray("events").add(node.get("requiredEndingState").deepCopy());
        var checked = check(Kind.PLAN, node);
        assertThat(checked.value().status()).isEqualTo("SUCCEEDED");
        assertThat(checked.candidate()).isFalse();
    }

    @Test
    void shouldKeepV1RestrictionsAndValidateV2GeneralFactAnchors() throws Exception {
        for (String category : List.of("IDENTITY", "RELATIONSHIP", "WORLD", "KNOWLEDGE")) {
            ObjectNode node = plan();
            object(node, "/preservedFacts/0").put("category", category);
            rejected(Kind.PLAN, node);
            node.put("schemaVersion", "adaptation-plan-v2");
            assertThat(check(Kind.PLAN, node).value().status()).isEqualTo("SUCCEEDED");
            object(node, "/events/0").put("anchor", "x".repeat("traveler leaves".length()));
            rejected(Kind.PLAN, node);
        }
    }

    @TestFactory
    Stream<DynamicTest> shouldRejectIncompleteContradictoryOrUnboundedCriticBeforeStorage() {
        return List.of(
                mutation("top-level reasoning", n -> n.put("reasoning", "private sentinel")),
                mutation("missing outcome", n -> n.remove("outcome")),
                mutation("wrong version", n -> n.put("schemaVersion", "adaptation-plan-v1")),
                mutation("invalid candidate hash", n -> n.put("candidateSha256", "invalid")),
                mutation("invalid constraint hash", n -> n.put("constraintSha256", "A".repeat(64))),
                mutation("unknown outcome", n -> n.put("outcome", "APPROVED")),
                mutation("inconsistent outcome", n -> n.put("outcome", "BLOCKED")),
                mutation("missing dimension", n -> array(n, "checks").remove(0)),
                mutation("extra dimension", n -> array(n, "checks").add(n.at("/checks/0").deepCopy())),
                mutation("duplicate dimension", n -> array(n, "checks").set(1, n.at("/checks/0").deepCopy())),
                mutation("unknown dimension", n -> object(n, "/checks/0").put("category", "STYLE")),
                mutation("non-enum verdict", n -> object(n, "/checks/0").put("verdict", true)),
                mutation("check extra", n -> object(n, "/checks/0").put("rationale", "private sentinel")),
                mutation("missing evidence", n -> object(n, "/checks/0").putNull("evidence")),
                mutation("evidence extra", n -> object(n, "/checks/0/evidence").put("secret", "private sentinel")),
                mutation("bad evidence bounds", n -> object(n, "/checks/0/evidence").put("sourceStart", -1)),
                mutation("failed check without issue", n -> object(n, "/checks/0").put("verdict", "FAIL")),
                mutation("issue without failure", n -> array(n, "issues").addObject().put("category", "FACTS").put("severity", "REPAIRABLE").put("summary", "Mismatch")),
                mutation("unknown without blocking", n -> object(n, "/checks/0").put("verdict", "UNKNOWN").putNull("evidence")),
                mutation("issues not array", n -> n.putObject("issues")))
                .stream().map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
                    ObjectNode node = critic(); mutation.change().accept(node); rejected(Kind.CRITIC, node);
                }));
    }

    @Test
    void shouldRejectUnexplainedFailuresAndSafetyDowngradeEvenWithDeclaredPass() throws Exception {
        ObjectNode repairable = (ObjectNode) mapper.readTree(StoryFixtures.critic(ORIGINAL, "a".repeat(64), "FACTS", false));
        rejected(Kind.CRITIC, repairable.deepCopy().put("outcome", "PASS"));
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                n -> object(n, "/issues/0").put("category", "NUMBERS"),
                n -> object(n, "/issues/0").put("severity", "MINOR"),
                n -> object(n, "/issues/0").put("summary", "x".repeat(501)),
                n -> object(n, "/issues/0").put("reasoning", "private sentinel"),
                n -> fill(n, "issues", 33))) {
            ObjectNode copy = repairable.deepCopy(); mutation.accept(copy); rejected(Kind.CRITIC, copy);
        }
        ObjectNode safety = (ObjectNode) mapper.readTree(StoryFixtures.critic(ORIGINAL, "a".repeat(64), "SAFETY", false));
        safety.put("outcome", "REPAIRABLE"); object(safety, "/issues/0").put("severity", "REPAIRABLE"); rejected(Kind.CRITIC, safety);
    }

    @Test
    void shouldUseCodePointsForChineseAndSupplementaryCharactersWithoutProvingSemanticTruth() throws Exception {
        String original = "\u4ed6\u63a8\u95e8\u800c\u5165\ud83c\udf19\u3002\u95e8\u4ecd\u7136\u5173\u7740\u3002";
        String raw = StoryFixtures.plan(original, List.of("\u63a8\u95e8\u800c\u5165\ud83c\udf19"), "\u95e8\u4ecd\u7136\u5173\u7740", List.of());
        assertThat(payloads.check(Kind.PLAN, success(raw)).value().structuredJson()).contains("adaptation-plan-v1");
        ObjectNode wrongHash = (ObjectNode) mapper.readTree(raw); wrongHash.put("originalSha256", "b".repeat(64));
        // 摘要是否属于实际原章仍由已有封存引擎检查，入库 schema 不冒充语义通过。
        assertThat(check(Kind.PLAN, wrongHash).candidate()).isFalse();
        ObjectNode wrongQuote = (ObjectNode) mapper.readTree(raw); object(wrongQuote, "/expansionPoints/0").put("sourceEnd", 7);
        rejected(Kind.PLAN, wrongQuote);
    }

    @Test
    void shouldRejectWrongStageAndOversizedBytePayloadWithoutLeakingOriginalException() throws Exception {
        rejected(Kind.PLAN, critic()); rejected(Kind.CRITIC, plan());
        assertThatThrownBy(() -> AdaptationOutputSchema.check(Kind.GENERATE, plan())).isInstanceOf(ChapterAdaptationException.class);
        for (String raw : List.of("{\"analysis\":\"private sentinel\"}", "{\"x\":1,\"x\":2}", "{}{}", "{\"x\":\"" + "\u4e2d".repeat(22000) + "\"}")) {
            assertThatThrownBy(() -> payloads.check(Kind.PLAN, success(raw))).isInstanceOf(ChapterAdaptationException.class)
                    .hasNoCause().hasMessageNotContaining("private sentinel");
        }
    }

    private ObjectNode plan() throws Exception { return (ObjectNode) mapper.readTree(StoryFixtures.plan(ORIGINAL, List.of("traveler leaves"), "gate remains closed", List.of("traveler"))); }
    private ObjectNode critic() throws Exception { return (ObjectNode) mapper.readTree(StoryFixtures.critic(ORIGINAL, "a".repeat(64), null, false)); }
    private AdaptationAttemptPayload.Checked check(Kind kind, ObjectNode node) { return payloads.check(kind, success(node.toString())); }
    private void rejected(Kind kind, ObjectNode node) {
        assertThatThrownBy(() -> check(kind, node)).isInstanceOfSatisfying(ChapterAdaptationException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ADAPTATION_REQUEST_INVALID)).hasNoCause().hasMessageNotContaining("private sentinel");
    }
    private static ObjectNode object(ObjectNode node, String pointer) { return (ObjectNode) node.at(pointer); }
    private static ArrayNode array(ObjectNode node, String field) { return (ArrayNode) node.get(field); }
    private static void fill(ObjectNode node, String field, int size) { ArrayNode array = array(node, field); while (array.size() < size) array.add(array.get(0).deepCopy()); }
    private static Terminal success(String json) { return new Terminal("SUCCEEDED", null, json, "stop", null, null, null, 200, null, null); }
    private static Mutation mutation(String name, Consumer<ObjectNode> change) { return new Mutation(name, change); }
    private record Mutation(String name, Consumer<ObjectNode> change) { }
}
