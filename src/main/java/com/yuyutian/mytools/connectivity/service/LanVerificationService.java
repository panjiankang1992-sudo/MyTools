package com.yuyutian.mytools.connectivity.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.connectivity.model.LanBootstrapResponse;
import com.yuyutian.mytools.connectivity.model.LanChallengeRequest;
import com.yuyutian.mytools.connectivity.model.LanChallengeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在不向未知局域网主机发送 JWT 的前提下验证 MyTools 服务身份。
 */
@Service
public class LanVerificationService {

    private static final Duration PROBE_TTL = Duration.ofMinutes(10);
    private static final int MAX_ACTIVE_PROBES = 4096;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Probe> probes = new ConcurrentHashMap<>();

    @Value("${mytools.connectivity.instance-id:mytools-default}")
    private String instanceId;

    @Value("${mytools.connectivity.api-version:v1}")
    private String apiVersion;

    /**
     * 为已认证用户签发短期局域网探测材料。
     *
     * @param userId 当前用户标识
     * @return 探测标识和只交付给 App 的证明密钥
     */
    public LanBootstrapResponse issue(Long userId) {
        cleanupExpired();
        if (probes.size() >= MAX_ACTIVE_PROBES) {
            probes.entrySet().stream().min(Map.Entry.comparingByValue())
                    .ifPresent(entry -> probes.remove(entry.getKey(), entry.getValue()));
        }
        byte[] idBytes = randomBytes(16);
        byte[] proofKey = randomBytes(32);
        String probeId = HexFormat.of().formatHex(idBytes);
        long expiresAt = clock.millis() + PROBE_TTL.toMillis();
        probes.put(probeId, new Probe(userId, proofKey, expiresAt));
        return new LanBootstrapResponse(instanceId, "_mytools._tcp.local", apiVersion, probeId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(proofKey), expiresAt, localIpv4Addresses());
    }

    /**
     * 在局域网候选地址上响应随机挑战。
     *
     * @param request 挑战请求
     * @return 可由 App 使用公网签发密钥验证的证明
     */
    public LanChallengeResponse challenge(LanChallengeRequest request) {
        Probe probe = probes.get(request.probeId());
        if (probe == null) {
            throw new BusinessException(ErrorCode.CONNECTIVITY_001);
        }
        if (probe.expiresAt() <= clock.millis()) {
            probes.remove(request.probeId(), probe);
            throw new BusinessException(ErrorCode.CONNECTIVITY_002);
        }
        String message = proofMessage(request.nonce(), probe.expiresAt());
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(probe.proofKey(), message));
        return new LanChallengeResponse(instanceId, apiVersion, request.nonce(), probe.expiresAt(), proof);
    }

    private String proofMessage(String nonce, long expiresAt) {
        return instanceId + "\n" + nonce + "\n" + apiVersion + "\n" + expiresAt;
    }

    private byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to create LAN verification proof", ex);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        secureRandom.nextBytes(value);
        return value;
    }

    private void cleanupExpired() {
        long now = clock.millis();
        probes.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private List<String> localIpv4Addresses() {
        try {
            List<String> addresses = new ArrayList<>();
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
            return List.copyOf(addresses.stream().distinct().sorted().toList());
        } catch (SocketException ex) {
            return List.of();
        }
    }

    private record Probe(Long userId, byte[] proofKey, long expiresAt) implements Comparable<Probe> {
        /**
         * 按过期时间比较探测记录。
         *
         * @param other 其他记录
         * @return 比较结果
         */
        @Override
        public int compareTo(Probe other) {
            return Long.compare(expiresAt, other.expiresAt);
        }
    }
}
