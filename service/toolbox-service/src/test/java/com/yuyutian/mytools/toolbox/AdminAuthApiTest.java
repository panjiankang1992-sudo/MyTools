package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * 管理接口的匿名边界（验收矩阵 A02 的服务端部分）。
 * 管理功能在测试 profile 下默认关闭，因此这里同时验证"关闭时不得暴露"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminAuthApiTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("匿名查询管理会话不建立会话，也不返回管理员身份")
    void anonymousSessionIsNotAuthenticated() {
        ResponseEntity<String> response = rest.getForEntity("/admin/v1/session", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = TestJson.parse(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        assertThat(body.path("data").path("authenticated").asBoolean()).isFalse();
        assertThat(body.path("data").has("username")).isFalse();
    }

    @Test
    @DisplayName("匿名直接访问管理读接口返回 401 且不泄漏草稿")
    void anonymousReadIsRejected() {
        ResponseEntity<String> response = rest.getForEntity("/admin/v1/textbooks", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        JsonNode body = TestJson.parse(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_ADMIN_001");
        assertThat(body.has("data")).isFalse();
    }

    @Test
    @DisplayName("匿名写入被拒绝；缺少 CSRF 不会执行变更")
    void anonymousWriteIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>("{\"subject\":\"chinese\"}", headers), String.class);
        // 未登录时先判登录（401）；即使补充了伪造 Cookie 也不会因为缺 CSRF 而放行。
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(TestJson.parse(response.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_001");
    }

    @Test
    @DisplayName("伪造的管理 Cookie 不能获得管理权限")
    void forgedCookieIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "__Host-toolbox_admin=forged-value");
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
