package com.yuyutian.mytools.toolbox.mapper.admin;

import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSessionStore;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminTokenCipher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 管理会话的数据库存储。
 * 上游令牌在写入前用 {@link AdminTokenCipher} 加密，读取时解密；库中不出现明文令牌。
 * 本实现取代进程内存储，使会话在服务重启后仍然有效。
 */
@Repository
@Primary
public class JdbcAdminSessionStore implements AdminSessionStore {

    private final JdbcTemplate jdbc;
    private final AdminTokenCipher cipher;

    /**
     * 创建数据库会话存储。
     *
     * @param jdbc   JDBC 模板
     * @param cipher 上游令牌加密器
     */
    public JdbcAdminSessionStore(JdbcTemplate jdbc, AdminTokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void save(AdminSession session) {
        // 主键冲突即更新：会话活动时间会被频繁推进，用 upsert 避免先删后插的空窗。
        jdbc.update("""
                INSERT INTO toolbox_admin_session (token_hash, mytools_user_id, username,
                    encrypted_access, encrypted_refresh, access_expires_at, created_at,
                    last_activity_at, absolute_expires_at, refresh_revision, csrf_token_hash, encrypted_csrf)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE mytools_user_id = VALUES(mytools_user_id),
                    username = VALUES(username), encrypted_access = VALUES(encrypted_access),
                    encrypted_refresh = VALUES(encrypted_refresh),
                    access_expires_at = VALUES(access_expires_at),
                    last_activity_at = VALUES(last_activity_at),
                    absolute_expires_at = VALUES(absolute_expires_at),
                    refresh_revision = VALUES(refresh_revision),
                    csrf_token_hash = VALUES(csrf_token_hash),
                    encrypted_csrf = VALUES(encrypted_csrf)
                """,
                session.sessionIdHash(), session.mytoolsUserId(), session.username(),
                cipher.encrypt(session.accessToken()), cipher.encrypt(session.refreshToken()),
                new Timestamp(session.accessExpiresAt()), new Timestamp(session.createdAt()),
                new Timestamp(session.lastActivityAt()), new Timestamp(session.absoluteExpiresAt()),
                session.refreshRevision(), session.csrfTokenHash(), session.csrfTokenSealed());
    }


    /**
     * **只推进活动时间，且只在会话仍然存在时**（F13）。
     *
     * 为什么不能用 `save`（INSERT ... ON DUPLICATE KEY UPDATE）：
     * 那是 **upsert**——如果会话在这条请求读它之后、写它之前被**注销删除**了，
     * upsert 会把一个**已经注销的会话重新插回去**。
     * 于是"我明明退出了，下一跳请求却还是登录状态"。
     *
     * 这里用**纯 UPDATE**：行不在了就什么都不做，注销不会被旧请求撤销。
     *
     * @param sessionIdHash 会话标识哈希
     * @param now           当前时刻（毫秒）
     * @return 实际更新的行数；0 表示会话已不存在（例如已被注销）
     */
    public int touchIfPresent(String sessionIdHash, long now) {
        return jdbc.update("""
                UPDATE toolbox_admin_session
                   SET last_activity_at = ?
                 WHERE token_hash = ?
                """, new Timestamp(now), sessionIdHash);
    }

    @Override
    public int claimRefresh(String sessionIdHash, long expected, long next) {
        // **一条 UPDATE 完成"判断 + 抢占"**：
        // 分成"先读再写"两步就会有两个请求同时通过判断——那正是要避免的。
        // 这里的 `AND refresh_revision = ?` 让第二个请求的更新行数为 0。
        return jdbc.update("""
                UPDATE toolbox_admin_session
                   SET refresh_revision = ?
                 WHERE token_hash = ?
                   AND refresh_revision = ?
                """, next, sessionIdHash, expected);
    }

    @Override
    public int adoptRefreshedTokens(String sessionIdHash, String accessTokenSealed, String refreshTokenSealed,
                                    long accessExpiresAt) {
        // 与 claimRefresh 同理：**只 UPDATE，不 INSERT**。
        // 会话若已被注销，行不在了就什么都不做——刷新**不能**把注销撤销掉。
        return jdbc.update("""
                UPDATE toolbox_admin_session
                   SET encrypted_access = ?, encrypted_refresh = ?, access_expires_at = ?
                 WHERE token_hash = ?
                """, accessTokenSealed, refreshTokenSealed, new Timestamp(accessExpiresAt), sessionIdHash);
    }

    @Override
    public Optional<AdminSession> find(String sessionIdHash) {
        List<AdminSession> found = jdbc.query(
                "SELECT * FROM toolbox_admin_session WHERE token_hash = ?", ROW_MAPPER, sessionIdHash);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public void remove(String sessionIdHash) {
        jdbc.update("DELETE FROM toolbox_admin_session WHERE token_hash = ?", sessionIdHash);
    }

    @Override
    public void removeByUser(String mytoolsUserId) {
        jdbc.update("DELETE FROM toolbox_admin_session WHERE mytools_user_id = ?", mytoolsUserId);
    }

    /** 把结果集行映射为会话，并解密上游令牌。 */
    private final RowMapper<AdminSession> ROW_MAPPER = (ResultSet rs, int rowNum) -> mapRow(rs);

    /** 单行映射。 */
    private AdminSession mapRow(ResultSet rs) throws SQLException {
        return new AdminSession(
                rs.getString("token_hash"),
                rs.getString("mytools_user_id"),
                rs.getString("username"),
                cipher.decrypt(rs.getString("encrypted_access")),
                cipher.decrypt(rs.getString("encrypted_refresh")),
                rs.getTimestamp("access_expires_at").getTime(),
                rs.getTimestamp("created_at").getTime(),
                rs.getTimestamp("last_activity_at").getTime(),
                rs.getTimestamp("absolute_expires_at").getTime(),
                rs.getString("csrf_token_hash"),
                rs.getString("encrypted_csrf"),
                rs.getLong("refresh_revision"));
    }
}
