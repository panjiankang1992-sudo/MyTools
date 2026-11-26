package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.session.AdminTokenRefreshPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A08：提前刷新的判断。
 *
 * 这一条锁的是设计里那个**看起来矛盾**的要求：
 * 「令牌仍有效时提前刷新」与「禁止对任意 401 盲目刷新」。
 */
class AdminTokenRefreshPolicyTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long AHEAD = 60_000L;

    @Test
    @DisplayName("剩余时间充足时不刷（否则每个请求都去换令牌）")
    void doesNotRefreshWhenPlentyOfTimeLeft() {
        assertThat(AdminTokenRefreshPolicy.shouldRefresh(NOW + 10 * AHEAD, NOW, AHEAD)).isFalse();
    }

    @Test
    @DisplayName("仍有效但临近过期时提前刷（不要等它过期）")
    void refreshesAheadOfExpiry() {
        assertThat(AdminTokenRefreshPolicy.shouldRefresh(NOW + AHEAD / 2, NOW, AHEAD))
                .as("剩余时间进入阈值就该提前换，避免请求打到一半令牌过期")
                .isTrue();
    }

    @Test
    @DisplayName("已经过期时不刷——这就是「禁止对任意 401 盲目刷新」")
    void doesNotRefreshAfterExpiry() {
        // 走到这里说明令牌已经不可用；再拿它去换新令牌，
        // 要么被上游拒绝，要么让一条本该失败的请求"看起来成功了"——掩盖真实的授权问题。
        assertThat(AdminTokenRefreshPolicy.shouldRefresh(NOW - 1, NOW, AHEAD)).isFalse();
        assertThat(AdminTokenRefreshPolicy.shouldRefresh(NOW - 10 * AHEAD, NOW, AHEAD)).isFalse();
    }

    @Test
    @DisplayName("恰好到期算已失效（边界不能落进\"可刷新\"）")
    void exactlyAtExpiryCountsAsExpired() {
        assertThat(AdminTokenRefreshPolicy.shouldRefresh(NOW, NOW, AHEAD)).isFalse();
    }

    @Test
    @DisplayName("剩余时间恰好等于阈值时刷新（边界包含）")
    void exactlyAtThresholdRefreshes() {
        assertThat(AdminTokenRefreshPolicy.shouldRefresh(NOW + AHEAD, NOW, AHEAD)).isTrue();
    }
}
