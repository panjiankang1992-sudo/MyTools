package com.yuyutian.mytools.toolbox.service.admin.session;

/**
 * 管理员访问令牌的**提前刷新判断**（A08）。
 *
 * 设计对 A08 的要求里有两条看起来互相矛盾、实则需要同一个判断：
 *
 * - **令牌仍有效时提前刷新**——不要等它过期，否则用户会在请求中途被踢；
 * - **禁止对任意 401 盲目刷新**——那时令牌**已经失效**，刷新救不回这条请求，
 *   而且会让一条本该失败的请求看起来"重试成功"，掩盖真实的授权问题。
 *
 * 两者的分界是**时间**而不是响应码：只要**在过期之前**按剩余时间做决定，
 * 就不需要、也不应该依赖 401 来触发刷新。
 */
public final class AdminTokenRefreshPolicy {

    /**
     * 提前刷新的阈值：**5 分钟**。
     *
     * 用常量而不是新配置项：`AdminProperties` 是 `@ConfigurationProperties` record，
     * 加字段会改构造器签名并波及大量测试构造点（我在更早一轮踩过这个坑）。
     * 5 分钟对"空闲 30 分钟退出"的默认值是合适的——
     * 既留足余量，又不会每次请求都去刷新。
     */
    public static final long REFRESH_AHEAD_MILLIS = 5 * 60_000L;

    /** 工具类不允许实例化。 */
    private AdminTokenRefreshPolicy() {
    }

    /**
     * 判断是否应当提前刷新。
     *
     * @param expiresAt          访问令牌的到期时刻（毫秒）
     * @param now                当前时刻（毫秒）
     * @param refreshAheadMillis 提前刷新的阈值（毫秒）
     * @return 应当刷新时为 true
     */
    public static boolean shouldRefresh(long expiresAt, long now, long refreshAheadMillis) {
        // 已经过期（或恰好到期）：**不刷**。
        //
        // 这正是"禁止对任意 401 盲目刷新"：走到这里说明令牌已经不可用，
        // 再拿它去换新令牌，要么被上游拒绝，要么让一条本该失败的请求"看起来成功了"——
        // 那会掩盖真实的授权问题。**失效就该让请求失败并重新登录。**
        if (expiresAt <= now) {
            return false;
        }
        // 仍然有效，但剩余时间已经进入阈值：提前换，避免请求打到一半令牌过期。
        return expiresAt - now <= refreshAheadMillis;
    }
}
