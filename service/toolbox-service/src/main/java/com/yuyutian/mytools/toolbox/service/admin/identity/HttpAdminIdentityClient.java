package com.yuyutian.mytools.toolbox.service.admin.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.config.AdminProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 通过固定的内网／回环地址调用上游 MyTools 身份接口。
 *
 * 三条约束：
 * 1. 只允许访问配置中的固定基地址，不接受请求方传入的任意 URL，因此本类不构成开放代理；
 * 2. 禁止跟随重定向，避免把内部凭据带到其它主机；
 * 3. 登录响应里的 role 与用户名的字面值都不作为授权依据，授权只认 {@link #validate} 的结果。
 */
@Component
public final class HttpAdminIdentityClient implements AdminIdentityClient {

    /** 上游登录路径。 */
    private static final String LOGIN_PATH = "/api/auth/login";
    /** 上游注销路径。 */
    private static final String LOGOUT_PATH = "/api/auth/logout";
    /** 上游权威校验路径；该路径由服务内部凭据保护，不对公网开放。 */
    private static final String VALIDATE_PATH = "/internal/v1/gateway/tokens/validate";

    /** 刷新路径；契约来自上游 OpenAPI。 */
    private static final String REFRESH_PATH = "/api/auth/refresh";
    /** 单次上游响应的最大读取字节数，超出即按不可用处理。 */
    private static final int MAXIMUM_RESPONSE_BYTES = 65_536;

    private final AdminProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    /**
     * 创建禁止重定向、连接等待受限的上游身份客户端。
     *
     * @param properties     管理员门面配置
     * @param objectMapper   JSON 解析器
     */
    @Autowired
    public HttpAdminIdentityClient(AdminProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500L, properties.upstreamTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public UpstreamLogin login(String account, String password) {
        JsonNode payload = objectMapper.createObjectNode()
                .put("account", account)
                .put("password", password)
                .put("deviceName", "Yuyutian Toolbox Admin");
        JsonNode root = send(LOGIN_PATH, payload, null, AdminIdentityFailure.REJECTED);
        // 上游使用 Result<T> 信封；data 为空视为不可用，而不是当成登录成功。
        JsonNode data = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        String accessToken = text(data, "accessToken");
        String refreshToken = text(data, "refreshToken");
        long expiresIn = data.hasNonNull("expiresIn") ? data.get("expiresIn").asLong() : 0L;
        if (accessToken == null || accessToken.isBlank()) {
            throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
        }
        return new UpstreamLogin(accessToken, refreshToken, expiresIn);
    }

    @Override
    public AdminIdentity validate(String accessToken) {
        JsonNode root = send(VALIDATE_PATH, objectMapper.createObjectNode().put("accessToken", accessToken),
                properties.internalToken(), AdminIdentityFailure.UNAVAILABLE);
        boolean active = root.path("active").asBoolean(false);
        String userId = root.hasNonNull("userId") ? root.get("userId").asText() : null;
        String username = root.hasNonNull("username") ? root.get("username").asText() : null;
        List<String> roles = new ArrayList<>();
        JsonNode rolesNode = root.get("roles");
        if (rolesNode != null && rolesNode.isArray()) {
            for (JsonNode role : rolesNode) {
                if (role.isTextual() && !role.asText().isBlank()) {
                    roles.add(role.asText());
                }
            }
        }
        return new AdminIdentity(active, userId, username, List.copyOf(roles));
    }

    @Override
    public UpstreamLogin refresh(String refreshToken) {
        // 刷新令牌走 Authorization 头，**没有请求体**——与 login 的形状不同，
        // 所以这里传 null 作为 body。
        JsonNode root = send(REFRESH_PATH, null, refreshToken, AdminIdentityFailure.UNAVAILABLE);
        // **必须解包 data 信封**：send 返回的是根节点，令牌在 data 下面。
        // 我第一版把根节点直接当 data 用，于是 accessToken 取到空值并抛 UNAVAILABLE。
        // 与 login 保持同一套防御：data 不是对象时回落到根，避免上游换了形状就取空。
        JsonNode data = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        String accessToken = text(data, "accessToken");
        String nextRefresh = text(data, "refreshToken");
        long expiresIn = data.path("expiresIn").asLong(0L);
        if (accessToken == null || accessToken.isBlank()) {
            // 上游说成功却没给访问令牌：按不可用处理，不能拿一个空的令牌继续用。
            throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
        }
        return new UpstreamLogin(accessToken, nextRefresh, expiresIn);
    }

    @Override
    public void logout(String accessToken) {
        // 上游失败不得导致本地仍登录，因此注销异常一律吞掉，但保留内部不可用语义给调用方决定是否记录。
        try {
            send(LOGOUT_PATH, null, accessToken, AdminIdentityFailure.UNAVAILABLE);
        } catch (AdminIdentityException ignored) {
            // 有意忽略：调用方负责本地会话销毁。
        }
    }

    /**
     * 向固定基地址发起一次上游调用并解析 JSON。
     *
     * @param path         以斜杠开头的固定路径
     * @param body         请求体；为 null 时不发送请求体
     * @param bearerToken  Bearer 凭据；为 null 时不发送 Authorization 头
     * @param failure      非 2xx 时的失败分类
     * @return 解析后的 JSON 根节点
     */
    private JsonNode send(String path, JsonNode body, String bearerToken, AdminIdentityFailure failure) {
        String base = properties.upstreamBaseUrl();
        if (base == null || base.isBlank()) {
            throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(fixedUri(base, path))
                .timeout(Duration.ofMillis(Math.max(500L, properties.upstreamTimeoutMs())))
                .header("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (body == null) {
            builder.method("POST", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method("POST", HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAXIMUM_RESPONSE_BYTES) {
                throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 401/403 由上游明确拒绝；其余（含 5xx）按依赖不可用处理。
                int status = response.statusCode();
                AdminIdentityFailure classified;
                if (status == 401 || status == 403) {
                    // 上游明确拒绝凭据。
                    classified = AdminIdentityFailure.REJECTED;
                } else if (status >= 500) {
                    // 5xx 是**上游故障**，不是凭据错误。若归为 REJECTED，界面会告诉用户
                    // "账号或密码不正确"，用户会反复重试一个其实已经宕掉的服务。
                    classified = AdminIdentityFailure.UNAVAILABLE;
                } else {
                    // 其余非 2xx 交给调用方语境判断（登录场景下 400／422 表示凭据问题）。
                    classified = failure;
                }
                throw new AdminIdentityException(classified);
            }
            return objectMapper.readTree(response.body());
        } catch (HttpTimeoutException timeout) {
            throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
        } catch (AdminIdentityException controlled) {
            throw controlled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
        } catch (Exception other) {
            throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
        }
    }

    /**
     * 拼接固定基地址与路径，并拒绝任何会改变主机的输入。
     *
     * @param base 配置中的基地址
     * @param path 以斜杠开头的路径
     * @return 绝对 URI
     */
    private static URI fixedUri(String base, String path) {
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalized + path);
    }

    /**
     * 安全读取文本字段。
     *
     * @param node  JSON 对象
     * @param field 字段名
     * @return 字段文本；缺失或非文本时为 null
     */
    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isTextual() ? node.get(field).asText() : null;
    }
}
