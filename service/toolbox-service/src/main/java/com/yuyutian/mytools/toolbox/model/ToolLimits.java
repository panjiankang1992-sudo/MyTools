package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 本服务实际执行的限额，以及尚未启用的公开 PDF 执行限额声明。
 *
 * @param jsonBodyBytes 普通 JSON 请求体上限
 * @param eventsBodyBytes 事件接口请求体上限
 * @param textBytes 单次文本输入 UTF-8 字节上限
 * @param eventsPerBatch 单批事件数量上限
 * @param entriesPageSizeMax 教材条目分页上限
 * @param sessionTtlHours 会话有效期小时数
 * @param pdfExecution 公开 PDF 执行限额声明
 */
public record ToolLimits(
        /** 普通 JSON 请求体上限。 */
        @JsonProperty("jsonBodyBytes") long jsonBodyBytes,
        /** 事件接口请求体上限。 */
        @JsonProperty("eventsBodyBytes") long eventsBodyBytes,
        /** 单次文本输入 UTF-8 字节上限。 */
        @JsonProperty("textBytes") long textBytes,
        /** 单批事件数量上限。 */
        @JsonProperty("eventsPerBatch") int eventsPerBatch,
        /** 教材条目分页上限。 */
        @JsonProperty("entriesPageSizeMax") int entriesPageSizeMax,
        /** 会话有效期小时数。 */
        @JsonProperty("sessionTtlHours") long sessionTtlHours,
        /** 公开 PDF 执行限额声明。 */
        @JsonProperty("pdfExecution") PdfExecutionLimits pdfExecution) {
}
