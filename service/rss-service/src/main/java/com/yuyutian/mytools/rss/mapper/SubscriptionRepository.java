package com.yuyutian.mytools.rss.mapper;

import com.yuyutian.mytools.feed.FeedParser;
import com.yuyutian.mytools.feed.PublicNetworkPolicy;
import com.yuyutian.mytools.rss.Model.RssModels.*;
import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 用户订阅、分组及手动刷新请求的持久化。 */
@Repository
public class SubscriptionRepository {
    private final RssStore store;
    private static final String SELECT = "SELECT s.*, (SELECT COUNT(*) FROM rss_entry e JOIN rss_entry_state t ON t.owner_id=e.owner_id AND t.entry_id=e.id WHERE e.subscription_id=s.id AND t.is_read=FALSE AND e.content_purged_at IS NULL) AS unread_count FROM rss_subscription s";
    /** 注入独立数据库与事务。 */
    public SubscriptionRepository(RssStore store) { this.store = store; }

    /** 返回本人有效订阅与真实未读数量。 */
    public List<Subscription> list(long owner) {
        return store.jdbc.query(SELECT + " WHERE s.owner_id=? AND s.status<>'REMOVED' ORDER BY s.created_at,s.id", this::map, owner);
    }
    /** 获取本人单个有效订阅，不泄漏其他用户资源是否存在。 */
    public Subscription get(long owner, String id) {
        var values = store.jdbc.query(SELECT + " WHERE s.owner_id=? AND s.id=? AND s.status<>'REMOVED'", this::map, owner, id);
        if (values.isEmpty()) throw new RssException(ErrorCode.RSS_005);
        return values.getFirst();
    }
    /** 添加订阅或恢复软取消记录，重复添加不重置用户状态。 */
    public Subscription create(long owner, CreateSubscription request) {
        String url = FeedParser.canonical(PublicNetworkPolicy.requireHttps(request.url().strip()).toString());
        return store.transaction.execute(status -> {
            store.lockOwner(owner);
            var old = store.jdbc.queryForList("SELECT id,status FROM rss_subscription WHERE owner_id=? AND feed_url_hash=?", owner, FeedParser.hash(url));
            if (!old.isEmpty() && !old.getFirst().get("status").equals("REMOVED")) return get(owner, old.getFirst().get("id").toString());
            // 容量计数与写入共享用户锁，不能由并发添加突破上限。
            if (store.jdbc.queryForObject("SELECT COUNT(*) FROM rss_subscription WHERE owner_id=? AND status<>'REMOVED'", Long.class, owner) >= 100) throw new RssException(ErrorCode.RSS_012);
            store.requireGroup(owner, request.groupId());
            String id = old.isEmpty() ? UUID.randomUUID().toString() : old.getFirst().get("id").toString();
            String title = request.title() == null ? "" : request.title().strip();
            if (old.isEmpty()) {
                store.jdbc.update("INSERT INTO rss_subscription(id,owner_id,group_id,feed_url,feed_url_hash,title,custom_title,initial_read_all,created_at,next_fetch_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        id, owner, RssStore.group(request.groupId()), url, FeedParser.hash(url), java.net.URI.create(url).getHost(), title, request.initialReadAll(), store.clock.millis(), store.clock.millis());
            } else {
                store.jdbc.update("UPDATE rss_subscription SET status='ACTIVE',unsubscribed_at=NULL,next_fetch_at=?,revision=revision+1 WHERE id=? AND owner_id=?", store.clock.millis(), id, owner);
            }
            return get(owner, id);
        });
    }
    /** 更新设置并使旧采集租约失效，恢复不能绕过供应商冷却。 */
    public Subscription update(long owner, String id, UpdateSubscription request) {
        if (!Set.of("ACTIVE", "PAUSED").contains(request.status()) || !Set.of(15, 30, 60, 180).contains(request.intervalMinutes())) throw new RssException(ErrorCode.RSS_001);
        return store.transaction.execute(status -> {
            store.lockOwner(owner); get(owner, id); store.requireGroup(owner, request.groupId());
            int changed = store.jdbc.update("UPDATE rss_subscription SET custom_title=?,group_id=?,status=?,interval_minutes=?,revision=revision+1,lease_token=NULL,lease_until=0,next_fetch_at=GREATEST(?,retry_after) WHERE owner_id=? AND id=? AND revision=?",
                    request.title().strip(), RssStore.group(request.groupId()), request.status(), request.intervalMinutes(), store.clock.millis(), owner, id, request.expectedRevision());
            if (changed == 0) throw new RssException(ErrorCode.RSS_009);
            return get(owner, id);
        });
    }
    /** 取消更新并保留收藏，正在执行的采集不能再提交结果。 */
    public void remove(long owner, String id) {
        store.transaction.executeWithoutResult(status -> {
            store.lockOwner(owner);
            int changed = store.jdbc.update("UPDATE rss_subscription SET status='REMOVED',unsubscribed_at=?,lease_token=NULL,lease_until=0,revision=revision+1 WHERE owner_id=? AND id=?", store.clock.millis(), owner, id);
            if (changed == 0) throw new RssException(ErrorCode.RSS_005);
        });
    }
    /** 请求异步刷新，不直接访问网络，重复请求合并并保留 Retry-After。 */
    public Subscription refresh(long owner, String id) {
        return store.transaction.execute(status -> {
            store.lockOwner(owner);
            Subscription subscription = get(owner, id);
            if (!subscription.status().equals("ACTIVE") && !subscription.status().equals("GONE")) throw new RssException(ErrorCode.RSS_009);
            store.jdbc.update("UPDATE rss_subscription SET status='ACTIVE',next_fetch_at=GREATEST(?,retry_after),request_after=? WHERE owner_id=? AND id=? AND request_after<=? AND lease_until<=?",
                    store.clock.millis(), store.clock.millis() + 60_000, owner, id, store.clock.millis(), store.clock.millis());
            return get(owner, id);
        });
    }
    /** 返回本人分组。 */
    public List<Group> groups(long owner) {
        return store.jdbc.query("SELECT id,name,sort_order,revision FROM rss_group WHERE owner_id=? ORDER BY sort_order,name", (row, index) -> new Group(row.getString(1), row.getString(2), row.getInt(3), row.getLong(4)), owner);
    }
    /** 创建分组，重复名称返回既有分组。 */
    public Group createGroup(long owner, GroupChange request) {
        return store.transaction.execute(status -> {
            store.lockOwner(owner);
            var existing = groups(owner).stream().filter(group -> group.name().equals(request.name().strip())).findFirst();
            if (existing.isPresent()) return existing.orElseThrow();
            if (groups(owner).size() >= 100) throw new RssException(ErrorCode.RSS_012);
            String id = UUID.randomUUID().toString();
            store.jdbc.update("INSERT INTO rss_group(id,owner_id,name,sort_order,created_at) VALUES (?,?,?,?,?)", id, owner, request.name().strip(), request.sortOrder(), store.clock.millis());
            return new Group(id, request.name().strip(), request.sortOrder(), 0);
        });
    }
    /** 更新个人分组并检查版本。 */
    public Group updateGroup(long owner, String id, GroupChange request) {
        return store.transaction.execute(status -> {
            store.lockOwner(owner); store.requireGroup(owner, id);
            if (request.expectedRevision() == null) throw new RssException(ErrorCode.RSS_001);
            if (store.jdbc.update("UPDATE rss_group SET name=?,sort_order=?,revision=revision+1 WHERE owner_id=? AND id=? AND revision=?", request.name().strip(), request.sortOrder(), owner, id, request.expectedRevision()) == 0) throw new RssException(ErrorCode.RSS_009);
            return new Group(id, request.name().strip(), request.sortOrder(), request.expectedRevision() + 1);
        });
    }
    /** 删除分组时将订阅移至未分组，保留阅读状态。 */
    public void removeGroup(long owner, String id) {
        store.transaction.executeWithoutResult(status -> {
            store.lockOwner(owner); store.requireGroup(owner, id);
            store.jdbc.update("UPDATE rss_subscription SET group_id=NULL,revision=revision+1 WHERE owner_id=? AND group_id=?", owner, id);
            store.jdbc.update("DELETE FROM rss_group WHERE owner_id=? AND id=?", owner, id);
        });
    }

    private Subscription map(ResultSet row, int index) throws SQLException {
        return new Subscription(row.getString("id"), row.getString("group_id"), row.getString("feed_url"), row.getString("title"),
                row.getString("custom_title"), row.getString("site_url"), row.getString("description"), row.getString("status"),
                row.getInt("interval_minutes"), row.getBoolean("initialized"), RssStore.nullableLong(row, "last_attempt_at"),
                RssStore.nullableLong(row, "last_success_at"), row.getLong("next_fetch_at"), row.getString("last_error_code"),
                row.getLong("lease_until") > store.clock.millis(), row.getLong("revision"), row.getLong("unread_count"));
    }
}
