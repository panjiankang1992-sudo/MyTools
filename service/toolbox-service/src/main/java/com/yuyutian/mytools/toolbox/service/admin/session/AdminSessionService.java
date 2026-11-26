package com.yuyutian.mytools.toolbox.service.admin.session;

import com.yuyutian.mytools.toolbox.config.AdminProperties;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 管理会话的签发、校验与撤销。
 * 浏览器只拿到随机串，服务端只保存其哈希；CSRF 令牌同理，比较一律使用常量时间算法。
 */
@Service
public final class AdminSessionService {

    /** 随机标识的字节长度。 */
    private static final int TOKEN_BYTES = 32;

    private final AdminSessionStore store;
    private final AdminProperties properties;
    private final AdminTokenCipher cipher;
    private final SecureRandom random = new SecureRandom();

    /**
     * 创建管理会话服务。
     *
     * @param store      会话存储
     * @param properties 管理员门面配置
     */
    public AdminSessionService(AdminSessionStore store, AdminProperties properties, AdminTokenCipher cipher) {
        this.store = store;
        this.properties = properties;
        this.cipher = cipher;
    }

    /**
     * 为已验证为管理员的身份签发新会话。
     *
     * @param identity        上游权威身份；调用方必须先确认 isAdmin()
     * @param accessToken     上游访问令牌
     * @param refreshToken    上游刷新令牌
     * @param accessExpiresIn 上游给出的访问令牌有效秒数
     * @return 新会话及其对浏览器可见的明文会话标识与 CSRF 令牌
     */
    public IssuedSession issue(AdminIdentity identity, String accessToken, String refreshToken, long accessExpiresIn) {
        long now = System.currentTimeMillis();
        String sessionId = randomToken();
        String csrfToken = randomToken();
        long accessExpiresAt = now + Math.max(0L, accessExpiresIn) * 1000L;
        AdminSession session = new AdminSession(
                hash(sessionId),
                identity.userId(),
                identity.username(),
                accessToken,
                refreshToken,
                accessExpiresAt,
                now,
                now,
                now + properties.absoluteHours() * 3_600_000L,
                hash(csrfToken),
                cipher.isConfigured() ? cipher.encrypt(csrfToken) : null);
        store.save(session);
        return new IssuedSession(sessionId, csrfToken, session);
    }

    /**
     * 解析并校验会话；可用时推进活动时间。
     * 轮询与静态资源不经过本方法，因此不会无限延长空闲时间。
     *
     * @param sessionId 浏览器持有的明文会话标识
     * @return 可用会话；无效或过期时为空
     */
    public Optional<AdminSession> resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String key = hash(sessionId);
        Optional<AdminSession> found = store.find(key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdminSession session = found.get();
        long idleLimitMillis = properties.idleMinutes() * 60_000L;
        if (!session.isUsableAt(System.currentTimeMillis(), idleLimitMillis)) {
            store.remove(key);
            return Optional.empty();
        }
        AdminSession touched = session.touched(System.currentTimeMillis());
        // F13：用**只 UPDATE、不 INSERT** 的方式推进活动时间。
        // `save` 是 upsert——若会话在这条请求读它之后被注销删除，
        // upsert 会把**已注销的会话重新插回去**，用户会发现自己"退不掉"。
        store.touchIfPresent(touched.sessionIdHash(), touched.lastActivityAt());
        return Optional.of(touched);
    }

    /**
     * **只读**校验会话：可用则返回，但**不推进空闲期限**（F13）。
     *
     * 为什么需要它：`GET /admin/v1/session` 是**状态查询**——
     * 页面上什么都不做、只是轮询或刷新，也会把空闲期限往后推。
     * 于是"30 分钟不操作就退出"这条规则**永远不会触发**：
     * 只要页面开着，查询就一直替用户"续期"。
     *
     * 与之相对，**真正的操作**（经 `AdminAuthFilter` 的请求）仍然 touch——
     * 那才是"用户在做事"的证据。
     *
     * @param sessionId 会话标识
     * @return 可用会话；不可用或不存在时为空
     */
    /**
     * 抢占一次刷新权（服务层入口，供编排使用）。
     *
     * @param sessionId 会话标识
     * @param expected  期望的当前 revision
     * @param next      要写入的新 revision
     * @return 抢占成功时为 true
     */
    public boolean claimRefresh(String sessionId, long expected, long next) {
        return store.claimRefresh(hash(sessionId), expected, next) == 1;
    }

    /**
     * 写入刷新后的凭据（服务层入口）。
     *
     * @param sessionId       会话标识
     * @param accessToken     新的访问令牌（此处做加密）
     * @param refreshToken    新的刷新令牌
     * @param accessExpiresAt 新的到期时刻（毫秒）
     * @return 是否写成功（会话已不存在时为 false）
     */
    public boolean adoptRefreshedTokens(String sessionId, String accessToken, String refreshToken,
                                        long accessExpiresAt) {
        return store.adoptRefreshedTokens(hash(sessionId), cipher.encrypt(accessToken),
                cipher.encrypt(refreshToken), accessExpiresAt) == 1;
    }

    public Optional<AdminSession> peek(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String key = hash(sessionId);
        Optional<AdminSession> found = store.find(key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdminSession session = found.get();
        long idleLimitMillis = properties.idleMinutes() * 60_000L;
        if (!session.isUsableAt(System.currentTimeMillis(), idleLimitMillis)) {
            store.remove(key);
            return Optional.empty();
        }
        // **不 save**：读操作不改变"用户最近做了什么"这个事实。
        return Optional.of(session);
    }

    /**
     * 校验请求携带的 CSRF 令牌是否与会话绑定且一致。
     *
     * @param session 已解析的会话
     * @param supplied 请求头中的 CSRF 令牌
     * @return 一致时为 true
     */
    /**
     * 取回该会话的 CSRF 令牌（供刷新／新标签页恢复）。
     *
     * **为什么能取回**：会话里另存了一份**加密**的令牌，因此服务端可以把它还给
     * 同一个会话的浏览器——而不是轮换一个新令牌（轮换会让其它标签页立即失效）。
     *
     * @param session 已认证会话
     * @return 明文 CSRF 令牌；旧会话（无该字段）或未配置加密时为空
     */
    public java.util.Optional<String> csrfTokenFor(AdminSession session) {
        if (session.csrfTokenSealed() == null || !cipher.isConfigured()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(cipher.decrypt(session.csrfTokenSealed()));
        } catch (RuntimeException failure) {
            // 解密失败不能导致整个会话不可用：只是无法恢复 CSRF，
            // 浏览器可以重新登录；这里不把异常抛给调用方。
            return java.util.Optional.empty();
        }
    }

    public boolean csrfMatches(AdminSession session, String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                session.csrfTokenHash().getBytes(StandardCharsets.UTF_8),
                hash(supplied).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 撤销会话。
     *
     * @param sessionId 浏览器持有的明文会话标识
     */
    public void revoke(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            store.remove(hash(sessionId));
        }
    }

    /**
     * 撤销某个账号的全部会话；权限被撤销或换账号登录时使用。
     *
     * @param mytoolsUserId 上游用户标识
     */
    public void revokeAllForUser(String mytoolsUserId) {
        store.removeByUser(mytoolsUserId);
    }

    /** 生成随机标识。 */
    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 计算 SHA-256 十六进制摘要。 */
    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 新签发的会话。
     *
     * @param sessionId 对浏览器可见的明文会话标识
     * @param csrfToken 对浏览器可见的明文 CSRF 令牌
     * @param session   服务端保存的会话
     */
    public record IssuedSession(String sessionId, String csrfToken, AdminSession session) {
    }
}
