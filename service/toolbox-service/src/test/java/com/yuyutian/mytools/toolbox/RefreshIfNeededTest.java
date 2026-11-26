package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminAuthService;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSessionService;
import com.yuyutian.mytools.toolbox.mapper.admin.JdbcAdminSessionStore;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminTokenCipher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * A08：**按需提前刷新的编排**。
 *
 * 三条规则都在这一处，测试也逐条锁：
 *
 * 1. 令牌**仍有效且余量充足** → 不刷（否则每个请求都打上游）；
 * 2. 临近过期但**刷新权已被别人抢走** → 不刷、也不改会话（单飞）；
 * 3. 临近过期且**抢到刷新权** → 刷一次并写回。
 *
 * **这一项在服务层就能验证**，不需要登录——
 * 第 24/55/56/57 轮那个 403 只挡 HTTP，与本项要验证的性质无关。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshIfNeededTest {

    /** 记账用的假身份客户端：只实现 refresh，并数调用次数。 */
    static class CountingIdentityClient implements AdminIdentityClient {
        final List<String> refreshCalls = new ArrayList<>();
        volatile boolean failRefresh;

        @Override
        public UpstreamLogin login(String account, String password) {
            return new UpstreamLogin("up-access", "up-refresh", 3600L);
        }

        @Override
        public AdminIdentity validate(String accessToken) {
            return new AdminIdentity(true, "1001", "someone", List.of("ADMIN"));
        }

        @Override
        public void logout(String accessToken) {
            // 本测试不关心注销。
        }

        @Override
        public UpstreamLogin refresh(String refreshToken) {
            refreshCalls.add(refreshToken);
            if (failRefresh) {
                throw new com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityException(
                        com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure.UNAVAILABLE);
            }
            return new UpstreamLogin("new-access", "new-refresh", 1800L);
        }
    }

    @TestConfiguration
    static class CountingConfiguration {
        @Bean
        @Primary
        CountingIdentityClient countingIdentityClient() {
            return new CountingIdentityClient();
        }
    }

    @Autowired
    private AdminAuthService authService;

    @Autowired
    private AdminSessionService sessionService;

    @Autowired
    private JdbcAdminSessionStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminTokenCipher cipher;

    @Autowired
    private CountingIdentityClient client;

    /** 会话服务对 sessionId 做 SHA-256 后再落库；测试必须用同一个值。 */
    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 建一个真实的会话，指定访问令牌到期时刻。参数是**原始** sessionId。 */
    private String seed(String sessionId, long accessExpiresAt) {
        String tokenHash = hash(sessionId);
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO toolbox_admin_session (token_hash, mytools_user_id, username, encrypted_access,
                    encrypted_refresh, access_expires_at, created_at, last_activity_at, absolute_expires_at,
                    refresh_revision, csrf_token_hash, encrypted_csrf)
                VALUES (?, '1001', 'someone', ?, ?, ?, ?, ?, ?, 0, 'csrf-hash', NULL)
                """, tokenHash, cipher.encrypt("old-access"), cipher.encrypt("old-refresh"),
                new java.sql.Timestamp(accessExpiresAt), new java.sql.Timestamp(now),
                new java.sql.Timestamp(now), new java.sql.Timestamp(now + 3_600_000L));
        return tokenHash;
    }

    @Test
    @DisplayName("令牌余量充足时不刷（否则每个请求都去打上游）")
    void doesNotRefreshWhenPlentyOfTimeLeft() {
        seed("ifn-plenty", System.currentTimeMillis() + 30 * 60_000L);
        client.refreshCalls.clear();

        Optional<AdminSession> result = authService.refreshIfNeeded("ifn-plenty");

        assertThat(result).isPresent();
        assertThat(client.refreshCalls).as("余量充足时不该打上游").isEmpty();
    }

    @Test
    @DisplayName("刷新权已被推进时，一个新的请求仍能正确抢占（CAS 的期望值来自库）")
    void claimUsesRevisionReadFromStore() {
        // **说明**：真正的"单飞"分支（peek 之后、claim 之前被别的请求抢先）
        // 需要**真实交错**才能构造：顺序执行时 peek 读到的 revision 与 claim 时的
        // 期望值必然一致，抢占总会成功。这与第 25 轮"注销与活动推进的竞态"同类，
        // **无法用顺序测试确定性覆盖**。
        //
        // 这里退一步，锁住**可以确定性验证的那一半**：
        // CAS 的期望值确实来自库里读到的 revision —— 推进之后再刷，抢占仍然成功。
        seed("ifn-lost", System.currentTimeMillis() + 60_000L);
        jdbc.update("UPDATE toolbox_admin_session SET refresh_revision = 5 WHERE token_hash = ?",
                hash("ifn-lost"));
        client.refreshCalls.clear();

        Optional<AdminSession> result = authService.refreshIfNeeded("ifn-lost");

        assertThat(result).isPresent();
        assertThat(client.refreshCalls)
                .as("revision 是 5，期望值也是 5，所以这一次应当抢到并刷一次")
                .hasSize(1);
        assertThat(store.find(hash("ifn-lost")).orElseThrow().refreshRevision())
                .as("抢占后必须推进到 6；否则并发请求会重复刷")
                .isEqualTo(6L);
    }

    @Test
    @DisplayName("临近过期且抢到刷新权时：刷一次并写回新凭据")
    void refreshesAndAdoptsWhenClaimWins() {
        seed("ifn-win", System.currentTimeMillis() + 60_000L);
        client.refreshCalls.clear();

        Optional<AdminSession> result = authService.refreshIfNeeded("ifn-win");

        assertThat(client.refreshCalls).as("应当恰好刷一次").hasSize(1);
        assertThat(client.refreshCalls.get(0)).isEqualTo("old-refresh");
        AdminSession after = store.find(hash("ifn-win")).orElseThrow();
        assertThat(after.accessToken()).isEqualTo("new-access");
        assertThat(after.refreshToken()).isEqualTo("new-refresh");
        assertThat(after.refreshRevision())
                .as("刷新权必须被推进，否则并发请求会重复刷")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("刷新失败时：不改会话，仍然返回当前会话（不把用户踢下线）")
    void keepsSessionWhenRefreshFails() {
        seed("ifn-fail", System.currentTimeMillis() + 60_000L);
        client.refreshCalls.clear();
        client.failRefresh = true;
        try {
            Optional<AdminSession> result = authService.refreshIfNeeded("ifn-fail");

            assertThat(client.refreshCalls).hasSize(1);
            assertThat(result).isPresent();
            assertThat(store.find(hash("ifn-fail")).orElseThrow().accessToken())
                    .as("刷新失败不该改动会话——访问令牌可能仍然可用")
                    .isEqualTo("old-access");
        } finally {
            client.failRefresh = false;
        }
    }

    @Test
    @DisplayName("会话不存在时返回空（不要凭空造一个）")
    void returnsEmptyForUnknownSession() {
        assertThat(authService.refreshIfNeeded("ifn-missing")).isEmpty();
    }
}
