package com.yuyutian.mytools.reader.service.adaptation.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderWorkloadAuthorizationProperties;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadResource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 密码学夹具使用真实 Ed25519 签名，原生证书属性仍是容器边界夹具，不是 TLS 握手证据。 */
public final class WorkloadAuthorizationFixtures {
    public static final String IDENTITY = "spiffe://fixture.test/executor";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private WorkloadAuthorizationFixtures() { }

    /** 返回测试配置，不读取所列占位凭据文件。 */
    public static ReaderWorkloadAuthorizationProperties properties() {
        return new ReaderWorkloadAuthorizationProperties(true, "https://scheduler.fixture.invalid", "mytools-task-scheduler", 3, 300,
                "/fixture/key.p12", "/fixture/password", "/fixture/trust.p12", "/fixture/trust-password",
                "spiffe://fixture.test/reader", Set.of(IDENTITY));
    }

    /** 创建具有唯一 URI SAN 和 clientAuth 的原生容器证书属性夹具。 */
    public static X509Certificate certificate() throws Exception {
        X509Certificate result = mock(X509Certificate.class);
        when(result.getBasicConstraints()).thenReturn(-1);
        when(result.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        when(result.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, IDENTITY)));
        when(result.getEncoded()).thenReturn("fixture-executor-certificate".getBytes(StandardCharsets.UTF_8));
        return result;
    }

    /** 返回与证书编码一致的 cnf 指纹。 */
    public static String thumbprint() throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest("fixture-executor-certificate".getBytes(StandardCharsets.UTF_8)));
    }

    /** 构造严格十五字段的原始 claims，供不同资源和错误场景复用。 */
    public static Map<String, Object> claims(WorkloadResource scope, UUID resource, UUID task, UUID execution, long fence, Instant now) throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "mytools-task-scheduler");
        claims.put("aud", scope.audience());
        claims.put("resourceType", scope.name());
        claims.put("packageName", scope.packageName());
        claims.put("packageVersion", "1.0.0");
        claims.put("taskParametersSha256", scope.parametersSha256(resource));
        claims.put("taskInstanceId", task.toString());
        claims.put("executionId", execution.toString());
        claims.put("fencingToken", fence);
        claims.put("assertionGeneration", 1);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(60).getEpochSecond());
        claims.put("leaseExpiresAt", now.plusSeconds(90).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("cnf", Map.of("x5t#S256", thumbprint()));
        return claims;
    }

    /** 对给定 claims 使用测试私钥真实签名。 */
    public static String token(KeyPair key, Map<String, Object> claims) throws Exception {
        return token(key, MAPPER.writeValueAsBytes(Map.of("alg", "Ed25519", "typ", "mytools-workload+jwt", "kid", "fixture")),
                MAPPER.writeValueAsBytes(claims));
    }

    /** 用原始 JSON 测试重复字段、尾随内容及未知 Header 的严格拒绝。 */
    public static String token(KeyPair key, byte[] header, byte[] payload) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String content = encoder.encodeToString(header) + "." + encoder.encodeToString(payload);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(content.getBytes(StandardCharsets.US_ASCII));
        return content + "." + encoder.encodeToString(signer.sign());
    }

    /** 在原生属性上附加证书，不通过 Header 模拟证书转发。 */
    public static MockHttpServletRequest request(String token) throws Exception {
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[]{certificate()});
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
