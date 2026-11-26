package com.yuyutian.mytools.toolbox.service.admin.session;

/**
 * 一次管理员登录对应的服务端会话。
 * 浏览器只持有随机 Cookie 值；服务端只保存该值的哈希，不保存明文。
 *
 * @param sessionIdHash    会话标识的哈希，作为主键
 * @param mytoolsUserId    上游用户标识，对前端输出时按字符串处理
 * @param username         显示用账号名
 * @param accessToken      上游访问令牌；第 3 步迁移到数据库时改为加密保存
 * @param refreshToken     上游刷新令牌；第 3 步迁移到数据库时改为加密保存
 * @param accessExpiresAt  上游访问令牌到期时刻（毫秒）
 * @param createdAt        会话创建时刻（毫秒）
 * @param lastActivityAt   最近一次真实管理请求时刻（毫秒），轮询不更新
 * @param absoluteExpiresAt 会话绝对到期时刻（毫秒）
 * @param csrfTokenHash    CSRF 令牌的哈希
 */
public record AdminSession(
        String sessionIdHash,
        String mytoolsUserId,
        String username,
        String accessToken,
        String refreshToken,
        long accessExpiresAt,
        long createdAt,
        long lastActivityAt,
        long absoluteExpiresAt,
        String csrfTokenHash,
        /** 加密后的 CSRF 令牌，仅供会话恢复时取回；旧行为 null。 */
        String csrfTokenSealed,
        /**
         * 刷新权的版本号（A08）。
         *
         * 编排用 CAS 抢占刷新权时需要它作为**期望值**——
         * 此前它只存在于数据库里，record 上没有，所以编排**读不到**
         * （第 64 轮就是撞在这上面，已还原）。
         */
        long refreshRevision) {

    /**
     * 用**刷新后的上游凭据**替换本会话的令牌（A08）。
     *
     * **为什么是方法而不是新的 record 组件**：加组件会改变构造器签名，
     * 波及所有构造点；而这里只是"换掉三个字段"，其余（会话标识、
     * 用户、CSRF、绝对期限）**必须原样保留**——刷新令牌不该让用户重新登录。
     *
     * **`lastActivityAt` 与 `createdAt` 都不动**：刷新是系统行为，不是用户活动。
     *
     * @param newAccessToken        新的访问令牌
     * @param newRefreshToken       新的刷新令牌；上游没给时沿用旧的
     * @param newAccessExpiresAt    新的访问令牌到期时刻（毫秒）
     * @return 替换凭据后的会话
     */
    public AdminSession withRefreshedTokens(String newAccessToken, String newRefreshToken,
                                            long newAccessExpiresAt) {
        return new AdminSession(sessionIdHash, mytoolsUserId, username, newAccessToken,
                newRefreshToken == null || newRefreshToken.isBlank() ? refreshToken : newRefreshToken,
                newAccessExpiresAt, createdAt, lastActivityAt, absoluteExpiresAt,
                csrfTokenHash, csrfTokenSealed, refreshRevision);
    }

    /**
     * 兼容构造：不带可恢复 CSRF 令牌的会话（等同于升级前建下的旧会话）。
     *
     * 这类会话仍可用于**校验**（`csrfTokenHash` 在），
     * 只是浏览器刷新后**无法恢复** CSRF——需要重新登录。
     *
     * @param sessionIdHash     会话标识哈希
     * @param mytoolsUserId     上游用户 ID
     * @param username          用户名
     * @param accessToken       上游访问令牌
     * @param refreshToken      上游刷新令牌
     * @param accessExpiresAt   访问令牌到期时刻
     * @param createdAt         创建时刻
     * @param lastActivityAt    最近活动时刻
     * @param absoluteExpiresAt 绝对到期时刻
     * @param csrfTokenHash     CSRF 令牌哈希
     */
    public AdminSession(String sessionIdHash, String mytoolsUserId, String username, String accessToken,
                        String refreshToken, long accessExpiresAt, long createdAt, long lastActivityAt,
                        long absoluteExpiresAt, String csrfTokenHash) {
        this(sessionIdHash, mytoolsUserId, username, accessToken, refreshToken, accessExpiresAt,
                createdAt, lastActivityAt, absoluteExpiresAt, csrfTokenHash, null, 0L);
    }

    /**
     * 兼容构造：不带刷新版本号（等同于"从未刷新过"）。
     *
     * 新增 {@code refreshRevision} 组件时用它**避免改动既有构造点**——
     * 这与当初加 {@code csrfTokenSealed} 是同一个手法。
     *
     * @param sessionIdHash     会话标识哈希
     * @param mytoolsUserId     上游用户 ID
     * @param username          用户名
     * @param accessToken       上游访问令牌
     * @param refreshToken      上游刷新令牌
     * @param accessExpiresAt   访问令牌到期时刻
     * @param createdAt         创建时刻
     * @param lastActivityAt    最近活动时刻
     * @param absoluteExpiresAt 绝对到期时刻
     * @param csrfTokenHash     CSRF 令牌哈希
     * @param csrfTokenSealed   加密后的 CSRF 令牌
     */
    public AdminSession(String sessionIdHash, String mytoolsUserId, String username, String accessToken,
                        String refreshToken, long accessExpiresAt, long createdAt, long lastActivityAt,
                        long absoluteExpiresAt, String csrfTokenHash, String csrfTokenSealed) {
        this(sessionIdHash, mytoolsUserId, username, accessToken, refreshToken, accessExpiresAt,
                createdAt, lastActivityAt, absoluteExpiresAt, csrfTokenHash, csrfTokenSealed, 0L);
    }

    /**
     * 判断会话在给定时刻是否仍然可用。
     * 空闲上限与绝对上限同时生效，以上游访问令牌的到期时间为更短边界。
     *
     * @param now              当前时刻（毫秒）
     * @param idleLimitMillis  空闲上限（毫秒）
     * @return 可用时为 true
     */
    public boolean isUsableAt(long now, long idleLimitMillis) {
        return now < absoluteExpiresAt
                && now < lastActivityAt + idleLimitMillis
                && now < accessExpiresAt;
    }

    /**
     * 推进最近活动时间。
     *
     * @param now 当前时刻（毫秒）
     * @return 更新后的会话
     */
    public AdminSession touched(long now) {
        return new AdminSession(sessionIdHash, mytoolsUserId, username, accessToken, refreshToken,
                accessExpiresAt, createdAt, now, absoluteExpiresAt, csrfTokenHash, csrfTokenSealed);
    }
}
