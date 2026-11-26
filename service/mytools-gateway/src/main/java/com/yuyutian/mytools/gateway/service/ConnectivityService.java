package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.model.ConnectivityModels.Bootstrap;
import com.yuyutian.mytools.gateway.model.ConnectivityModels.Challenge;
import com.yuyutian.mytools.gateway.model.ConnectivityModels.ChallengeRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 Gateway 边缘局域网探测的短期状态与证明。
 */
@Service
public class ConnectivityService {
    private static final Duration PROBE_TTL = Duration.ofMinutes(10);
    private static final int MAXIMUM_PROBES = 4096;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    // 新入口经本机代理访问同一个 Gateway，避免误连仍在运行的旧单体。
    private static final int LAN_GATEWAY_PORT = 23201;

    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Probe> probes = new ConcurrentHashMap<>();
    private final String instanceId;
    private final String apiVersion;
    @Value("${gateway.connectivity.ipv6-direct-origins:}")
    private String configuredDirectOrigins = "";
    private List<String> directOrigins = List.of();

    /** 启动时校验直连入口，禁止错误配置扩散至客户端。 */
    @PostConstruct
    void validateDirectOrigins() {
        if (configuredDirectOrigins.isBlank()) {
            directOrigins = List.of();
            return;
        }
        List<String> values = new ArrayList<>();
        for (String raw : configuredDirectOrigins.split(",", -1)) {
            URI origin = URI.create(raw.trim());
            String host = origin.getHost();
            if (!"https".equals(origin.getScheme()) || host == null
                    || !host.matches("(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,62}")
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null || !origin.getRawPath().isEmpty()
                    || origin.getPort() == 0 || origin.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid IPv6 direct origin configuration");
            }
            values.add(origin.toASCIIString());
        }
        if (values.size() > 4) {
            throw new IllegalArgumentException("Too many IPv6 direct origins");
        }
        directOrigins = values.stream().distinct().toList();
    }

    /**
     * 创建局域网探测服务。
     *
     * @param instanceId 稳定实例标识
     * @param apiVersion API 版本
     */
    public ConnectivityService(
            @Value("${gateway.connectivity.instance-id:mytools-gateway}") String instanceId,
            @Value("${gateway.connectivity.api-version:v1}") String apiVersion) {
        this.instanceId = instanceId;
        this.apiVersion = apiVersion;
    }

    /**
     * 为已认证主体签发一个短期探测材料。
     *
     * @param userId 可信用户标识
     * @return 探测材料
     */
    public Bootstrap issue(long userId) {
        cleanup();
        if (probes.size() >= MAXIMUM_PROBES) {
            // 达到硬上限时只移除最早过期的临时记录。
            probes.entrySet().stream().min(Map.Entry.comparingByValue())
                    .ifPresent(entry -> probes.remove(entry.getKey(), entry.getValue()));
        }
        byte[] identifier = randomBytes(16);
        byte[] proofKey = randomBytes(32);
        String probeId = HexFormat.of().formatHex(identifier);
        long expiresAt = clock.millis() + PROBE_TTL.toMillis();
        probes.put(probeId, new Probe(userId, proofKey, expiresAt));
        return new Bootstrap(instanceId, "_mytools-gateway._tcp.local", apiVersion, probeId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(proofKey), expiresAt,
                localIpv4Addresses(), LAN_GATEWAY_PORT, directOrigins,
                clock.millis() + Duration.ofDays(7).toMillis());
    }

    /**
     * 对一个尚未过期的探测执行随机挑战。
     *
     * @param request 挑战参数
     * @return HMAC 身份证明
     */
    public Challenge challenge(ChallengeRequest request) {
        Probe probe = probes.get(request.probeId());
        if (probe == null || probe.expiresAt() <= clock.millis()) {
            if (probe != null) {
                probes.remove(request.probeId(), probe);
            }
            throw new GatewayUnauthorizedException();
        }
        String message = instanceId + "\n" + request.nonce() + "\n" + apiVersion + "\n" + probe.expiresAt();
        return new Challenge(instanceId, apiVersion, request.nonce(), probe.expiresAt(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(probe.proofKey(), message)));
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        random.nextBytes(value);
        return value;
    }

    private byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create LAN verification proof", exception);
        }
    }

    private void cleanup() {
        long now = clock.millis();
        probes.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private List<String> localIpv4Addresses() {
        try {
            List<String> addresses = new ArrayList<>();
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    // 只导出 RFC1918 IPv4，避免公网或 IPv6 网卡导致客户端拒绝整份发现材料。
                    if (isLanAddress(address)) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
            return List.copyOf(addresses.stream().distinct().sorted().toList());
        } catch (SocketException exception) {
            return List.of();
        }
    }

    /** 判断网卡地址是否属于客户端允许发现的 RFC1918 IPv4。 */
    static boolean isLanAddress(InetAddress address) {
        return address instanceof Inet4Address && address.isSiteLocalAddress();
    }

    private record Probe(long userId, byte[] proofKey, long expiresAt) implements Comparable<Probe> {
        /**
         * 按过期时间比较临时记录。
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
