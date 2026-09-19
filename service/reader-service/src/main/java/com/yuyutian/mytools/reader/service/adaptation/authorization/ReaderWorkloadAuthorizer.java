package com.yuyutian.mytools.reader.service.adaptation.authorization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderWorkloadAuthorizationProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadIntrospection;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadResource;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 内部正文入口唯一的执行授权器：原生 mTLS、固定算法验签、资源绑定及每请求在线内省。 */
@Service
public final class ReaderWorkloadAuthorizer {
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "resourceType", "taskInstanceId", "packageName",
            "packageVersion", "taskParametersSha256", "executionId", "fencingToken", "leaseExpiresAt",
            "assertionGeneration", "iat", "exp", "jti", "cnf");
    private final ReaderWorkloadAuthorizationProperties properties;
    private final ReaderWorkloadAuthority authority;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 注入专属证书 allowlist、可信公钥来源和在线状态客户端。 */
    @Autowired
    public ReaderWorkloadAuthorizer(ReaderWorkloadAuthorizationProperties properties, ReaderWorkloadAuthority authority, ObjectMapper mapper) {
        this(properties, authority, mapper, Clock.systemUTC());
    }

    /** 注入可控时钟，用于有效期和续期边界测试。 */
    public ReaderWorkloadAuthorizer(ReaderWorkloadAuthorizationProperties properties, ReaderWorkloadAuthority authority, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.authority = authority;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = clock;
    }

    /** 在任何业务查询、正文返回或写入前完成本次身份验证；不接受 owner、转发证书或静态令牌降级。 */
    public WorkloadAuthorization authorize(HttpServletRequest request, WorkloadResource resource, UUID resourceId, UUID executionId) {
        if (!properties.enabled()) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE);
        try {
            String thumbprint = certificate(request);
            List<String> headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.size() != 1 || !headers.getFirst().startsWith("Bearer ") || headers.getFirst().length() > 8200) throw fenced();
            String token = headers.getFirst().substring(7);
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw fenced();
            JsonNode header = mapper.readTree(decode(parts[0], 512));
            JsonNode claims = mapper.readTree(decode(parts[1], 4096));
            byte[] signature = decode(parts[2], 64);
            if (signature.length != 64 || header == null || !header.isObject() || header.size() != 3
                    || !"Ed25519".equals(text(header, "alg")) || !"mytools-workload+jwt".equals(text(header, "typ"))
                    || !text(header, "kid").matches("[A-Za-z0-9_.-]{1,64}")
                    || claims == null || !claims.isObject() || claims.size() != CLAIMS.size()) throw fenced();
            for (String field : CLAIMS) if (!claims.has(field)) throw fenced();
            long issued = number(claims, "iat");
            long expiration = number(claims, "exp");
            long leaseExpiration = number(claims, "leaseExpiresAt");
            long fence = number(claims, "fencingToken");
            long generation = number(claims, "assertionGeneration");
            UUID task = uuid(claims, "taskInstanceId");
            UUID execution = uuid(claims, "executionId");
            UUID jti = uuid(claims, "jti");
            JsonNode cnf = claims.get("cnf");
            Instant now = clock.instant();
            if (resource == null || resourceId == null || executionId == null || !executionId.equals(execution)
                    || !properties.issuer().equals(text(claims, "iss")) || !resource.audience().equals(text(claims, "aud"))
                    || !resource.name().equals(text(claims, "resourceType")) || !resource.packageName().equals(text(claims, "packageName"))
                    || !"1.0.0".equals(text(claims, "packageVersion")) || !resource.parametersSha256(resourceId).equals(text(claims, "taskParametersSha256"))
                    || fence <= 0 || generation <= 0 || issued < 0 || issued > now.getEpochSecond() + 2
                    || expiration <= issued || expiration - issued > 120 || expiration <= now.getEpochSecond()
                    || leaseExpiration < expiration || cnf == null || !cnf.isObject() || cnf.size() != 1
                    || !thumbprint.equals(text(cnf, "x5t#S256"))) throw fenced();
            // 在验证完整资源边界后才查公钥，签名验证成功前不查询业务数据或在线 jti 状态。
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(authority.publicKey(text(header, "kid")));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) throw fenced();
            Instant activeUntil = authority.activeUntil(new WorkloadIntrospection(jti, task, execution, fence, generation,
                    thumbprint, resource.audience(), resource.name(), resource.parametersSha256(resourceId)));
            if (activeUntil == null) throw fenced();
            Instant expiresAt = Instant.ofEpochSecond(expiration);
            Instant until = activeUntil.isBefore(expiresAt) ? activeUntil : expiresAt;
            if (!until.isAfter(clock.instant())) throw fenced();
            return new WorkloadAuthorization(resource, resourceId, task, execution, fence, until, thumbprint);
        } catch (ChapterAdaptationException exception) {
            throw exception;
        } catch (Exception exception) {
            // 不能保留含 token、证书或正文片段的解析/验签异常链。
            throw fenced();
        }
    }

    private String certificate(HttpServletRequest request) throws Exception {
        if (!request.isSecure() || !(request.getAttribute("jakarta.servlet.request.X509Certificate") instanceof X509Certificate[] chain)
                || chain.length == 0 || chain.length > 8) throw fenced();
        X509Certificate leaf = chain[0];
        leaf.checkValidity(Date.from(clock.instant()));
        if (leaf.getBasicConstraints() >= 0 || leaf.getExtendedKeyUsage() == null
                || !leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")) throw fenced();
        var alternatives = leaf.getSubjectAlternativeNames();
        List<String> identities = alternatives == null ? List.of() : alternatives.stream()
                .filter(value -> value.size() == 2 && Integer.valueOf(6).equals(value.getFirst()))
                .map(value -> String.valueOf(value.get(1))).toList();
        if (identities.size() != 1 || !properties.executorIdentities().contains(identities.getFirst())) throw fenced();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
    }

    /** 窄结算例外仅验证原生 TLS，返回值不能单独授权任何读取或写入，必须再验证结算能力。 */
    public String settlementCertificate(HttpServletRequest request) {
        if (!properties.enabled()) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE);
        try { return certificate(request); }
        catch (Exception exception) { throw fenced(); }
    }

    private static byte[] decode(String value, int maximum) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw fenced();
        byte[] result = Base64.getUrlDecoder().decode(value);
        if (result.length > maximum || !Base64.getUrlEncoder().withoutPadding().encodeToString(result).equals(value)) throw fenced();
        return result;
    }
    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw fenced();
        return node.get(field).textValue();
    }
    private static long number(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber() || !node.get(field).canConvertToLong()) throw fenced();
        return node.get(field).longValue();
    }
    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value)) throw fenced();
        return result;
    }
    private static ChapterAdaptationException fenced() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_EXECUTION_FENCED); }
}
