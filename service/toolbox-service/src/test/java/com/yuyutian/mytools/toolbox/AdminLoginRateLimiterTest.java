package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.common.AdminLoginRateLimiter;
import com.yuyutian.mytools.toolbox.config.AdminProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 登录失败限流（设计第 6 节，`TOOLBOX_ADMIN_008`）。
 *
 * **关于策略来源**：用户要求"与现有 app 端登录保持一致"，但我在本仓库与远程部署上
 * **都找不到可对齐的现成规则**（远程只有构建产物）。因此这里用的是可辩护的默认值，
 * 且全部可配置。本测试锁定的是**行为语义**，不是具体数字。
 */
class AdminLoginRateLimiterTest {

    /** 造一个只关心登录限流两项配置的 AdminProperties。 */
    private static AdminProperties props(long maxFailures, long windowSeconds) {
        return new AdminProperties(true, "http://127.0.0.1:1", "token", 3000, 30, 8,
                "__Host-s", "__Host-l", true, "X-Csrf", "a2V5", 4194304L, maxFailures, windowSeconds);
    }

    @Test
    @DisplayName("失败次数达到上限后开始限流，并给出正数的等待秒数")
    void blocksAfterLimit() {
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(props(3, 300));
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.retryAfterSeconds("1.2.3.4")).as("未达上限不应限流").isZero();
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.retryAfterSeconds("1.2.3.4")).as("达到上限应限流").isPositive();
    }

    @Test
    @DisplayName("成功登录后清零：输错过几次的正常用户不会被挡住")
    void successClearsFailures() {
        // 这条最重要：如果成功登录不清零，"昨天打错三次"会在今天仍然占配额，
        // 那就把防暴力破解做成了妨碍正常使用。
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(props(3, 300));
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.clear("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.retryAfterSeconds("1.2.3.4")).as("清零后不应立即被限").isZero();
    }

    @Test
    @DisplayName("限流按来源隔离：一个来源被限不影响其他来源")
    void isolatesBySource() {
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(props(1, 300));
        limiter.recordFailure("1.1.1.1");
        assertThat(limiter.retryAfterSeconds("1.1.1.1")).isPositive();
        assertThat(limiter.retryAfterSeconds("2.2.2.2")).as("另一来源不应被牵连").isZero();
    }

    @Test
    @DisplayName("上限配成 0 表示关闭限流")
    void zeroDisables() {
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(props(0, 300));
        for (int i = 0; i < 50; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.retryAfterSeconds("1.2.3.4")).as("关闭时限流不应生效").isZero();
    }

    @Test
    @DisplayName("窗口过期后失败记录不再计入")
    void windowExpires() throws Exception {
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(props(2, 1));
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.retryAfterSeconds("1.2.3.4")).isPositive();
        Thread.sleep(1200);
        assertThat(limiter.retryAfterSeconds("1.2.3.4")).as("窗口过后应自动解除").isZero();
    }
}
