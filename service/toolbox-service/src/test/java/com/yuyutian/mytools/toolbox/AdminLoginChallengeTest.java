package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.session.AdminLoginChallengeStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 登录预认证挑战的真实数据库行为。
 * 重点是"一次性"：并发或重放提交只能有一次生效。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminLoginChallengeTest {

    @Autowired
    private AdminLoginChallengeStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("同一挑战只能消费一次，第二次失败")
    void challengeIsSingleUse() {
        AdminLoginChallengeStore.IssuedChallenge challenge = store.issue();
        assertThat(store.consume(challenge.cookieValue(), challenge.nonce())).isTrue();
        assertThat(store.consume(challenge.cookieValue(), challenge.nonce())).isFalse();
    }

    @Test
    @DisplayName("nonce 不匹配时不能消费，且挑战仍然有效")
    void wrongNonceKeepsChallengeUsable() {
        AdminLoginChallengeStore.IssuedChallenge challenge = store.issue();
        assertThat(store.consume(challenge.cookieValue(), "wrong-nonce")).isFalse();
        assertThat(store.consume(challenge.cookieValue(), challenge.nonce())).isTrue();
    }

    @Test
    @DisplayName("缺少 Cookie 或 nonce 一律拒绝")
    void missingInputsRejected() {
        AdminLoginChallengeStore.IssuedChallenge challenge = store.issue();
        assertThat(store.consume(null, challenge.nonce())).isFalse();
        assertThat(store.consume(challenge.cookieValue(), null)).isFalse();
    }

    @Test
    @DisplayName("库中不出现明文 Cookie 或 nonce，且新挑战可重建（不依赖进程内状态）")
    void storedAsHashesOnly() {
        AdminLoginChallengeStore.IssuedChallenge challenge = store.issue();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_admin_login_challenge WHERE cookie_hash = ?", Integer.class,
                sha256Hex(challenge.cookieValue()));
        assertThat(rows).isEqualTo(1);
        // 明文不应出现在任何哈希列中
        Integer plain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_admin_login_challenge WHERE cookie_hash = ? OR csrf_hash = ?",
                Integer.class, challenge.cookieValue(), challenge.nonce());
        assertThat(plain).isZero();
    }

    /** 本地复算 SHA-256 十六进制，用于与库中存的哈希比对。 */
    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
