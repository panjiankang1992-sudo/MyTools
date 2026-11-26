package review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.ToolboxServiceApplication;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 使用隔离 H2 和模拟身份执行管理会话黑盒探针，不访问真实认证服务。 */
public final class AdminAuthReviewProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static int logoutCalls;

    /** 创建仅存在于验收进程中的模拟管理员身份。 */
    @Configuration(proxyBeanMethods = false)
    public static class FakeConfiguration {
        /** 注入模拟身份，覆盖生产适配器的实际调用。 */
        @Bean
        @Primary
        public AdminIdentityClient reviewIdentity() {
            return new AdminIdentityClient() {
                @Override
                public UpstreamLogin login(String account, String password) {
                    return new UpstreamLogin("review-fake-access", "review-fake-refresh", 3600);
                }
                @Override
                public AdminIdentity validate(String accessToken) {
                    return new AdminIdentity(true, "review-user", "review-admin", List.of("ADMIN"));
                }
                @Override
                public void logout(String accessToken) {
                    logoutCalls++;
                }
            };
        }
    }

    /** 启动本机隔离服务并输出不含 Cookie 或 CSRF 值的验证结果。 */
    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reserved.getLocalPort();
        }
        String origin = "http://127.0.0.1:" + port;
        try (var context = new SpringApplicationBuilder(ToolboxServiceApplication.class, FakeConfiguration.class)
                .profiles("test")
                .run("--server.address=127.0.0.1", "--server.port=" + port,
                        "--spring.datasource.url=jdbc:h2:mem:admin_review_probe;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "--toolbox.public-origin=" + origin, "--toolbox.public-origins=",
                        "--spring.main.banner-mode=off", "--logging.level.root=OFF")) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("environment", "loopback-only; test profile; isolated H2; fake identity; no production credentials");
            HttpResponse<String> challenge = request(origin, "GET", "/admin/v1/auth/csrf", null, null, null);
            String nonce = JSON.readTree(challenge.body()).path("data").path("csrfToken").asText();
            String preauth = cookie(challenge, "toolbox_admin_login_local");
            HttpResponse<String> login = request(origin, "POST", "/admin/v1/auth/login", preauth, nonce,
                    "{\"account\":\"review-admin\",\"password\":\"fake-test-input\"}");
            require(login.statusCode() == 200, "Fake administrator login failed");
            String sessionCookie = cookie(login, "toolbox_admin_local");
            JsonNode loginData = JSON.readTree(login.body()).path("data");
            evidence.put("loginStatus", login.statusCode());
            evidence.put("loginProvidesCsrf", loginData.hasNonNull("csrfToken"));

            // 模拟刷新：只保留 HttpOnly Cookie，丢弃 JavaScript 内存中的管理 CSRF。
            HttpResponse<String> restored = request(origin, "GET", "/admin/v1/session", sessionCookie, null, null);
            JsonNode restoredData = JSON.readTree(restored.body()).path("data");
            evidence.put("restoredSessionStatus", restored.statusCode());
            evidence.put("restoredAuthenticated", restoredData.path("authenticated").asBoolean());
            evidence.put("restoredSessionProvidesCsrf", restoredData.hasNonNull("csrfToken"));
            require(restoredData.path("authenticated").asBoolean(), "Cookie session was not restored");
            HttpResponse<String> read = request(origin, "GET", "/admin/v1/textbooks", sessionCookie, null, null);
            evidence.put("restoredReadStatus", read.statusCode());
            HttpResponse<String> write = request(origin, "POST", "/admin/v1/textbooks", sessionCookie, null, "{}");
            evidence.put("writeWithoutRestoredCsrfStatus", write.statusCode());
            evidence.put("writeWithoutRestoredCsrfCode", JSON.readTree(write.body()).path("code").asText());
            require(write.statusCode() == 403, "Expected missing-CSRF write to be rejected");

            // 退出请求明确携带正确的同源 Origin，但故意不带管理 CSRF。
            HttpResponse<String> logout = request(origin, "POST", "/admin/v1/auth/logout", sessionCookie, null, null);
            evidence.put("sameOriginLogoutWithoutCsrfStatus", logout.statusCode());
            evidence.put("upstreamFakeLogoutCalls", logoutCalls);
            HttpResponse<String> after = request(origin, "GET", "/admin/v1/textbooks", sessionCookie, null, null);
            evidence.put("oldCookieReadAfterLogoutStatus", after.statusCode());
            require(logout.statusCode() == 200 && after.statusCode() == 401,
                    "Observed logout behavior differs from audited implementation");
            evidence.put("finding", "Reload restores administrator identity but no write CSRF; same-origin logout bypasses CSRF validation");
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }

    /** 发送仅限本机的测试请求。 */
    private static HttpResponse<String> request(String origin, String method, String path, String cookie,
                                                String csrf, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + path))
                .timeout(Duration.ofSeconds(10)).header("Origin", origin).header("Accept", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-Toolbox-Admin-Csrf", csrf);
        if (!"GET".equals(method)) request.header("Idempotency-Key", "review-" + path);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 提取测试 Cookie，仅用于内存中的后续请求。 */
    private static String cookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith(name + "="))
                .findFirst().orElseThrow().split(";", 2)[0];
    }

    /** 不满足预期时使探针以非零状态结束。 */
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
