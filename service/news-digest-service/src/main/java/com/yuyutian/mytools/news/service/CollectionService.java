package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.service.technical.TechnicalCollector;
import com.yuyutian.mytools.news.utils.TechnicalSourcePolicy;
import com.yuyutian.mytools.news.config.NewsProperties;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 单来源采集编排，网络阶段与数据库事务分离。 */
@Service
public class CollectionService {
    private final NewsRepository repository;
    private final FeedTransport transport;
    private final FeedParser parser;
    private final NewsProperties properties;
    private final Clock clock;
    private final List<TechnicalCollector> technicalCollectors;
    private final ArticleExcerptReader excerpts;

    /** 创建采集服务。 */
    public CollectionService(NewsRepository repository, FeedTransport transport, FeedParser parser,
                             NewsProperties properties, Clock clock, List<TechnicalCollector> technicalCollectors) {
        this.repository = repository;
        this.transport = transport;
        this.parser = parser;
        this.properties = properties;
        this.clock = clock;
        this.technicalCollectors = List.copyOf(technicalCollectors);
        this.excerpts = new ArticleExcerptReader(transport);
    }

    /** 抓取一个已启用来源，重复执行复用证据版本。 */
    public CollectionResult collect(String sourceId) {
        if (!properties.collectionEnabled()) throw new NewsException(ErrorCode.NEWS_005);
        SourceState source = repository.source(sourceId);
        if (!source.spec().enabled()) throw new NewsException(ErrorCode.NEWS_005);
        TechnicalSourcePolicy.validate(source.spec());
        if (source.nextAttemptAt() != null && source.nextAttemptAt().isAfter(clock.instant())) throw new SourceRateLimitException(source.nextAttemptAt());
        repository.providerCooldown(source.spec().kind()).filter(time -> time.isAfter(clock.instant()))
                .ifPresent(time -> { throw new SourceRateLimitException(time); });
        String token = UUID.randomUUID().toString();
        if (!repository.acquireCollection(sourceId, token, clock.instant())) throw new NewsException(ErrorCode.NEWS_010);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        try {
            if (source.spec().kind() != SourceKind.RSS_ATOM && source.spec().kind() != SourceKind.CHINANEWS_JIANGSU
                    && source.spec().kind() != SourceKind.JSCHINA_JIANGSU
                    && source.spec().kind() != SourceKind.BAIDU_HOT && source.spec().kind() != SourceKind.CCTV_MILITARY)
                return collectTechnical(source, token, deadline);
            FeedTransport.FeedResponse response = transport.fetch(source);
            Instant now = clock.instant();
            if (response.status() == 304) {
                // 未修改响应仍重新计算新鲜度，长期停更不能因 HTTP 成功保持健康。
                String health = health(source.spec().category(), source.newestPublishedAt(), now);
                repository.finishCollection(sourceId, token, now, List.of(),
                        response.etag() == null ? source.etag() : response.etag(),
                        response.lastModified() == null ? source.lastModified() : response.lastModified(),
                        health, source.newestPublishedAt());
                return new CollectionResult(sourceId, "NOT_MODIFIED", 0, 0, source.newestPublishedAt());
            }
            if (response.status() != 200) throw new NewsException(ErrorCode.NEWS_006);
            // 网页来源按已核对的模板解析，其他来源继续使用 RSS/Atom 协议。
            FeedParser.ParsedFeed parsed = switch (source.spec().kind()) {
                case CHINANEWS_JIANGSU -> JiangsuNewsParser.parse(response.body(), source.spec().feedUrl());
                case JSCHINA_JIANGSU -> JschinaNewsParser.parse(response.body(), source.spec().feedUrl());
                case BAIDU_HOT -> BaiduHotParser.parse(response.body());
                case CCTV_MILITARY -> CctvMilitaryParser.parse(response.body());
                default -> parser.parse(response.body(), source.spec().feedUrl());
            };
            Instant newest = parsed.articles().stream().map(ArticleInput::publishedAt).filter(java.util.Objects::nonNull)
                    .filter(published -> !published.isAfter(now)).max(Instant::compareTo).orElse(null);
            String health = parsed.articles().isEmpty() ? "EMPTY" : source.spec().kind() == SourceKind.BAIDU_HOT
                    ? "FRESH" : health(source.spec().category(), newest, now);
            // 优先使用订阅自带摘要，候选范围内最多补读十篇缺少详情的原文。
            // 热榜链接指向搜索结果，不能把搜索导航冒充新闻正文。
            List<ArticleInput> enriched = source.spec().kind() == SourceKind.BAIDU_HOT ? parsed.articles() : excerpts.enrich(source, parsed.articles().stream()
                    .map(article -> new CollectedArticle(article, null)).toList(), now, deadline).stream().map(CollectedArticle::content).toList();
            int inserted = repository.finishCollection(sourceId, token, clock.instant(), enriched, response.etag(),
                    response.lastModified(), health, newest);
            return new CollectionResult(sourceId, health, inserted, parsed.skipped(), newest);
        } catch (SourceRateLimitException exception) {
            repository.cooldownCollection(sourceId, token, clock.instant(), exception.retryAt());
            throw exception;
        } catch (RuntimeException exception) {
            // 只让本轮租约更新失败状态，较新的采集不能被旧任务覆盖。
            if (exception instanceof NewsException rejected && rejected.code() == ErrorCode.NEWS_028) {
                repository.rejectCollectionCapacity(sourceId, token, clock.instant());
            } else {
                repository.failCollection(sourceId, token, clock.instant());
            }
            throw exception;
        }
    }

    private CollectionResult collectTechnical(SourceState source, String token, long deadline) {
        TechnicalCollector collector = technicalCollectors.stream().filter(value -> value.kind() == source.spec().kind())
                .findFirst().orElseThrow(() -> new NewsException(ErrorCode.NEWS_002));
        CollectedBatch batch = collector.collect(source, clock.instant());
        batch = new CollectedBatch(excerpts.enrich(source, batch.articles(), clock.instant(), deadline), batch.snapshots(), batch.coverage(), batch.withdrawnIds());
        Instant now = clock.instant();
        // 仓库创建日期仍是创建日期，健康新鲜度则依据聚合快照的实际观测时间。
        Instant newest = source.spec().kind() == SourceKind.GITHUB
                ? batch.snapshots().stream().map(GithubSnapshot::createdAt).max(Instant::compareTo).orElse(null)
                : batch.articles().stream().map(article -> article.content().publishedAt()).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        Instant newestObserved = batch.snapshots().stream().map(GithubSnapshot::observedAt).max(Instant::compareTo).orElse(null);
        String health = batch.coverage().accepted() == 0 ? "EMPTY" : health(Category.TECH, newestObserved == null ? newest : newestObserved, now);
        if (batch.coverage().failed() > 0 || batch.coverage().limitations().contains("TIME_BUDGET_EXCEEDED")
                || batch.coverage().limitations().contains("UPSTREAM_INCOMPLETE_SEARCH")) health = "PARTIAL";
        int inserted = repository.finishTechnicalCollection(source.spec().id(), token, now, batch, health, newest);
        return new CollectionResult(source.spec().id(), health, inserted, batch.coverage().skipped(), newest,
                batch.coverage(), now.plus(TechnicalSourcePolicy.interval(source.spec().kind())), newestObserved);
    }

    private String health(Category category, Instant newest, Instant now) {
        if (newest == null) return "DATE_UNKNOWN";
        return newest.isBefore(now.minus(DigestEngine.window(category))) ? "STALE" : "FRESH";
    }
}
