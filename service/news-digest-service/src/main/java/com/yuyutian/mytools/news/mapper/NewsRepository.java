package com.yuyutian.mytools.news.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import com.yuyutian.mytools.news.utils.TechnicalSourcePolicy;
import com.yuyutian.mytools.news.service.technical.GithubTrendCalculator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 持久化不可变文章版本和日报，所有动态值均通过参数绑定。 */
@Repository
public class NewsRepository {
    private static final int MAX_CANDIDATES = 10000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final GithubSnapshotRepository githubSnapshots;
    private final CollectionCapacityRepository capacity;
    private final ControlCapacityRepository control;
    private final RegistryCapacityRepository registry;

    /** 注入数据库、序列化器和短事务执行器。 */
    public NewsRepository(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate transaction, GithubSnapshotRepository githubSnapshots, CollectionCapacityRepository capacity, ControlCapacityRepository control, RegistryCapacityRepository registry) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = transaction;
        this.githubSnapshots = githubSnapshots;
        this.capacity = capacity;
        this.control = control;
        this.registry = registry;
    }

    /** 列出受控来源及其健康状态。 */
    public List<SourceState> sources() {
        return jdbc.query("SELECT * FROM news_source ORDER BY id", (row, index) -> new SourceState(
                decode(row.getString("spec_json"), SourceSpec.class), row.getString("etag"),
                row.getString("last_modified"), instant(row.getObject("last_checked_ms", Long.class)),
                instant(row.getObject("newest_published_ms", Long.class)), row.getString("health"),
                instant(row.getObject("next_attempt_ms", Long.class)), row.getString("coverage_json") == null
                ? null : decode(row.getString("coverage_json"), CollectionCoverage.class), instant(row.getObject("newest_observed_ms", Long.class))));
    }

    /** 获取来源，不存在时返回统一错误。 */
    public SourceState source(String id) {
        return sources().stream().filter(source -> source.spec().id().equals(id)).findFirst()
                .orElseThrow(() -> new NewsException(ErrorCode.NEWS_003));
    }

    /** 登记来源，已有来源只能切换启停，内容政策变更须使用新来源标识。 */
    public SourceSpec saveSource(SourceSpec spec) {
        TechnicalSourcePolicy.validate(spec);
        return transaction.execute(status->{
            registry.lock();
            String encoded=encode(spec);
            List<SourceSpec> existing=jdbc.query("SELECT spec_json FROM news_source WHERE id=? FOR UPDATE",
                    (row,index)->decode(row.getString(1),SourceSpec.class),spec.id());
            if(existing.isEmpty()) {
                // 先确认身份不存在才扣新来源额度，登记失败与计费一起回滚。
                RegistryCapacityRepository.boundedPayload(encoded,false);
                registry.reserve(1,0,0,0,0);
                jdbc.update("INSERT INTO news_source(id,spec_json,enabled,health,capacity_recorded) VALUES(?,?,?,'NEVER_FETCHED',TRUE)",spec.id(),encoded,spec.enabled());
                return spec;
            }
            SourceSpec prior=existing.getFirst();
            SourceSpec withEnabled=new SourceSpec(prior.id(),prior.name(),prior.feedUrl(),prior.category(),prior.publisherKey(),prior.regionCode(),
                    prior.evidencePolicy(),prior.rightsReference(),prior.authority(),spec.enabled(),prior.kind());
            if(!spec.equals(withEnabled))throw new NewsException(ErrorCode.NEWS_004);
            // 满额仍允许启停既有来源，并让旧采集租约失效。
            jdbc.update("UPDATE news_source SET spec_json=?,enabled=?,lease_token=NULL,lease_until_ms=NULL WHERE id=?",encoded,spec.enabled(),spec.id());
            return spec;
        });
    }

    /** 取得有期限的采集租约，不在网络访问期间持有数据库连接。 */
    public boolean acquireCollection(String sourceId, String token, Instant now) {
        return jdbc.update("UPDATE news_source SET lease_token = ?, lease_until_ms = ? WHERE id = ? AND enabled = TRUE "
                        + "AND (lease_until_ms IS NULL OR lease_until_ms < ?) AND (next_attempt_ms IS NULL OR next_attempt_ms <= ?)",
                token, now.plusSeconds(120).toEpochMilli(), sourceId, now.toEpochMilli(), now.toEpochMilli()) == 1;
    }

    /** 原子保存采集结果和条件请求标记，旧租约不能写入。 */
    public int finishCollection(String sourceId, String token, Instant now, List<ArticleInput> input,
                                String etag, String modified, String health, Instant newest) {
        return finishBatch(sourceId, token, now, new CollectedBatch(input.stream()
                .map(article -> new CollectedArticle(article, null)).toList(), List.of(), null), etag, modified, health, newest);
    }

    /** 同一租约事务中保存技术文章、趋势快照和覆盖审计。 */
    public int finishTechnicalCollection(String sourceId, String token, Instant now, CollectedBatch batch,
                                         String health, Instant newest) {
        return finishBatch(sourceId, token, now, batch, null, null, health, newest);
    }

    private int finishBatch(String sourceId, String token, Instant now, CollectedBatch batch,
                            String etag, String modified, String health, Instant newest) {
        return Objects.requireNonNull(transaction.execute(status -> {
            capacity.lock();
            List<String> leases = jdbc.query("SELECT lease_token FROM news_source WHERE id = ? AND lease_until_ms >= ? FOR UPDATE",
                    (row, index) -> row.getString(1), sourceId, now.toEpochMilli());
            if (leases.isEmpty() || !token.equals(leases.getFirst())) throw new NewsException(ErrorCode.NEWS_010);
            SourceSpec spec = source(sourceId).spec();
            for (String withdrawn : batch.withdrawnIds()) {
                // 撤稿只影响观测之后的新榜单，已保存日报继续保持原样。
                try {
                    jdbc.update("INSERT INTO news_source_withdrawal(source_id, external_id, observed_ms, capacity_recorded) VALUES (?, ?, ?, TRUE)",
                            sourceId, withdrawn, now.toEpochMilli());
                    capacity.adjust(0, 0, 1, 0);
                } catch (DuplicateKeyException ignored) {
                    // 相同观测时刻的撤稿重放不产生重复记录。
                }
            }
            List<CollectedArticle> articles = new ArrayList<>(batch.articles());
            for (GithubSnapshot incoming : batch.snapshots()) {
                // 元数据来源不保存描述正文，快照指标和文章引用保持同一个真实采样版本。
                GithubSnapshot sanitized = new GithubSnapshot(incoming.repositoryId(), incoming.fullName(), incoming.url(), incoming.createdAt(),
                        incoming.pushedAt(), spec.evidencePolicy() == EvidencePolicy.EXCERPT_ALLOWED ? incoming.description() : "",
                        incoming.stars(), incoming.forks(), incoming.observedAt());
                GithubSnapshot stored = githubSnapshots.save(sourceId, sanitized);
                GithubTrend trend = GithubTrendCalculator.calculate(githubSnapshots.history(sourceId, stored.repositoryId(), stored.observedAt()), stored.observedAt());
                TechnicalMetadata metadata = new TechnicalMetadata(Long.toString(stored.repositoryId()), stored.url(), stored.observedAt(), stored.stars(), 0, trend);
                long comparableGrowth = trend.status() == TrendStatus.READY ? Math.max(0, trend.netStars()) : 0;
                articles.add(new CollectedArticle(new ArticleInput(stored.url(), stored.fullName(), stored.createdAt(), stored.description(), comparableGrowth), metadata));
            }
            int inserted = ingestLocked(spec, articles, now);
            Instant newestObserved = batch.snapshots().stream().map(GithubSnapshot::observedAt).max(Instant::compareTo).orElse(null);
            jdbc.update("UPDATE news_source SET etag = ?, last_modified = ?, last_checked_ms = ?, health = ?, "
                            + "newest_published_ms = ?, next_attempt_ms = ?, coverage_json = ?, newest_observed_ms = ?, lease_token = NULL, lease_until_ms = NULL WHERE id = ? AND lease_token = ?",
                    etag, modified, now.toEpochMilli(), health, epoch(newest), now.plus(TechnicalSourcePolicy.interval(spec.kind())).toEpochMilli(),
                    batch.coverage() == null ? null : encode(batch.coverage()), epoch(newestObserved), sourceId, token);
            return inserted;
        }));
    }

    /** 失败只更新当前租约的健康记录，不泄漏底层异常。 */
    public void failCollection(String sourceId, String token, Instant now) {
        jdbc.update("UPDATE news_source SET health = ?, last_checked_ms = ?, lease_token = NULL, lease_until_ms = NULL "
                + "WHERE id = ? AND lease_token = ?", "FETCH_FAILED", now.toEpochMilli(), sourceId, token);
    }

    /** 容量拒绝单独标记并短暂冷却，不推进来源条件请求标记。 */
    public void rejectCollectionCapacity(String sourceId, String token, Instant now) {
        jdbc.update("UPDATE news_source SET health='STORAGE_FULL',last_checked_ms=?,next_attempt_ms=?,lease_token=NULL,lease_until_ms=NULL "
                + "WHERE id=? AND lease_token=?", now.toEpochMilli(), now.plusSeconds(900).toEpochMilli(), sourceId, token);
    }

    /** 持久化限流冷却，重启后和来源重新启用后仍遵守上游限制。 */
    public void cooldownCollection(String sourceId, String token, Instant now, Instant retryAt) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE news_source SET health = ?, last_checked_ms = ?, next_attempt_ms = ?, lease_token = NULL, lease_until_ms = NULL "
                    + "WHERE id = ? AND lease_token = ?", "RATE_LIMITED", now.toEpochMilli(), retryAt.toEpochMilli(), sourceId, token);
            SourceKind kind = source(sourceId).spec().kind();
            if (kind != SourceKind.RSS_ATOM) {
                // 同一官方 API 共享配额，不能换一个来源 ID 立即绕过限流。
                try {
                    jdbc.update("INSERT INTO news_provider_cooldown(provider_kind, retry_ms) VALUES (?, ?)", kind.name(), retryAt.toEpochMilli());
                } catch (DuplicateKeyException ignored) {
                    jdbc.update("UPDATE news_provider_cooldown SET retry_ms = ? WHERE provider_kind = ? AND retry_ms < ?",
                            retryAt.toEpochMilli(), kind.name(), retryAt.toEpochMilli());
                }
            }
        });
    }

    /** 查询同一技术平台共享的持久冷却。 */
    public Optional<Instant> providerCooldown(SourceKind kind) {
        return jdbc.query("SELECT retry_ms FROM news_provider_cooldown WHERE provider_kind = ?",
                (row, index) -> Instant.ofEpochMilli(row.getLong(1)), kind.name()).stream().findFirst();
    }

    /** 导入已有来源的真实证据，抓取时间由服务端生成。 */
    public int ingest(String sourceId, List<ArticleInput> input, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            capacity.lock();
            List<SourceSpec> sources = jdbc.query("SELECT spec_json FROM news_source WHERE id = ? FOR UPDATE",
                    (row, index) -> decode(row.getString(1), SourceSpec.class), sourceId);
            if (sources.isEmpty()) throw new NewsException(ErrorCode.NEWS_003);
            if (sources.getFirst().kind() != SourceKind.RSS_ATOM) throw new NewsException(ErrorCode.NEWS_002);
            return ingestLocked(sources.getFirst(), input.stream().map(article -> new CollectedArticle(article, null)).toList(), now);
        }));
    }

    /** 读取截止时刻已观测的最新版本，超过容量时拒绝生成有偏差的榜单。 */
    public List<Article> candidates(Instant cutoff) {
        return candidates(cutoff, cutoff.minus(Duration.ofDays(7)), cutoff);
    }

    /** 按报道时间窗口和独立观测时刻读取材料，供手动七天报告使用。 */
    public List<Article> candidates(Instant observedAt, Instant windowStart, Instant windowEnd) {
        Instant cutoff = observedAt;
        List<Article> result = jdbc.query("SELECT a.payload_json FROM news_article a JOIN news_source s ON s.id = a.source_id "
                        + "WHERE s.enabled = TRUE AND a.fetched_ms <= ? AND a.ranking_ms >= ? AND a.ranking_ms < ? "
                        + "AND NOT EXISTS (SELECT 1 FROM news_source_withdrawal w WHERE w.source_id = a.source_id "
                        + "AND w.external_id = a.external_id AND w.observed_ms >= a.fetched_ms AND w.observed_ms <= ?) "
                        + "AND NOT EXISTS (SELECT 1 FROM news_article b WHERE b.source_id = a.source_id "
                        + "AND b.canonical_hash = a.canonical_hash AND b.fetched_ms <= ? "
                        + "AND (b.fetched_ms > a.fetched_ms OR (b.fetched_ms = a.fetched_ms AND b.id > a.id))) "
                        + "ORDER BY a.id LIMIT ?",
                (row, index) -> decode(row.getString(1), Article.class), cutoff.toEpochMilli(),
                windowStart.toEpochMilli(), windowEnd.toEpochMilli(), cutoff.toEpochMilli(), cutoff.toEpochMilli(), MAX_CANDIDATES + 1);
        if (result.size() > MAX_CANDIDATES) throw new NewsException(ErrorCode.NEWS_011);
        return result;
    }

    /** 按所有者和幂等键读取已有日报，并验证请求一致性。 */
    public Optional<Edition> findByKey(long ownerId, String key, String requestHash) {
        return findByKey(ownerId,key,requestHash,false);
    }

    private Optional<Edition> findByKey(long ownerId,String key,String requestHash,boolean lock) {
        return jdbc.query("SELECT request_hash, payload_json FROM news_edition WHERE owner_id = ? AND idempotency_hash = ?"+(lock?" FOR UPDATE":""),
                (row, index) -> {
                    if (!requestHash.equals(row.getString(1))) throw new NewsException(ErrorCode.NEWS_004);
                    if (row.getString(2) == null) throw new NewsException(ErrorCode.NEWS_026);
                    return decode(row.getString(2), Edition.class);
                }, ownerId, ContentIdentity.hash(key)).stream().findFirst();
    }

    /** 插入日报；并发重复请求由唯一约束收敛为同一个快照。 */
    public Edition saveEdition(Edition edition, String key, String requestHash) {
        return transaction.execute(status -> {
            control.admitContent(edition.ownerId(), key, requestHash);
            return saveEditionLocked(edition, key, requestHash);
        });
    }

    private Edition saveEditionLocked(Edition edition, String key, String requestHash) {
        try {
            jdbc.update("INSERT INTO news_edition(id, owner_id, idempotency_hash, request_hash, cutoff_ms, created_ms, quality, payload_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", edition.id().toString(), edition.ownerId(), ContentIdentity.hash(key),
                    requestHash, edition.cutoff().toEpochMilli(), edition.createdAt().toEpochMilli(), edition.quality().name(), encode(edition));
            return edition;
        } catch (DuplicateKeyException exception) {
            return findByKey(edition.ownerId(), key, requestHash, true).orElseThrow(() -> new NewsException(ErrorCode.NEWS_009));
        }
    }

    /** 查询属于指定所有者的快照，跨用户与不存在统一返回未找到。 */
    public Edition edition(long ownerId, UUID id) {
        return jdbc.query("SELECT payload_json FROM news_edition WHERE id = ? AND owner_id = ?",
                (row, index) -> {
                    if (row.getString(1) == null) throw new NewsException(ErrorCode.NEWS_026);
                    return decode(row.getString(1), Edition.class);
                }, id.toString(), ownerId).stream()
                .findFirst().orElseThrow(() -> new NewsException(ErrorCode.NEWS_003));
    }

    /** 查询最近的日报历史，不在列表返回正文。 */
    public List<EditionHeader> history(long ownerId, int limit) {
        return jdbc.query("SELECT id, cutoff_ms, created_ms, quality FROM news_edition WHERE owner_id = ? AND content_expired_ms IS NULL "
                        + "ORDER BY created_ms DESC, id DESC LIMIT ?",
                (row, index) -> new EditionHeader(UUID.fromString(row.getString(1)), Instant.ofEpochMilli(row.getLong(2)),
                        Instant.ofEpochMilli(row.getLong(3)), Quality.valueOf(row.getString(4))), ownerId, limit);
    }

    private int ingestLocked(SourceSpec source, List<CollectedArticle> inputs, Instant now) {
        if (!source.enabled()) throw new NewsException(ErrorCode.NEWS_005);
        // 与数据库毫秒精度一致，避免同一截止毫秒在 JSON 与 SQL 中出现不同判断。
        Instant observedAt = Instant.ofEpochMilli(now.toEpochMilli());
        int inserted = 0;
        for (CollectedArticle collected : inputs) {
            ArticleInput input = collected.content();
            String url = ContentIdentity.canonicalUrl(input.url());
            String title = ContentIdentity.plainText(input.title());
            if (title.isBlank()) throw new NewsException(ErrorCode.NEWS_002);
            // 未确认加工权限的来源连正文也不持久化，避免后续被误用。
            String evidence = source.evidencePolicy() == EvidencePolicy.EXCERPT_ALLOWED
                    ? ContentIdentity.plainText(input.evidenceText()) : "";
            String externalId = collected.technical() == null ? null : collected.technical().externalId();
            Long withdrawal = externalId == null ? null : jdbc.queryForObject("SELECT MAX(observed_ms) FROM news_source_withdrawal "
                    + "WHERE source_id = ? AND external_id = ? AND observed_ms < ?", Long.class, source.id(), externalId, now.toEpochMilli());
            String contentHash = ContentIdentity.hash(title + "\n" + input.publishedAt() + "\n" + evidence + "\n" + input.engagement()
                    + (collected.technical() == null ? "" : "\n" + encode(collected.technical()))
                    + (withdrawal == null ? "" : "\nrevived-after:" + withdrawal));
            String identity = source.kind() == SourceKind.GITHUB ? "github:" + collected.technical().externalId() : url;
            String id = ContentIdentity.hash(source.id() + "\n" + identity + "\n" + contentHash);
            // 热榜以采样时间进入候选窗口，新闻发布时间仍保持未知。
            Instant rankingAt = source.kind() == SourceKind.BAIDU_HOT ? observedAt
                    : source.kind() == SourceKind.GITHUB ? collected.technical().activityAt() : input.publishedAt();
            Article article = new Article(id, source, url, title, input.publishedAt(), observedAt, evidence, contentHash, input.engagement(), collected.technical());
            String payload = encode(article);
            long bytes = CollectionCapacityRepository.payloadBytes(payload, true);
            try {
                jdbc.update("INSERT INTO news_article(id, source_id, canonical_hash, content_hash, published_ms, fetched_ms, payload_json, ranking_ms, external_id, capacity_bytes) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, source.id(), ContentIdentity.hash(identity), contentHash,
                        epoch(input.publishedAt()), now.toEpochMilli(), payload, epoch(rankingAt), externalId, bytes);
                capacity.adjust(1, 0, 0, bytes);
                inserted++;
            } catch (DuplicateKeyException ignored) {
                // 重复抓取同一内容保留第一次观测时间，避免把旧稿刷新成新稿。
            }
        }
        return inserted;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }

    private Instant instant(Long epoch) { return epoch == null ? null : Instant.ofEpochMilli(epoch); }
    private Long epoch(Instant instant) { return instant == null ? null : instant.toEpochMilli(); }
}
