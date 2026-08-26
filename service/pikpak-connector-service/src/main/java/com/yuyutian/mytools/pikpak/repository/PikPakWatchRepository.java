package com.yuyutian.mytools.pikpak.repository;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PikPak 固定目录 watcher 的持久化检查点仓储。 */
@Repository
public class PikPakWatchRepository {
    private final JdbcTemplate jdbc;

    /** 创建仓储。 @param jdbc JDBC 模板 */
    public PikPakWatchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 幂等保存 watcher 配置。 @param request 请求 @return 配置 */
    public Watcher register(RegisterWatcherRequest request) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO pikpak_watcher(account_id,watch_root,backup_root,enabled,stable_seconds,process_existing,
            baseline_completed,created_at,updated_at) VALUES (?,?,?,?,?,?,FALSE,?,?)
            ON DUPLICATE KEY UPDATE watch_root=VALUES(watch_root),
            backup_root=VALUES(backup_root),enabled=VALUES(enabled),stable_seconds=VALUES(stable_seconds),
            process_existing=VALUES(process_existing),updated_at=VALUES(updated_at)
            """, request.accountId().toString(), request.watchRoot(), request.backupRoot(), request.enabled(),
            request.stableSeconds(), request.processExisting(), Timestamp.from(now), Timestamp.from(now));
        return requireWatcher(request.accountId());
    }

    /** 读取账户 watcher。 @param accountId 账户 @return 配置 */
    public Watcher requireWatcher(UUID accountId) {
        return jdbc.query("SELECT * FROM pikpak_watcher WHERE account_id=?", (rs, row) ->
            new Watcher(UUID.fromString(rs.getString("account_id")), rs.getString("watch_root"),
                rs.getString("backup_root"), rs.getBoolean("enabled"), rs.getInt("stable_seconds"),
                rs.getBoolean("process_existing"), rs.getBoolean("baseline_completed")),
            accountId.toString()).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("PIKPAK_WATCHER_NOT_FOUND"));
    }

    /** 列出已启用 watcher。 @return 配置 */
    public List<Watcher> enabledWatchers() {
        return jdbc.query("SELECT * FROM pikpak_watcher WHERE enabled=TRUE ORDER BY account_id", (rs, row) ->
            new Watcher(UUID.fromString(rs.getString("account_id")), rs.getString("watch_root"),
                rs.getString("backup_root"), true, rs.getInt("stable_seconds"),
                rs.getBoolean("process_existing"), rs.getBoolean("baseline_completed")));
    }

    /** 读取明确忽略的历史批次路径。 @param accountId 账户 @return 路径 */
    public Set<String> baselinedPaths(UUID accountId) {
        return new HashSet<>(jdbc.queryForList("""
            SELECT batch_path FROM pikpak_watch_batch
            WHERE account_id=? AND error_code='PIKPAK_WATCH_BASELINED'
            """, String.class, accountId.toString()));
    }

    /** 查询批次。 @param accountId 账户 @param path 批次路径 @return 批次 */
    public Optional<WatchBatch> find(UUID accountId, String path) {
        return jdbc.query("SELECT * FROM pikpak_watch_batch WHERE account_id=? AND batch_path=?",
            this::mapBatch, accountId.toString(), path).stream().findFirst();
    }

    /** 读取批次。 @param id 批次 @return 批次 */
    public WatchBatch requireBatch(UUID id) {
        return jdbc.query("SELECT * FROM pikpak_watch_batch WHERE id=?", this::mapBatch, id.toString())
            .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("PIKPAK_WATCH_BATCH_NOT_FOUND"));
    }

    /** 创建观察批次。 @param accountId 账户 @param path 路径 @param signature 签名 @param now 时间 @return 批次 */
    public WatchBatch insert(UUID accountId, String path, String signature, Instant now, boolean baseline) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pikpak_watch_batch VALUES (?,?,?,?,?,'OBSERVING',NULL,?,0,?,?)",
            id.toString(), accountId.toString(), path, signature, Timestamp.from(now),
            baseline ? "PIKPAK_WATCH_BASELINED" : null,
            Timestamp.from(now), Timestamp.from(now));
        return requireBatch(id);
    }

    /** 标记 watcher 已完成历史目录基线。 @param accountId 账户 */
    public void completeBaseline(UUID accountId) {
        jdbc.update("UPDATE pikpak_watcher SET baseline_completed=TRUE,updated_at=? WHERE account_id=?",
            Timestamp.from(Instant.now()), accountId.toString());
    }

    /** 原子更新批次。 @param current 当前值 @param signature 签名 @param stableSince 稳定时间 @param phase 阶段 @param jobId 任务 @param error 错误 @return 批次 */
    public WatchBatch transition(WatchBatch current, String signature, Instant stableSince,
                                 String phase, Long jobId, String error) {
        int changed = jdbc.update("""
            UPDATE pikpak_watch_batch SET signature=?,stable_since=?,phase=?,remote_job_id=?,error_code=?,
            version=version+1,updated_at=? WHERE id=? AND version=?
            """, signature, Timestamp.from(stableSince), phase, jobId, error, Timestamp.from(Instant.now()),
            current.id().toString(), current.version());
        if (changed != 1) throw new IllegalStateException("PIKPAK_WATCH_CONCURRENT_UPDATE");
        return requireBatch(current.id());
    }

    /** 替换批次对象快照。 @param batchId 批次 @param items 对象 */
    public void replaceItems(UUID batchId, List<RemoteItem> items) {
        jdbc.update("DELETE FROM pikpak_watch_item WHERE batch_id=?", batchId.toString());
        for (RemoteItem item : items) jdbc.update("INSERT INTO pikpak_watch_item VALUES (?,?,?,?,?)",
            batchId.toString(), item.remoteFileId(), item.relativePath(), item.sizeBytes(), item.modifiedAt());
    }

    /** 读取批次对象。 @param batchId 批次 @return 对象 */
    public List<RemoteItem> items(UUID batchId) {
        return jdbc.query("SELECT remote_file_id,relative_path,size_bytes,modified_at FROM pikpak_watch_item WHERE batch_id=? ORDER BY relative_path",
            (rs, row) -> new RemoteItem(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)),
            batchId.toString());
    }

    private WatchBatch mapBatch(ResultSet rs, int row) throws SQLException {
        long job = rs.getLong("remote_job_id");
        Long remoteJobId = rs.wasNull() ? null : job;
        return new WatchBatch(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("account_id")),
            rs.getString("batch_path"), rs.getString("signature"), rs.getTimestamp("stable_since").toInstant(),
            rs.getString("phase"), remoteJobId, rs.getString("error_code"), rs.getLong("version"));
    }
}
