package com.yuyutian.mytools.rss.mapper;

import com.yuyutian.mytools.feed.FeedModels;
import com.yuyutian.mytools.feed.FeedParser;
import com.yuyutian.mytools.rss.Model.RssModels.*;
import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 文章身份、状态与稳定游标查询，所有访问均限定 owner。 */
@Repository
public class EntryRepository {
    private final RssStore store;
    private static final String FROM = " FROM rss_entry e JOIN rss_subscription s ON s.owner_id=e.owner_id AND s.id=e.subscription_id JOIN rss_entry_state t ON t.owner_id=e.owner_id AND t.entry_id=e.id";
    private static final String COLUMNS = "SELECT e.id,e.subscription_id,CASE WHEN s.custom_title='' THEN s.title ELSE s.custom_title END AS source_title,e.title,e.author,e.url,e.summary_text,e.image_url,e.published_at,e.source_updated_at,e.discovered_at,t.is_read,t.starred_at,t.revision,";
    /** 创建用户文章仓储。 */
    public EntryRepository(RssStore store) { this.store = store; }

    /** 在已验证租约的事务内写入 Feed，不使用标题或正文哈希冒充原生身份。 */
    public void ingest(long owner, String subscription, FeedModels.Feed feed, boolean initialized, boolean initialReadAll) {
        List<FeedModels.Entry> values = feed.entries();
        // 逆序插入保持 Feed 中最新条目位于自增游标前端。
        for (int index = values.size() - 1; index >= 0; index--) {
            var item = values.get(index);
            var existing = store.jdbc.queryForList("SELECT id,content_purged_at FROM rss_entry WHERE owner_id=? AND subscription_id=? AND identity_type=? AND identity_hash=?", owner, subscription, item.identityType(), item.identityHash());
            if (!existing.isEmpty()) {
                if (existing.getFirst().get("content_purged_at") == null) {
                    store.jdbc.update("UPDATE rss_entry SET title=?,author=?,url=?,summary_text=?,content_html=?,image_url=?,published_at=?,source_updated_at=?,content_hash=? WHERE owner_id=? AND id=?",
                            item.title(), item.author(), item.url(), item.summary(), item.contentHtml(), item.imageUrl(), millis(item.publishedAt()), millis(item.updatedAt()), item.contentHash(), owner, existing.getFirst().get("id"));
                }
                continue; // 修订不修改首次发现时间、已读或收藏。
            }
            var key = new GeneratedKeyHolder();
            store.jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO rss_entry(owner_id,subscription_id,identity_type,identity_hash,source_id,url,title,author,summary_text,content_html,image_url,published_at,source_updated_at,discovered_at,content_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                Object[] arguments = {owner, subscription, item.identityType(), item.identityHash(), item.sourceId(), item.url(), item.title(), item.author(), item.summary(), item.contentHtml(), item.imageUrl(), millis(item.publishedAt()), millis(item.updatedAt()), store.clock.millis(), item.contentHash()};
                for (int field = 0; field < arguments.length; field++) statement.setObject(field + 1, arguments[field]);
                return statement;
            }, key);
            Number id = key.getKey();
            if (id == null) throw new RssException(ErrorCode.RSS_011);
            boolean read = !initialized && (initialReadAll || index >= 50);
            store.jdbc.update("INSERT INTO rss_entry_state(owner_id,entry_id,is_read,read_at) VALUES (?,?,?,?)", owner, id.longValue(), read, read ? store.clock.millis() : null);
        }
    }

    /** 返回有界列表，游标绑定筛选条件和首屏快照。 */
    public EntryPage list(long owner, Filter filter, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new RssException(ErrorCode.RSS_001);
        Query query = filter(owner, filter);
        long snapshot = store.jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM rss_entry WHERE owner_id=?", Long.class, owner);
        long before = Long.MAX_VALUE;
        String fingerprint = FeedParser.hash(owner + "|" + query.where() + "|" + query.arguments()).substring(0, 24);
        if (cursor != null && !cursor.isBlank()) {
            try {
                if (cursor.length() > 160) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split(":");
                if (parts.length != 3 || !parts[2].equals(fingerprint)) throw new IllegalArgumentException();
                snapshot = Long.parseLong(parts[0]); before = Long.parseLong(parts[1]);
                if (snapshot < 0 || before < 1 || before > snapshot) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) { throw new RssException(ErrorCode.RSS_001); }
        }
        var arguments = new ArrayList<>(query.arguments());
        arguments.add(snapshot); arguments.add(before); arguments.add(limit + 1);
        var items = store.jdbc.query(COLUMNS + "'' AS content_html" + FROM + query.where() + " AND e.id<=? AND e.id<? ORDER BY e.id DESC LIMIT ?", this::map, arguments.toArray());
        String next = "";
        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((snapshot + ":" + items.getLast().id() + ":" + fingerprint).getBytes(StandardCharsets.US_ASCII));
        }
        return new EntryPage(List.copyOf(items), next);
    }

    /** 读取详情不产生已读副作用，取消订阅后只有收藏仍可读。 */
    public Entry get(long owner, long id) {
        var items = store.jdbc.query(COLUMNS + "e.content_html" + FROM + " WHERE e.owner_id=? AND e.id=? AND (s.status<>'REMOVED' OR t.starred_at IS NOT NULL)", this::map, owner, id);
        if (items.isEmpty()) throw new RssException(ErrorCode.RSS_005);
        return items.getFirst();
    }
    /** 原子设置状态，过期版本不能覆盖其他设备的选择。 */
    public Entry state(long owner, long id, StateChange change) {
        return store.transaction.execute(status -> {
            store.lockOwner(owner); get(owner, id);
            if (store.jdbc.update("UPDATE rss_entry_state SET is_read=?,read_at=?,starred_at=CASE WHEN ? THEN COALESCE(starred_at,?) ELSE NULL END,revision=revision+1 WHERE owner_id=? AND entry_id=? AND revision=?",
                    change.read(), change.read() ? store.clock.millis() : null, change.starred(), store.clock.millis(), owner, id, change.expectedRevision()) == 0) throw new RssException(ErrorCode.RSS_009);
            // 取消订阅收藏时仍返回已变更的状态，不因随后失去可见性回滚操作。
            return store.jdbc.queryForObject(COLUMNS + "e.content_html" + FROM + " WHERE e.owner_id=? AND e.id=?", this::map, owner, id);
        });
    }
    /** 用户锁内获取确定集合，新采集条目在事务之后入库并保持未读。 */
    public Map<String, Integer> markRead(long owner, MarkRead request) {
        return store.transaction.execute(status -> {
            store.lockOwner(owner);
            Query query = filter(owner, new Filter(request.subscriptionId(), request.groupId(), "unread", request.q()));
            var ids = store.jdbc.query("SELECT e.id" + FROM + query.where(), (row, index) -> row.getLong(1), query.arguments().toArray());
            int changed = 0;
            for (Long id : ids) changed += store.jdbc.update("UPDATE rss_entry_state SET is_read=TRUE,read_at=?,revision=revision+1 WHERE owner_id=? AND entry_id=? AND is_read=FALSE", store.clock.millis(), owner, id);
            return Map.of("updated", changed, "conflicts", 0);
        });
    }
    /** 返回首页统计，普通未读不包含取消订阅或已清理条目。 */
    public Counts counts(long owner) {
        long subscriptions = store.jdbc.queryForObject("SELECT COUNT(*) FROM rss_subscription WHERE owner_id=? AND status<>'REMOVED'", Long.class, owner);
        long unread = store.jdbc.queryForObject("SELECT COUNT(*)" + FROM + " WHERE e.owner_id=? AND s.status<>'REMOVED' AND e.content_purged_at IS NULL AND t.is_read=FALSE", Long.class, owner);
        long starred = store.jdbc.queryForObject("SELECT COUNT(*) FROM rss_entry_state WHERE owner_id=? AND starred_at IS NOT NULL", Long.class, owner);
        return new Counts(subscriptions, unread, starred);
    }
    /** 分批清理旧正文并保留去重身份，收藏永不被后台清理。 */
    public int purge(long owner) {
        return store.transaction.execute(status -> {
            store.lockOwner(owner);
            var ids = store.jdbc.query("SELECT e.id" + FROM + " WHERE e.owner_id=? AND e.discovered_at<? AND e.content_purged_at IS NULL AND t.starred_at IS NULL ORDER BY e.id LIMIT 500", (row, index) -> row.getLong(1), owner, store.clock.millis() - 90L * 86400_000);
            for (Long id : ids) store.jdbc.update("UPDATE rss_entry SET content_html='',summary_text='',image_url='',content_purged_at=? WHERE owner_id=? AND id=?", store.clock.millis(), owner, id);
            return ids.size();
        });
    }

    private Query filter(long owner, Filter filter) {
        String view = filter.view() == null ? "all" : filter.view();
        if (!Set.of("all", "unread", "starred").contains(view) || (filter.q() != null && filter.q().length() > 100)) throw new RssException(ErrorCode.RSS_001);
        StringBuilder sql = new StringBuilder(" WHERE e.owner_id=? AND (e.content_purged_at IS NULL OR t.starred_at IS NOT NULL)");
        var args = new ArrayList<Object>(); args.add(owner);
        if (view.equals("starred")) sql.append(" AND t.starred_at IS NOT NULL");
        else sql.append(" AND s.status<>'REMOVED'");
        if (view.equals("unread")) sql.append(" AND t.is_read=FALSE");
        if (filter.subscriptionId() != null && !filter.subscriptionId().isBlank()) { sql.append(" AND e.subscription_id=?"); args.add(filter.subscriptionId()); }
        if (filter.groupId() != null && !filter.groupId().isBlank()) { sql.append(" AND s.group_id=?"); args.add(filter.groupId()); }
        if (filter.q() != null && !filter.q().isBlank()) {
            sql.append(" AND (LOWER(e.title) LIKE ? ESCAPE '!' OR LOWER(e.summary_text) LIKE ? ESCAPE '!')");
            String term = "%" + filter.q().strip().toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.add(term); args.add(term);
        }
        return new Query(sql.toString(), args);
    }

    private Entry map(ResultSet row, int index) throws SQLException {
        return new Entry(Long.toString(row.getLong("id")), row.getString("subscription_id"), row.getString("source_title"),
                row.getString("title"), row.getString("author"), row.getString("url"), row.getString("summary_text"),
                row.getString("content_html"), row.getString("image_url"), RssStore.nullableLong(row, "published_at"),
                RssStore.nullableLong(row, "source_updated_at"), row.getLong("discovered_at"), row.getBoolean("is_read"),
                RssStore.nullableLong(row, "starred_at") != null, row.getLong("revision"));
    }
    private static Long millis(Instant value) { return value == null ? null : value.toEpochMilli(); }
    private record Query(String where, List<Object> arguments) { }
}
