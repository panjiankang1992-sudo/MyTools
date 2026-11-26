package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 公开 PDF 执行限额；active=false 表示本部署尚未启用，数值仅作声明。
 *
 * @param active 执行链是否已启用
 * @param noteKey 状态说明键
 * @param values 契约 5.5 的初值声明
 */
public record PdfExecutionLimits(
        /** 执行链是否已启用。 */
        @JsonProperty("active") boolean active,
        /** 状态说明键。 */
        @JsonProperty("noteKey") String noteKey,
        /** 契约 5.5 的初值声明。 */
        @JsonProperty("values") Map<String, Long> values) {
}
