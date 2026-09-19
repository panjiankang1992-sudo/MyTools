package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Kind;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reader 独立的阶段输出白名单；校验结构与引用，不把格式合格等同于剧情或内容审核通过。 */
public final class AdaptationOutputSchema {
    private static final Set<String> CATEGORIES = Set.of("FACTS", "ENTITIES", "NUMBERS", "EVENT_ORDER", "ENTRY_STATE",
            "ENDING_STATE", "POINT_OF_VIEW", "WORLD_RULES", "INTENT", "SAFETY");

    private AdaptationOutputSchema() { }

    /** 在任何持久化及摘要生成之前验证固定阶段的完整结构，不接受未声明的推理或任意附件。 */
    public static void check(Kind kind, JsonNode node) {
        if (kind == Kind.PLAN) plan(node);
        else if (kind == Kind.CRITIC) critic(node);
        else throw invalid();
    }

    private static void plan(JsonNode node) {
        exact(node, "schemaVersion", "originalSha256", "intentDisposition", "intentSummary", "preservedFacts",
                "entities", "events", "expansionPoints", "forbiddenChanges", "requiredEndingState", "pointOfView");
        String version = choice(node, "schemaVersion", Set.of("adaptation-plan-v1", "adaptation-plan-v2"));
        Set<String> anchorCategories = "adaptation-plan-v2".equals(version)
                ? Set.of("IDENTITY", "RELATIONSHIP", "STATE", "WORLD", "KNOWLEDGE", "EVENT") : Set.of("EVENT", "STATE");
        sha(node, "originalSha256");
        choice(node, "intentDisposition", Set.of("COMPATIBLE", "CONFLICT"));
        text(node.get("intentSummary"), 1, 1000);
        choice(node, "pointOfView", Set.of("FIRST", "THIRD_LIMITED", "THIRD_OMNISCIENT", "MIXED"));
        Map<String, JsonNode> facts = new HashMap<>();
        for (JsonNode fact : array(node, "preservedFacts", 1, 64)) {
            exact(fact, "id", "category", "statement", "sourceQuote", "sourceStart", "sourceEnd");
            String id = text(fact.get("id"), 2, 4);
            if (!id.matches("F[1-9][0-9]{0,2}") || facts.putIfAbsent(id, fact) != null) throw invalid();
            choice(fact, "category", Set.of("IDENTITY", "RELATIONSHIP", "STATE", "WORLD", "KNOWLEDGE", "EVENT"));
            text(fact.get("statement"), 1, 500); span(fact, "sourceQuote", 200);
        }
        Set<String> names = new HashSet<>();
        for (JsonNode entity : array(node, "entities", 0, 64)) {
            exact(entity, "name", "kind", "sourceStart", "sourceEnd");
            span(entity, "name", 64);
            if (!names.add(entity.get("name").textValue())) throw invalid();
            choice(entity, "kind", Set.of("PERSON", "PLACE", "ORGANIZATION", "ITEM", "ABILITY", "TITLE"));
        }
        int previousEnd = -1;
        for (JsonNode event : array(node, "events", 1, 32)) {
            // 状态锚点同样必须逐字来自被引用事实，不能通过类别兼容绕过证据校验。
            anchor(event, facts, anchorCategories);
            // 事件定位按原章顺序且不重叠，不能凭计划改变已有因果顺序。
            if (event.get("sourceStart").intValue() < previousEnd) throw invalid();
            previousEnd = event.get("sourceEnd").intValue();
        }
        anchor(node.get("requiredEndingState"), facts, anchorCategories);
        for (JsonNode expansion : array(node, "expansionPoints", 1, 32)) {
            exact(expansion, "anchor", "sourceStart", "sourceEnd", "additionType", "purpose");
            span(expansion, "anchor", 128); text(expansion.get("purpose"), 1, 500);
            choice(expansion, "additionType", Set.of("SENSORY", "ACTION_DETAIL", "DIALOGUE_DELIVERY", "INNER_RESPONSE", "PACING"));
        }
        for (JsonNode item : array(node, "forbiddenChanges", 1, 32)) text(item, 1, 500);
    }

    private static void critic(JsonNode node) {
        exact(node, "schemaVersion", "candidateSha256", "constraintSha256", "outcome", "checks", "issues");
        choice(node, "schemaVersion", Set.of("adaptation-critic-v1"));
        sha(node, "candidateSha256"); sha(node, "constraintSha256");
        String outcome = choice(node, "outcome", Set.of("PASS", "REPAIRABLE", "BLOCKED"));
        Set<String> seen = new HashSet<>();
        Set<String> failures = new HashSet<>();
        Set<String> explained = new HashSet<>();
        boolean blocked = false;
        for (JsonNode check : array(node, "checks", 10, 10)) {
            exact(check, "category", "verdict", "evidence");
            String category = choice(check, "category", CATEGORIES);
            if (!seen.add(category)) throw invalid();
            String verdict = choice(check, "verdict", Set.of("PASS", "FAIL", "UNKNOWN"));
            if (!"PASS".equals(verdict)) failures.add(category);
            blocked |= "UNKNOWN".equals(verdict) || "SAFETY".equals(category) && "FAIL".equals(verdict);
            JsonNode evidence = check.get("evidence");
            if (evidence.isNull()) { if (!"UNKNOWN".equals(verdict)) throw invalid(); }
            else { exact(evidence, "quote", "sourceStart", "sourceEnd"); span(evidence, "quote", 200); }
        }
        for (JsonNode issue : array(node, "issues", 0, 32)) {
            exact(issue, "category", "severity", "summary");
            String category = choice(issue, "category", CATEGORIES);
            if (!failures.contains(category)) throw invalid();
            explained.add(category);
            blocked |= "BLOCKED".equals(choice(issue, "severity", Set.of("REPAIRABLE", "BLOCKED")));
            text(issue.get("summary"), 1, 500);
        }
        // 自报 PASS 不得覆盖失败维度，未知判断及安全失败也不能降级为可修复。
        String expected = blocked ? "BLOCKED" : failures.isEmpty() ? "PASS" : "REPAIRABLE";
        if (!explained.equals(failures) || !expected.equals(outcome)) throw invalid();
    }

    private static void anchor(JsonNode node, Map<String, JsonNode> facts, Set<String> categories) {
        exact(node, "factId", "anchor", "sourceStart", "sourceEnd");
        JsonNode fact = facts.get(text(node.get("factId"), 2, 4));
        span(node, "anchor", 128);
        if (fact == null || !categories.contains(fact.get("category").textValue())) throw invalid();
        int start = node.get("sourceStart").intValue() - fact.get("sourceStart").intValue();
        int end = node.get("sourceEnd").intValue() - fact.get("sourceStart").intValue();
        String source = fact.get("sourceQuote").textValue();
        if (start < 0 || end > source.codePointCount(0, source.length())
                || !source.substring(source.offsetByCodePoints(0, start), source.offsetByCodePoints(0, end)).equals(node.get("anchor").textValue())) throw invalid();
    }
    private static void span(JsonNode node, String field, int maximum) {
        String value = text(node.get(field), 2, maximum);
        JsonNode start = node.get("sourceStart"); JsonNode end = node.get("sourceEnd");
        if (start == null || end == null || !start.isIntegralNumber() || !end.isIntegralNumber()
                || !start.canConvertToInt() || !end.canConvertToInt() || start.intValue() < 0 || start.intValue() > 120000
                || end.intValue() <= start.intValue() || end.intValue() > 120000
                || end.intValue() - start.intValue() != value.codePointCount(0, value.length())) throw invalid();
    }
    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw invalid();
        for (String field : fields) if (!node.has(field)) throw invalid();
    }
    private static JsonNode array(JsonNode node, String field, int minimum, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum) throw invalid();
        return value;
    }
    private static String text(JsonNode node, int minimum, int maximum) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.textValue();
        AdaptationText.requireText(value, minimum, maximum, ErrorCode.ADAPTATION_REQUEST_INVALID);
        if (value.isBlank()) throw invalid();
        return value;
    }
    private static String choice(JsonNode node, String field, Set<String> choices) {
        String value = text(node.get(field), 1, 64);
        if (!choices.contains(value)) throw invalid();
        return value;
    }
    private static void sha(JsonNode node, String field) {
        if (!text(node.get(field), 64, 64).matches("[a-f0-9]{64}")) throw invalid();
    }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID); }
}
