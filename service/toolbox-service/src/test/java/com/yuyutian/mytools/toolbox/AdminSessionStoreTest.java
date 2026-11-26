package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 数据库管理会话存储的真实往返测试。
 * 重点：上游令牌不得以明文出现在库中；会话在"重启"（重新读取）后仍可用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminSessionStoreTest {

    @Autowired
    private AdminSessionStore store;

    @Autowired
    private JdbcTemplate jdbc;

    /** 构造一条两小时后到期的会话。 */
    private static AdminSession session(String hash, String userId, String accessToken) {
        long now = System.currentTimeMillis();
        return new AdminSession(hash, userId, "someone", accessToken, "refresh-" + accessToken,
                now + 7_200_000L, now, now, now + 28_800_000L, "csrf-hash-" + hash);
    }

    @Test
    @DisplayName("会话可保存并原样读回，上游令牌完成加解密往返")
    void roundTripKeepsTokensUsable() {
        AdminSession saved = session("hash-a", "1001", "access-token-plain");
        store.save(saved);
        AdminSession loaded = store.find("hash-a").orElseThrow();
        assertThat(loaded.mytoolsUserId()).isEqualTo("1001");
        assertThat(loaded.accessToken()).isEqualTo("access-token-plain");
        assertThat(loaded.refreshToken()).isEqualTo("refresh-access-token-plain");
        assertThat(loaded.csrfTokenHash()).isEqualTo("csrf-hash-hash-a");
    }

    @Test
    @DisplayName("库中不得出现明文上游令牌")
    void tokensAreEncryptedAtRest() {
        store.save(session("hash-b", "1002", "must-not-appear-in-db"));
        String stored = jdbc.queryForObject(
                "SELECT encrypted_access FROM toolbox_admin_session WHERE token_hash = ?", String.class, "hash-b");
        assertThat(stored).doesNotContain("must-not-appear-in-db");
        assertThat(stored).isNotBlank();
    }

    @Test
    @DisplayName("重复保存推进活动时间而不是插入第二行")
    void saveIsUpsert() {
        store.save(session("hash-c", "1003", "token-c"));
        store.save(session("hash-c", "1003", "token-c"));
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_admin_session WHERE token_hash = ?", Integer.class, "hash-c");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("按用户清理会移除该账号的全部会话")
    void removeByUserClearsAllSessions() {
        store.save(session("hash-d1", "1004", "token-d1"));
        store.save(session("hash-d2", "1004", "token-d2"));
        store.save(session("hash-e", "1005", "token-e"));
        store.removeByUser("1004");
        assertThat(store.find("hash-d1")).isEmpty();
        assertThat(store.find("hash-d2")).isEmpty();
        assertThat(store.find("hash-e")).isPresent();
    }
}
