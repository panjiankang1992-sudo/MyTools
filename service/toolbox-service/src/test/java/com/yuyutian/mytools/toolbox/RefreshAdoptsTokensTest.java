package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.mapper.admin.JdbcAdminSessionStore;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * A08 前置件：**刷新后的凭据能写回会话，而且会话不存在时什么都不做**。
 *
 * 这两条锁的是 {@link AdminSession#withRefreshedTokens} 与
 * {@link JdbcAdminSessionStore#adoptRefreshedTokens} 的行为。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshAdoptsTokensTest {

    @Autowired
    private JdbcAdminSessionStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.yuyutian.mytools.toolbox.service.admin.session.AdminTokenCipher cipher;

    /** 造一个真实的会话行（字段齐、约束满足）。 */
    private String seedSession(String tokenHash) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO toolbox_admin_session (token_hash, mytools_user_id, username, encrypted_access,
                    encrypted_refresh, access_expires_at, created_at, last_activity_at, absolute_expires_at,
                    refresh_revision, csrf_token_hash, encrypted_csrf)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, NULL)
                """, tokenHash, "1001", "someone", cipher.encrypt("old-access"), cipher.encrypt("old-refresh"),
                new java.sql.Timestamp(now + 60_000), new java.sql.Timestamp(now),
                new java.sql.Timestamp(now), new java.sql.Timestamp(now + 3_600_000), "csrf-hash");
        return tokenHash;
    }

    @Test
    @DisplayName("刷新后的凭据能写回，且其它字段（用户、CSRF、创建时间）保持不变")
    void adoptWritesTokensAndKeepsEverythingElse() {
        String key = seedSession("hash-adopt-ok");
        AdminSession before = store.find(key).orElseThrow();
        long newExpiry = System.currentTimeMillis() + 1_800_000L;

        // 仓储保存的是**密文**，所以传进去的也必须是密文。
        assertThat(store.adoptRefreshedTokens(key, cipher.encrypt("new-access"),
                cipher.encrypt("new-refresh"), newExpiry)).isEqualTo(1);

        AdminSession after = store.find(key).orElseThrow();
        assertThat(after.accessToken()).isEqualTo("new-access");
        assertThat(after.refreshToken()).isEqualTo("new-refresh");
        assertThat(after.accessExpiresAt()).isEqualTo(newExpiry);
        // 刷新**不是**登录：用户的身份与 CSRF 绑定必须原样。
        assertThat(after.mytoolsUserId()).isEqualTo(before.mytoolsUserId());
        assertThat(after.csrfTokenHash()).isEqualTo(before.csrfTokenHash());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        // 刷新**不是**用户活动：活动时间不该被推进。
        assertThat(after.lastActivityAt()).isEqualTo(before.lastActivityAt());
    }

    @Test
    @DisplayName("会话已被注销时，刷新凭据不得把它复活")
    void adoptDoesNothingWhenSessionIsGone() {
        String key = seedSession("hash-adopt-gone");
        jdbc.update("DELETE FROM toolbox_admin_session WHERE token_hash = ?", key);
        assertThat(store.find(key)).isEmpty();

        assertThat(store.adoptRefreshedTokens(key, cipher.encrypt("new-access"), cipher.encrypt("new-refresh"),
                System.currentTimeMillis() + 60_000L))
                .as("行不在了就该影响 0 行")
                .isZero();
        assertThat(store.find(key))
                .as("刷新不能把已注销的会话复活——否则用户'退不掉'")
                .isEmpty();
    }

    @Test
    @DisplayName("A08：find 必须读回真实的 refreshRevision（编排的 CAS 期望值靠它）")
    void findReturnsRefreshRevision() {
        // 第 64 轮编排撞上的就是这个：record 上**没有** refreshRevision，
        // 于是编排拿不到 CAS 的期望值。这里锁住"读得回来"。
        String key = seedSession("hash-revision-read");
        assertThat(store.find(key).orElseThrow().refreshRevision())
                .as("新会话的 revision 应当是 0")
                .isZero();

        jdbc.update("UPDATE toolbox_admin_session SET refresh_revision = ? WHERE token_hash = ?", 7L, key);

        assertThat(store.find(key).orElseThrow().refreshRevision())
                .as("find 必须读回库里的真实值；否则编排永远拿 0 去 CAS，必然抢不到")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("A08：CAS 推进后 revision 会随 find 一起回来")
    void claimAdvancesRevisionVisibleThroughFind() {
        String key = seedSession("hash-revision-claim");
        long before = store.find(key).orElseThrow().refreshRevision();

        assertThat(store.claimRefresh(key, before, before + 1)).isEqualTo(1);

        assertThat(store.find(key).orElseThrow().refreshRevision()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("withRefreshedTokens 只换令牌，保留会话标识与期限")
    void withRefreshedTokensKeepsIdentityAndDeadlines() {
        String key = seedSession("hash-adopt-view");
        AdminSession before = store.find(key).orElseThrow();
        AdminSession after = before.withRefreshedTokens("a2", "r2", before.accessExpiresAt() + 1000);

        assertThat(after.accessToken()).isEqualTo("a2");
        assertThat(after.refreshToken()).isEqualTo("r2");
        assertThat(after.sessionIdHash()).isEqualTo(before.sessionIdHash());
        assertThat(after.absoluteExpiresAt()).isEqualTo(before.absoluteExpiresAt());
        assertThat(after.csrfTokenHash()).isEqualTo(before.csrfTokenHash());
    }

    @Test
    @DisplayName("上游没给新刷新令牌时沿用旧的，而不是把它清空")
    void withRefreshedTokensKeepsOldRefreshTokenWhenUpstreamOmitsIt() {
        String key = seedSession("hash-adopt-keep");
        AdminSession before = store.find(key).orElseThrow();
        AdminSession after = before.withRefreshedTokens("a3", null, before.accessExpiresAt() + 1000);

        assertThat(after.refreshToken())
                .as("清空刷新令牌会让下一次无法刷新——必须沿用旧的")
                .isEqualTo(before.refreshToken());
    }
}
