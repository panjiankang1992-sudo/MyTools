package com.yuyutian.mytools.rss.mapper;

import com.yuyutian.mytools.feed.FeedModels;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Repository;

/** 以数据库租约保证抓取结果的提交权限及重启恢复。 */
@Repository
public class PollRepository {
    /** 抓取时使用的不可变租约快照，不通过用户接口返回。 */
    public record Claim(String id, long owner, String url, String etag, String modified, String token,
                        boolean initialized, boolean initialReadAll, int interval, int failures) { }
    private final RssStore store;
    private final EntryRepository entries;
    /** 创建轮询仓储。 */
    public PollRepository(RssStore store, EntryRepository entries) { this.store = store; this.entries = entries; }

    /** 原子领取一个到期订阅，过期租约允许后续实例恢复。 */
    public Optional<Claim> claim() {
        var candidates = store.jdbc.query("SELECT id,owner_id FROM rss_subscription WHERE status='ACTIVE' AND next_fetch_at<=? AND lease_until<=? ORDER BY next_fetch_at,id LIMIT 16",
                (row, index) -> new Candidate(row.getString(1), row.getLong(2)), store.clock.millis(), store.clock.millis());
        for (var candidate : candidates) {
            Optional<Claim> claimed = store.transaction.execute(status -> {
                store.lockOwner(candidate.owner());
                String token = UUID.randomUUID().toString();
                int count = store.jdbc.update("UPDATE rss_subscription SET lease_token=?,lease_until=?,last_attempt_at=?,request_after=GREATEST(request_after,?) WHERE id=? AND owner_id=? AND status='ACTIVE' AND next_fetch_at<=? AND lease_until<=?",
                        token, store.clock.millis() + 60_000, store.clock.millis(), store.clock.millis() + 60_000, candidate.id(), candidate.owner(), store.clock.millis(), store.clock.millis());
                if (count == 0) return Optional.empty();
                Claim value = store.jdbc.queryForObject("SELECT * FROM rss_subscription WHERE id=? AND owner_id=?", (row, index) -> new Claim(row.getString("id"), row.getLong("owner_id"), row.getString("feed_url"), row.getString("etag"), row.getString("last_modified"), token,
                        row.getBoolean("initialized"), row.getBoolean("initial_read_all"), row.getInt("interval_minutes"), row.getInt("failure_count")), candidate.id(), candidate.owner());
                return Optional.ofNullable(value);
            });
            if (claimed != null && claimed.isPresent()) return claimed;
        }
        return Optional.empty();
    }
    /** 成功正文与条件请求标记同事务提交，旧租约不能写入。 */
    public boolean success(Claim claim, FeedModels.Fetch response, FeedModels.Feed feed) {
        return Boolean.TRUE.equals(store.transaction.execute(status -> {
            store.lockOwner(claim.owner());
            if (!valid(claim)) return false;
            if (feed != null) {
                entries.ingest(claim.owner(), claim.id(), feed, claim.initialized(), claim.initialReadAll());
                store.jdbc.update("UPDATE rss_subscription SET title=?,site_url=?,description=?,etag=?,last_modified=? WHERE id=? AND owner_id=?",
                        feed.title().isBlank() ? java.net.URI.create(claim.url()).getHost() : feed.title(), feed.siteUrl(), feed.description(), response.etag(), response.lastModified(), claim.id(), claim.owner());
            } else {
                store.jdbc.update("UPDATE rss_subscription SET etag=COALESCE(?,etag),last_modified=COALESCE(?,last_modified) WHERE id=? AND owner_id=?", response.etag(), response.lastModified(), claim.id(), claim.owner());
            }
            long next = store.clock.millis() + claim.interval() * 60_000L + ThreadLocalRandom.current().nextLong(30_001);
            store.jdbc.update("UPDATE rss_subscription SET initialized=TRUE,last_success_at=?,next_fetch_at=?,retry_after=0,failure_count=0,last_error_code=NULL,lease_token=NULL,lease_until=0 WHERE id=? AND owner_id=?", store.clock.millis(), next, claim.id(), claim.owner());
            return true;
        }));
    }
    /** 失败退避和供应商冷却同样受租约保护。 */
    public void failure(Claim claim, String code, long retryAfter, boolean gone) {
        store.transaction.executeWithoutResult(status -> {
            store.lockOwner(claim.owner());
            if (!valid(claim)) return;
            int failures = Math.min(30, claim.failures() + 1);
            long delay = Math.min(6 * 3600_000L, switch (failures) {
                case 1 -> 300_000L;
                case 2 -> 900_000L;
                default -> 1800_000L << Math.min(4, failures - 3);
            });
            store.jdbc.update("UPDATE rss_subscription SET status=?,next_fetch_at=?,retry_after=?,failure_count=?,last_error_code=?,lease_token=NULL,lease_until=0 WHERE id=? AND owner_id=?",
                    gone ? "GONE" : "ACTIVE", Math.max(store.clock.millis() + delay, retryAfter), retryAfter, failures, code, claim.id(), claim.owner());
        });
    }
    /** 同主机工作槽被占用时推迟扫描，不记作来源故障。 */
    public void defer(Claim claim) {
        store.jdbc.update("UPDATE rss_subscription SET lease_token=NULL,lease_until=0,next_fetch_at=? WHERE id=? AND owner_id=? AND lease_token=?", store.clock.millis() + 30_000, claim.id(), claim.owner(), claim.token());
    }
    /** 返回有限批次的账户供保留策略处理。 */
    public List<Long> owners(long after) {
        return store.jdbc.query("SELECT owner_id FROM rss_owner WHERE owner_id>? ORDER BY owner_id LIMIT 100", (row, index) -> row.getLong(1), after);
    }
    private boolean valid(Claim claim) {
        return store.jdbc.queryForObject("SELECT COUNT(*) FROM rss_subscription WHERE id=? AND owner_id=? AND status='ACTIVE' AND lease_token=? AND lease_until>?", Long.class, claim.id(), claim.owner(), claim.token(), store.clock.millis()) == 1;
    }
    private record Candidate(String id, long owner) { }
}
