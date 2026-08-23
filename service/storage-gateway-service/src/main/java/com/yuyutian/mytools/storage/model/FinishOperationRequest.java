package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 执行器设置操作终态请求。
 *
 * @param status 操作终态
 * @param errorCode 可选错误码
 */
public record FinishOperationRequest(
        @NotBlank @Pattern(regexp = "^(SUCCEEDED|FAILED|TIMED_OUT|CANCELLED)$") String status,
        @Size(max = 128) String errorCode) {
}
