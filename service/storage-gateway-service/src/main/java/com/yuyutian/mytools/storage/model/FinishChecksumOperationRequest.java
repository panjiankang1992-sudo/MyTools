package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 完成本地校验和任务请求。
 *
 * @param status 终态
 * @param sizeBytes 文件大小
 * @param contentSha256 内容摘要
 * @param errorCode 可选错误码
 */
public record FinishChecksumOperationRequest(
        @NotBlank @Pattern(regexp = "^(SUCCEEDED|FAILED|TIMED_OUT|CANCELLED)$") String status,
        @Min(0) Long sizeBytes,
        @Pattern(regexp = "^[0-9a-f]{64}$") String contentSha256,
        @Size(max = 128) String errorCode) {
}
