package com.yuyutian.mytools.news.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.GithubSnapshot;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 六小时桶内仅保留首次快照，由采集提交事务统一写入。 */
@Repository
public class GithubSnapshotRepository {
    private static final long BUCKET_MILLIS = Duration.ofHours(6).toMillis();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CollectionCapacityRepository capacity;
    private final TransactionTemplate tx;

    /** 创建 GitHub 聚合快照仓储。 */
    public GithubSnapshotRepository(JdbcTemplate jdbc, ObjectMapper json, CollectionCapacityRepository capacity, TransactionTemplate tx) {
        this.jdbc = jdbc; this.json = json; this.capacity = capacity; this.tx = tx;
    }

    /** 在调用方事务中插入快照，重复桶返回已有原始值。 */
    public GithubSnapshot save(String sourceId, GithubSnapshot snapshot) {
        return tx.execute(status -> {
            capacity.lock();
            return saveLocked(sourceId, snapshot);
        });
    }

    private GithubSnapshot saveLocked(String sourceId, GithubSnapshot snapshot) {
        long bucket = Math.floorDiv(snapshot.observedAt().toEpochMilli(), BUCKET_MILLIS) * BUCKET_MILLIS;
        String payload = encode(snapshot);
        long bytes = CollectionCapacityRepository.payloadBytes(payload, false);
        try {
            jdbc.update("INSERT INTO news_github_snapshot(source_id, repository_id, bucket_ms, observed_ms, full_name, stars, forks, payload_json, capacity_bytes) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", sourceId, snapshot.repositoryId(), bucket,
                    snapshot.observedAt().toEpochMilli(), snapshot.fullName(), snapshot.stars(), snapshot.forks(), payload, bytes);
            capacity.adjust(0, 1, 0, bytes);
            return snapshot;
        } catch (DuplicateKeyException exception) {
            // 重试不能用同桶后来的更高星数覆盖已观察的证据。
            return jdbc.queryForObject("SELECT payload_json FROM news_github_snapshot WHERE source_id = ? AND repository_id = ? AND bucket_ms = ?",
                    (row, index) -> decode(row.getString(1)), sourceId, snapshot.repositoryId(), bucket);
        }
    }

    /** 查询可比端点及连续性所需的八日历史，不读取截止后数据。 */
    public List<GithubSnapshot> history(String sourceId, long repositoryId, Instant cutoff) {
        return jdbc.query("SELECT payload_json FROM news_github_snapshot WHERE source_id = ? AND repository_id = ? "
                        + "AND observed_ms >= ? AND observed_ms <= ? ORDER BY observed_ms, bucket_ms",
                (row, index) -> decode(row.getString(1)), sourceId, repositoryId,
                cutoff.minus(Duration.ofDays(8)).toEpochMilli(), cutoff.toEpochMilli());
    }

    /** 保持一个有界的旧仓库观察队列，避免发现榜暂时变化就完全丢失基线。 */
    public List<GithubSnapshot> tracked(String sourceId, Instant cutoff, int limit) {
        if (limit == 0) return List.of();
        return jdbc.query("SELECT a.payload_json FROM news_github_snapshot a WHERE a.source_id = ? "
                        + "AND a.observed_ms >= ? AND a.observed_ms <= ? "
                        + "AND NOT EXISTS (SELECT 1 FROM news_github_snapshot b WHERE b.source_id = a.source_id "
                        + "AND b.repository_id = a.repository_id AND b.observed_ms > a.observed_ms AND b.observed_ms <= ?) "
                        + "ORDER BY (SELECT MIN(c.observed_ms) FROM news_github_snapshot c WHERE c.source_id = a.source_id "
                        + "AND c.repository_id = a.repository_id), a.repository_id LIMIT ?",
                (row, index) -> decode(row.getString(1)), sourceId, cutoff.minus(Duration.ofDays(14)).toEpochMilli(),
                cutoff.toEpochMilli(), cutoff.toEpochMilli(), limit);
    }

    private String encode(GithubSnapshot snapshot) {
        try { return json.writeValueAsString(snapshot); }
        catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }

    private GithubSnapshot decode(String value) {
        try { return json.readValue(value, GithubSnapshot.class); }
        catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }
}
