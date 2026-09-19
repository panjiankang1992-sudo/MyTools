package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** 从当前候选确定性生成有界证据目录，模型只能选择编号，不能伪造引文或位置。 */
final class NovelCandidateEvidence {
    private static final int WINDOW = 160;

    private NovelCandidateEvidence() { }

    static ArrayNode catalog(String candidate) {
        NovelProviderJson.text(candidate, 120000);
        int length = candidate.codePointCount(0, candidate.length());
        if (length < 2) throw invalid();
        ArrayNode result = NovelProviderJson.MAPPER.createArrayNode();
        for (int start = 0, index = 1; start < length; start += WINDOW, index++) {
            // 尾部单码点并入前段，既不遗漏文本，也不产生小于协议下界的引文。
            int end = Math.min(length, start + WINDOW);
            if (length - end == 1) end = length;
            String quote = candidate.substring(candidate.offsetByCodePoints(0, start), candidate.offsetByCodePoints(0, end));
            result.addObject().put("id", String.format(Locale.ROOT, "E%04d", index)).put("quote", quote);
            if (end == length) break;
        }
        return result;
    }

    static JsonNode decode(JsonNode input, String candidate, String constraintSha) {
        exact(input, "schemaVersion", "outcome", "checks", "issues");
        if (!"adaptation-critic-refs-v1".equals(input.path("schemaVersion").asText())
                || constraintSha == null || !constraintSha.matches("[0-9a-f]{64}")) throw invalid();
        ArrayNode evidence = catalog(candidate);
        ObjectNode node = input.deepCopy();
        JsonNode checks = node.get("checks");
        if (!checks.isArray() || checks.size() != 10) throw invalid();
        for (JsonNode check : checks) {
            exact(check, "category", "verdict", "evidenceId");
            JsonNode reference = check.get("evidenceId");
            ((ObjectNode) check).remove("evidenceId");
            if (reference.isNull()) {
                // UNKNOWN 的空证据仍由最终严格协议检查；不把它变成 PASS。
                ((ObjectNode) check).putNull("evidence");
                continue;
            }
            if (!reference.isTextual() || !reference.textValue().matches("E[0-9]{4}")) throw invalid();
            int index = Integer.parseInt(reference.textValue().substring(1)) - 1;
            if (index < 0 || index >= evidence.size()) throw invalid();
            String quote = evidence.get(index).get("quote").textValue();
            int start = index * WINDOW;
            ((ObjectNode) check).putObject("evidence").put("quote", quote).put("sourceStart", start)
                    .put("sourceEnd", start + quote.codePointCount(0, quote.length()));
        }
        node.put("schemaVersion", "adaptation-critic-v1")
                .put("candidateSha256", NovelProviderJson.sha(candidate.getBytes(StandardCharsets.UTF_8)))
                .put("constraintSha256", constraintSha);
        return node;
    }

    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length
                || !List.of(fields).stream().allMatch(node::has)) throw invalid();
    }

    private static NovelProviderException invalid() {
        return new NovelProviderException(ErrorCode.PROTOCOL, NovelProviderProtocolDiagnostics.Reason.INVALID_REFERENCE);
    }
}
