package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/** 确定性的内容引擎，基线排序只使用当前已经取得的信号。 */
@Component
public class DigestEngine {
    public static final String RANKING_VERSION = "personal-source-mix-v4";
    private static final int HEADLINE_LIMIT = 20;
    private static final int DETAIL_LIMIT = 10;

    /** 根据固定时间窗和地区生成不可变内容，不执行网络请求或消息发送。 */
    public Edition build(UUID id, long ownerId, Instant cutoff, Instant createdAt, ZoneId zone,
                         RegionSnapshot region, List<Article> input) {
        return build(id, ownerId, cutoff, createdAt, zone, region, input, HEADLINE_LIMIT, DETAIL_LIMIT);
    }

    /** 为个人分析提供每类最多三十条候选及其正文，不改变普通日报的二十条协议。 */
    public Edition buildCandidates(Edition base, List<Article> input) {
        return build(base.id(), base.ownerId(), base.cutoff(), base.createdAt(), ZoneId.of(base.zone()),
                base.region(), input, 30, 30);
    }

    private Edition build(UUID id, long ownerId, Instant cutoff, Instant createdAt, ZoneId zone,
                          RegionSnapshot region, List<Article> input, int headlineLimit, int detailLimit) {
        return build(id, ownerId, cutoff, createdAt, zone, region, input, headlineLimit, detailLimit, null);
    }

    /** 手动报告使用固定七天窗口，允许受理后取得的正文，不把抓取时间冒充发布时间。 */
    public Edition buildWeeklyCandidates(Edition base, List<Article> input, Instant cutoff, int limit) {
        if (limit < 20 || limit > 150 || cutoff == null || cutoff.isAfter(base.createdAt())) {
            throw new NewsException(ErrorCode.NEWS_002);
        }
        return build(base.id(), base.ownerId(), cutoff, base.createdAt(), ZoneId.of(base.zone()),
                base.region(), input, limit, limit, Duration.ofDays(7));
    }

    private Edition build(UUID id, long ownerId, Instant cutoff, Instant createdAt, ZoneId zone,
                          RegionSnapshot region, List<Article> input, int headlineLimit, int detailLimit, Duration fixedWindow) {
        List<Article> eligible = latestVersions(input.stream().filter(article -> !article.fetchedAt().isAfter(fixedWindow == null ? cutoff : createdAt)).toList())
                .stream().filter(article -> eligible(article, cutoff, region, fixedWindow)).toList();
        Map<String, Double> engagement = normalizedEngagement(eligible, cutoff);
        Map<Category, List<Event>> grouped = new EnumMap<>(Category.class);
        for (List<Article> articles : cluster(eligible)) {
            // 跨分类聚类后只选一个主分类，避免同一事件重复占榜。
            Article representative = articles.stream().min(representativeOrder()).orElseThrow();
            Score score = score(articles, representative.source().category(), cutoff, engagement, fixedWindow);
            String eventId = ContentIdentity.hash(articles.stream().map(Article::url).sorted().findFirst().orElseThrow());
            grouped.computeIfAbsent(representative.source().category(), ignored -> new ArrayList<>())
                    .add(new Event(eventId, representative, articles, score));
        }
        Map<Category, Section> sections = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            // 稳定的最终比较项确保同一材料集合重放时顺序一致。
            List<Event> events = grouped.getOrDefault(category, List.of()).stream()
                    .sorted(Comparator.comparingDouble((Event event) -> event.score().total()).reversed()
                            .thenComparing(event -> rankingAt(event.representative()), Comparator.reverseOrder())
                            .thenComparing(Event::id)).toList();
            List<RankedItem> items = new ArrayList<>();
            for (Event event : selectHeadlines(category, events, headlineLimit)) {
                int rank = items.size() + 1;
                Detail detail = rank <= detailLimit ? extractDetail(event.articles()) : null;
                DetailStatus status = rank > detailLimit ? DetailStatus.HEADLINE_ONLY
                        : detail != null ? DetailStatus.AVAILABLE
                        : event.articles().stream().noneMatch(article -> article.source().evidencePolicy() == EvidencePolicy.EXCERPT_ALLOWED)
                        ? DetailStatus.NOT_AUTHORIZED : event.articles().stream().anyMatch(value -> value.evidenceExpiredAt() != null)
                        ? DetailStatus.EVIDENCE_EXPIRED : DetailStatus.EVIDENCE_TOO_SHORT;
                Article article = event.representative();
                TechnicalMetadata technical = event.articles().stream().map(Article::technical).filter(Objects::nonNull)
                        .filter(value -> value.github() != null).findFirst().orElse(article.technical());
                items.add(new RankedItem(rank, event.id(), article.title(), article.url(), article.publishedAt(),
                        event.articles().stream().map(value -> value.source().id()).distinct().sorted().toList(),
                        event.score(), status, detail, metadataAt(technical, cutoff)));
            }
            int details = (int) items.stream().filter(item -> item.detail() != null).count();
            List<String> gaps = new ArrayList<>();
            if (items.size() < headlineLimit) gaps.add("HEADLINE_SHORTFALL"); // 不以旧稿或重复稿补足数量。
            if (details < detailLimit) gaps.add("DETAIL_SHORTFALL"); // 详情不足不替换排名靠前的条目。
            if (category == Category.LOCAL && !"VERIFIED".equals(region.status())) gaps.add("REGION_" + region.status());
            items.stream().map(RankedItem::technical).filter(Objects::nonNull).map(TechnicalMetadata::github).filter(Objects::nonNull)
                    .map(GithubTrend::status).filter(status -> status != TrendStatus.READY).distinct().sorted()
                    .forEach(status -> gaps.add("GITHUB_TREND_" + status.name()));
            sections.put(category, new Section(category, cutoff.minus(fixedWindow == null ? window(category) : fixedWindow), cutoff, events.size(),
                    items.size(), details, gaps.isEmpty() ? Quality.COMPLETE : Quality.DEGRADED,
                    List.copyOf(gaps), List.copyOf(items)));
        }
        Quality quality = sections.values().stream().allMatch(section -> section.quality() == Quality.COMPLETE)
                ? Quality.COMPLETE : Quality.DEGRADED;
        return new Edition(id, ownerId, cutoff, createdAt, zone.getId(), fixedWindow == null ? RANKING_VERSION : "weekly-source-mix-v1", region, quality,
                Collections.unmodifiableMap(sections));
    }

    /** 返回分类统计窗口，技术为七日，新闻为二十四小时。 */
    public static Duration window(Category category) {
        return Duration.ofDays(category == Category.TECH ? 7 : 1);
    }

    private List<Event> selectHeadlines(Category category, List<Event> events, int headlineLimit) {
        if (category != Category.TECH) return events.stream().limit(headlineLimit).toList();
        // 给实际取得的 GitHub 候选保留最多五席，避免冷启动时全部被论坛指标挤出。
        Set<String> selected = new HashSet<>();
        events.stream().filter(event -> event.articles().stream().anyMatch(article -> article.source().kind() == SourceKind.GITHUB))
                .limit(5).forEach(event -> selected.add(event.id()));
        for (Event event : events) {
            if (selected.size() >= headlineLimit) break;
            selected.add(event.id()); // 其余名额仍按原始得分填满，不修改周增量或热度分数。
        }
        return events.stream().filter(event -> selected.contains(event.id())).toList();
    }

    private boolean eligible(Article article, Instant cutoff, RegionSnapshot region, Duration fixedWindow) {
        Instant rankedAt = rankingAt(article);
        if (!article.source().enabled() || rankedAt == null || (fixedWindow == null && article.fetchedAt().isAfter(cutoff))
                || rankedAt.isBefore(cutoff.minus(fixedWindow == null ? window(article.source().category()) : fixedWindow))
                || !rankedAt.isBefore(cutoff)) return false;
        // 本地来源必须匹配实际执行节点的已验证地区，空地区不能通配。
        return article.source().category() != Category.LOCAL || (region.usable()
                && region.regionCode().equals(article.source().regionCode())
                && (region.details() == null || region.details().sourceIds().contains(article.source().id())));
    }

    private List<Article> latestVersions(List<Article> input) {
        Map<String, Article> latest = new TreeMap<>();
        Comparator<Article> order = Comparator.comparing(Article::fetchedAt).thenComparing(Article::id);
        for (Article article : input) {
            // 同一来源同一网址只参与一次排序，较新的证据版本替换旧版本。
            String identity = article.source().kind() == SourceKind.GITHUB && article.technical() != null
                    ? "github:" + article.technical().externalId() : article.url();
            latest.merge(article.source().id() + "\n" + identity, article,
                    (left, right) -> order.compare(left, right) >= 0 ? left : right);
        }
        return List.copyOf(latest.values());
    }

    private List<List<Article>> cluster(List<Article> articles) {
        int[] parent = new int[articles.size()];
        Map<String, Integer> identities = new HashMap<>();
        for (int index = 0; index < articles.size(); index++) {
            parent[index] = index; // 并查集将网址与保守标题匹配统一为事件分组。
            Article article = articles.get(index);
            List<String> keys = new ArrayList<>(List.of("url:" + article.url()));
            if (article.source().kind() == SourceKind.GITHUB && article.technical() != null) {
                keys.add("github:" + article.technical().externalId()); // 仓库改名后仍按数字身份去重。
            }
            String title = ContentIdentity.titleKey(article.title());
            if (title.codePointCount(0, title.length()) >= 12) {
                // 仅合并同一自然日的长标题，短标题或不同日期不做推测性合并。
                keys.add("title:" + rankingAt(article).atZone(ZoneOffset.UTC).toLocalDate() + ":" + title);
            }
            for (String key : keys) {
                Integer previous = identities.putIfAbsent(key, index);
                if (previous != null) parent[root(parent, index)] = root(parent, previous);
            }
        }
        Map<Integer, List<Article>> result = new TreeMap<>();
        for (int index = 0; index < articles.size(); index++) {
            result.computeIfAbsent(root(parent, index), ignored -> new ArrayList<>()).add(articles.get(index));
        }
        return List.copyOf(result.values());
    }

    private int root(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]]; // 路径压缩保证批量聚类复杂度稳定。
            index = parent[index];
        }
        return index;
    }

    private Comparator<Article> representativeOrder() {
        return Comparator.comparingInt((Article article) -> switch (article.source().category()) {
            case LOCAL -> 0;
            case TECH -> 1;
            case DOMESTIC -> 2;
            case WORLD -> 3;
            case MILITARY, FINANCE, SOCIETY, SPORTS, ENTERTAINMENT -> 1;
            case TRENDING -> 4;
        }).thenComparing(Comparator.comparingDouble((Article article) -> article.source().authority()).reversed())
                .thenComparing(this::rankingAt).thenComparing(Article::id);
    }

    private Map<String, Double> normalizedEngagement(List<Article> articles, Instant cutoff) {
        Map<String, Long> maxima = new HashMap<>();
        for (Article article : articles) {
            maxima.merge(article.source().id(), comparableEngagement(article, cutoff), Math::max);
        }
        Map<String, Double> result = new HashMap<>();
        for (Article article : articles) {
            // 只比较同源归一化指标；无指标时保留零值。
            long maximum = maxima.get(article.source().id());
            result.put(article.id(), maximum == 0 ? 0 : Math.log1p(comparableEngagement(article, cutoff)) / Math.log1p(maximum));
        }
        return result;
    }

    private Score score(List<Article> articles, Category category, Instant cutoff, Map<String, Double> engagement, Duration fixedWindow) {
        List<Article> reports = articles.stream().filter(article -> article.source().kind() != SourceKind.GITHUB).toList();
        Instant firstPublished = (reports.isEmpty() ? articles : reports).stream().map(this::rankingAt).min(Instant::compareTo).orElseThrow();
        double ageHours = Duration.between(firstPublished, cutoff).toSeconds() / 3600.0;
        double freshness = Math.pow(0.5, ageHours / (fixedWindow != null || category == Category.TECH ? 84 : 12));
        if (reports.isEmpty() && articles.stream().noneMatch(article -> comparableEngagement(article, cutoff) > 0)) freshness = 0;
        // HN 是讨论入口，不把对同一原文的讨论再算作独立出版者佐证。
        long publishers = articles.stream().filter(article -> article.source().kind() != SourceKind.HACKER_NEWS)
                .map(article -> article.source().publisherKey()).distinct().count();
        double corroboration = Math.max(0, Math.min(1, (publishers - 1) / 2.0));
        double heat = articles.stream().mapToDouble(article -> engagement.get(article.id())).max().orElse(0);
        double authority = articles.stream().mapToDouble(article -> article.source().authority()).max().orElse(0);
        // GitHub 只使用覆盖合格的净增长，其他来源使用各自的原生社区指标。
        double total = 0.35 * freshness + 0.25 * corroboration + 0.20 * heat + 0.20 * authority;
        return new Score(freshness, corroboration, heat, authority, total);
    }

    private Instant rankingAt(Article article) {
        // 热榜仅有采样时间，排序采用观测时间，原新闻发布时间继续留空。
        if (article.source().kind() == SourceKind.BAIDU_HOT) return article.fetchedAt();
        return article.source().kind() == SourceKind.GITHUB
                ? article.technical() == null ? null : article.technical().activityAt() : article.publishedAt();
    }

    private long comparableEngagement(Article article, Instant cutoff) {
        if (article.source().kind() != SourceKind.GITHUB) return article.engagement();
        TechnicalMetadata metadata = metadataAt(article.technical(), cutoff);
        if (metadata == null || metadata.github() == null || metadata.github().status() != TrendStatus.READY) return 0;
        return Math.max(0, metadata.github().netStars());
    }

    private TechnicalMetadata metadataAt(TechnicalMetadata metadata, Instant cutoff) {
        if (metadata == null || metadata.github() == null) return metadata;
        GithubTrend trend = metadata.github();
        if (Duration.between(trend.observedAt(), cutoff).compareTo(Duration.ofHours(12)) <= 0) return metadata;
        GithubTrend stale = new GithubTrend(trend.repositoryId(), trend.stars(), trend.forks(), trend.observedAt(),
                trend.baselineAt(), trend.baselineStars(), trend.netStars(), trend.coverageSeconds(), trend.sampleCount(), TrendStatus.STALE);
        return new TechnicalMetadata(metadata.externalId(), metadata.discussionUrl(), metadata.activityAt(), metadata.nativeScore(), metadata.commentCount(), stale, metadata.evidenceUrl());
    }

    private Detail extractDetail(List<Article> articles) {
        return articles.stream().filter(article -> article.source().evidencePolicy() == EvidencePolicy.EXCERPT_ALLOWED)
                .filter(article -> ArticleExcerptReader.sufficient(article.title(), article.evidenceText()))
                .sorted(Comparator.comparingInt((Article article) -> article.evidenceText().length()).reversed().thenComparing(Article::id))
                .findFirst().map(article -> new Detail(ContentIdentity.truncate(article.evidenceText(), 300),
                        "SOURCE_EXCERPT_V1", List.of(new Citation(article.id(), evidenceUrl(article), article.source().id(),
                        article.contentHash(), article.fetchedAt())))).orElse(null);
    }

    /** 渲染引用和证据状态检查共用实际材料地址，兼容旧版 HN 讨论正文。 */
    public static String evidenceUrl(Article article) {
        if (article.technical() != null && article.technical().evidenceUrl() != null) return article.technical().evidenceUrl();
        // 没有独立原文证据地址的旧 HN 材料来自讨论页，不能冒充外站正文。
        return article.source().kind() == SourceKind.HACKER_NEWS && article.technical() != null
                ? article.technical().discussionUrl() : article.url();
    }

    private record Event(String id, Article representative, List<Article> articles, Score score) { }
}
