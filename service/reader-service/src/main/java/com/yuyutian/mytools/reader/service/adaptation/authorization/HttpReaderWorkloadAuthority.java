package com.yuyutian.mytools.reader.service.adaptation.authorization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderWorkloadAuthorizationProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadIntrospection;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.utils.adaptation.BoundedByteArraySubscriber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 固定 mTLS Scheduler 地址的有界授权客户端；只缓存公钥，所有内省均实际发起请求。 */
@Component
public final class HttpReaderWorkloadAuthority implements ReaderWorkloadAuthority {
    private static final String ROOT = "/api/internal/v1/task-execution-authorizations";
    private final ReaderWorkloadAuthorizationProperties properties;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private Map<String, PublicKey> keys = Map.of();
    private Instant keysExpireAt = Instant.EPOCH;
    private Instant nextRefreshAt = Instant.EPOCH;
    private boolean lastRefreshFailed;

    /** 生产只使用已加载 Reader 证书和受限信任库的客户端。 */
    @Autowired
    public HttpReaderWorkloadAuthority(ReaderWorkloadAuthorizationProperties properties, ReaderWorkloadTls tls, ObjectMapper mapper) {
        this(properties, tls.client(), mapper, Clock.systemUTC());
    }

    /** 为本机传输夹具注入 HTTP 客户端及测试时钟，不改变生产 TLS 配置。 */
    public HttpReaderWorkloadAuthority(ReaderWorkloadAuthorizationProperties properties, HttpClient client, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = clock;
    }

    /** 合并并发的公钥刷新；未知 kid 最多每五秒触发一次刷新，过期缓存不可降级使用。 */
    @Override
    public synchronized PublicKey publicKey(String keyId) {
        requireAvailable();
        if (keyId == null || !keyId.matches("[A-Za-z0-9_.-]{1,64}")) throw fenced();
        Instant now = clock.instant();
        if (keysExpireAt.isAfter(now) && keys.containsKey(keyId)) return keys.get(keyId);
        if (nextRefreshAt.isAfter(now)) {
            if (lastRefreshFailed || !keysExpireAt.isAfter(now)) throw unavailable();
            throw fenced();
        }
        nextRefreshAt = now.plusSeconds(5);
        try {
            Map<String, PublicKey> refreshed = parseKeys(invoke("GET", "/jwks", null, 8192));
            // 整份 JWKS 验证通过才替换缓存，部分坏 key 不能覆盖当前可用集合。
            keys = refreshed;
            keysExpireAt = clock.instant().plusSeconds(properties.jwksCacheSeconds());
            lastRefreshFailed = false;
        } catch (Exception exception) {
            lastRefreshFailed = true;
            throw unavailable();
        }
        PublicKey result = keys.get(keyId);
        if (result == null) throw fenced();
        return result;
    }

    /** 不复用上一个 active 结论，Scheduler 不可达或响应异常时禁止继续访问业务数据。 */
    @Override
    public Instant activeUntil(WorkloadIntrospection request) {
        requireAvailable();
        try {
            JsonNode response = invoke("POST", "/introspect", mapper.writeValueAsBytes(request), 1024);
            if (!response.isObject() || response.size() != 2 || !response.path("active").isBoolean()
                    || !response.has("authorizedUntil")) throw unavailable();
            if (!response.get("active").booleanValue()) {
                if (!response.get("authorizedUntil").isNull()) throw unavailable();
                return null;
            }
            if (!response.get("authorizedUntil").isTextual()) throw unavailable();
            Instant until = Instant.parse(response.get("authorizedUntil").textValue());
            if (!until.isAfter(clock.instant())) return null;
            if (until.isAfter(clock.instant().plusSeconds(120))) throw unavailable();
            return until;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private Map<String, PublicKey> parseKeys(JsonNode root) throws Exception {
        if (root == null || !root.isObject() || root.size() != 1 || !root.path("keys").isArray()
                || root.get("keys").isEmpty() || root.get("keys").size() > 3) throw unavailable();
        Map<String, PublicKey> result = new HashMap<>();
        for (JsonNode key : root.get("keys")) {
            // 固定算法、公钥格式及用途，不允许 d、jku、x5u 或额外扩展字段。
            if (!key.isObject() || key.size() != 6 || !"OKP".equals(text(key, "kty"))
                    || !"Ed25519".equals(text(key, "crv")) || !"Ed25519".equals(text(key, "alg"))
                    || !"sig".equals(text(key, "use")) || !text(key, "kid").matches("[A-Za-z0-9_.-]{1,64}")) throw unavailable();
            String encoded = text(key, "x");
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (raw.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(encoded)) throw unavailable();
            byte[] spki = new byte[44];
            System.arraycopy(HexFormat.of().parseHex("302a300506032b6570032100"), 0, spki, 0, 12);
            System.arraycopy(raw, 0, spki, 12, 32);
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            if (result.putIfAbsent(text(key, "kid"), publicKey) != null) throw unavailable();
        }
        return Map.copyOf(result);
    }

    private JsonNode invoke(String method, String suffix, byte[] body, int maximum) {
        requireAvailable();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            URI target = URI.create(properties.schedulerUrl()).resolve(ROOT + suffix);
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                    .header("Accept", "application/json").header("Accept-Encoding", "identity").header("Cache-Control", "no-store")
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending = client.sendAsync(request, ignored -> new BoundedByteArraySubscriber(maximum));
            var response = pending.get(properties.requestTimeoutSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() != 200
                    || !response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")
                    || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw unavailable();
            JsonNode result = mapper.readTree(response.body());
            if (result == null) throw unavailable();
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception exception) {
            // 网络、JSON 或证书异常链可能携带地址和凭据，只返回稳定错误码。
            throw unavailable();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private void requireAvailable() {
        if (!properties.enabled() || TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw unavailable();
        return node.get(field).textValue();
    }
    private static ChapterAdaptationException unavailable() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE); }
    private static ChapterAdaptationException fenced() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_EXECUTION_FENCED); }
}
