package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 中止远端移动请求。
 *
 * @param status 期望终态
 */
public record AbortMoveRequest(
        @NotBlank @Pattern(regexp = "^(FAILED|TIMED_OUT|CANCELLED)$") String status) {
}
