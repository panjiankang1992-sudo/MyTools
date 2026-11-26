package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * A05：上游把会话降级／撤销后，**下一次管理请求必须被拒绝**。
 *
 * 用可切换状态的假身份模拟"上游撤销"：登录成功后把 `active` 置为 false，
 * 之后的管理请求必须 401，且**不得靠本地会话继续通行**。
 *
 * 这一条验证的是"**每个请求都重新校验**"这一设计决定——
 * 若实现改成"登录时校验一次、之后信任本地会话"，本测试会失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminSessionRevocationTest {

    /** 上游身份是否仍然有效；测试中可切换。 */
    static final AtomicBoolean upstreamActive = new AtomicBoolean(true);

    /** 可切换状态的假身份。 */
    @TestConfiguration
    static class FakeSwitchableIdentity {
        @Bean
        @Primary
        AdminIdentityClient fakeSwitchableIdentityClient() {
            return new AdminIdentityClient() {
                @Override
                public UpstreamLogin login(String account, String password) {
                    return new UpstreamLogin("access-live", "refresh-live", 3600L);
                }

                @Override
                public AdminIdentity validate(String accessToken) {
                    // 上游一旦撤销，这里就返回 active=false，角色也清空。
                    return upstreamActive.get()
                            ? new AdminIdentity(true, "1001", "someone", List.of(AdminIdentity.ROLE_ADMIN))
                            : new AdminIdentity(false, "1001", "someone", List.of());
                }

                @Override
                public void logout(String accessToken) {
                    upstreamActive.set(false);
                }
            };
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("上游撤销会话后，下一次管理请求被拒绝且不靠本地会话通行（A05）")
    void revokedUpstreamSessionBlocksNextRequest() {
        upstreamActive.set(true);
        HttpHeaders headers = loginAndHeaders();

        // 撤销前：管理请求正常。
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(200);

        // 上游撤销（模拟 MyTools 禁用／降级该会话）。
        upstreamActive.set(false);

        // 撤销后：**即使本地仍有有效会话 Cookie**，下一次请求也必须被拒绝。
        ResponseEntity<String> afterRevocation = rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(afterRevocation.getStatusCode().value())
                .as("上游已撤销，本地会话不得继续通行").isEqualTo(401);
        assertThat(TestJson.parse(afterRevocation.getBody()).path("code").asText())
                .isEqualTo("TOOLBOX_ADMIN_001");

        // 写请求同样被拒（不能只保护读）。
        ResponseEntity<String> writeAfterRevocation = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
        assertThat(writeAfterRevocation.getStatusCode().value()).isEqualTo(401);

        upstreamActive.set(true);
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
