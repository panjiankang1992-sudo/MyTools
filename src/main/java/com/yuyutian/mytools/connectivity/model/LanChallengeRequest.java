package com.yuyutian.mytools.connectivity.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 局域网候选服务身份验证请求。
 */
public record LanChallengeRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{32}$")
        String probeId,
        @NotBlank
        @Size(min = 16, max = 96)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        String nonce) {
}
