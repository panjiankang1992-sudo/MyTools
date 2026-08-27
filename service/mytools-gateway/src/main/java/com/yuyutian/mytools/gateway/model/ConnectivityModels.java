package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Gateway 局域网发现协议模型。
 */
public final class ConnectivityModels {
    private ConnectivityModels() {
    }

    /** 通过可信连接签发的短期探测材料。 */
    public record Bootstrap(String instanceId, String serviceType, String apiVersion,
                            String probeId, String proofKey, long expiresAt,
                            List<String> lanAddresses) {
    }

    /** 未携带用户令牌的局域网挑战。 */
    public record ChallengeRequest(@NotBlank @Pattern(regexp = "^[0-9a-f]{32}$") String probeId,
                                   @NotBlank @Size(min = 16, max = 96)
                                   @Pattern(regexp = "^[A-Za-z0-9_-]+$") String nonce) {
    }

    /** 可由 App 本地验证的服务身份证明。 */
    public record Challenge(String instanceId, String apiVersion, String nonce,
                            long expiresAt, String proof) {
    }
}
