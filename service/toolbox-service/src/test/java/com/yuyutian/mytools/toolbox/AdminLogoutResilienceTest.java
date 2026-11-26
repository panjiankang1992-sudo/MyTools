package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityException;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * A07：**上游不可用时也必须能退出**。
 *
 * 退出的语义是"本地会话销毁"，它**不能依赖上游成功**——否则上游一挂，
 * 用户就被困在"已登录但什么也做不了"的状态里，连登出都做不到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminLogoutResilienceTest {

    /** 上游注销是否失败。 */
    static volatile boolean upstreamLogoutFails = false;

    /** 注销总是失败的上游。 */
    @TestConfiguration
    static class FakeFailingLogout {
        @Bean
        @Primary
        AdminIdentityClient fakeFailingLogout() {
            return new AdminIdentityClient() {
                @Override
                public UpstreamLogin login(String account, String password) {
                    return new UpstreamLogin("access-x", "refresh-x", 3600L);
                }

                @Override
                public AdminIdentity validate(String accessToken) {
                    return new AdminIdentity(true, "1001", "someone", List.of(AdminIdentity.ROLE_ADMIN));
                }

                @Override
                public void logout(String accessToken) {
                    if (upstreamLogoutFails) {
                        throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
                    }
                }
            };
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("上游注销失败时，本地会话仍被销毁且后续请求被拒（A07）")
    void logoutSucceedsEvenWhenUpstreamFails() {
        HttpHeaders headers = loginAndHeaders();
        // 先确认登录有效。
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(200);

        upstreamLogoutFails = true;
        try {
            // 上游注销失败，但退出必须成功——否则用户无法登出。
            ResponseEntity<String> logout = rest.exchange("/admin/v1/auth/logout", HttpMethod.POST,
                    new HttpEntity<>(headers), String.class);
            assertThat(logout.getStatusCode().value())
                    .as("上游不可用时退出仍须成功").isEqualTo(200);
        } finally {
            upstreamLogoutFails = false;
        }

        // 且本地会话确实被销毁：用同一个 Cookie 再请求必须被拒。
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value())
                .as("退出后必须真正失效").isEqualTo(401);
    }

    /** 走完整登录流程，返回带会话与 CSRF 的请求头。 */
    private HttpHeaders loginAndHeaders() {
        ResponseEntity<String> csrf = rest.getForEntity("/admin/v1/auth/csrf", String.class);
        String preAuth = cookieOf(csrf, "toolbox_admin_login_local");
        String nonce = TestJson.parse(csrf.getBody()).path("data").path("csrfToken").asText();

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "toolbox_admin_login_local=" + preAuth);
        loginHeaders.add("X-Toolbox-Admin-Csrf", nonce);
        ResponseEntity<String> login = rest.exchange("/admin/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"account\":\"someone\",\"password\":\"secret\"}", loginHeaders), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "toolbox_admin_local=" + cookieOf(login, "toolbox_admin_local"));
        headers.add("X-Toolbox-Admin-Csrf",
                TestJson.parse(login.getBody()).path("data").path("csrfToken").asText());
        return headers;
    }

    /** 从响应中取出指定名称的 Cookie 值。 */
    private static String cookieOf(ResponseEntity<String> response, String name) {
        for (String header : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            String prefix = name + "=";
            if (header.startsWith(prefix)) {
                return header.substring(prefix.length()).split(";")[0];
            }
        }
        throw new AssertionError("cookie not found: " + name);
    }
}
