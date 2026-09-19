package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeMap;

/** 有界增补协议保护原文字面骨架；新增语义仍须通过独立 Reader 评审，不能直接采用。 */
final class NovelInsertionProtocol {
    private NovelInsertionProtocol() { }

    static Scope prepare(ObjectNode input) {
        String original = input.path("original").textValue();
        NovelProviderJson.text(original, 120000);
        int[] points = original.codePoints().toArray();
        JsonNode constraints = input.path("constraints");
        JsonNode plan = constraints.path("supplement");
        int minimum = Math.toIntExact((points.length * constraints.path("deterministic").path("minimumLengthPermille").asLong() + 999) / 1000);
        int maximum = Math.min(120000, Math.toIntExact(points.length * constraints.path("deterministic").path("maximumLengthPermille").asLong() / 1000));
        int lastCut = points.length * 3 / 4;
        List<Integer> options = new ArrayList<>();
        options.add(0);
        for (int index = 0; index < lastCut; index++) {
            int value = points[index];
            boolean boundary = value == '\n' || "\u3002\uff01\uff1f!?".indexOf(value) >= 0
                    || value == '.' && index + 1 < points.length && Character.isWhitespace(points[index + 1]);
            if (!boundary) continue;
            int cut = index + 1;
            while (cut < points.length && "\u201d\u2019\"'\r\n".indexOf(points[cut]) >= 0) cut++;
            if (cut > lastCut) continue;
            if (options.isEmpty() || options.getLast() != cut) options.add(cut);
        }
        List<Integer> cuts = new ArrayList<>();
        int count = Math.min(8, options.size());
        for (int index = 0; index < count; index++) cuts.add(options.get(index * options.size() / count));
        Set<Integer> allowed = new HashSet<>();
        for (int index = 0; index < cuts.size(); index++) {
            int cut = cuts.get(index);
            // 边界编号只由原文决定，不因派生版本的新 PLAN 改变；本次 PLAN 仅限制可写边界。
            if (cut <= plan.path("requiredEndingState").path("sourceStart").asInt(0)
                    && !protectedAt(plan.path("events"), cut) && !protectedAt(plan.path("entities"), cut)) allowed.add(index + 1);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int cut : cuts) { parts.add(new String(points, start, cut - start)); start = cut; }
        parts.add(new String(points, start, points.length - start));
        int maximumAdditions = Math.min(allowed.size(), Math.min(8, points.length / 500 + 2));
        int budget = Math.min(2400, Math.min(points.length / 2, maximum - points.length)) - 2 * maximumAdditions;
        if (budget < 1 || minimum > points.length + budget) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_BUDGET);
        int perAddition = Math.min(budget, Math.min(120, Math.max(40, points.length / 6)));
        Scope scope = new Scope(original, List.copyOf(parts), Set.copyOf(allowed), maximumAdditions, budget, perAddition, minimum, maximum);
        String previous = input.has("candidate") ? input.path("candidate").textValue()
                : input.has("baseInput") ? input.path("baseInput").textValue() : original;
        List<String> existing = scope.additions(previous);
        ArrayNode slots = input.putArray("insertionSlots");
        for (int index = 0; index < cuts.size(); index++) {
            if (!allowed.contains(index + 1)) continue;
            int cut = cuts.get(index);
            slots.addObject().put("slotId", index + 1).put("leftContext", new String(points, Math.max(0, cut - 80), Math.min(80, cut)))
                    .put("rightContext", new String(points, cut, Math.min(80, points.length - cut)))
                    .put("currentAddition", existing.get(index));
        }
        input.putObject("insertionBudget").put("maximumAdditions", maximumAdditions).put("maximumTotalCodepoints", budget)
                .put("maximumPerAdditionCodepoints", perAddition).put("targetTotalCodepoints", Math.min(budget, Math.max(1, budget * 2 / 3)))
                .put("minimumFinalCodepoints", minimum).put("maximumFinalCodepoints", maximum);
        return scope;
    }

    private static boolean protectedAt(JsonNode spans, int cut) {
        for (JsonNode span : spans) if (span.path("sourceStart").asInt() < cut && cut < span.path("sourceEnd").asInt()) return true;
        return false;
    }

    /** 只驻留宿主发送票据，不允许日志递归展开原文或历史增补。 */
    static final class Scope {
        private final String original;
        private final List<String> parts;
        private final Set<Integer> allowed;
        private final int maximumAdditions;
        private final int budget;
        private final int perAddition;
        private final int minimum;
        private final int maximum;

        private Scope(String original, List<String> parts, Set<Integer> allowed, int maximumAdditions, int budget, int perAddition, int minimum, int maximum) {
            this.original = original; this.parts = parts; this.allowed = allowed; this.maximumAdditions = maximumAdditions;
            this.budget = budget; this.perAddition = perAddition; this.minimum = minimum; this.maximum = maximum;
        }

        String assemble(JsonNode node) {
            if (node == null || !node.isObject() || !node.has("additions") || node.size() != (node.has("schemaVersion") ? 2 : 1)) {
                throw failure(NovelProviderProtocolDiagnostics.Reason.ROOT_FIELDS);
            }
            // 协议由发送票据选择，不依赖模型回抄标签；可选标签只允许有界 ASCII 元数据，不参与解释正文。
            if (node.has("schemaVersion") && (!node.get("schemaVersion").isTextual()
                    || !node.get("schemaVersion").textValue().matches("[A-Za-z0-9._-]{1,64}"))) throw failure(NovelProviderProtocolDiagnostics.Reason.SCHEMA_VERSION);
            JsonNode additions = node.get("additions");
            if (!additions.isArray() || additions.isEmpty() || additions.size() > maximumAdditions) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_BUDGET);
            Map<Integer, String> values = new TreeMap<>();
            int used = 0;
            for (JsonNode addition : additions) {
                if (!addition.isObject() || addition.size() != 2 || !addition.has("slotId") || !addition.has("text")) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_SLOT);
                JsonNode id = addition.get("slotId");
                if (!id.isIntegralNumber() || !id.canConvertToInt() || !allowed.contains(id.intValue())) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_SLOT);
                if (!addition.get("text").isTextual()) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_SLOT);
                String text = addition.get("text").textValue().strip();
                NovelProviderJson.text(text, 120000);
                if (text.codePointCount(0, text.length()) > perAddition) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_BUDGET);
                if (values.putIfAbsent(id.intValue(), text) != null) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_SLOT);
                used += text.codePointCount(0, text.length());
            }
            if (used > budget) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_BUDGET);
            StringBuilder body = new StringBuilder(parts.getFirst());
            for (int index = 1; index < parts.size(); index++) {
                String text = values.get(index);
                if (text != null) body.append('\n').append(text).append('\n');
                body.append(parts.get(index));
            }
            String candidate = body.toString();
            int length = candidate.codePointCount(0, candidate.length());
            if (length < minimum || length > maximum || candidate.equals(original)) throw failure(NovelProviderProtocolDiagnostics.Reason.INSERTION_BUDGET);
            // 必须能够无歧义还原增补边界，确保后续优化和修复确实沿用当前版本，而非重写原文。
            var restored = additions(candidate);
            for (int index = 0; index < restored.size(); index++) {
                if (!restored.get(index).equals(values.getOrDefault(index + 1, ""))) throw failure(NovelProviderProtocolDiagnostics.Reason.BASE_NOT_INSERTION);
            }
            return candidate;
        }

        private List<String> additions(String candidate) {
            if (candidate == null || !candidate.startsWith(parts.getFirst())) throw failure(NovelProviderProtocolDiagnostics.Reason.BASE_NOT_INSERTION);
            List<String> result = new ArrayList<>();
            int cursor = parts.getFirst().length();
            for (int index = 1; index < parts.size(); index++) {
                String next = parts.get(index);
                int found = candidate.indexOf(next, cursor);
                if (found < 0) throw failure(NovelProviderProtocolDiagnostics.Reason.BASE_NOT_INSERTION);
                String between = candidate.substring(cursor, found);
                if (between.isEmpty()) result.add("");
                else {
                    if (!between.startsWith("\n") || !between.endsWith("\n") || between.length() < 3) throw failure(NovelProviderProtocolDiagnostics.Reason.BASE_NOT_INSERTION);
                    String added = between.substring(1, between.length() - 1);
                    NovelProviderJson.text(added, perAddition);
                    result.add(added);
                }
                cursor = found + next.length();
            }
            if (cursor != candidate.length()) throw failure(NovelProviderProtocolDiagnostics.Reason.BASE_NOT_INSERTION);
            return result;
        }

        /** 只显示协议类型，不输出原文和用户增补内容。 */
        @Override public String toString() { return "NovelInsertionScope[REDACTED]"; }
    }

    private static NovelProviderException failure(NovelProviderProtocolDiagnostics.Reason reason) { return new NovelProviderException(ErrorCode.PROTOCOL, reason); }
}
