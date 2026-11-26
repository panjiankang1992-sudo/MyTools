package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.toolbox.config.AdminProperties;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityException;
import com.yuyutian.mytools.toolbox.service.admin.identity.HttpAdminIdentityClient;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上游身份接口的**解析层**测试（真实 HTTP，进程内假上游）。
 *
 * 这一层此前完全没有测试。它承的契约里有一条最容易被写错：
 * 登录响应同时有 `role`（单数）与校验响应的 `roles`（复数数组），
 * **授权只能依据 `roles`**，`role` 与用户名都不得参与判定。
 */
class HttpAdminIdentityClientTest {

    /** 进程内假上游。 */
    private HttpServer server;

    /** 最近一次刷新请求带的 Authorization 头；用于验证契约。 */
    private String refreshAuthorization;
    private String baseUrl;
    private ObjectMapper objectMapper;

    /** 最近一次校验请求携带的 Authorization 头，用于验证内部凭据确实被发送。 */
    private volatile String lastAuthorization;

    /** 启动假上游。 */
    @BeforeEach
    void startServer() throws Exception {
        objectMapper = new ObjectMapper();
        lastAuthorization = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", exchange -> {
            String body = """
                    {"code":20001,"message":"ok","data":{"userId":1001,"username":"someone",
                     "role":"USER","accessToken":"access-1","refreshToken":"refresh-1","expiresIn":3600}}
                    """;
            respond(exchange, 200, body);
        });
        server.createContext("/internal/v1/gateway/tokens/validate", exchange -> {
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            String body = """
                    {"active":true,"userId":1001,"username":"someone","role":"USER","roles":["ADMIN"]}
                    """;
            respond(exchange, 200, body);
        });
        server.createContext("/api/auth/logout", exchange -> respond(exchange, 200, "{\"code\":20001}"));
        // A08：刷新契约来自上游 OpenAPI——**刷新令牌放在 Authorization 头**，无请求体。
        server.createContext("/api/auth/refresh", exchange -> {
            refreshAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            String body = """
                    {"code":20001,"message":"ok","data":{"accessToken":"access-2",
                     "refreshToken":"refresh-2","expiresIn":1800}}
                    """;
            respond(exchange, 200, body);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 停止假上游。 */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 构造指向假上游的客户端。
     *
     * @param internalToken 内部凭据
     * @return 客户端
     */
    private HttpAdminIdentityClient client(String internalToken) {
        AdminProperties properties = new AdminProperties(true, baseUrl, internalToken, 3000L, 30L, 8L,
                "toolbox_admin_local", "toolbox_admin_login_local", false, "X-Toolbox-Admin-Csrf",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 4194304L, 10L, 300L);
        return new HttpAdminIdentityClient(properties, objectMapper);
    }

    @Test
    @DisplayName("登录解析取 data 信封内的令牌，且不把 role 带入授权")
    void loginParsesTokenFromEnvelope() {
        AdminIdentityClient.UpstreamLogin login = client("t").login("someone", "secret");
        assertThat(login.accessToken()).isEqualTo("access-1");
        assertThat(login.refreshToken()).isEqualTo("refresh-1");
        assertThat(login.expiresIn()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("校验只采信 roles 数组：role=USER 但 roles=[ADMIN] 时判定为管理员")
    void validateUsesRolesArrayNotSingularRole() {
        AdminIdentity identity = client("t").validate("access-1");
        assertThat(identity.active()).isTrue();
        assertThat(identity.roles()).containsExactly("ADMIN");
        // 关键：单数的 role=USER 不得影响判定。
        assertThat(identity.isAdmin()).as("授权只认 roles，不认单数 role").isTrue();
        // 内部凭据必须以 Bearer 形式发送。
        assertThat(lastAuthorization).isEqualTo("Bearer t");
    }

    @Test
    @DisplayName("roles 为 [USER] 时判定为非管理员，即使单数 role 写成 ADMIN")
    void singularRoleAdminDoesNotGrantRights() {
        server.removeContext("/internal/v1/gateway/tokens/validate");
        server.createContext("/internal/v1/gateway/tokens/validate", exchange -> respond(exchange, 200,
                "{\"active\":true,\"userId\":1002,\"username\":\"u\",\"role\":\"ADMIN\",\"roles\":[\"USER\"]}"));
        AdminIdentity identity = client("t").validate("access-1");
        assertThat(identity.roles()).containsExactly("USER");
        assertThat(identity.isAdmin()).as("单数 role=ADMIN 不得授予管理员权限").isFalse();
    }

    @Test
    @DisplayName("登录响应缺少令牌时判为上游不可用，而不是登录成功")
    void missingTokenIsUnavailable() {
        server.removeContext("/api/auth/login");
        server.createContext("/api/auth/login", exchange -> respond(exchange, 200,
                "{\"code\":20001,\"data\":{\"userId\":1001,\"username\":\"someone\"}}"));
        assertThatThrownBy(() -> client("t").login("someone", "secret"))
                .isInstanceOf(AdminIdentityException.class)
                .satisfies(error -> assertThat(((AdminIdentityException) error).failure())
                        .isEqualTo(com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure.UNAVAILABLE));
    }

    @Test
    @DisplayName("上游返回非 JSON 时判为不可用，不抛出未处理异常")
    void nonJsonResponseIsUnavailable() {
        server.removeContext("/api/auth/login");
        server.createContext("/api/auth/login", exchange -> respond(exchange, 200, "<html>not json</html>"));
        assertThatThrownBy(() -> client("t").login("someone", "secret"))
                .isInstanceOf(AdminIdentityException.class);
    }

    @Test
    @DisplayName("上游超时时失败关闭：判为不可用，绝不当作登录成功（A08）")
    void upstreamTimeoutFailsClosed() {
        server.removeContext("/api/auth/login");
        // 假上游故意不响应，直到远超客户端超时。
        server.createContext("/api/auth/login", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"code\":20001,\"data\":{\"accessToken\":\"late\"}}");
        });
        // 客户端超时设为 300ms，因此必然先超时。
        AdminProperties properties = new AdminProperties(true, baseUrl, "t", 300L, 30L, 8L,
                "toolbox_admin_local", "toolbox_admin_login_local", false, "X-Toolbox-Admin-Csrf",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 4194304L, 10L, 300L);
        HttpAdminIdentityClient client = new HttpAdminIdentityClient(properties, objectMapper);
        // 关键：必须**抛出不可用**，而不是等待后把迟到的响应当成成功。
        assertThatThrownBy(() -> client.login("someone", "secret"))
                .isInstanceOf(AdminIdentityException.class)
                .satisfies(error -> assertThat(((AdminIdentityException) error).failure())
                        .isEqualTo(com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure.UNAVAILABLE));
    }

    @Test
    @DisplayName("上游返回 5xx 时判为不可用，而不是登录成功")
    void upstreamServerErrorIsUnavailable() {
        server.removeContext("/api/auth/login");
        server.createContext("/api/auth/login", exchange -> respond(exchange, 503, "{\"code\":50001}"));
        assertThatThrownBy(() -> client("t").login("someone", "secret"))
                .isInstanceOf(AdminIdentityException.class)
                .satisfies(error -> assertThat(((AdminIdentityException) error).failure())
                        .isEqualTo(com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure.UNAVAILABLE));
    }

    @Test
    @DisplayName("注销失败被吞掉，不阻止本地会话销毁")
    void logoutFailureIsSwallowed() {
        server.removeContext("/api/auth/logout");
        server.createContext("/api/auth/logout", exchange -> respond(exchange, 500, "{\"code\":50001}"));
        // 不应抛出：上游失败不能导致本地仍显示已登录。
        client("t").logout("access-1");
    }

    /** 写回 JSON 响应。 */
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("A08：刷新把刷新令牌放在 Authorization 头，并解析新的凭据")
    void refreshUsesAuthorizationHeaderAndParsesNewTokens() {
        AdminIdentityClient.UpstreamLogin refreshed = client("t").refresh("refresh-1");

        // 契约（来自上游 OpenAPI）：刷新令牌走 Authorization，**不是请求体**。
        // 如果发错了位置，上游会 401，而这里会看到 null。
        assertThat(refreshAuthorization).isEqualTo("Bearer refresh-1");
        assertThat(refreshed.accessToken()).isEqualTo("access-2");
        assertThat(refreshed.refreshToken()).isEqualTo("refresh-2");
        assertThat(refreshed.expiresIn()).isEqualTo(1800L);
    }
}
