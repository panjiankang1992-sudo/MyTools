package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * 管理接口的 CORS 策略与公开接口分离（设计文档 §7 第 3 点）。
 * 关键要求：管理额外允许 PUT 与并发／幂等头，但**不放宽公开来源白名单**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminCorsPolicyTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("管理预检允许 PUT 与 If-Match／Idempotency-Key／管理 CSRF 头")
    void adminPreflightAllowsWriteHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:23120");
        headers.setAccessControlRequestMethod(HttpMethod.PUT);
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks/x/draft", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);
        String allowedMethods = response.getHeaders().getFirst("Access-Control-Allow-Methods");
        String allowedHeaders = response.getHeaders().getFirst("Access-Control-Allow-Headers");
        assertThat(allowedMethods).contains("PUT");
        assertThat(allowedHeaders).contains("If-Match");
        assertThat(allowedHeaders).contains("Idempotency-Key");
        assertThat(allowedHeaders).contains("X-Toolbox-Admin-Csrf");
    }

    @Test
    @DisplayName("公开预检不因管理需求而放宽：仍不含 PUT 与管理专用头")
    void publicPreflightStaysRestricted() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:23120");
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        ResponseEntity<String> response = rest.exchange("/public/v1/events", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Methods")).doesNotContain("PUT");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Headers")).doesNotContain("Idempotency-Key");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Headers")).doesNotContain("X-Toolbox-Admin-Csrf");
    }

    @Test
    @DisplayName("未配置来源对变更请求仍然失败关闭")
    void unknownOriginStillRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://evil.example");
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
