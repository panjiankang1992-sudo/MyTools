package com.yuyutian.mytools.news.mapper;

import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.CollectionCapacityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 与采集提交和清理共用事务行锁，避免多实例同时超额写入。 */
@Repository
public class CollectionCapacityRepository {
    private final JdbcTemplate jdbc;
    private final CollectionCapacityProperties limits;

    /** 注入持久额度和当前进程配置。 */
    public CollectionCapacityRepository(JdbcTemplate jdbc, CollectionCapacityProperties limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    /** 必须在业务事务取得其他采集行锁之前调用，统一锁顺序。 */
    public void lock() {
        requireTransaction();
        jdbc.queryForObject("SELECT id FROM news_collection_capacity WHERE id=1 FOR UPDATE", Integer.class);
    }

    /** 修改事务内额度；重复行不调用此方法，释放额度不受降低后的限额阻碍。 */
    public void adjust(long articles, long snapshots, long withdrawals, long bytes) {
        requireTransaction();
        long[] used = jdbc.queryForObject("SELECT article_rows,snapshot_rows,withdrawal_rows,payload_bytes "
                + "FROM news_collection_capacity WHERE id=1 FOR UPDATE", (row, index) -> new long[]{
                    row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4)});
        if (used == null) throw new NewsException(ErrorCode.NEWS_009);
        long[] changes = {articles, snapshots, withdrawals, bytes};
        long[] maximum = {limits.articleRows(), limits.snapshotRows(), limits.withdrawalRows(), limits.payloadBytes()};
        for (int index = 0; index < used.length; index++) {
            // 负值表示计费与数据不一致，整体回滚而不能把计数归零掩盖问题。
            used[index] = Math.addExact(used[index], changes[index]);
            if (used[index] < 0) throw new NewsException(ErrorCode.NEWS_009);
            if (changes[index] > 0 && used[index] > maximum[index]) throw new NewsException(ErrorCode.NEWS_028);
        }
        jdbc.update("UPDATE news_collection_capacity SET article_rows=?,snapshot_rows=?,withdrawal_rows=?,payload_bytes=? WHERE id=1",
                used[0], used[1], used[2], used[3]);
    }

    /** 返回计费字节数；文章预留到期标记空间，避免清理反而申请新额度。 */
    public static long payloadBytes(String payload, boolean article) {
        return payload.getBytes(StandardCharsets.UTF_8).length + (article ? 128L : 0L);
    }

    /** 仅公开聚合用量和本进程额度，不读取文章正文或用户标识。 */
    public Map<String, Object> status() {
        // 同一查询读取账本和持久行，避免并发提交发生在两次查询之间产生假差异。
        return jdbc.queryForObject("SELECT article_rows,snapshot_rows,withdrawal_rows,payload_bytes,"
                + "(SELECT COUNT(*) FROM news_article),(SELECT COUNT(*) FROM news_github_snapshot),"
                + "(SELECT COUNT(*) FROM news_source_withdrawal),"
                + "(SELECT COALESCE(SUM(capacity_bytes),0) FROM news_article)+(SELECT COALESCE(SUM(capacity_bytes),0) FROM news_github_snapshot) "
                + "FROM news_collection_capacity WHERE id=1", (row,index) -> {
                    Map<String,Long> used=Map.of("articleRows",row.getLong(1),"snapshotRows",row.getLong(2),
                            "withdrawalRows",row.getLong(3),"payloadBytes",row.getLong(4));
                    Map<String,Long> measured=Map.of("articleRows",row.getLong(5),"snapshotRows",row.getLong(6),
                            "withdrawalRows",row.getLong(7),"payloadBytes",row.getLong(8));
                    return Map.of("scope","COLLECTION_DATA","used",used,"measured",measured,
                            "accountingMatches",used.equals(measured),"limits",limits);
                });
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new NewsException(ErrorCode.NEWS_009);
    }
}
