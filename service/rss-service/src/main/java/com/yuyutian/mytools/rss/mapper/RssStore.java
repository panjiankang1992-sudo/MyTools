package com.yuyutian.mytools.rss.mapper;

import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久化辅助与用户级事务互斥，保证配额、导入及阅读状态的一致性。 */
@Component
public class RssStore {
    final JdbcTemplate jdbc;
    final TransactionTemplate transaction;
    final Clock clock;
    /** 创建独立 RSS 数据库事务入口。 */
    public RssStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.clock = clock;
    }
    /** 在当前事务内串行化同一用户的状态写入，不持锁执行外部 HTTP。 */
    public void lockOwner(long owner) {
        if (owner <= 0) throw new RssException(ErrorCode.RSS_010);
        // 重复键也直接取得排他锁，避免 INSERT IGNORE 的共享锁在并发升级时发生死锁。
        jdbc.update("INSERT INTO rss_owner(owner_id,created_at) VALUES (?,?) ON DUPLICATE KEY UPDATE owner_id=owner_id", owner, clock.millis());
        jdbc.queryForObject("SELECT owner_id FROM rss_owner WHERE owner_id=? FOR UPDATE", Long.class, owner);
    }
    /** 检测请求使用持久冷却，重启或多实例不能绕过限流。 */
    public void reserveDiscovery(long owner) {
        transaction.executeWithoutResult(status -> {
            lockOwner(owner);
            if (jdbc.update("UPDATE rss_owner SET discover_after=? WHERE owner_id=? AND discover_after<=?", clock.millis() + 3000, owner, clock.millis()) == 0) throw new RssException(ErrorCode.RSS_007);
        });
    }
    /** 读取允许为空的毫秒时间。 */
    public static Long nullableLong(ResultSet row, String name) throws SQLException {
        long value = row.getLong(name);
        return row.wasNull() ? null : value;
    }
    /** 保证引用的分组确属当前用户。 */
    public void requireGroup(long owner, String group) {
        if (group != null && !group.isBlank() && jdbc.queryForObject("SELECT COUNT(*) FROM rss_group WHERE owner_id=? AND id=?", Long.class, owner, group) == 0) throw new RssException(ErrorCode.RSS_005);
    }
    /** 将空分组统一映射为数据库空值。 */
    public static String group(String value) { return value == null || value.isBlank() ? null : value; }
}
