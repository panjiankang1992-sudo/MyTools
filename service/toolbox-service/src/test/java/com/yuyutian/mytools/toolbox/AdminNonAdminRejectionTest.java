package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
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
 * A03：凭据正确但**不具备管理员角色**的账号必须被拒绝。
 *
 * 用**注入式假身份**代替真实账号：上游返回 `active=true, roles=["USER"]`。
 * 这验证的是服务端授权判定（`isAdmin()` 只认 `ADMIN` 角色），
 * 而不是"某个真实账号恰好是普通用户"——后者不可复现，前者才是契约。
 *
 * **本测试覆盖的范围要说清**：注入式假身份**直接返回 `AdminIdentity`**，
 * 因此它验证的是**授权判定**（`isAdmin()` 只认 `ADMIN` 角色），
 * 而**不覆盖**上游 JSON 里 `role`（单数）与 `roles`（复数）的解析区别——
 * 那属于 `HttpAdminIdentityClient` 的职责，需要以真实 HTTP 假上游来验证。
 * （已知缺口：该解析层目前没有任何测试。）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminNonAdminRejectionTest {

    /** 上游返回"登录成功但不是管理员"。 */
    @TestConfiguration
    static class FakeNonAdminIdentity {
        @Bean
        @Primary
        AdminIdentityClient fakeNonAdminIdentityClient() {
            return new AdminIdentityClient() {
                @Override
                public UpstreamLogin login(String account, String password) {
                    return new UpstreamLogin("access-user", "refresh-user", 3600L);
                }

                @Override
                public AdminIdentity validate(String accessToken) {
                    // 只有 USER，没有 ADMIN —— 应当被拒绝。
                    return new AdminIdentity(true, "2002", "normal-user", List.of("USER"));
                }

                @Override
                public void logout(String accessToken) {
                    // 假实现不做事。
                }
            };
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("凭据有效但非管理员时返回 403，且不建立管理会话（A03）")
    void nonAdminIsRejectedWith403AndNoSession() {
        // 领取 nonce 与预认证 Cookie。
        ResponseEntity<String> csrf = rest.getForEntity("/admin/v1/auth/csrf", String.class);
        assertThat(csrf.getStatusCode().value()).isEqualTo(200);
        String preAuth = cookieOf(csrf, "toolbox_admin_login_local");
        String nonce = TestJson.parse(csrf.getBody()).path("data").path("csrfToken").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "toolbox_admin_login_local=" + preAuth);
        headers.add("X-Toolbox-Admin-Csrf", nonce);
        ResponseEntity<String> login = rest.exchange("/admin/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"account\":\"normal-user\",\"password\":\"secret\"}", headers), String.class);

        assertThat(login.getStatusCode().value()).as("非管理员必须被拒绝").isEqualTo(403);
        assertThat(TestJson.parse(login.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_002");
        // 不得下发管理会话 Cookie。
        assertThat(login.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of()).stream()
                .anyMatch(cookie -> cookie.startsWith("toolbox_admin_local=") && !cookie.contains("Max-Age=0")))
                .as("非管理员不得获得管理会话").isFalse();

        // 后续管理请求仍应被拒绝。
        ResponseEntity<String> list = rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(401);
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
