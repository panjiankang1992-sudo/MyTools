package com.yuyutian.mytools.reader.service.adaptation.authorization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAttemptSettlementProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅 Reader 可签发的有状态窄能力，MAC 绑定数据库中的完整范围，不携带正文。 */
@Component
public final class AttemptSettlementSigner {
    private final String active;
    private final Map<String, SecretKeySpec> keys;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** 从独立 owner-only 挂载加载专用密钥环，格式及权限错误失败关闭。 */
    @Autowired
    public AttemptSettlementSigner(ReaderAttemptSettlementProperties properties, ObjectMapper mapper) {
        this(load(properties.keyringFile(), mapper), Clock.systemUTC());
    }

    private AttemptSettlementSigner(Keyring ring, Clock clock) {
        this.active = ring.active();
        this.keys = ring.keys();
        this.clock = clock;
    }

    /** 测试只使用随机内存密钥，不读取真实部署凭据。 */
    public AttemptSettlementSigner(String active, Map<String, byte[]> keys, Clock clock) {
        this(validate(active, keys), clock);
    }

    /** 显式范围全部来自受锁定的 Reader 行，原执行身份不随接管改变。 */
    public record Scope(String adaptationId, String attemptId, String providerAttemptId, String requestSha256,
                        String providerDeploymentId, String taskInstanceId, String executionId, long fencingToken,
                        long deleteEpoch, String certificateThumbprint, Instant expiresAt) {
        /** 固定 audience 及逐项长度前缀防止跨用途和字段拼接碰撞。 */
        public String fingerprint() {
            return AdaptationText.fingerprint("reader-adaptation-attempt-settle-v1", List.of("reader-adaptation-attempt-settle",
                    adaptationId, attemptId, providerAttemptId, requestSha256, providerDeploymentId, taskInstanceId,
                    executionId, Long.toString(fencingToken), Long.toString(deleteEpoch), certificateThumbprint, expiresAt.toString()));
        }
    }

    /** 数据库仅保存 SHA 和无权限的重建材料，不保存 token。 */
    public record Grant(String token, String sha256, String keyId, String nonce) {
        /** 令牌及签名不进入诊断。 */
        @Override
        public String toString() { return "Grant[token=REDACTED]"; }
    }

    /** 只在发送事务内首次签发，最长有效期为 300 秒。 */
    public Grant issue(Scope scope) {
        if (!scope.expiresAt().isAfter(clock.instant()) || scope.expiresAt().isAfter(clock.instant().plusSeconds(300))) throw fenced();
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        return recreate(scope, active, encode(nonce));
    }

    /** 相同发送回执只重建原令牌，不能改变期限或换用当前签名 key。 */
    public Grant recreate(Scope scope, String keyId, String nonce) {
        if (!keys.containsKey(keyId)) throw unavailable();
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{43}") || !encode(decode(nonce)).equals(nonce)) throw fenced();
        String prefix = "settle-v1." + keyId + "." + nonce;
        String token = prefix + "." + encode(mac(keyId, prefix + "." + scope.fingerprint()));
        return new Grant(token, AdaptationText.sha256(token), keyId, nonce);
    }

    /** 校验摘要、MAC、原生 TLS 叶证书绑定和原有期限；不能延长已过期的结算能力。 */
    public void verify(String token, String expectedSha256, Scope scope, String certificateThumbprint) {
        if (token == null || token.length() > 256 || !scope.expiresAt().isAfter(clock.instant())
                || !scope.certificateThumbprint().equals(certificateThumbprint)) throw fenced();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !"settle-v1".equals(parts[0]) || !keys.containsKey(parts[1])
                || !parts[3].matches("[A-Za-z0-9_-]{43}")) throw fenced();
        Grant expected = recreate(scope, parts[1], parts[2]);
        if (!equal(expected.token(), token) || !equal(expected.sha256(), expectedSha256)) throw fenced();
    }

    private byte[] mac(String keyId, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keys.get(keyId));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) { throw unavailable(); }
    }

    private static Keyring load(String file, ObjectMapper mapper) {
        if (file.isBlank()) return new Keyring("", Map.of());
        byte[] content = null;
        Map<String, byte[]> decoded = new HashMap<>();
        try {
            Path path = Path.of(file);
            if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096
                    || !Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                        .containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))) throw new IllegalArgumentException();
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { content = input.readNBytes(4097); }
            if (content.length > 4096) throw new IllegalArgumentException();
            var root = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
            if (root == null || !root.isObject() || root.size() != 2 || !root.path("activeKeyId").isTextual()
                    || !root.path("keys").isObject() || root.path("keys").size() > 3) throw new IllegalArgumentException();
            var fields = root.get("keys").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!entry.getValue().isTextual()) throw new IllegalArgumentException();
                byte[] key = Base64.getDecoder().decode(entry.getValue().textValue());
                decoded.put(entry.getKey(), key);
                if (!Base64.getEncoder().encodeToString(key).equals(entry.getValue().textValue())) throw new IllegalArgumentException();
            }
            return validate(root.get("activeKeyId").textValue(), decoded);
        } catch (Exception exception) {
            throw new IllegalStateException("Attempt settlement keyring is unavailable");
        } finally {
            if (content != null) Arrays.fill(content, (byte) 0);
            decoded.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        }
    }

    private static Keyring validate(String active, Map<String, byte[]> keys) {
        if (active == null || keys == null || keys.isEmpty() || keys.size() > 3 || !keys.containsKey(active)) throw new IllegalArgumentException("Invalid settlement keyring");
        Map<String, SecretKeySpec> validated = new HashMap<>();
        keys.forEach((id, bytes) -> {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || bytes == null || bytes.length != 32) throw new IllegalArgumentException("Invalid settlement keyring");
            validated.put(id, new SecretKeySpec(bytes, "HmacSHA256"));
        });
        return new Keyring(active, Map.copyOf(validated));
    }
    private record Keyring(String active, Map<String, SecretKeySpec> keys) { }
    private static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] decode(String value) {
        try { return Base64.getUrlDecoder().decode(value); } catch (IllegalArgumentException exception) { throw fenced(); }
    }
    private static boolean equal(String left, String right) {
        return right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
    private static ChapterAdaptationException fenced() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_EXECUTION_FENCED); }
    private static ChapterAdaptationException unavailable() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE); }
}
