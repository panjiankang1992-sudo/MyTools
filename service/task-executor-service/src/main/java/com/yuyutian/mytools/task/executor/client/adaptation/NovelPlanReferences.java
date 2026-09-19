package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 仅用于原文保护型增补；模型选择原文编号，宿主绑定原句、坐标和不可改动的首尾。 */
final class NovelPlanReferences {
    private static final int WINDOW = 120;
    private static final Set<String> ROOT_FIELDS = Set.of("intentDisposition", "intentSummary", "preservedFacts",
            "expansionPoints", "forbiddenChanges", "pointOfView");
    private static final Set<String> FACT_CATEGORIES = Set.of("IDENTITY", "RELATIONSHIP", "STATE", "WORLD", "KNOWLEDGE", "EVENT");
    private static final Set<String> ADDITION_TYPES = Set.of("SENSORY", "ACTION_DETAIL", "DIALOGUE_DELIVERY", "INNER_RESPONSE", "PACING");

    private NovelPlanReferences() { }

    static ArrayNode catalog(String original) {
        var result = NovelProviderJson.MAPPER.createArrayNode();
        for (var fragment : fragments(original)) result.addObject().put("sourceId", fragment.id()).put("text", fragment.text());
        return result;
    }

    static JsonNode decode(JsonNode input, String original) {
        // 模型回抄标签不决定协议；其余字段必须完整且不得混入旧引文或坐标协议。
        var fields = new java.util.HashSet<>(ROOT_FIELDS);
        if (input != null && input.has("schemaVersion")) fields.add("schemaVersion");
        exact(input, fields);
        if (input.has("schemaVersion") && (!input.get("schemaVersion").isTextual()
                || !input.get("schemaVersion").textValue().matches("[A-Za-z0-9._-]{1,64}"))) throw failure(NovelProviderProtocolDiagnostics.Reason.SCHEMA_VERSION);
        var source = fragments(original);
        ObjectNode plan = NovelProviderJson.MAPPER.createObjectNode().put("schemaVersion", "adaptation-plan-v2")
                .put("originalSha256", NovelProviderJson.sha(original.getBytes(StandardCharsets.UTF_8)))
                .put("intentDisposition", choice(input, "intentDisposition", Set.of("COMPATIBLE", "CONFLICT")))
                .put("intentSummary", text(input, "intentSummary", 1000))
                .put("pointOfView", choice(input, "pointOfView", Set.of("FIRST", "THIRD_LIMITED", "THIRD_OMNISCIENT", "MIXED")));
        ArrayNode facts = plan.putArray("preservedFacts");
        Map<Integer, String> factIds = new HashMap<>();
        for (var value : array(input, "preservedFacts", 1, 30)) {
            exact(value, Set.of("sourceId", "category", "statement"));
            var fragment = reference(value.get("sourceId"), source);
            String id = fact(facts, fragment, choice(value, "category", FACT_CATEGORIES), text(value, "statement", 500));
            // 同段可以包含多个事实；锚点按片段去重，不遗漏模型的事实判断。
            factIds.putIfAbsent(fragment.id(), id);
        }
        for (var fragment : List.of(source.getFirst(), source.getLast())) {
            if (!factIds.containsKey(fragment.id())) factIds.put(fragment.id(), fact(facts, fragment, "STATE",
                    "Preserve this original boundary passage verbatim; do not alter its plot or knowledge state."));
        }
        var events = plan.putArray("events");
        for (var entry : new TreeMap<>(factIds).entrySet()) {
            var fragment = source.get(entry.getKey() - 1);
            String anchor = prefix(fragment.text(), 32);
            // Reader 的顺序检查使用第一次出现位置；重复段不伪装成可区分的文字锚点。
            if (original.indexOf(anchor) != original.offsetByCodePoints(0, fragment.start())) anchor = fragment.text();
            if (original.indexOf(anchor) != original.offsetByCodePoints(0, fragment.start())) continue;
            span(events.addObject().put("factId", entry.getValue()), "anchor", anchor, fragment.start());
        }
        var ending = source.getLast();
        String endingAnchor = suffix(ending.text(), 32);
        span(plan.putObject("requiredEndingState").put("factId", factIds.get(ending.id())), "anchor", endingAnchor,
                ending.end() - endingAnchor.codePointCount(0, endingAnchor.length()));
        // 增补组装保留全部原句和名称；原有 Reader 名称提取及新增实体审查仍独立运行。
        plan.putArray("entities");
        var expansions = plan.putArray("expansionPoints");
        for (var value : array(input, "expansionPoints", 1, 16)) {
            exact(value, Set.of("sourceId", "additionType", "purpose"));
            var fragment = reference(value.get("sourceId"), source);
            span(expansions.addObject().put("additionType", choice(value, "additionType", ADDITION_TYPES))
                    .put("purpose", text(value, "purpose", 500)), "anchor", fragment.text(), fragment.start());
        }
        var forbidden = plan.putArray("forbiddenChanges");
        for (var value : array(input, "forbiddenChanges", 1, 32)) {
            if (!value.isTextual()) throw failure(NovelProviderProtocolDiagnostics.Reason.INVALID_SHAPE);
            NovelProviderJson.text(value.textValue(), 500); forbidden.add(value.textValue());
        }
        return plan;
    }

    private static List<Fragment> fragments(String original) {
        NovelProviderJson.text(original, 120000);
        int count = original.codePointCount(0, original.length());
        if (count < 2) throw failure(NovelProviderProtocolDiagnostics.Reason.INVALID_SHAPE);
        List<Fragment> result = new ArrayList<>();
        int cursor = 0;
        for (int start = 0; start < count;) {
            int end = Math.min(count, start + WINDOW);
            // 最后一个单码点从前段借一个码点，不重叠、不越界，全文可逐段重建。
            if (count - end == 1) end--;
            int next = original.offsetByCodePoints(cursor, end - start);
            result.add(new Fragment(result.size() + 1, original.substring(cursor, next), start, end));
            cursor = next; start = end;
        }
        return List.copyOf(result);
    }

    private static String fact(ArrayNode facts, Fragment fragment, String category, String statement) {
        String id = "F" + (facts.size() + 1);
        span(facts.addObject().put("id", id).put("category", category).put("statement", statement),
                "sourceQuote", fragment.text(), fragment.start());
        return id;
    }

    private static void span(ObjectNode node, String field, String quote, int start) {
        node.put(field, quote).put("sourceStart", start).put("sourceEnd", start + quote.codePointCount(0, quote.length()));
    }

    private static Fragment reference(JsonNode value, List<Fragment> source) {
        int id;
        if (value != null && value.isIntegralNumber() && value.canConvertToInt()) id = value.intValue();
        else if (value != null && value.isTextual() && value.textValue().matches("S[0-9]{1,4}")) id = Integer.parseInt(value.textValue().substring(1));
        else throw failure(NovelProviderProtocolDiagnostics.Reason.REFERENCE_TYPE);
        if (id < 1 || id > source.size()) throw failure(NovelProviderProtocolDiagnostics.Reason.REFERENCE_RANGE);
        return source.get(id - 1);
    }

    private static String prefix(String value, int maximum) { return value.substring(0, value.offsetByCodePoints(0, Math.min(maximum, value.codePointCount(0, value.length())))); }
    private static String suffix(String value, int maximum) { return value.substring(value.offsetByCodePoints(0, Math.max(0, value.codePointCount(0, value.length()) - maximum))); }
    private static JsonNode array(JsonNode input, String field, int minimum, int maximum) {
        var value = input.get(field);
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum) throw failure(NovelProviderProtocolDiagnostics.Reason.INVALID_SHAPE);
        return value;
    }
    private static String choice(JsonNode node, String field, Set<String> values) {
        String value = text(node, field, 64);
        if (!values.contains(value)) throw failure(NovelProviderProtocolDiagnostics.Reason.INVALID_SHAPE);
        return value;
    }
    private static String text(JsonNode node, String field, int maximum) {
        if (!node.path(field).isTextual()) throw failure(NovelProviderProtocolDiagnostics.Reason.INVALID_SHAPE);
        String value = node.get(field).textValue(); NovelProviderJson.text(value, maximum); return value;
    }
    private static void exact(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size() || !fields.stream().allMatch(node::has)) throw failure(NovelProviderProtocolDiagnostics.Reason.ROOT_FIELDS);
    }
    private static NovelProviderException failure(NovelProviderProtocolDiagnostics.Reason reason) { return new NovelProviderException(ErrorCode.PROTOCOL, reason); }
    /** 原文片段只驻留宿主，不通过日志输出正文。 */
    private record Fragment(int id, String text, int start, int end) {
        /** 防止隐式记录原文。 */
        @Override public String toString() { return "NovelSourceFragment[REDACTED]"; }
    }
}
