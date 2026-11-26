package com.yuyutian.mytools.toolbox.mapper.stats;

import com.yuyutian.mytools.toolbox.model.EventRow;
import com.yuyutian.mytools.toolbox.model.ToolCategory;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 事件明细与统计 Outbox 数据访问层：全部使用参数化 SQL，时间按 UTC 字面值读写。
 */
@Repository
public class EventMapper {

    /** Outbox 待处理状态。 */
    public static final String STATE_PENDING = "PENDING";

    /** Outbox 处理中状态。 */
    public static final String STATE_PROCESSING = "PROCESSING";

    /** Outbox 已聚合状态。 */
    public static final String STATE_APPLIED = "APPLIED";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /**
     * 构造数据访问对象。
     *
     * @param jdbcTemplate      Spring JDBC 模板
     * @param namedJdbcTemplate 具名参数 JDBC 模板
     */
    public EventMapper(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    /**
     * 插入事件明细并返回自增主键；违反 (site_id, guest_id, event_id) 唯一键时抛出重复键异常。
     *
     * @param row 事件行
     * @return 事件主键
     */
    public long insertEvent(EventRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO toolbox_event (site_id, guest_id, event_id, tool_id, category, event_type, source,"
                            + " operation_id, occurred_at, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, row.siteId());
            statement.setString(2, row.guestId());
            statement.setString(3, row.eventId());
            statement.setString(4, row.toolId());
            statement.setString(5, row.category().value());
            statement.setString(6, row.eventType());
            statement.setString(7, row.source());
            statement.setString(8, row.operationId());
            statement.setTimestamp(9, utc(row.occurredAt()));
            statement.setTimestamp(10, utc(row.receivedAt()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("eventIdNotGenerated");
        }
        return key.longValue();
    }

    /**
     * 插入成功语义去重记录；违反唯一键时抛出重复键异常，用于标记 duplicate。
     *
     * @param siteId      站点标识
     * @param source      事件来源
     * @param guestId     访客标识
     * @param toolId      工具标识
     * @param operationId 操作标识
     * @param createdAt   写入时间
     */
    public void insertSuccessDedupe(String siteId, String source, String guestId, String toolId,
                                    String operationId, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO toolbox_success_dedupe (site_id, source, guest_id, tool_id, operation_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                siteId, source, guestId, toolId, operationId, utc(createdAt));
    }

    /**
     * 插入统计 Outbox，与事件写入处于同一事务。
     *
     * @param eventPk 事件主键
     */
    public void insertOutbox(long eventPk) {
        jdbcTemplate.update(
                "INSERT INTO toolbox_stat_outbox (event_pk, state, attempts, lease_until) VALUES (?, ?, 0, NULL)",
                eventPk, STATE_PENDING);
    }

    /**
     * 把租约过期的处理中记录退回待处理，保证进程崩溃后可被重新接管。
     *
     * @param now 当前时间
     * @return 受影响行数
     */
    public int releaseExpiredLeases(Instant now) {
        return jdbcTemplate.update(
                "UPDATE toolbox_stat_outbox SET state = ?, lease_until = NULL"
                        + " WHERE state = ? AND lease_until IS NOT NULL AND lease_until < ?",
                STATE_PENDING, STATE_PROCESSING, utc(now));
    }

    /**
     * 取一批待处理事件主键。
     *
     * @param limit 批量大小
     * @return 事件主键列表，按主键升序
     */
    public List<Long> findPendingEventPks(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT event_pk FROM toolbox_stat_outbox WHERE state = ? ORDER BY event_pk LIMIT ?",
                Long.class, STATE_PENDING, limit);
    }

    /**
     * 以当前租约值占用指定事件，只有仍处于待处理状态的行会被占用。
     *
     * @param eventPks   事件主键
     * @param leaseUntil 租约到期时间
     * @return 受影响行数
     */
    public int claim(List<Long> eventPks, Instant leaseUntil) {
        if (eventPks.isEmpty()) {
            return 0;
        }
        Map<String, Object> parameters = Map.of(
                "pks", eventPks,
                "processing", STATE_PROCESSING,
                "pending", STATE_PENDING,
                "leaseUntil", utc(leaseUntil));
        return namedJdbcTemplate.update(
                "UPDATE toolbox_stat_outbox SET state = :processing, attempts = attempts + 1, lease_until = :leaseUntil"
                        + " WHERE state = :pending AND event_pk IN (:pks)",
                parameters);
    }

    /**
     * 查询本次已占用的事件明细。
     * 使用占用时返回的主键集合而不是租约时间：TIMESTAMP 列的精度低于 Instant，
     * 以时间相等作为条件会在真实数据库上匹配不到任何行，导致事件永远无法聚合。
     *
     * @param eventPks 本次占用成功的事件主键
     * @return 事件行列表，按事件主键升序
     */
    public List<EventRow> findClaimedEvents(List<Long> eventPks) {
        if (eventPks.isEmpty()) {
            return List.of();
        }
        Map<String, Object> parameters = Map.of("pks", eventPks, "processing", STATE_PROCESSING);
        return namedJdbcTemplate.query(
                "SELECT e.id, e.site_id, e.guest_id, e.event_id, e.tool_id, e.category, e.event_type, e.source,"
                        + " e.operation_id, e.occurred_at, e.received_at"
                        + " FROM toolbox_stat_outbox o JOIN toolbox_event e ON e.id = o.event_pk"
                        + " WHERE o.state = :processing AND o.event_pk IN (:pks) ORDER BY o.event_pk",
                parameters,
                (resultSet, rowNum) -> new EventRow(
                        resultSet.getLong("id"),
                        resultSet.getString("site_id"),
                        resultSet.getString("guest_id"),
                        resultSet.getString("event_id"),
                        resultSet.getString("tool_id"),
                        ToolCategory.fromJson(resultSet.getString("category")),
                        resultSet.getString("event_type"),
                        resultSet.getString("source"),
                        resultSet.getString("operation_id"),
                        instant(resultSet.getTimestamp("occurred_at")),
                        instant(resultSet.getTimestamp("received_at"))));
    }

    /**
     * 标记某事件已聚合完成。
     *
     * @param eventPk 事件主键
     * @return 受影响行数
     */
    public int markApplied(long eventPk) {
        return jdbcTemplate.update(
                "UPDATE toolbox_stat_outbox SET state = ?, lease_until = NULL WHERE event_pk = ?",
                STATE_APPLIED, eventPk);
    }

    /**
     * 返回最早一条尚未完成聚合的事件接收时间，用于推导聚合完整水位。
     *
     * @return 时间点或空
     */
    public Instant findOldestIncompleteReceivedAt() {
        List<Timestamp> values = jdbcTemplate.queryForList(
                "SELECT MIN(e.received_at) FROM toolbox_stat_outbox o JOIN toolbox_event e ON e.id = o.event_pk"
                        + " WHERE o.state <> ?",
                Timestamp.class, STATE_APPLIED);
        return values.isEmpty() ? null : instant(values.get(0));
    }

    /**
     * 返回最新一条事件的接收时间。
     *
     * @return 时间点或空
     */
    public Instant findNewestReceivedAt() {
        List<Timestamp> values = jdbcTemplate.queryForList(
                "SELECT MAX(received_at) FROM toolbox_event", Timestamp.class);
        return values.isEmpty() ? null : instant(values.get(0));
    }

    /**
     * 统计事件总数，仅用于测试与诊断。
     *
     * @return 事件总数
     */
    public long countEvents() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM toolbox_event", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 判断是否仍有未完成聚合的事件。
     *
     * @return 是否存在未完成事件
     */
    public boolean hasIncomplete() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM toolbox_stat_outbox WHERE state <> ?", Long.class, STATE_APPLIED);
        return count != null && count > 0;
    }

    /**
     * 把 Instant 转成 UTC 字面量时间戳，避免依赖 JVM 默认时区。
     *
     * @param instant 时间点
     * @return UTC 时间戳
     */
    static Timestamp utc(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * 把数据库时间戳按 UTC 字面值还原为 Instant。
     *
     * @param timestamp 数据库时间戳，可为空
     * @return 时间点或空
     */
    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
