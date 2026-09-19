package com.yuyutian.mytools.reader.service.adaptation.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadResource;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReaderWorkloadAuthorizerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private final UUID resource = UUID.randomUUID();
    private final UUID execution = UUID.randomUUID();
    private final UUID task = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReaderWorkloadAuthority authority = mock(ReaderWorkloadAuthority.class);
    private KeyPair keys;
    private ReaderWorkloadAuthorizer authorizer;

    @BeforeEach
    void setup() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        when(authority.publicKey("fixture")).thenReturn(keys.getPublic());
        when(authority.activeUntil(any())).thenReturn(NOW.plusSeconds(60));
        authorizer = new ReaderWorkloadAuthorizer(WorkloadAuthorizationFixtures.properties(), authority, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldVerifySignatureAndIntrospectEveryRequest() throws Exception {
        var request = request(claims());
        when(authority.activeUntil(any())).thenReturn(NOW.plusSeconds(5));
        var result = authorizer.authorize(request, WorkloadResource.CHAPTER_ADAPTATION, resource, execution);
        assertThat(result.taskInstanceId()).isEqualTo(task);
        assertThat(result.authorizedUntil()).isEqualTo(NOW.plusSeconds(5));
        authorizer.authorize(request, WorkloadResource.CHAPTER_ADAPTATION, resource, execution);
        verify(authority, times(2)).activeUntil(argThat(value -> value.executionId().equals(execution)
                && value.taskInstanceId().equals(task) && value.fencingToken() == 1));
    }

    @Test
    void shouldSeparateAllThreeResources() throws Exception {
        for (var scope : WorkloadResource.values()) {
            var values = WorkloadAuthorizationFixtures.claims(scope, resource, task, execution, 1, NOW);
            var result = authorizer.authorize(request(values), scope, resource, execution);
            assertThat(result.resource()).isEqualTo(scope);
            var wrong = scope == WorkloadResource.CHAPTER_ADAPTATION ? WorkloadResource.EBOOK_BINDING : WorkloadResource.CHAPTER_ADAPTATION;
            reject(request(values), wrong, resource, execution, ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
    }

    @Test
    void shouldRejectMissingTlsAndForwardedCertificates() throws Exception {
        var request = request(claims());
        request.removeAttribute("jakarta.servlet.request.X509Certificate");
        request.addHeader("X-Client-Cert", "fixture-forwarded-certificate");
        request.addHeader("X-Forwarded-Proto", "https");
        reject(request);
        request = request(claims());
        request.setSecure(false);
        reject(request);
        request = request(claims());
        request.addHeader("Authorization", "Bearer another-token");
        reject(request);
        verifyNoInteractions(authority);
    }

    @Test
    void shouldRejectUntrustedSanEkuCaAndExpiredCertificates() throws Exception {
        var request = request(claims());
        X509Certificate certificate = ((X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate"))[0];
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, "spiffe://fixture.test/other")));
        reject(request);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, WorkloadAuthorizationFixtures.IDENTITY), List.of(6, "spiffe://fixture.test/other")));
        reject(request);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, WorkloadAuthorizationFixtures.IDENTITY)));
        when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.1"));
        reject(request);
        when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        when(certificate.getBasicConstraints()).thenReturn(1);
        reject(request);
        when(certificate.getBasicConstraints()).thenReturn(-1);
        doThrow(new CertificateExpiredException("fixture")).when(certificate).checkValidity(any(java.util.Date.class));
        reject(request);
        verifyNoInteractions(authority);
    }

    @Test
    void shouldRejectCrossResourceAndClaimTamperingBeforeOnlineLookup() throws Exception {
        List<Map<String, Object>> changes = List.of(Map.of("iss", "other"), Map.of("aud", "other"), Map.of("resourceType", "OTHER"),
                Map.of("packageName", "other"), Map.of("packageVersion", "2.0.0"), Map.of("taskParametersSha256", "b".repeat(64)),
                Map.of("executionId", UUID.randomUUID().toString()), Map.of("fencingToken", 0), Map.of("assertionGeneration", 0),
                Map.of("cnf", Map.of("x5t#S256", "b".repeat(43))), Map.of("ownerId", 42), Map.of("jti", "0-0-0-0-0"));
        for (var change : changes) {
            var values = claims();
            values.putAll(change);
            reject(request(values));
        }
        reject(request(claims()), WorkloadResource.CHAPTER_ADAPTATION, UUID.randomUUID(), execution, ErrorCode.ADAPTATION_EXECUTION_FENCED);
        verifyNoInteractions(authority);
    }

    @Test
    void shouldRejectTemporalAndNonIntegralClaims() throws Exception {
        for (var change : List.of(Map.<String, Object>of("iat", NOW.plusSeconds(3).getEpochSecond()),
                Map.<String, Object>of("exp", NOW.getEpochSecond()), Map.<String, Object>of("exp", NOW.plusSeconds(121).getEpochSecond()),
                Map.<String, Object>of("leaseExpiresAt", NOW.plusSeconds(30).getEpochSecond()), Map.<String, Object>of("fencingToken", 1.0))) {
            var values = claims();
            values.putAll(change);
            reject(request(values));
        }
    }

    @Test
    void shouldRejectInvalidSignatureAndStrictJsonExtensions() throws Exception {
        var wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        reject(WorkloadAuthorizationFixtures.request(WorkloadAuthorizationFixtures.token(wrongKey, claims())));
        verify(authority, never()).activeUntil(any());
        byte[] payload = mapper.writeValueAsBytes(claims());
        for (var header : List.of(Map.of("alg", "none", "typ", "mytools-workload+jwt", "kid", "fixture"),
                Map.of("alg", "EdDSA", "typ", "mytools-workload+jwt", "kid", "fixture"),
                Map.of("alg", "Ed25519", "typ", "mytools-workload+jwt", "kid", "fixture", "jku", "https://untrusted.invalid"))) {
            reject(WorkloadAuthorizationFixtures.request(WorkloadAuthorizationFixtures.token(keys, mapper.writeValueAsBytes(header), payload)));
        }
        byte[] header = mapper.writeValueAsBytes(Map.of("alg", "Ed25519", "typ", "mytools-workload+jwt", "kid", "fixture"));
        String json = mapper.writeValueAsString(claims());
        reject(WorkloadAuthorizationFixtures.request(WorkloadAuthorizationFixtures.token(keys, header,
                (json.substring(0, json.length() - 1) + ",\"fencingToken\":1}").getBytes(StandardCharsets.UTF_8))));
        reject(WorkloadAuthorizationFixtures.request(WorkloadAuthorizationFixtures.token(keys, header, (json + "{}").getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void shouldFailClosedOnRevocationAndUncertainAuthority() throws Exception {
        var request = request(claims());
        when(authority.activeUntil(any())).thenReturn(null);
        reject(request);
        when(authority.activeUntil(any())).thenThrow(new ChapterAdaptationException(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE));
        reject(request, WorkloadResource.CHAPTER_ADAPTATION, resource, execution, ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE);
    }

    private Map<String, Object> claims() throws Exception { return WorkloadAuthorizationFixtures.claims(WorkloadResource.CHAPTER_ADAPTATION, resource, task, execution, 1, NOW); }
    private MockHttpServletRequest request(Map<String, Object> claims) throws Exception { return WorkloadAuthorizationFixtures.request(WorkloadAuthorizationFixtures.token(keys, claims)); }
    private void reject(MockHttpServletRequest request) { reject(request, WorkloadResource.CHAPTER_ADAPTATION, resource, execution, ErrorCode.ADAPTATION_EXECUTION_FENCED); }
    private void reject(MockHttpServletRequest request, WorkloadResource scope, UUID resourceId, UUID executionId, ErrorCode code) {
        assertThatThrownBy(() -> authorizer.authorize(request, scope, resourceId, executionId)).isInstanceOfSatisfying(ChapterAdaptationException.class,
                exception -> { assertThat(exception.errorCode()).isEqualTo(code); assertThat(exception.getCause()).isNull(); });
    }
}
