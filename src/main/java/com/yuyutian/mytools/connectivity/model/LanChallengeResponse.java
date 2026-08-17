package com.yuyutian.mytools.connectivity.model;

/**
 * 局域网服务对随机挑战的带密钥证明。
 */
public record LanChallengeResponse(
        String instanceId,
        String apiVersion,
        String nonce,
        long expiresAt,
        String proof) {
}
