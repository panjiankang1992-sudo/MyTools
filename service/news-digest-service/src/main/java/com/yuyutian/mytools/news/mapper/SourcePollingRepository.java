package com.yuyutian.mytools.news.mapper;

import com.yuyutian.mytools.news.Model.PipelineModels.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 单来源持久轮询游标与短租约，不在采集网络请求期间持有事务。 */
@Repository
public class SourcePollingRepository {
    private final JdbcTemplate jdbc;
    /** 创建来源轮询仓储。 */
    public SourcePollingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 首次启用立即安排当前材料采样，重复登记不改变既有退避。 */
    public void register(String id, Instant now) {
        try { jdbc.update("INSERT INTO news_source_poll(source_id, next_due_ms) VALUES (?, ?)", id, now.toEpochMilli()); }
        catch (DuplicateKeyException ignored) { /* 已有调度游标不可被扫描重置。 */ }
    }
    /** 仅领取已启用、到期且上游冷却结束的来源。 */
    public Optional<PollClaim> claim(Instant now) {
        var candidates = jdbc.query("SELECT p.source_id, p.failures FROM news_source_poll p JOIN news_source s ON s.id = p.source_id "
                + "WHERE s.enabled = TRUE AND p.next_due_ms <= ? AND (p.lease_until_ms IS NULL OR p.lease_until_ms <= ?) "
                + "AND (s.next_attempt_ms IS NULL OR s.next_attempt_ms <= ?) ORDER BY p.next_due_ms, p.source_id LIMIT 100",
                (row, index) -> new PollClaim(row.getString(1), UUID.randomUUID(), row.getInt(2)), now.toEpochMilli(), now.toEpochMilli(), now.toEpochMilli());
        for (PollClaim claim : candidates) {
            // 三分钟轮询租约长于底层两分钟采集租约，崩溃后不会立即重叠抓取。
            int updated = jdbc.update("UPDATE news_source_poll SET lease_token = ?, lease_until_ms = ? WHERE source_id = ? AND next_due_ms <= ? AND (lease_until_ms IS NULL OR lease_until_ms <= ?)",
                    claim.token().toString(), now.plusSeconds(180).toEpochMilli(), claim.sourceId(), now.toEpochMilli(), now.toEpochMilli());
            if (updated == 1) return Optional.of(claim);
        }
        return Optional.empty();
    }
    /** 发出网络请求前预留成功采样间隔，提交结果后进程崩溃也不密集重抓。 */
    public boolean reserve(PollClaim claim, Instant next, Instant now) {
        return jdbc.update("UPDATE news_source_poll SET next_due_ms = ? WHERE source_id = ? AND lease_token = ? AND lease_until_ms > ?",
                next.toEpochMilli(), claim.sourceId(), claim.token().toString(), now.toEpochMilli()) == 1;
    }

    /** 只有当前租约能写入下次采样时间，旧工作者不能覆盖新退避。 */
    public boolean finish(PollClaim claim, Instant now, Instant next, String status, int failures) {
        return jdbc.update("UPDATE news_source_poll SET next_due_ms = ?, failures = ?, last_status = ?, last_finished_ms = ?, lease_token = NULL, lease_until_ms = NULL "
                        + "WHERE source_id = ? AND lease_token = ? AND lease_until_ms > ?", next.toEpochMilli(), failures, status, now.toEpochMilli(),
                claim.sourceId(), claim.token().toString(), now.toEpochMilli()) == 1;
    }
    /** 管理查询只返回脱敏状态，最多最近的一百个来源。 */
    public List<PollState> states() {
        return jdbc.query("SELECT * FROM news_source_poll ORDER BY next_due_ms, source_id LIMIT 100", (row, index) ->
                new PollState(row.getString("source_id"), Instant.ofEpochMilli(row.getLong("next_due_ms")), row.getInt("failures"), row.getString("last_status"),
                        time(row.getObject("last_finished_ms", Long.class)), time(row.getObject("lease_until_ms", Long.class))));
    }
    private Instant time(Long epoch) { return epoch == null ? null : Instant.ofEpochMilli(epoch); }
}
