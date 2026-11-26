package com.yuyutian.mytools.toolbox.service.admin.session;

import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 管理员登录与逐请求授权。
 * 授权只认上游实时校验结果：登录响应里的 role、Cookie、浏览器字段一律不作为依据。
 */
@Service
public final class AdminAuthService {

    private final AdminIdentityClient identityClient;
    private final AdminSessionService sessionService;

    /**
     * 创建管理员鉴权服务。
     *
     * @param identityClient 上游身份适配层
     * @param sessionService 管理会话服务
     */
    public AdminAuthService(AdminIdentityClient identityClient, AdminSessionService sessionService) {
        this.identityClient = identityClient;
        this.sessionService = sessionService;
    }

    /**
     * 使用账号与密码登录；只有上游确认当前为管理员才签发会话。
     *
     * @param account  用户名或邮箱
     * @param password 密码
     * @return 新会话
     * @throws AdminIdentityException 账号被拒、非管理员或上游不可用
     */
    public AdminSessionService.IssuedSession login(String account, String password) {
        AdminIdentityClient.UpstreamLogin login = identityClient.login(account, password);
        // F13：**上游会话一旦建立，之后任何失败都必须清理它。**
        //
        // 此前只有"validate 成功但判定不可用／非管理员"这两条路径会注销；
        // 而 `validate` **自己抛异常**（上游不可用、凭据被拒）时，
        // 以及本地**建会话失败**时，都直接冒泡出去——上游于是留下一批**孤儿会话**：
        // 用户根本没登上工具箱，上游却替他建了会话。
        try {
            AdminIdentity identity = identityClient.validate(login.accessToken());
            if (!identity.active()) {
                // 登录成功却立即失效：这是失败，交给 catch 统一注销。
                throw new AdminIdentityException(AdminIdentityFailure.REJECTED);
            }
            if (!identity.isAdmin()) {
                throw new AdminIdentityException(AdminIdentityFailure.NOT_ADMIN);
            }
            return sessionService.issue(identity, login.accessToken(), login.refreshToken(),
                    login.expiresIn());
        } catch (RuntimeException failure) {
            // 尽力注销；**注销自身的失败不得掩盖原始原因**，否则用户会看到一个
            // 与真实问题无关的错误（例如"上游不可用"盖住了"你不是管理员"）。
            try {
                identityClient.logout(login.accessToken());
            } catch (RuntimeException ignored) {
                // 留下孤儿会话比丢掉真实错误更可接受：上层会记录原始异常。
            }
            throw failure;
        }
    }

    /**
     * 校验会话并对上游做一次实时管理员校验。
     * 每个管理读取、保存、发布请求都必须经过本方法；上游降级或撤销会立即使会话失效。
     *
     * @param sessionId 浏览器持有的明文会话标识
     * @return 通过校验的会话；未登录或已失效时为空
     */
    /**
     * 取回会话的 CSRF 令牌，供浏览器刷新／新标签页恢复写入能力。
     *
     * @param session 已认证会话
     * @return 明文 CSRF 令牌；旧会话或未配置加密时为空
     */
    /**
     * 校验请求携带的 CSRF 令牌是否属于该会话。
     *
     * @param session  已认证会话
     * @param supplied 请求头里的令牌，可为空
     * @return 匹配时为 true
     */
    public boolean csrfMatches(AdminSession session, String supplied) {
        return sessionService.csrfMatches(session, supplied);
    }

    /**
     * **只读**校验会话（不推进空闲期限）；用于状态查询与退出前的 CSRF 校验。
     *
     * @param sessionId 会话标识
     * @return 可用会话；不可用时为空
     */
    /**
     * **按需提前刷新上下游凭据**（A08）。
     *
     * 规则来自设计，三条都落在这一处：
     *
     * 1. **只在令牌仍有效、但临近过期时刷**——判据是时间（{@link AdminTokenRefreshPolicy}），
     *    **不是**某个响应码。走到"已经过期"就不刷：那时刷也救不回当前请求，
     *    只会让一条本该失败的请求看起来成功了。
     * 2. **单飞**：先用 CAS 抢占刷新权。**抢不到就什么都不做**——
     *    说明另一个请求正在刷，复用它的结果即可，不要重复打上游。
     * 3. **抢到之后才刷**；刷新成功就写回会话，失败**原样返回**——
     *    刷新失败不该让用户掉线，访问令牌可能仍然可用。
     *
     * @param sessionId 会话标识
     * @return 处理后的会话；会话不可用时为空
     */
    public Optional<AdminSession> refreshIfNeeded(String sessionId) {
        Optional<AdminSession> found = sessionService.peek(sessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdminSession session = found.get();
        long now = System.currentTimeMillis();
        if (!AdminTokenRefreshPolicy.shouldRefresh(session.accessExpiresAt(), now,
                AdminTokenRefreshPolicy.REFRESH_AHEAD_MILLIS)) {
            return found;
        }
        // 单飞：抢不到刷新权说明别人正在刷，直接复用当前会话。
        long expected = session.refreshRevision();
        if (!sessionService.claimRefresh(sessionId, expected, expected + 1)) {
            return found;
        }
        try {
            AdminIdentityClient.UpstreamLogin refreshed = identityClient.refresh(session.refreshToken());
            long expiresAt = now + Math.max(60_000L, refreshed.expiresIn() * 1000L);
            sessionService.adoptRefreshedTokens(sessionId, refreshed.accessToken(),
                    refreshed.refreshToken(), expiresAt);
            return sessionService.peek(sessionId);
        } catch (RuntimeException failure) {
            // 刷新失败**不改动会话**：访问令牌可能仍然可用，
            // 让请求照原样继续，比把用户踢下线更接近正确。
            return found;
        }
    }

    public Optional<AdminSession> peek(String sessionId) {
        return sessionService.peek(sessionId);
    }

    public java.util.Optional<String> csrfTokenFor(AdminSession session) {
        return sessionService.csrfTokenFor(session);
    }

    public Optional<AdminSession> authenticate(String sessionId) {
        Optional<AdminSession> resolved = sessionService.resolve(sessionId);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        AdminSession session = resolved.get();
        AdminIdentity identity;
        try {
            identity = identityClient.validate(session.accessToken());
        } catch (AdminIdentityException unavailable) {
            // 依赖不可用不等于权限有效：拒绝本次请求，但保留会话以便恢复后继续。
            throw unavailable;
        }
        if (!identity.isAdmin()) {
            sessionService.revoke(sessionId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * 退出登录：先销毁本地会话，再尽力注销上游会话。
     *
     * @param sessionId 浏览器持有的明文会话标识
     */
    public void logout(String sessionId) {
        Optional<AdminSession> resolved = sessionService.resolve(sessionId);
        sessionService.revoke(sessionId);
        resolved.ifPresent(session -> identityClient.logout(session.accessToken()));
    }
}
