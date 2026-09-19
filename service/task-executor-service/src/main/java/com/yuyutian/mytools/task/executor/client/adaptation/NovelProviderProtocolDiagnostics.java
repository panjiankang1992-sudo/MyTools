package com.yuyutian.mytools.task.executor.client.adaptation;

import org.slf4j.LoggerFactory;

/** 协议诊断只使用固定枚举和已验证摘要，绝不输出模型正文、字段值或异常链。 */
final class NovelProviderProtocolDiagnostics {
    /** 固定的协议处理边界。 */
    enum Layer { REASONING_WRAPPER, JSON_WRAPPER, JSON_PARSE, QUOTE_BINDING, REFERENCE_BINDING, INSERTION_BINDING, PLAN_REFERENCE_BINDING, CANONICAL_SCHEMA }
    /** 固定失败原因，不允许由 Provider 提供字符串。 */
    enum Reason { INVALID_SHAPE, MISSING_QUOTE, AMBIGUOUS_QUOTE, INVALID_OCCURRENCE, INVALID_REFERENCE,
        ROOT_FIELDS, SCHEMA_VERSION, CHECK_FIELDS, REFERENCE_TYPE, REFERENCE_RANGE, VERDICT_ISSUE,
        INSERTION_BUDGET, INSERTION_SLOT, BASE_NOT_INSERTION }

    private NovelProviderProtocolDiagnostics() { }

    static void rejected(NovelProviderModels.Phase phase, Layer layer, NovelProviderException failure, String digest) {
        String safeDigest = digest != null && digest.matches("[0-9a-f]{64}") ? digest : "unavailable";
        LoggerFactory.getLogger(NovelProviderProtocolDiagnostics.class).warn(
                "novel_provider_protocol_rejected phase={} layer={} reason={} diagnosticSha256={}",
                phase, layer, failure.protocolReason(), safeDigest);
    }
}
