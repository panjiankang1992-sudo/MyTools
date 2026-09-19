package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** 模型逐项判断，宿主按固定规则聚合；不猜测缺失项，也不改写否决或问题描述。 */
final class NovelCriticChecklist {
    static final List<String> CATEGORIES = List.of("FACTS", "ENTITIES", "NUMBERS", "EVENT_ORDER", "ENTRY_STATE", "ENDING_STATE",
            "POINT_OF_VIEW", "WORLD_RULES", "INTENT", "SAFETY");
    private NovelCriticChecklist() { }

    static JsonNode decode(JsonNode input, String candidate, String constraintSha) {
        // 显式接受已复现的扁平检查表和单层 checks 包装；未知字段、混合形状及缺项仍拒绝。
        boolean wrapped = input != null && input.has("checks");
        exact(input, wrapped ? input.has("schemaVersion") ? Set.of("schemaVersion", "checks") : Set.of("checks")
                : Set.copyOf(CATEGORIES), NovelProviderProtocolDiagnostics.Reason.ROOT_FIELDS);
        // 可选版本回抄不具有协议选择权；宿主始终解释当前已发送的固定十项检查表。
        if (input.has("schemaVersion") && (!input.get("schemaVersion").isTextual()
                || !input.get("schemaVersion").textValue().matches("[A-Za-z0-9._-]{1,64}"))) throw failure(NovelProviderProtocolDiagnostics.Reason.SCHEMA_VERSION);
        if (constraintSha == null || !constraintSha.matches("[0-9a-f]{64}")) throw failure(NovelProviderProtocolDiagnostics.Reason.INVALID_SHAPE);
        JsonNode sourceChecks = wrapped ? input.get("checks") : input;
        exact(sourceChecks, Set.copyOf(CATEGORIES), NovelProviderProtocolDiagnostics.Reason.CHECK_FIELDS);
        var catalog = NovelCandidateEvidence.catalog(candidate);
        ObjectNode result = NovelProviderJson.MAPPER.createObjectNode().put("schemaVersion", "adaptation-critic-v1")
                .put("candidateSha256", NovelProviderJson.sha(candidate.getBytes(StandardCharsets.UTF_8))).put("constraintSha256", constraintSha);
        var checks = result.putArray("checks");
        var issues = result.putArray("issues");
        boolean failed = false;
        boolean blocked = false;
        for (String category : CATEGORIES) {
            JsonNode item = sourceChecks.get(category);
            exact(item, Set.of("verdict", "evidenceId", "issue"), NovelProviderProtocolDiagnostics.Reason.CHECK_FIELDS);
            String verdict = item.path("verdict").asText();
            if (!Set.of("PASS", "FAIL", "UNKNOWN").contains(verdict)) throw failure(NovelProviderProtocolDiagnostics.Reason.VERDICT_ISSUE);
            var check = checks.addObject().put("category", category).put("verdict", verdict);
            JsonNode reference = item.get("evidenceId");
            if (reference.isNull()) {
                if (!"UNKNOWN".equals(verdict)) throw failure(NovelProviderProtocolDiagnostics.Reason.REFERENCE_TYPE);
                check.putNull("evidence");
            } else {
                // 十进制整数和目录 E 编号是显式的一基别名；不按近似字符串匹配或裁剪越界编号。
                int id;
                if (reference.isIntegralNumber() && reference.canConvertToInt()) id = reference.intValue();
                else if (reference.isTextual() && reference.textValue().matches("E[0-9]{1,4}")) id = Integer.parseInt(reference.textValue().substring(1));
                else throw failure(NovelProviderProtocolDiagnostics.Reason.REFERENCE_TYPE);
                if (id < 1 || id > catalog.size()) throw failure(NovelProviderProtocolDiagnostics.Reason.REFERENCE_RANGE);
                String quote = catalog.get(id - 1).path("quote").textValue();
                int start = (id - 1) * 160;
                check.putObject("evidence").put("quote", quote).put("sourceStart", start).put("sourceEnd", start + quote.codePointCount(0, quote.length()));
            }
            JsonNode issue = item.get("issue");
            if ("PASS".equals(verdict)) {
                if (!issue.isNull()) throw failure(NovelProviderProtocolDiagnostics.Reason.VERDICT_ISSUE);
            } else {
                exact(issue, Set.of("severity", "summary"), NovelProviderProtocolDiagnostics.Reason.VERDICT_ISSUE);
                String severity = issue.path("severity").asText();
                if (!Set.of("REPAIRABLE", "BLOCKED").contains(severity) || !issue.path("summary").isTextual()) throw failure(NovelProviderProtocolDiagnostics.Reason.VERDICT_ISSUE);
                NovelProviderJson.text(issue.get("summary").textValue(), 500);
                issues.addObject().put("category", category).put("severity", severity).put("summary", issue.get("summary").textValue());
                failed = true;
                blocked |= "UNKNOWN".equals(verdict) || "SAFETY".equals(category) || "BLOCKED".equals(severity);
            }
        }
        result.put("outcome", blocked ? "BLOCKED" : failed ? "REPAIRABLE" : "PASS");
        return result;
    }

    private static void exact(JsonNode node, Set<String> fields, NovelProviderProtocolDiagnostics.Reason reason) {
        if (node == null || !node.isObject() || node.size() != fields.size() || !fields.stream().allMatch(node::has)) throw failure(reason);
    }
    private static NovelProviderException failure(NovelProviderProtocolDiagnostics.Reason reason) { return new NovelProviderException(ErrorCode.PROTOCOL, reason); }
}
