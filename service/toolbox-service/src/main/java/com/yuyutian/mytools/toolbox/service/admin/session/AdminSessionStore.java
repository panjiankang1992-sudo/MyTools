package com.yuyutian.mytools.toolbox.service.admin.session;

import java.util.Optional;

/**
 * 管理会话存储。
 * 第 2 步先用进程内实现支撑鉴权闭环；第 3 步迁移到工具箱数据库，接口保持不变。
 */
public interface AdminSessionStore {

    /**
     * 保存会话。
     *
     * @param session 会话
     */
    void save(AdminSession session);

    /**
     * 按会话标识哈希读取会话。
     *
     * @param sessionIdHash 会话标识哈希
     * @return 会话；不存在时为空
     */
    Optional<AdminSession> find(String sessionIdHash);

    /**
     * **只推进活动时间，且只在会话仍然存在时**（F13）。
     *
     * 不能用 `save`（upsert）：若会话在这条请求读它之后被注销删除，
     * upsert 会把已注销的会话重新插回去，用户会"退不掉"。
     *
     * @param sessionIdHash 会话标识哈希
     * @param now           当前时刻（毫秒）
     * @return 实际更新的行数；0 表示会话已不存在
     */
    int touchIfPresent(String sessionIdHash, long now);

    /**
     * **抢占一次刷新权**（CAS，F13／A08）。
     *
     * 单飞与"不能对任意 401 盲目刷新"都要靠它落地：
     * 同一会话可能同时有多个请求发现访问令牌将过期，**只允许一个去上游刷新**，
     * 其余的等结果复用。用"比较并交换 `refresh_revision`"实现：
     * 只有把 revision 从 `expected` 改到 `next` 的那一个返回 1，其余返回 0。
     *
     * @param sessionIdHash 会话标识哈希
     * @param expected      期望的当前 revision
     * @param next          要写入的新 revision
     * @return 抢占成功返回 1；会话不存在或 revision 不符返回 0
     */
    int claimRefresh(String sessionIdHash, long expected, long next);

    /**
     * 写入**刷新后的上游凭据**（A08）。
     *
     * **纯 UPDATE，不是 upsert**：若会话在刷新期间被注销，
     * upsert 会把它**复活**——用户会发现自己"退不掉"（第 25 轮踩过同一个坑）。
     *
     * @param sessionIdHash      会话标识哈希
     * @param accessTokenSealed  加密后的新访问令牌
     * @param refreshTokenSealed 加密后的新刷新令牌
     * @param accessExpiresAt    新的访问令牌到期时刻（毫秒）
     * @return 实际更新的行数；0 表示会话已不存在
     */
    int adoptRefreshedTokens(String sessionIdHash, String accessTokenSealed, String refreshTokenSealed,
                             long accessExpiresAt);

    /**
     * 删除会话。
     *
     * @param sessionIdHash 会话标识哈希
     */
    void remove(String sessionIdHash);

    /**
     * 删除某个上游用户的全部会话；退出与权限撤销时使用。
     *
     * @param mytoolsUserId 上游用户标识
     */
    void removeByUser(String mytoolsUserId);
}
