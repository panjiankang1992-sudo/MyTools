package com.yuyutian.mytools.toolbox.mapper.session;

import com.yuyutian.mytools.toolbox.model.GuestRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 匿名访客会话数据访问层：使用 JdbcTemplate 参数化 SQL，时间统一按 UTC 字面值读写。
 */
@Repository
public class GuestMapper {

    /** 查询列固定顺序，避免执行计划随调用变化。 */
    private static final String COLUMNS =
            "owner_id, guest_id, token_hash, created_at, expires_at, revoked_at";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造数据访问对象。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public GuestMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条匿名会话，返回公开命名空间的自增 owner_id。
     *
     * @param guestId   访客标识
     * @param tokenHash 会话 token 的 SHA-256
     * @param createdAt 创建时间
     * @param expiresAt 到期时间
     * @return 自增 owner_id
     */
    public long insert(String guestId, byte[] tokenHash, Instant createdAt, Instant expiresAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO toolbox_guest (guest_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, guestId);
            statement.setBytes(2, tokenHash);
            statement.setTimestamp(3, utc(createdAt));
            statement.setTimestamp(4, utc(expiresAt));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("guestOwnerIdNotGenerated");
        }
        return key.longValue();
    }

    /**
     * 按 token 哈希查找会话，服务端不保存原始 token。
     *
     * @param tokenHash 会话 token 的 SHA-256
     * @return 会话行或空
     */
    public Optional<GuestRow> findByTokenHash(byte[] tokenHash) {
        List<GuestRow> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM toolbox_guest WHERE token_hash = ?",
                (resultSet, rowNum) -> new GuestRow(
                        resultSet.getLong("owner_id"),
                        resultSet.getString("guest_id"),
                        resultSet.getBytes("token_hash"),
                        instant(resultSet.getTimestamp("created_at")),
                        instant(resultSet.getTimestamp("expires_at")),
                        instant(resultSet.getTimestamp("revoked_at"))),
                tokenHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 撤销一个会话，用于到期或显式失效。
     *
     * @param ownerId   公开命名空间主键
     * @param revokedAt 撤销时间
     * @return 受影响行数
     */
    public int revoke(long ownerId, Instant revokedAt) {
        return jdbcTemplate.update(
                "UPDATE toolbox_guest SET revoked_at = ? WHERE owner_id = ? AND revoked_at IS NULL",
                utc(revokedAt), ownerId);
    }

    /**
     * 把 Instant 转成 UTC 字面量时间戳，避免依赖 JVM 默认时区。
     *
     * @param instant 时间点
     * @return UTC 时间戳
     */
    private static Timestamp utc(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * 把数据库时间戳按 UTC 字面值还原为 Instant。
     *
     * @param timestamp 数据库时间戳，可为空
     * @return 时间点或空
     */
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
