package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 会话生命周期（设计第 4.3 节：空闲上限 30 分钟、绝对上限 8 小时，且受上游令牌约束）。
 *
 * 此前这条逻辑**没有任何直接测试**——`AdminSessionService` 的测试都是经 Spring 运行的
 * 集成路径，不覆盖"两种上限同时生效"的边界。而它是**纯函数**，本可以廉价地测透。
 *
 * 最要紧的一条是：**`touched` 不得延长绝对上限**。否则一个持续有活动的会话可以永远不失效，
 * 绝对上限就形同虚设。
 */
class AdminSessionLifetimeTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long IDLE_LIMIT = 30 * MINUTE;

    /** 构造一个在 T0 登录、空闲 30 分钟、绝对 8 小时、上游令牌 1 小时的会话。 */
    private static AdminSession sessionAt(long t0) {
        return new AdminSession("hash", "1001", "someone", "access", "refresh",
                t0 + HOUR, t0, t0, t0 + 8 * HOUR, "csrf-hash");
    }

    @Test
    @DisplayName("各上限之内可用")
    void usableWithinAllLimits() {
        long t0 = 1_700_000_000_000L;
        assertThat(sessionAt(t0).isUsableAt(t0 + 5 * MINUTE, IDLE_LIMIT)).isTrue();
    }

    @Test
    @DisplayName("超过空闲上限即失效，即使绝对上限还很远")
    void idleLimitEndsSession() {
        long t0 = 1_700_000_000_000L;
        assertThat(sessionAt(t0).isUsableAt(t0 + 31 * MINUTE, IDLE_LIMIT)).isFalse();
    }

    @Test
    @DisplayName("持续活动也会被绝对上限终止（空闲上限不能无限续命）")
    void absoluteLimitEndsSessionDespiteActivity() {
        long t0 = 1_700_000_000_000L;
        // 模拟"每隔 10 分钟就有真实请求"，一路推进到 8 小时之后。
        AdminSession session = sessionAt(t0);
        long now = t0;
        // 49 步 × 10 分钟 = 8 小时 10 分，**明确越过**绝对上限（48 步恰好等于 8 小时，边界不好判定）。
        for (int step = 0; step < 49; step += 1) {
            now += 10 * MINUTE;
            session = session.touched(now);
        }
        // 此刻最近活动就在 0 分钟前，但绝对上限已过。
        assertThat(now).isGreaterThan(t0 + 8 * HOUR);
        assertThat(session.isUsableAt(now, IDLE_LIMIT))
                .as("绝对上限必须终止会话，否则它可以被活动无限续命").isFalse();
    }

    @Test
    @DisplayName("上游访问令牌到期即失效，即使本地两种上限都还宽裕")
    void upstreamTokenExpiryEndsSession() {
        long t0 = 1_700_000_000_000L;
        // 上游令牌 1 小时到期；本地空闲上限 30 分钟，因此把活动时间往前推以隔离令牌这一条。
        AdminSession session = new AdminSession("hash", "1001", "someone", "access", "refresh",
                t0 + HOUR, t0, t0 + 70 * MINUTE, t0 + 8 * HOUR, "csrf-hash");
        assertThat(session.isUsableAt(t0 + 70 * MINUTE, IDLE_LIMIT))
                .as("更短的边界优先：上游令牌已过期").isFalse();
    }

    @Test
    @DisplayName("touched 只推进活动时间，不改动绝对上限与创建时间")
    void touchedDoesNotExtendAbsoluteLimit() {
        long t0 = 1_700_000_000_000L;
        AdminSession touched = sessionAt(t0).touched(t0 + 20 * MINUTE);
        assertThat(touched.lastActivityAt()).isEqualTo(t0 + 20 * MINUTE);
        assertThat(touched.absoluteExpiresAt()).as("绝对上限不得被活动延长").isEqualTo(t0 + 8 * HOUR);
        assertThat(touched.createdAt()).isEqualTo(t0);
        // 其余字段原样保留。
        assertThat(touched.accessToken()).isEqualTo("access");
        assertThat(touched.mytoolsUserId()).isEqualTo("1001");
    }
}
