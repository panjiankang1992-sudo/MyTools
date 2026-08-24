package com.yuyutian.mytools.messaging.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * IMAP UID 检查点仓储。
 */
@Repository
public class EmailPollCheckpointRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建检查点仓储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public EmailPollCheckpointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询账户邮箱检查点。
     *
     * @param accountKey 账户逻辑键
     * @param mailbox 邮箱名称
     * @return 检查点
     */
    public Optional<Checkpoint> find(String accountKey, String mailbox) {
        return jdbcTemplate.query("""
                SELECT uid_validity, last_uid FROM email_poll_checkpoint
                WHERE account_key = ? AND mailbox_name = ?
                """, (resultSet, rowNumber) -> new Checkpoint(resultSet.getLong("uid_validity"),
                resultSet.getLong("last_uid")), accountKey, mailbox).stream().findFirst();
    }

    /**
     * 仅向前保存同一 UIDVALIDITY 下的检查点。
     *
     * @param accountKey 账户逻辑键
     * @param mailbox 邮箱名称
     * @param uidValidity UIDVALIDITY
     * @param lastUid 最后 UID
     */
    public void save(String accountKey, String mailbox, long uidValidity, long lastUid) {
        Optional<Checkpoint> existing = find(accountKey, mailbox);
        if (existing.isEmpty()) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO email_poll_checkpoint
                            (account_key, mailbox_name, uid_validity, last_uid, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, accountKey, mailbox, uidValidity, lastUid, Timestamp.from(Instant.now()));
                return;
            } catch (DuplicateKeyException ignored) {
                // 并发写入转入条件更新。
            }
        }
        Checkpoint current = find(accountKey, mailbox).orElseThrow();
        if (current.uidValidity() != uidValidity) {
            jdbcTemplate.update("""
                    UPDATE email_poll_checkpoint SET uid_validity = ?, last_uid = ?, updated_at = ?
                    WHERE account_key = ? AND mailbox_name = ? AND uid_validity = ?
                    """, uidValidity, lastUid, Timestamp.from(Instant.now()), accountKey, mailbox,
                    current.uidValidity());
            return;
        }
        jdbcTemplate.update("""
                UPDATE email_poll_checkpoint SET last_uid = ?, updated_at = ?
                WHERE account_key = ? AND mailbox_name = ? AND uid_validity = ? AND last_uid < ?
                """, lastUid, Timestamp.from(Instant.now()), accountKey, mailbox, uidValidity, lastUid);
    }

    /**
     * IMAP 检查点值。
     *
     * @param uidValidity UIDVALIDITY
     * @param lastUid 最后 UID
     */
    public record Checkpoint(long uidValidity, long lastUid) {
    }
}
