package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 执行一个已配置邮箱账户轮询的请求。
 *
 * @param accountKey 账户逻辑键
 */
public record EmailPollRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_]+$") String accountKey) {
}
