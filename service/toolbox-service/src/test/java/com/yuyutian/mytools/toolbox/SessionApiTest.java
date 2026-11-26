package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 匿名会话接口测试：cookie 属性、会话复用、过期新建与 CSRF/来源校验。
 */
class SessionApiTest extends ToolboxApiTestSupport {

    /** 测试环境的会话 cookie 名称。 */
    private static final String COOKIE_NAME = "toolbox_guest_local";

    @Test
    @DisplayName("session is issued as cookie only and leaks no token or owner")
    void issuesCookieSessionWithoutLeakingToken() {
        ResponseEntity<String> response = rest.postForEntity("/public/v1/session", null, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie)
                .contains(COOKIE_NAME + "=")
                .contains("Path=/")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
        // 测试 profile 不启用 Secure，生产默认启用。
        assertThat(setCookie).doesNotContain("Secure");
        String token = setCookie.split(";", 2)[0].split("=", 2)[1];
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);

        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("OK");
        assertThat(body.path("data").path("csrfToken").asText()).isNotBlank();
        assertThat(body.path("data").path("expiresAt").asText()).endsWith("Z");
        assertThat(response.getBody()).doesNotContain(token);
        assertThat(response.getBody()).doesNotContain("ownerId");
        assertThat(response.getBody()).doesNotContain("owner_id");
        assertThat(response.getHeaders().getFirst("X-Request-Id"))
                .isEqualTo(body.path("requestId").asText());

        // 服务端只保存 SHA-256，不保存原始 token。
        byte[] expectedHash = sha256(token);
        Long stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_guest WHERE token_hash = ?", Long.class, expectedHash);
        assertThat(stored).isEqualTo(1L);
    }

    @Test
    @DisplayName("re-posting with a valid cookie reuses the same session")
    void rePostReusesExistingSession() {
        TestSession first = createSession();
        ResponseEntity<String> second = rest.exchange(
                "/public/v1/session", HttpMethod.POST, new HttpEntity<>(null, cookieHeaders(first.cookie())), String.class);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        TestSession reused = sessionOf(second);
        assertThat(reused.csrf()).isEqualTo(first.csrf());
        assertThat(reused.cookie()).isEqualTo(first.cookie());
        Long guests = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_guest", Long.class);
        assertThat(guests).isEqualTo(1L);
    }

    @Test
    @DisplayName("expired cookie issues a new session")
    void expiredSessionIssuesNewSession() throws Exception {
        String expiredToken = "expired-token-abcdefghijklmnopqrstuvwxyz0123456789";
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        jdbc.update("INSERT INTO toolbox_guest (guest_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                sha256(expiredToken),
                Timestamp.valueOf(LocalDateTime.ofInstant(past, ZoneOffset.UTC)),
                Timestamp.valueOf(LocalDateTime.ofInstant(past.plus(1, ChronoUnit.HOURS), ZoneOffset.UTC)));

        ResponseEntity<String> response = rest.exchange(
                "/public/v1/session", HttpMethod.POST,
                new HttpEntity<>(null, cookieHeaders(COOKIE_NAME + "=" + expiredToken)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        TestSession issued = sessionOf(response);
        assertThat(issued.cookie()).isNotEqualTo(COOKIE_NAME + "=" + expiredToken);
        Long guests = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_guest", Long.class);
        assertThat(guests).isEqualTo(2L);
    }

    @Test
    @DisplayName("event ingest without cookie returns TOOLBOX_002")
    void eventsWithoutCookieAreUnauthorized() {
        ResponseEntity<String> response = postJson("/public/v1/events", eventBatch(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_002");
        assertThat(body.path("messageKey").asText()).isEqualTo("session.required");
        assertThat(body.path("details").isObject()).isTrue();
    }

    @Test
    @DisplayName("event ingest with wrong CSRF returns TOOLBOX_010")
    void eventsWithWrongCsrfAreForbidden() {
        TestSession session = createSession();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, session.cookie());
        headers.add("X-Toolbox-Csrf", "not-the-session-token");

        ResponseEntity<String> response = rest.exchange(
                "/public/v1/events", HttpMethod.POST, new HttpEntity<>(eventBatch(), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_010");
        assertThat(body.path("messageKey").asText()).isEqualTo("request.forbidden");
        Long events = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_event", Long.class);
        assertThat(events).isZero();
    }

    @Test
    @DisplayName("foreign origin mutation returns TOOLBOX_010")
    void foreignOriginIsRejected() {
        TestSession session = createSession();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, session.cookie());
        headers.add("X-Toolbox-Csrf", session.csrf());
        headers.add(HttpHeaders.ORIGIN, "https://evil.example");

        ResponseEntity<String> response = rest.exchange(
                "/public/v1/events", HttpMethod.POST, new HttpEntity<>(eventBatch(), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(json(response).path("code").asText()).isEqualTo("TOOLBOX_010");
    }

    @Test
    @DisplayName("configured origin preflight allows credentials without wildcard")
    void allowedOriginGetsExplicitCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "http://localhost:23120");
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");

        ResponseEntity<String> response = rest.exchange(
                "/public/v1/tools", HttpMethod.OPTIONS, new HttpEntity<>(null, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:23120");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    /**
     * 构造最小合法事件批量请求体。
     *
     * @return 请求体
     */
    private Map<String, Object> eventBatch() {
        return Map.of("events", List.of(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "entry_click",
                "category", "copybook",
                "toolId", "worksheet.chinese-dictation",
                "occurredAt", Instant.now().minusSeconds(5).toString(),
                "operationId", "op-1",
                "schemaVersion", 1)));
    }

    /**
     * 构造只携带 cookie 的请求头。
     *
     * @param cookie cookie 键值对
     * @return 请求头
     */
    private HttpHeaders cookieHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        return headers;
    }

    /**
     * 计算 SHA-256。
     *
     * @param text 文本
     * @return 32 字节摘要
     */
    private byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
