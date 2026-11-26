package com.yuyutian.mytools.toolbox.mapper.stats;

import com.yuyutian.mytools.toolbox.model.DailyStatBucket;
import com.yuyutian.mytools.toolbox.model.ToolRankingRow;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 统计读取与聚合数据访问层：增量更新采用先更新后插入的可移植写法，重复执行不会重复累加。
 */
@Repository
public class StatsMapper {

    /** 无明确工具标识的入口事件使用的固定桶键，避免 NULL 破坏唯一约束语义。 */
    public static final String CATEGORY_ONLY_TOOL_KEY = "__category__";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造数据访问对象。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public StatsMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入已聚合标记；事件已聚合时抛出重复键异常，调用方据此跳过累加。
     *
     * @param eventPk   事件主键
     * @param appliedAt 聚合时间
     */
    public void insertApplied(long eventPk, Instant appliedAt) {
        jdbcTemplate.update(
                "INSERT INTO toolbox_stat_applied (event_pk, applied_at) VALUES (?, ?)",
                eventPk, EventMapper.utc(appliedAt));
    }

    /**
     * 对日聚合桶自增 1，不存在时插入。
     *
     * @param localDate 北京时间自然日
     * @param category  工具分类
     * @param toolIdKey 工具桶键，无工具时为 __category__
     * @param eventType 事件类型
     */
    public void incrementDailyStat(LocalDate localDate, String category, String toolIdKey, String eventType) {
        int updated = jdbcTemplate.update(
                "UPDATE toolbox_daily_stat SET count = count + 1 WHERE local_date = ? AND category = ?"
                        + " AND tool_id_key = ? AND event_type = ?",
                Date.valueOf(localDate), category, toolIdKey, eventType);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO toolbox_daily_stat (local_date, category, tool_id_key, event_type, count)"
                            + " VALUES (?, ?, ?, ?, 1)",
                    Date.valueOf(localDate), category, toolIdKey, eventType);
        } catch (DuplicateKeyException exception) {
            // 并发插入时改为更新，保证最终只累加一次。
            jdbcTemplate.update(
                    "UPDATE toolbox_daily_stat SET count = count + 1 WHERE local_date = ? AND category = ?"
                            + " AND tool_id_key = ? AND event_type = ?",
                    Date.valueOf(localDate), category, toolIdKey, eventType);
        }
    }

    /**
     * 读取聚合完整水位。
     *
     * @param siteId 站点标识
     * @return 水位时间点或空
     */
    public Optional<Instant> findCompleteThrough(String siteId) {
        List<Timestamp> values = jdbcTemplate.queryForList(
                "SELECT complete_through FROM toolbox_stat_cursor WHERE site_id = ?", Timestamp.class, siteId);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(EventMapper.instant(values.get(0)));
    }

    /**
     * 推进聚合完整水位，只允许单调前进。
     *
     * @param siteId          站点标识
     * @param completeThrough 完整水位
     * @param now             更新时间
     */
    public void advanceCursor(String siteId, Instant completeThrough, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE toolbox_stat_cursor SET complete_through = ?, updated_at = ?"
                        + " WHERE site_id = ? AND (complete_through IS NULL OR complete_through < ?)",
                EventMapper.utc(completeThrough), EventMapper.utc(now), siteId, EventMapper.utc(completeThrough));
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO toolbox_stat_cursor (site_id, complete_through, updated_at) VALUES (?, ?, ?)",
                    siteId, EventMapper.utc(completeThrough), EventMapper.utc(now));
        } catch (DuplicateKeyException exception) {
            // 并发下已有水位行，保持已有值即可。
            jdbcTemplate.update(
                    "UPDATE toolbox_stat_cursor SET complete_through = ?, updated_at = ?"
                            + " WHERE site_id = ? AND (complete_through IS NULL OR complete_through < ?)",
                    EventMapper.utc(completeThrough), EventMapper.utc(now), siteId, EventMapper.utc(completeThrough));
        }
    }

    /**
     * 汇总指定日期区间与分类的事件次数。
     *
     * @param from      起始自然日，含
     * @param to        结束自然日，含
     * @param category  工具分类，null 表示全部分类
     * @param eventType 事件类型
     * @return 次数合计
     */
    public long sumCount(LocalDate from, LocalDate to, String category, String eventType) {
        String sql = "SELECT COALESCE(SUM(count), 0) FROM toolbox_daily_stat"
                + " WHERE local_date >= ? AND local_date <= ? AND event_type = ?"
                + (category == null ? "" : " AND category = ?");
        Object[] args = category == null
                ? new Object[] {Date.valueOf(from), Date.valueOf(to), eventType}
                : new Object[] {Date.valueOf(from), Date.valueOf(to), eventType, category};
        Long total = jdbcTemplate.queryForObject(sql, Long.class, args);
        return total == null ? 0L : total;
    }

    /**
     * 汇总全历史的事件次数，累计值只来自长期日聚合行。
     *
     * @param category  工具分类，null 表示全部分类
     * @param eventType 事件类型
     * @return 累计次数
     */
    public long sumAllTime(String category, String eventType) {
        String sql = "SELECT COALESCE(SUM(count), 0) FROM toolbox_daily_stat WHERE event_type = ?"
                + (category == null ? "" : " AND category = ?");
        Object[] args = category == null ? new Object[] {eventType} : new Object[] {eventType, category};
        Long total = jdbcTemplate.queryForObject(sql, Long.class, args);
        return total == null ? 0L : total;
    }

    /**
     * 查询日期区间内的日聚合分桶行。
     *
     * @param from     起始自然日，含
     * @param to       结束自然日，含
     * @param category 工具分类，null 表示全部分类
     * @return 分桶行列表
     */
    public List<DailyStatBucket> findSeries(LocalDate from, LocalDate to, String category) {
        String sql = "SELECT local_date, event_type, SUM(count) AS total FROM toolbox_daily_stat"
                + " WHERE local_date >= ? AND local_date <= ?"
                + (category == null ? "" : " AND category = ?")
                + " GROUP BY local_date, event_type ORDER BY local_date, event_type";
        Object[] args = category == null
                ? new Object[] {Date.valueOf(from), Date.valueOf(to)}
                : new Object[] {Date.valueOf(from), Date.valueOf(to), category};
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> new DailyStatBucket(
                resultSet.getDate("local_date").toLocalDate(),
                resultSet.getString("event_type"),
                resultSet.getLong("total")), args);
    }

    /**
     * 查询日期区间内按工具的成功使用排名。
     *
     * @param from     起始自然日，含
     * @param to       结束自然日，含
     * @param category 工具分类，null 表示全部分类
     * @return 排名行列表，按成功次数降序、工具标识升序
     */
    public List<ToolRankingRow> findRanking(LocalDate from, LocalDate to, String category) {
        String sql = "SELECT tool_id_key, SUM(count) AS total FROM toolbox_daily_stat"
                + " WHERE local_date >= ? AND local_date <= ? AND event_type = 'worksheet_success'"
                + " AND tool_id_key <> '" + CATEGORY_ONLY_TOOL_KEY + "'"
                + (category == null ? "" : " AND category = ?")
                + " GROUP BY tool_id_key ORDER BY total DESC, tool_id_key ASC";
        Object[] args = category == null
                ? new Object[] {Date.valueOf(from), Date.valueOf(to)}
                : new Object[] {Date.valueOf(from), Date.valueOf(to), category};
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> new ToolRankingRow(
                resultSet.getString("tool_id_key"),
                resultSet.getLong("total")), args);
    }
}
