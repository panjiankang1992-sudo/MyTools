package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Phase;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Result;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 在宿主回写前拒绝阶段外字段；事实证据和剧情语义仍由 Reader 权威引擎重新校验。 */
public final class NovelStageOutput {
    private static final Set<String> CATEGORIES = Set.of("FACTS", "ENTITIES", "NUMBERS", "EVENT_ORDER", "ENTRY_STATE",
            "ENDING_STATE", "POINT_OF_VIEW", "WORLD_RULES", "INTENT", "SAFETY");

    /** 把模型结果转换成 Reader 终态字段，未知推理字段和不合格正文不进入回写载荷。 */
    public Terminal normalize(Phase phase, Result result) {
        if (phase == null || result == null) throw invalid();
        if (!"SUCCEEDED".equals(result.status())) return terminal(result, null, null);
        try {
            if (phase == Phase.GENERATE || phase == Phase.REPAIR) {
                NovelProviderJson.text(result.content(), 120000);
                String leading = result.content().stripLeading().toLowerCase(java.util.Locale.ROOT);
                if (leading.startsWith("```") || leading.startsWith("#") || leading.startsWith("<html") || leading.startsWith("<!doctype")) throw invalid();
                return terminal(result, result.content(), null);
            }
            if (result.content() == null || result.content().getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
            JsonNode parsed = NovelProviderJson.parse(result.content());
            if (phase == Phase.PLAN) plan(parsed); else critic(parsed);
            String canonical = NovelProviderJson.encode(parsed);
            if (canonical.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
            return terminal(result, null, canonical);
        } catch (NovelProviderException exception) {
            NovelProviderProtocolDiagnostics.rejected(phase, NovelProviderProtocolDiagnostics.Layer.CANONICAL_SCHEMA,
                    exception, result.diagnosticSha256());
            return terminal(NovelProviderResponseParser.failure(ErrorCode.PROTOCOL,
                    result.httpStatus(), result.diagnosticSha256()), null, null);
        }
    }

    /** 此 DTO 只允许发往受限 Reader 结算路由，不进入 Scheduler completion 或 SQLite 日志。 */
    public record Terminal(String status, String outputText, String structuredJson, String finishReason,
                           String providerRequestId, Integer inputTokens, Integer outputTokens,
                           Integer httpStatus, String errorCode, String diagnosticSha256) {
        /** 避免终态正文通过日志输出。 */
        @Override public String toString() { return "NovelStageTerminal[status=" + status + ", content=REDACTED]"; }
    }

    private static void plan(JsonNode node) {
        exact(node, "schemaVersion", "originalSha256", "intentDisposition", "intentSummary", "preservedFacts",
                "entities", "events", "expansionPoints", "forbiddenChanges", "requiredEndingState", "pointOfView");
        String version = literal(node, "schemaVersion", Set.of("adaptation-plan-v1", "adaptation-plan-v2"));
        Set<String> anchorCategories = "adaptation-plan-v2".equals(version)
                ? Set.of("IDENTITY", "RELATIONSHIP", "STATE", "WORLD", "KNOWLEDGE", "EVENT") : Set.of("EVENT", "STATE");
        sha(node, "originalSha256");
        literal(node, "intentDisposition", Set.of("COMPATIBLE", "CONFLICT"));
        text(node.get("intentSummary"), 1, 1000);
        literal(node, "pointOfView", Set.of("FIRST", "THIRD_LIMITED", "THIRD_OMNISCIENT", "MIXED"));
        Map<String, JsonNode> facts = new HashMap<>();
        for (JsonNode fact : array(node, "preservedFacts", 1, 64)) {
            exact(fact, "id", "category", "statement", "sourceQuote", "sourceStart", "sourceEnd");
            String id = text(fact.get("id"), 2, 4);
            if (!id.matches("F[1-9][0-9]{0,2}") || facts.putIfAbsent(id, fact) != null) throw invalid();
            literal(fact, "category", Set.of("IDENTITY", "RELATIONSHIP", "STATE", "WORLD", "KNOWLEDGE", "EVENT"));
            text(fact.get("statement"), 1, 500); span(fact, "sourceQuote", 200);
        }
        Set<String> names = new HashSet<>();
        for (JsonNode entity : array(node, "entities", 0, 64)) {
            exact(entity, "name", "kind", "sourceStart", "sourceEnd");
            span(entity, "name", 64);
            if (!names.add(entity.get("name").textValue())) throw invalid();
            literal(entity, "kind", Set.of("PERSON", "PLACE", "ORGANIZATION", "ITEM", "ABILITY", "TITLE"));
        }
        int previousEnd = -1;
        for (JsonNode event : array(node, "events", 1, 32)) {
            // 顺序锚点保护原文事件及当时已有状态，不把状态事实重分类为新事件。
            anchor(event, facts, anchorCategories);
            // 与 Reader 入库边界保持一致，非法计划先转为固定协议失败，避免带正文重复结算。
            if (event.get("sourceStart").intValue() < previousEnd) throw invalid();
            previousEnd = event.get("sourceEnd").intValue();
        }
        anchor(node.get("requiredEndingState"), facts, anchorCategories);
        for (JsonNode expansion : array(node, "expansionPoints", 1, 32)) {
            exact(expansion, "anchor", "sourceStart", "sourceEnd", "additionType", "purpose");
            span(expansion, "anchor", 128); text(expansion.get("purpose"), 1, 500);
            literal(expansion, "additionType", Set.of("SENSORY", "ACTION_DETAIL", "DIALOGUE_DELIVERY", "INNER_RESPONSE", "PACING"));
        }
        for (JsonNode item : array(node, "forbiddenChanges", 1, 32)) text(item, 1, 500);
    }

    private static void critic(JsonNode node) {
        exact(node, "schemaVersion", "candidateSha256", "constraintSha256", "outcome", "checks", "issues");
        literal(node, "schemaVersion", Set.of("adaptation-critic-v1"));
        sha(node, "candidateSha256"); sha(node, "constraintSha256");
        String outcome = literal(node, "outcome", Set.of("PASS", "REPAIRABLE", "BLOCKED"));
        Set<String> seen = new HashSet<>();
        Set<String> failures = new HashSet<>();
        Set<String> explained = new HashSet<>();
        boolean blocked = false;
        for (JsonNode check : array(node, "checks", 10, 10)) {
            exact(check, "category", "verdict", "evidence");
            String category = literal(check, "category", CATEGORIES);
            if (!seen.add(category)) throw invalid();
            String verdict = literal(check, "verdict", Set.of("PASS", "FAIL", "UNKNOWN"));
            if (!"PASS".equals(verdict)) failures.add(category);
            blocked |= "UNKNOWN".equals(verdict) || "SAFETY".equals(category) && "FAIL".equals(verdict);
            JsonNode evidence = check.get("evidence");
            if (evidence.isNull()) { if (!"UNKNOWN".equals(verdict)) throw invalid(); }
            else { exact(evidence, "quote", "sourceStart", "sourceEnd"); span(evidence, "quote", 200); }
        }
        for (JsonNode issue : array(node, "issues", 0, 32)) {
            exact(issue, "category", "severity", "summary");
            String category = literal(issue, "category", CATEGORIES);
            if (!failures.contains(category)) throw invalid();
            explained.add(category);
            blocked |= "BLOCKED".equals(literal(issue, "severity", Set.of("REPAIRABLE", "BLOCKED")));
            text(issue.get("summary"), 1, 500);
        }
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
        String quote = fact.get("sourceQuote").textValue();
        if (start < 0 || end > quote.codePointCount(0, quote.length())
                || !quote.substring(quote.offsetByCodePoints(0, start), quote.offsetByCodePoints(0, end)).equals(node.get("anchor").textValue())) throw invalid();
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
        JsonNode result = node.get(field);
        if (result == null || !result.isArray() || result.size() < minimum || result.size() > maximum) throw invalid();
        return result;
    }
    private static String text(JsonNode node, int minimum, int maximum) {
        if (node == null || !node.isTextual()) throw invalid();
        NovelProviderJson.text(node.textValue(), maximum);
        if (node.textValue().codePointCount(0, node.textValue().length()) < minimum) throw invalid();
        return node.textValue();
    }
    private static String literal(JsonNode node, String field, Set<String> allowed) {
        String value = text(node.get(field), 1, 64);
        if (!allowed.contains(value)) throw invalid();
        return value;
    }
    private static void sha(JsonNode node, String field) {
        if (!text(node.get(field), 64, 64).matches("[0-9a-f]{64}")) throw invalid();
    }
    private static Terminal terminal(Result result, String text, String json) {
        return new Terminal(result.status(), text, json, result.finishReason(), result.providerRequestId(),
                result.inputTokens(), result.outputTokens(), result.httpStatus(), result.errorCode(), result.diagnosticSha256());
    }
    private static NovelProviderException invalid() { return new NovelProviderException(ErrorCode.PROTOCOL); }
}
