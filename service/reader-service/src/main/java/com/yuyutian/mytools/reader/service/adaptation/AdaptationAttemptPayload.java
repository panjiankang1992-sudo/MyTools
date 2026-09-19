package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Kind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Terminal;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

/** 账本只接受有限、已归一的模型输出，规范摘要涵盖全部持久终态字段。 */
@Component
public final class AdaptationAttemptPayload {
    private static final Set<String> FAILURES = Set.of("READER_039", "READER_040", "READER_041", "READER_043",
            "READER_044", "READER_053", "READER_055", "READER_058");
    private final ObjectMapper mapper;

    /** 为结构化结果单独设置重复键、尾随 JSON、深度和标量限制。 */
    public AdaptationAttemptPayload(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(32768).maxNumberLength(32).build());
    }

    /** 规范结果只允许正文或结构化结果之一，失败载荷不保存 Provider 错误正文。 */
    public Checked check(Kind kind, Terminal input) {
        if (input == null || kind == null || !Set.of("SUCCEEDED", "FAILED", "CALL_OUTCOME_UNKNOWN").contains(input.status() == null ? "" : input.status())) throw invalid();
        if (input.providerRequestId() != null && !input.providerRequestId().matches("[A-Za-z0-9_.:-]{1,128}")) throw invalid();
        if (input.finishReason() != null && !Set.of("stop", "length", "content_filter", "error", "unknown").contains(input.finishReason())) throw invalid();
        if (input.diagnosticSha256() != null) AdaptationText.requireSha256(input.diagnosticSha256(), ErrorCode.ADAPTATION_REQUEST_INVALID);
        if (input.httpStatus() != null && (input.httpStatus() < 100 || input.httpStatus() > 599)
                || !validTokens(input.inputTokens()) || !validTokens(input.outputTokens())) throw invalid();
        String structured = null;
        int codepoints = 0;
        boolean success = "SUCCEEDED".equals(input.status());
        if (success) {
            if (input.errorCode() != null || !"stop".equals(input.finishReason())
                    || input.httpStatus() == null || input.httpStatus() < 200 || input.httpStatus() >= 300) throw invalid();
            if (kind == Kind.GENERATE || kind == Kind.REPAIR) {
                codepoints = AdaptationText.requireText(input.outputText(), 1, 120000, ErrorCode.ADAPTATION_REQUEST_INVALID);
                if (input.outputText().isBlank() || input.structuredJson() != null) throw invalid();
                String leading = input.outputText().stripLeading().toLowerCase(java.util.Locale.ROOT);
                // Reader 再次检查正文封装，不能只信任执行器已经过滤了 Markdown 或 HTML 包裹。
                if (leading.startsWith("```") || leading.startsWith("#") || leading.startsWith("<html") || leading.startsWith("<!doctype")) throw invalid();
            } else {
                if (input.outputText() != null) throw invalid();
                structured = canonical(kind, input.structuredJson());
            }
        } else {
            if (input.outputText() != null || input.structuredJson() != null || input.errorCode() == null) throw invalid();
            if ("CALL_OUTCOME_UNKNOWN".equals(input.status()) ? !"READER_054".equals(input.errorCode()) : !FAILURES.contains(input.errorCode())) throw invalid();
            // 普通 403 不是密钥失效，只有显式归一的鉴权分类可打开认证桶。
            if ("READER_040".equals(input.errorCode()) && Integer.valueOf(403).equals(input.httpStatus())) throw invalid();
        }
        Terminal normalized = new Terminal(input.status(), input.outputText(), structured, input.finishReason(), input.providerRequestId(),
                input.inputTokens(), input.outputTokens(), input.httpStatus(), input.errorCode(), input.diagnosticSha256());
        String digest = AdaptationText.fingerprint("adaptation-attempt-terminal-v1", Arrays.asList(kind.name(), normalized.status(), normalized.outputText(),
                structured, normalized.finishReason(), normalized.providerRequestId(), string(normalized.inputTokens()), string(normalized.outputTokens()),
                string(normalized.httpStatus()), normalized.errorCode(), normalized.diagnosticSha256()));
        return new Checked(normalized, digest, normalized.outputText() == null ? null : AdaptationText.sha256(normalized.outputText()),
                structured == null ? null : AdaptationText.sha256(structured), codepoints,
                success && (kind == Kind.GENERATE || kind == Kind.REPAIR));
    }

    /** 仅完整校验后输出规范内容、长度与摘要，调用者不能声明自己的终态摘要。 */
    public record Checked(Terminal value, String sha256, String outputSha256, String structuredSha256, int codepoints, boolean candidate) {
        /** 所有正文及结构化内容保持脱敏。 */
        @Override
        public String toString() { return "Checked[payload=REDACTED]"; }
    }

    private String canonical(Kind kind, String raw) {
        if (raw == null || raw.length() > 65536 || raw.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
        AdaptationText.requireText(raw, 1, 65536, ErrorCode.ADAPTATION_REQUEST_INVALID);
        try {
            JsonNode parsed = mapper.readTree(raw);
            // 完整字段白名单、证据定位及引用先通过，任何未知字段均不进入终态 JSON 或摘要。
            AdaptationOutputSchema.check(kind, parsed);
            String result = mapper.writeValueAsString(sorted(parsed));
            if (result.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
            return result;
        } catch (Exception exception) { throw invalid(); }
    }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            var result = mapper.createObjectNode();
            for (String name : names) {
                AdaptationText.requireText(name, 0, 32768, ErrorCode.ADAPTATION_REQUEST_INVALID);
                result.set(name, sorted(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            for (JsonNode item : node) result.add(sorted(item));
            return result;
        }
        if (node.isTextual()) AdaptationText.requireText(node.textValue(), 0, 32768, ErrorCode.ADAPTATION_REQUEST_INVALID);
        return node;
    }
    private static boolean validTokens(Long value) { return value == null || value >= 0 && value <= 10000000; }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID); }
}
