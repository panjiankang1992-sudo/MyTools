package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 显式启用的 Provider 方言解码；只规范外层包装及可由原文证明的位置，不改引文或判断。 */
final class NovelProviderContentCodec {
    private NovelProviderContentCodec() { }

    static NovelProviderModels.Result decodeBounded(NovelProviderModels.Phase phase, NovelProviderModels.Result result,
                                                     String candidate, String constraintSha, NovelInsertionProtocol.Scope scope) {
        if (!"SUCCEEDED".equals(result.status())) return result;
        var layer = NovelProviderProtocolDiagnostics.Layer.REASONING_WRAPPER;
        try {
            String content = withoutReasoning(result.content());
            if (content.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
            layer = NovelProviderProtocolDiagnostics.Layer.JSON_WRAPPER;
            content = structuralPunctuation(withoutFence(content));
            layer = NovelProviderProtocolDiagnostics.Layer.JSON_PARSE;
            var node = NovelProviderJson.parse(content);
            layer = phase == NovelProviderModels.Phase.PLAN ? NovelProviderProtocolDiagnostics.Layer.PLAN_REFERENCE_BINDING
                    : phase == NovelProviderModels.Phase.CRITIC ? NovelProviderProtocolDiagnostics.Layer.REFERENCE_BINDING : NovelProviderProtocolDiagnostics.Layer.INSERTION_BINDING;
            if (phase == NovelProviderModels.Phase.PLAN) content = NovelProviderJson.encode(NovelPlanReferences.decode(node, candidate));
            else if (phase == NovelProviderModels.Phase.CRITIC) content = NovelProviderJson.encode(NovelCriticChecklist.decode(node, candidate, constraintSha));
            else {
                if (scope == null || phase != NovelProviderModels.Phase.GENERATE && phase != NovelProviderModels.Phase.REPAIR) throw invalid();
                content = scope.assemble(node);
            }
            return new NovelProviderModels.Result(result.status(), content, result.finishReason(), result.providerRequestId(),
                    result.inputTokens(), result.outputTokens(), result.httpStatus(), result.errorCode(), result.diagnosticSha256());
        } catch (NovelProviderException exception) {
            NovelProviderProtocolDiagnostics.rejected(phase, layer, exception, result.diagnosticSha256());
            return NovelProviderResponseParser.failure(ErrorCode.PROTOCOL, result.httpStatus(), result.diagnosticSha256());
        }
    }

    static NovelProviderModels.Result decodeQuotes(NovelProviderModels.Phase phase, NovelProviderModels.Result result,
                                                    String source, String constraintSha) {
        return decodeStructured(phase, result, source, constraintSha, false);
    }

    static NovelProviderModels.Result decodeReferences(NovelProviderModels.Result result, String candidate, String constraintSha) {
        return decodeStructured(NovelProviderModels.Phase.CRITIC, result, candidate, constraintSha, true);
    }

    private static NovelProviderModels.Result decodeStructured(NovelProviderModels.Phase phase, NovelProviderModels.Result result,
                                                               String source, String constraintSha, boolean references) {
        if (!"SUCCEEDED".equals(result.status())) return result;
        if (phase != NovelProviderModels.Phase.PLAN && phase != NovelProviderModels.Phase.CRITIC) return decode(phase, result, source);
        var layer = NovelProviderProtocolDiagnostics.Layer.REASONING_WRAPPER;
        try {
            String content = withoutReasoning(result.content());
            if (content.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
            layer = NovelProviderProtocolDiagnostics.Layer.JSON_WRAPPER;
            content = structuralPunctuation(withoutFence(content));
            layer = NovelProviderProtocolDiagnostics.Layer.JSON_PARSE;
            var wire = NovelProviderJson.parse(content);
            layer = references ? NovelProviderProtocolDiagnostics.Layer.REFERENCE_BINDING : NovelProviderProtocolDiagnostics.Layer.QUOTE_BINDING;
            content = NovelProviderJson.encode(references ? NovelCandidateEvidence.decode(wire, source, constraintSha)
                    : NovelProviderQuoteProtocol.decode(phase, wire, source, constraintSha));
            return new NovelProviderModels.Result(result.status(), content, result.finishReason(), result.providerRequestId(),
                    result.inputTokens(), result.outputTokens(), result.httpStatus(), result.errorCode(), result.diagnosticSha256());
        } catch (NovelProviderException exception) {
            NovelProviderProtocolDiagnostics.rejected(phase, layer, exception, result.diagnosticSha256());
            return NovelProviderResponseParser.failure(ErrorCode.PROTOCOL, result.httpStatus(), result.diagnosticSha256());
        }
    }

    static NovelProviderModels.Result decode(NovelProviderModels.Phase phase, NovelProviderModels.Result result, String source) {
        if (!"SUCCEEDED".equals(result.status())) return result;
        try {
            String content = withoutReasoning(result.content());
            if (phase == NovelProviderModels.Phase.PLAN || phase == NovelProviderModels.Phase.CRITIC) {
                if (content.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
                var node = NovelProviderJson.parse(structuralPunctuation(withoutFence(content)));
                NovelProviderJson.text(source, 120000);
                String digest = phase == NovelProviderModels.Phase.PLAN ? "originalSha256" : "candidateSha256";
                // 只能使用当前已发送请求的原文或候选；不能借解码器重绑另一份内容。
                if (!NovelProviderJson.sha(source.getBytes(StandardCharsets.UTF_8)).equals(node.path(digest).asText())) throw invalid();
                if (phase == NovelProviderModels.Phase.PLAN) plan(node, source); else critic(node, source);
                content = NovelProviderJson.encode(node);
            }
            return new NovelProviderModels.Result(result.status(), content, result.finishReason(), result.providerRequestId(),
                    result.inputTokens(), result.outputTokens(), result.httpStatus(), result.errorCode(), result.diagnosticSha256());
        } catch (NovelProviderException exception) {
            return NovelProviderResponseParser.failure(ErrorCode.PROTOCOL, result.httpStatus(), result.diagnosticSha256());
        }
    }

    private static String withoutReasoning(String content) {
        NovelProviderJson.text(content, 120000);
        String leading = content.stripLeading();
        if (!leading.startsWith("<think>")) {
            if (content.contains("<think>") || content.contains("</think>")) throw invalid();
            return content;
        }
        int end = leading.indexOf("</think>");
        // 仅识别一个有界且闭合的前导协议块；不猜测缺失的边界，也不保留推理正文。
        if (end < 7 || end > 32768 || leading.indexOf("<think>", 7) >= 0
                || leading.indexOf("</think>", end + 8) >= 0) throw invalid();
        String body = leading.substring(end + 8).stripLeading();
        NovelProviderJson.text(body, 120000);
        return body;
    }

    private static String withoutFence(String content) {
        String value = content.strip();
        if (!value.startsWith("```")) return value;
        int newline = value.indexOf('\n');
        if (newline < 0 || !("```json".equals(value.substring(0, newline).stripTrailing())
                || "```".equals(value.substring(0, newline).stripTrailing())) || !value.endsWith("\n```")) throw invalid();
        return value.substring(newline + 1, value.length() - 4);
    }

    private static String structuralPunctuation(String content) {
        StringBuilder result = new StringBuilder(content.length());
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (quoted) {
                // 字符串内部逐字保留，包括中文标点及转义；绝不全局替换用户引文。
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else {
                if (current == '"') quoted = true;
                current = switch (current) {
                    case '\uff1a' -> ':';
                    case '\uff0c' -> ',';
                    case '\uff5b' -> '{';
                    case '\uff5d' -> '}';
                    case '\uff3b' -> '[';
                    case '\uff3d' -> ']';
                    default -> current;
                };
            }
            result.append(current);
        }
        return result.toString();
    }

    private static void plan(JsonNode node, String source) {
        Map<String, JsonNode> facts = new HashMap<>();
        for (var fact : array(node, "preservedFacts", 64)) {
            String id = text(fact, "id", 4);
            if (facts.putIfAbsent(id, fact) != null) throw invalid();
            span(fact, "sourceQuote", source, 0, 200, false);
        }
        for (var entity : array(node, "entities", 64)) {
            // 实体位置只证明名称出现；首次出现是确定性坐标，不承载事件顺序或审查证据。
            span(entity, "name", source, 0, 64, true);
        }
        for (var event : array(node, "events", 32)) anchor(event, facts);
        for (var expansion : array(node, "expansionPoints", 32)) span(expansion, "anchor", source, 0, 128, false);
        anchor(node.get("requiredEndingState"), facts);
    }

    private static void critic(JsonNode node, String source) {
        for (var check : array(node, "checks", 10)) {
            JsonNode evidence = check.get("evidence");
            if (evidence == null) throw invalid();
            if (!evidence.isNull()) span(evidence, "quote", source, 0, 200, false);
        }
    }

    private static void anchor(JsonNode node, Map<String, JsonNode> facts) {
        JsonNode fact = facts.get(text(node, "factId", 4));
        if (fact == null) throw invalid();
        span(node, "anchor", text(fact, "sourceQuote", 200), fact.get("sourceStart").intValue(), 128, false);
    }

    private static void span(JsonNode node, String field, String source, int base, int maximum, boolean presenceOnly) {
        String quote = text(node, field, maximum);
        if (quote.codePointCount(0, quote.length()) < 2) throw invalid();
        int start = offset(node, "sourceStart");
        int end = offset(node, "sourceEnd");
        int count = source.codePointCount(0, source.length());
        if (start >= base && end > start && end - base <= count
                && source.substring(source.offsetByCodePoints(0, start - base), source.offsetByCodePoints(0, end - base)).equals(quote)) return;
        int found = source.indexOf(quote);
        // 不存在的引用和无法消歧的事实或评审证据必须失败，不能按最近位置猜测。
        if (found < 0 || !presenceOnly && source.indexOf(quote, found + 1) >= 0) throw invalid();
        int canonicalStart = base + source.codePointCount(0, found);
        ((ObjectNode) node).put("sourceStart", canonicalStart).put("sourceEnd", canonicalStart + quote.codePointCount(0, quote.length()));
    }

    private static int offset(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > 120000) throw invalid();
        return value.intValue();
    }

    private static JsonNode array(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() > maximum) throw invalid();
        return value;
    }

    private static String text(JsonNode node, String field, int maximum) {
        if (node == null || !node.isObject() || !node.path(field).isTextual()) throw invalid();
        String value = node.get(field).textValue();
        NovelProviderJson.text(value, maximum);
        return value;
    }

    private static NovelProviderException invalid() { return new NovelProviderException(ErrorCode.PROTOCOL); }
}
