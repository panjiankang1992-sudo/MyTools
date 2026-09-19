package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationConsentModels.Payload;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 严格解析已发布的告知，摘要覆盖用户实际看到的全部文案和 Provider 合约身份。 */
public final class AdaptationDisclosurePayload {
    private final ObjectMapper mapper;

    /** 独立配置 JSON 约束，不影响旧 Reader API。 */
    public AdaptationDisclosurePayload(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(8192).build());
    }

    /** 数据库存储可有一次 H2 字符串包装，不能容忍额外字段、坏摘要或任意 Provider。 */
    public Payload parse(Object raw, String expectedSha, String providerCode, String contractSha) {
        try {
            if (raw == null) throw invalid();
            String text = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : raw.toString();
            if (text.length() > 32768) throw invalid();
            var node = mapper.readTree(text);
            if (node == null) throw invalid();
            if (node.isTextual()) node = mapper.readTree(node.textValue());
            if (node == null || !node.isObject() || node.size() != 8) throw invalid();
            var fields = node.elements();
            while (fields.hasNext()) if (!fields.next().isTextual()) throw invalid();
            Payload result = mapper.treeToValue(node, Payload.class);
            validate(result);
            if (!providerCode.equals(result.providerCode()) || !contractSha.equals(result.contractSha256())
                    || !sha256(result).equals(expectedSha)) throw invalid();
            return result;
        } catch (JsonProcessingException | IllegalArgumentException exception) { throw invalid(); }
    }

    /** 按固定字段顺序输出规范 JSON，供受控登记工具和测试计算同一摘要。 */
    public String canonical(Payload value) {
        validate(value);
        var node = mapper.createObjectNode();
        node.put("schemaVersion", value.schemaVersion()).put("providerCode", value.providerCode())
                .put("contractSha256", value.contractSha256()).put("providerName", value.providerName())
                .put("providerOrigin", value.providerOrigin()).put("dataUseNotice", value.dataUseNotice())
                .put("retentionNotice", value.retentionNotice()).put("rightsNotice", value.rightsNotice());
        try { return mapper.writeValueAsString(node); }
        catch (JsonProcessingException exception) { throw invalid(); }
    }

    /** 摘要包括权利声明原文，单独替换文案不能复用已取得的同意。 */
    public String sha256(Payload value) { return AdaptationText.sha256(canonical(value)); }

    private static void validate(Payload value) {
        if (value == null || !"adaptation-disclosure-v1".equals(value.schemaVersion())
                || value.providerCode() == null || !value.providerCode().matches("[A-Za-z0-9_.:-]{1,32}")
                || !"https://api.sillytraven.dev".equals(value.providerOrigin())) throw invalid();
        AdaptationText.requireSha256(value.contractSha256(), ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED);
        AdaptationText.requireText(value.providerName(), 1, 120, ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED);
        for (String text : List.of(value.dataUseNotice() == null ? "" : value.dataUseNotice(),
                value.retentionNotice() == null ? "" : value.retentionNotice(), value.rightsNotice() == null ? "" : value.rightsNotice())) {
            AdaptationText.requireText(text, 5, 2000, ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED);
            if (text.isBlank()) throw invalid();
        }
    }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED); }
}
