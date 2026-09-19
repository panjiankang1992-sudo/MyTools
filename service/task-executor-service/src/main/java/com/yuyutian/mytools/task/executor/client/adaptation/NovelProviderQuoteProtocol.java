package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 引文协议只接收模型判断；摘要、坐标和引文归属由持有发送票据的宿主确定。 */
final class NovelProviderQuoteProtocol {
    private NovelProviderQuoteProtocol() { }

    static JsonNode decode(NovelProviderModels.Phase phase, JsonNode input, String source, String constraintSha) {
        NovelProviderJson.text(source, 120000);
        if (input == null || !input.isObject()) throw invalid();
        ObjectNode node = input.deepCopy();
        if (phase == NovelProviderModels.Phase.PLAN) {
            exact(node, "schemaVersion", "intentDisposition", "intentSummary", "preservedFacts", "entities", "events",
                    "expansionPoints", "forbiddenChanges", "requiredEndingState", "pointOfView");
            if (!"adaptation-plan-quotes-v1".equals(node.path("schemaVersion").asText())) throw invalid();
            Map<String, JsonNode> facts = new HashMap<>();
            for (JsonNode fact : array(node, "preservedFacts", 64)) {
                quotedFields(fact, "id", "category", "statement", "sourceQuote");
                String id = text(fact, "id", 4);
                if (facts.putIfAbsent(id, fact) != null) throw invalid();
                locate(fact, "sourceQuote", source, 0, 200, false);
            }
            for (JsonNode entity : array(node, "entities", 64)) {
                quotedFields(entity, "name", "kind");
                locate(entity, "name", source, 0, 64, true);
            }
            for (JsonNode anchor : array(node, "events", 32)) anchor(anchor, facts);
            anchor(node.get("requiredEndingState"), facts);
            for (JsonNode point : array(node, "expansionPoints", 32)) {
                quotedFields(point, "anchor", "additionType", "purpose");
                locate(point, "anchor", source, 0, 128, false);
            }
            // v2 明确保护原文事实的出现顺序，不把知识或身份事实偷偷重分类为事件。
            node.put("schemaVersion", "adaptation-plan-v2").put("originalSha256", sha(source));
        } else if (phase == NovelProviderModels.Phase.CRITIC) {
            exact(node, "schemaVersion", "outcome", "checks", "issues");
            if (!"adaptation-critic-quotes-v1".equals(node.path("schemaVersion").asText())
                    || constraintSha == null || !constraintSha.matches("[0-9a-f]{64}")) throw invalid();
            for (JsonNode check : array(node, "checks", 10)) {
                exact(check, "category", "verdict", "evidence");
                JsonNode evidence = check.get("evidence");
                if (!evidence.isNull()) {
                    quotedFields(evidence, "quote");
                    locate(evidence, "quote", source, 0, 200, false);
                }
            }
            // 摘要绑定本次实际发送的候选及约束；不改变模型的任何 verdict、issue 或 outcome。
            node.put("schemaVersion", "adaptation-critic-v1").put("candidateSha256", sha(source))
                    .put("constraintSha256", constraintSha);
        } else throw invalid();
        return node;
    }

    private static void anchor(JsonNode node, Map<String, JsonNode> facts) {
        quotedFields(node, "factId", "anchor");
        JsonNode fact = facts.get(text(node, "factId", 4));
        if (fact == null) throw invalid();
        locate(node, "anchor", text(fact, "sourceQuote", 200), fact.path("sourceStart").intValue(), 128, false);
    }

    private static void locate(JsonNode node, String field, String source, int base, int maximum, boolean presenceOnly) {
        String quote = text(node, field, maximum);
        if (quote.codePointCount(0, quote.length()) < 2) throw invalid();
        JsonNode occurrence = node.get("occurrence");
        int requested = 1;
        if (occurrence != null) {
            if (!occurrence.isIntegralNumber() || !occurrence.canConvertToInt()
                    || occurrence.intValue() < 1 || occurrence.intValue() > 120000) throw reason(NovelProviderProtocolDiagnostics.Reason.INVALID_OCCURRENCE);
            requested = occurrence.intValue();
        }
        int found = -1;
        // 仅使用显式的一基出现序号消歧，包含重叠出现；无匹配时不得猜测或改写引文。
        for (int index = 0; index < requested; index++) {
            found = source.indexOf(quote, found + 1);
            if (found < 0) throw reason(NovelProviderProtocolDiagnostics.Reason.MISSING_QUOTE);
        }
        if (!presenceOnly && occurrence == null && source.indexOf(quote, found + 1) >= 0) throw reason(NovelProviderProtocolDiagnostics.Reason.AMBIGUOUS_QUOTE);
        int start = base + source.codePointCount(0, found);
        ((ObjectNode) node).remove("occurrence");
        ((ObjectNode) node).put("sourceStart", start).put("sourceEnd", start + quote.codePointCount(0, quote.length()));
    }

    private static void quotedFields(JsonNode node, String... fields) {
        if (node != null && node.has("occurrence")) {
            String[] extended = java.util.Arrays.copyOf(fields, fields.length + 1);
            extended[fields.length] = "occurrence";
            exact(node, extended);
        } else exact(node, fields);
    }

    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw invalid();
        for (String field : fields) if (!node.has(field)) throw invalid();
    }

    private static JsonNode array(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() > maximum) throw invalid();
        return value;
    }

    private static String text(JsonNode node, String field, int maximum) {
        if (node == null || !node.path(field).isTextual()) throw invalid();
        String value = node.get(field).textValue();
        NovelProviderJson.text(value, maximum);
        return value;
    }

    private static String sha(String value) { return NovelProviderJson.sha(value.getBytes(StandardCharsets.UTF_8)); }
    private static NovelProviderException invalid() { return new NovelProviderException(ErrorCode.PROTOCOL); }
    private static NovelProviderException reason(NovelProviderProtocolDiagnostics.Reason reason) {
        return new NovelProviderException(ErrorCode.PROTOCOL, reason);
    }
}
