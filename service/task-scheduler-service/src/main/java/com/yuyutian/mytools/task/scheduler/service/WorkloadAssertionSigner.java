package com.yuyutian.mytools.task.scheduler.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.WorkloadAuthorizationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 固定 Ed25519 JWS 签发器；密钥只从受限部署文件或测试内存注入，公钥集不含私钥。 */
@Component
public final class WorkloadAssertionSigner {
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "resourceType", "taskInstanceId", "packageName", "packageVersion",
            "taskParametersSha256", "executionId", "fencingToken", "leaseExpiresAt", "assertionGeneration", "iat", "exp", "jti", "cnf");
    private final String activeKeyId;
    private final Map<String, KeyPair> keys;
    private final ObjectMapper mapper;

    /** 加载最多三个轮换版本，权限或解析失败只产生不包含密钥的固定诊断。 */
    @Autowired
    public WorkloadAssertionSigner(WorkloadAuthorizationProperties properties, ObjectMapper mapper) {
        this.mapper = strict(mapper);
        String active = "";
        Map<String, KeyPair> loaded = new LinkedHashMap<>();
        if (properties.enabled()) {
            byte[] bytes = null;
            try {
                Path path = Path.of(properties.signingKeyringFile());
                if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException();
                }
                var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                if (!Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).containsAll(permissions)
                        || !permissions.contains(PosixFilePermission.OWNER_READ)) {
                    throw new IllegalArgumentException();
                }
                try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = stream.readNBytes(32769);
                }
                if (bytes.length > 32768) {
                    throw new IllegalArgumentException();
                }
                var root = this.mapper.readTree(bytes);
                if (root == null || !root.isObject() || root.size() != 2 || !root.path("activeKeyId").isTextual()
                        || !root.path("keys").isArray() || root.path("keys").isEmpty() || root.path("keys").size() > 3) {
                    throw new IllegalArgumentException();
                }
                active = root.get("activeKeyId").textValue();
                KeyFactory factory = KeyFactory.getInstance("Ed25519");
                for (var entry : root.get("keys")) {
                    if (!entry.isObject() || entry.size() < 2 || entry.size() > 3 || !entry.path("kid").isTextual()
                            || !entry.path("publicKey").isTextual()
                            || (entry.size() == 3 && !entry.path("privateKey").isTextual())) {
                        throw new IllegalArgumentException();
                    }
                    PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(entry.get("publicKey").textValue())));
                    PrivateKey privateKey = entry.has("privateKey") ? factory.generatePrivate(new PKCS8EncodedKeySpec(
                            Base64.getDecoder().decode(entry.get("privateKey").textValue()))) : null;
                    if (loaded.put(entry.get("kid").textValue(), new KeyPair(publicKey, privateKey)) != null) {
                        throw new IllegalArgumentException();
                    }
                }
                validate(active, loaded);
            } catch (Exception exception) {
                throw new IllegalStateException("Workload signing keyring is unavailable");
            } finally {
                if (bytes != null) {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
        this.activeKeyId = active;
        this.keys = Map.copyOf(loaded);
    }

    /** 测试使用随机内存密钥，禁止将其当作生产自动生成密钥的替代路径。 */
    public WorkloadAssertionSigner(String activeKeyId, Map<String, KeyPair> keys, ObjectMapper mapper) {
        validate(activeKeyId, keys);
        this.activeKeyId = activeKeyId;
        this.keys = Map.copyOf(keys);
        this.mapper = strict(mapper);
    }

    /** 只签固定字段集合，不接受额外 owner、Provider 或小说载荷。 */
    public String sign(Map<String, Object> claims) {
        if (!keys.containsKey(activeKeyId) || !CLAIMS.equals(claims.keySet())) {
            throw unavailable();
        }
        try {
            byte[] payload = mapper.writeValueAsBytes(claims);
            if (payload.length > 4096) {
                throw unavailable();
            }
            String input = encode(mapper.writeValueAsBytes(Map.of("alg", "Ed25519", "typ", "mytools-workload+jwt", "kid", activeKeyId)))
                    + "." + encode(payload);
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keys.get(activeKeyId).getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + encode(signature.sign());
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    /** 发布当前及过渡公钥，永不包含 d、PKCS8 或私钥字段。 */
    public Map<String, Object> jwks() {
        if (!keys.containsKey(activeKeyId)) {
            throw unavailable();
        }
        var publicKeys = keys.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> Map.of(
                "kty", "OKP", "crv", "Ed25519", "alg", "Ed25519", "use", "sig", "kid", entry.getKey(),
                "x", encode(rawPublicKey(entry.getValue().getPublic())))).toList();
        return Map.of("keys", publicKeys);
    }

    private static void validate(String active, Map<String, KeyPair> keys) {
        try {
            if (keys.isEmpty() || keys.size() > 3 || !keys.containsKey(active) || keys.get(active).getPrivate() == null) {
                throw new IllegalArgumentException();
            }
            for (var entry : keys.entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException();
                }
                rawPublicKey(entry.getValue().getPublic());
                if (entry.getValue().getPrivate() != null) {
                    byte[] challenge = "mytools-workload-key-match-v1".getBytes(StandardCharsets.US_ASCII);
                    Signature signature = Signature.getInstance("Ed25519");
                    signature.initSign(entry.getValue().getPrivate());
                    signature.update(challenge);
                    byte[] signed = signature.sign();
                    signature.initVerify(entry.getValue().getPublic());
                    signature.update(challenge);
                    if (!signature.verify(signed)) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid workload signing keys");
        }
    }

    private static byte[] rawPublicKey(PublicKey key) {
        if (!(key instanceof EdECPublicKey edKey) || !"Ed25519".equals(edKey.getParams().getName())) {
            throw new IllegalArgumentException("Invalid workload signing key");
        }
        byte[] encoded = key.getEncoded();
        byte[] prefix = java.util.HexFormat.of().parseHex("302a300506032b6570032100");
        if (encoded.length != 44 || !Arrays.equals(prefix, Arrays.copyOf(encoded, 12))) {
            throw new IllegalArgumentException("Invalid workload signing key encoding");
        }
        return Arrays.copyOfRange(encoded, 12, 44);
    }

    private static ObjectMapper strict(ObjectMapper mapper) {
        return mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static SchedulerException unavailable() {
        return new SchedulerException(ErrorCode.WORKLOAD_AUTHORIZATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Workload signing is unavailable");
    }
}
