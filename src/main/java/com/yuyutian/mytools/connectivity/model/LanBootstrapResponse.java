package com.yuyutian.mytools.connectivity.model;

import java.util.List;

/**
 * 通过可信公网连接签发的局域网验证材料。
 */
public record LanBootstrapResponse(
        String instanceId,
        String serviceType,
        String apiVersion,
        String probeId,
        String proofKey,
        long expiresAt,
        List<String> lanAddresses) {
}
