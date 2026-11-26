package com.yuyutian.mytools.news.service.technical;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.config.TechSourceProperties;
import com.yuyutian.mytools.news.mapper.GithubSnapshotRepository;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** 搜索仅发现候选，周期性聚合快照负责真实增长，二者不能混称官方周榜。 */
@Component
public class GithubCollector implements TechnicalCollector {
    private final JsonSourceClient client;
    private final TechSourceProperties properties;
    private final GithubSnapshotRepository snapshots;
    private final Clock clock;

    /** 创建 GitHub 适配器。 */
    public GithubCollector(JsonSourceClient client, TechSourceProperties properties, GithubSnapshotRepository snapshots, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /** 返回 GitHub 协议。 */
    @Override
    public SourceKind kind() { return SourceKind.GITHUB; }

    /** 收集搜索候选和有界跟踪队列，不下载仓库源码或点赞用户列表。 */
    @Override
    public CollectedBatch collect(SourceState source, Instant startedAt) {
        long deadline = TechnicalParsing.deadline();
        String since = startedAt.minus(Duration.ofDays(7)).atZone(ZoneOffset.UTC).toLocalDate().toString();
        String query = source.spec().feedUrl() + "?q=pushed%3A%3E%3D" + since
                + "%20archived%3Afalse%20fork%3Afalse&sort=stars&order=desc&per_page=" + properties.itemLimit() + "&page=1";
        JsonNode result = client.get(source, query);
        JsonNode items = result.path("items");
        if (!items.isArray() || items.size() > 1000 || !result.path("incomplete_results").isBoolean()) throw new NewsException(ErrorCode.NEWS_007);
        long totalCount = TechnicalParsing.nonNegative(result, "total_count");
        Map<Long, GithubSnapshot> collected = new LinkedHashMap<>();
        List<String> withdrawn = new ArrayList<>();
        int visited = 0;
        int skipped = 0;
        int failed = 0;
        List<String> limitations = new ArrayList<>(List.of("SEARCH_DISCOVERY_NOT_WEEKLY_TRENDING", "BOUNDED_TRACKED_COHORT"));
        if (result.path("incomplete_results").asBoolean()) limitations.add("UPSTREAM_INCOMPLETE_SEARCH");
        if (totalCount > items.size() || items.size() > properties.itemLimit()) limitations.add("SEARCH_FIRST_PAGE_ONLY");
        for (JsonNode item : items) {
            if (visited >= properties.itemLimit()) break;
            visited++;
            try {
                GithubSnapshot snapshot = parse(item, clock.instant());
                if (snapshot == null) { withdrawn.add(Long.toString(TechnicalParsing.positive(item, "id"))); skipped++; continue; }
                if (collected.putIfAbsent(snapshot.repositoryId(), snapshot) != null) skipped++;
            } catch (NewsException exception) { failed++; }
        }
        List<GithubSnapshot> tracked = snapshots.tracked(source.spec().id(), startedAt, properties.trackedLimit());
        for (GithubSnapshot previous : tracked) {
            if (collected.containsKey(previous.repositoryId())) continue;
            if (TechnicalParsing.expired(deadline)) { limitations.add("TIME_BUDGET_EXCEEDED"); break; }
            visited++;
            try {
                JsonNode item = client.get(source, "https://api.github.com/repos/" + previous.fullName());
                if (TechnicalParsing.positive(item, "id") != previous.repositoryId()) throw new NewsException(ErrorCode.NEWS_007);
                GithubSnapshot snapshot = parse(item, clock.instant());
                if (snapshot == null) { withdrawn.add(Long.toString(previous.repositoryId())); skipped++; continue; }
                if (snapshot.repositoryId() != previous.repositoryId()) throw new NewsException(ErrorCode.NEWS_007);
                collected.put(snapshot.repositoryId(), snapshot);
            } catch (SourceRateLimitException exception) {
                throw exception;
            } catch (NewsException exception) { failed++; }
        }
        if (failed > 0) limitations.add("REPOSITORY_FETCH_OR_SCHEMA_FAILURE");
        return new CollectedBatch(List.of(), List.copyOf(collected.values()), new CollectionCoverage(
                Math.toIntExact(Math.min(Integer.MAX_VALUE, totalCount)), visited, collected.size(), skipped, failed, List.copyOf(limitations)), List.copyOf(withdrawn));
    }

    private GithubSnapshot parse(JsonNode item, Instant observedAt) {
        if (!item.isObject()) throw new NewsException(ErrorCode.NEWS_007);
        if (!item.path("private").isBoolean() || !item.path("archived").isBoolean() || !item.path("fork").isBoolean()) {
            throw new NewsException(ErrorCode.NEWS_007);
        }
        // 搜索与跟踪都执行同一准入检查，仓库状态变化后退出新候选。
        if (item.path("private").asBoolean() || item.path("archived").asBoolean() || item.path("disabled").asBoolean()
                || item.path("fork").asBoolean()) return null;
        long id = TechnicalParsing.positive(item, "id");
        String name = item.path("full_name").asText("");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}/[A-Za-z0-9_.-]{1,100}")
                || name.endsWith("/.") || name.endsWith("/..")) throw new NewsException(ErrorCode.NEWS_007);
        String url = ContentIdentity.canonicalUrl(item.path("html_url").asText(""));
        if (!url.equalsIgnoreCase("https://github.com/" + name)) throw new NewsException(ErrorCode.NEWS_007);
        Instant created = TechnicalParsing.date(item, "created_at");
        if (created == null || created.isAfter(observedAt)) throw new NewsException(ErrorCode.NEWS_007);
        return new GithubSnapshot(id, name, url, created, TechnicalParsing.date(item, "pushed_at"),
                TechnicalParsing.text(item, "description", 20000), TechnicalParsing.nonNegative(item, "stargazers_count"),
                TechnicalParsing.nonNegative(item, "forks_count"), Instant.ofEpochMilli(observedAt.toEpochMilli()));
    }
}
