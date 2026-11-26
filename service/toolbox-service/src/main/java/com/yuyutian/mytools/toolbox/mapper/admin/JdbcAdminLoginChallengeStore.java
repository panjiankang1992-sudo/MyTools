package com.yuyutian.mytools.toolbox.mapper.admin;

import com.yuyutian.mytools.toolbox.service.admin.session.AdminLoginChallengeStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 登录预认证挑战的数据库存储。
 * 一次性消费由 SQL 的条件更新保证：只有把 consumed_at 从 NULL 改成当前时间的调用者才算消费成功，
 * 因此并发或重放提交只会有一次生效。取代进程内实现后，重启与多实例都不会丢失挑战。
 */
@Repository
public class JdbcAdminLoginChallengeStore implements AdminLoginChallengeStore {

    /** 挑战有效期：10 分钟。 */
    private static final long TTL_MILLIS = 600_000L;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    /**
     * 创建数据库挑战存储。
     *
     * @param jdbc JDBC 模板
     */
    public JdbcAdminLoginChallengeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public IssuedChallenge issue() {
        purgeExpired();
        String cookieValue = randomToken();
        String nonce = randomToken();
        jdbc.update("""
                INSERT INTO toolbox_admin_login_challenge (cookie_hash, csrf_hash, expires_at, consumed_at, created_at)
                VALUES (?, ?, ?, NULL, ?)
                """,
                hash(cookieValue), hash(nonce),
                new Timestamp(System.currentTimeMillis() + TTL_MILLIS),
                new Timestamp(System.currentTimeMillis()));
        return new IssuedChallenge(cookieValue, nonce);
    }

    @Override
    public boolean consume(String cookieValue, String nonce) {
        if (cookieValue == null || nonce == null) {
            return false;
        }
        // 条件更新即"一次性"的判定：只有本次把 consumed_at 置为 NULL→非 NULL 的调用返回 1。
        int updated = jdbc.update("""
                UPDATE toolbox_admin_login_challenge
                SET consumed_at = ?
                WHERE cookie_hash = ? AND csrf_hash = ? AND consumed_at IS NULL AND expires_at > ?
                """,
                new Timestamp(System.currentTimeMillis()), hash(cookieValue), hash(nonce),
                new Timestamp(System.currentTimeMillis()));
        return updated == 1;
    }

    /** 清理过期或已消费的挑战，避免无界增长。 */
    private void purgeExpired() {
        jdbc.update("DELETE FROM toolbox_admin_login_challenge WHERE expires_at <= ? OR consumed_at IS NOT NULL",
                new Timestamp(System.currentTimeMillis()));
    }

    /** 生成随机标识。 */
    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 计算 SHA-256 十六进制摘要。 */
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
