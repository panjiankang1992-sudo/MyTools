package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/** 仅宿主加密日志内部使用的窄结算记录，不包含普通 assertion、模型密钥或生成请求。 */
@JsonIgnoreType
final class ReaderRelayEnvelope {
    private static final String VERSION = "reader-settlement-relay-v1";
    final URI origin;
    final String certificate;
    final UUID adaptationId;
    final UUID executionId;
    final UUID attemptId;
    final UUID providerId;
    final String requestSha;
    final Instant expiresAt;
    final String token;
    final NovelStageOutput.Terminal terminal;

    ReaderRelayEnvelope(URI origin, String certificate, UUID adaptationId, UUID executionId, UUID attemptId,
                        UUID providerId, String requestSha, Instant expiresAt, String token, NovelStageOutput.Terminal terminal) {
        this.origin = root(origin); this.certificate = certificate; this.adaptationId = adaptationId; this.executionId = executionId;
        this.attemptId = attemptId; this.providerId = providerId; this.requestSha = requestSha; this.expiresAt = expiresAt;
        this.token = token; this.terminal = terminal;
        if (!base64(certificate) || adaptationId == null || executionId == null || attemptId == null || providerId == null
                || requestSha == null || !requestSha.matches("[0-9a-f]{64}") || expiresAt == null || token == null
                || !token.matches("settle-v1\\.[A-Za-z0-9_-]{1,64}\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}")) throw invalid();
        String[] parts = token.split("\\.");
        if (!base64(parts[2]) || !base64(parts[3])) throw invalid();
        if (terminal != null) terminal(NovelProviderJson.MAPPER.valueToTree(terminal));
    }

    ReaderRelayEnvelope withTerminal(NovelStageOutput.Terminal result) {
        if (result == null) throw invalid();
        return new ReaderRelayEnvelope(origin, certificate, adaptationId, executionId, attemptId, providerId, requestSha, expiresAt, token, result);
    }

    byte[] encode() {
        ObjectNode value = NovelProviderJson.MAPPER.createObjectNode().put("version", VERSION).put("origin", origin.toASCIIString())
                .put("certificate", certificate).put("adaptationId", adaptationId.toString()).put("executionId", executionId.toString())
                .put("attemptId", attemptId.toString()).put("providerId", providerId.toString()).put("requestSha256", requestSha)
                .put("expiresAt", expiresAt.toString()).put("token", token);
        value.set("terminal", NovelProviderJson.MAPPER.valueToTree(terminal));
        return NovelProviderJson.encode(value).getBytes(StandardCharsets.UTF_8);
    }

    static ReaderRelayEnvelope decode(byte[] bytes) {
        try {
            JsonNode value = NovelProviderJson.parse(NovelProviderJson.utf8(bytes));
            exact(value, "version", "origin", "certificate", "adaptationId", "executionId", "attemptId", "providerId",
                    "requestSha256", "expiresAt", "token", "terminal");
            if (!VERSION.equals(text(value, "version"))) throw invalid();
            return new ReaderRelayEnvelope(URI.create(text(value, "origin")), text(value, "certificate"), id(value, "adaptationId"),
                    id(value, "executionId"), id(value, "attemptId"), id(value, "providerId"), text(value, "requestSha256"),
                    Instant.parse(text(value, "expiresAt")), text(value, "token"), value.get("terminal").isNull() ? null : terminal(value.get("terminal")));
        } catch (Exception exception) { throw invalid(); }
    }

    static NovelStageOutput.Terminal unknown() {
        return new NovelStageOutput.Terminal("CALL_OUTCOME_UNKNOWN", null, null, null, null, null, null, null, ErrorCode.UNKNOWN.code(), null);
    }

    static URI root(URI value) {
        if (value == null || !"https".equals(value.getScheme()) || value.getHost() == null || value.getRawUserInfo() != null
                || value.getRawQuery() != null || value.getRawFragment() != null || !(value.getPath().isEmpty() || "/".equals(value.getPath()))) throw invalid();
        return value.resolve("/");
    }

    private static NovelStageOutput.Terminal terminal(JsonNode node) {
        exact(node, "status", "outputText", "structuredJson", "finishReason", "providerRequestId", "inputTokens", "outputTokens", "httpStatus", "errorCode", "diagnosticSha256");
        String status = text(node, "status");
        if (!Set.of("SUCCEEDED", "FAILED", "CALL_OUTCOME_UNKNOWN").contains(status)) throw invalid();
        for (String field : new String[]{"outputText", "structuredJson", "finishReason", "providerRequestId", "errorCode", "diagnosticSha256"}) {
            if (!node.get(field).isNull() && !node.get(field).isTextual()) throw invalid();
        }
        for (String field : new String[]{"inputTokens", "outputTokens", "httpStatus"}) {
            JsonNode number = node.get(field);
            if (!number.isNull() && (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 0 || number.intValue() > 10000000)) throw invalid();
        }
        if (!node.get("httpStatus").isNull() && (node.get("httpStatus").intValue() < 100 || node.get("httpStatus").intValue() > 599)
                || !node.get("providerRequestId").isNull() && !text(node, "providerRequestId").matches("[A-Za-z0-9_.:-]{1,128}")
                || !node.get("diagnosticSha256").isNull() && !text(node, "diagnosticSha256").matches("[0-9a-f]{64}")) throw invalid();
        if ("SUCCEEDED".equals(status)) {
            if (!"stop".equals(text(node, "finishReason")) || node.get("outputText").isNull() == node.get("structuredJson").isNull()
                    || !node.get("errorCode").isNull() || !node.get("httpStatus").isInt()
                    || node.get("httpStatus").intValue() < 200 || node.get("httpStatus").intValue() > 299) throw invalid();
            if (!node.get("outputText").isNull()) NovelProviderJson.text(text(node, "outputText"), 120000);
            else {
                if (text(node, "structuredJson").getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
                NovelProviderJson.parse(text(node, "structuredJson"));
            }
        } else {
            if (!node.get("outputText").isNull() || !node.get("structuredJson").isNull() || !node.get("finishReason").isNull()) throw invalid();
            String error = text(node, "errorCode");
            if ("CALL_OUTCOME_UNKNOWN".equals(status) ? !ErrorCode.UNKNOWN.code().equals(error)
                    : !Set.of(ErrorCode.UNAVAILABLE, ErrorCode.UNAUTHORIZED, ErrorCode.PROTOCOL, ErrorCode.CONSTRAINTS,
                            ErrorCode.FENCED, ErrorCode.CONTENT_REJECTED, ErrorCode.REQUEST_REJECTED, ErrorCode.DEADLINE)
                            .contains(ErrorCode.fromReader(error))) throw invalid();
        }
        try { return NovelProviderJson.MAPPER.treeToValue(node, NovelStageOutput.Terminal.class); }
        catch (Exception exception) { throw invalid(); }
    }

    private static boolean base64(String value) {
        try {
            if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException exception) { return false; }
    }
    private static UUID id(JsonNode node, String field) { String text = text(node, field); UUID result = UUID.fromString(text); if (!result.toString().equals(text)) throw invalid(); return result; }
    private static String text(JsonNode node, String field) { if (node == null || !node.path(field).isTextual()) throw invalid(); return node.get(field).textValue(); }
    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw invalid();
        for (String field : fields) if (!node.has(field)) throw invalid();
    }
    private static ReaderAdaptationException invalid() { return new ReaderAdaptationException(ErrorCode.PERSISTENCE_UNAVAILABLE, 0); }
    /** 窄令牌和正文不可进入诊断。 */
    @Override public String toString() { return "ReaderRelayEnvelope[REDACTED]"; }
}
