package com.yuyutian.mytools.news.Model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 新闻采集、证据和不可变日报的数据协议。 */
public final class NewsModels {
    private NewsModels() { }

    /** 日报主分类，同一事件只在一个分类占位。 */
    public enum Category { TECH, WORLD, DOMESTIC, LOCAL, TRENDING, MILITARY, FINANCE, SOCIETY, SPORTS, ENTERTAINMENT }

    /** 显式指定采集协议，旧来源缺省为 RSS/Atom。 */
    public enum SourceKind { RSS_ATOM, HACKER_NEWS, DEV, GITHUB, CHINANEWS_JIANGSU, JSCHINA_JIANGSU, BAIDU_HOT, CCTV_MILITARY }

    /** 周趋势样本质量与业务热度分开表达。 */
    public enum TrendStatus { WARMING_UP, READY, MISSING_BASELINE, GAPPED_HISTORY, STALE }

    /** 正文加工权限必须由来源管理者明确确认。 */
    public enum EvidencePolicy { METADATA_ONLY, EXCERPT_ALLOWED }

    /** 日报质量独立于生成是否成功。 */
    public enum Quality { COMPLETE, DEGRADED }

    /** 详情可用性不改变原始排名。 */
    public enum DetailStatus { AVAILABLE, EVIDENCE_TOO_SHORT, NOT_AUTHORIZED, HEADLINE_ONLY, EVIDENCE_EXPIRED }

    /** 来源按新闻编辑归属去重，转载站点不应登记为不同原始出版者。 */
    public record SourceSpec(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String id,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 2048) String feedUrl,
            @NotNull Category category,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String publisherKey,
            @Size(max = 64) String regionCode,
            @NotNull EvidencePolicy evidencePolicy,
            @NotBlank @Size(max = 1024) String rightsReference,
            @DecimalMin("0.0") @DecimalMax("1.0") double authority,
            boolean enabled,
            SourceKind kind) {
        /** 兼容第一批已存储的不含协议字段的来源。 */
        public SourceSpec {
            if (kind == null) kind = SourceKind.RSS_ATOM;
        }

        /** 保留既有 RSS 调用的构造协议。 */
        public SourceSpec(String id, String name, String feedUrl, Category category, String publisherKey,
                          String regionCode, EvidencePolicy evidencePolicy, String rightsReference,
                          double authority, boolean enabled) {
            this(id, name, feedUrl, category, publisherKey, regionCode, evidencePolicy, rightsReference,
                    authority, enabled, SourceKind.RSS_ATOM);
        }
    }

    /** 采集接口只接受已有来源，服务端生成抓取时间和证据版本。 */
    public record ArticleInput(@NotBlank @Size(max = 2048) String url,
                               @NotBlank @Size(max = 1000) String title,
                               Instant publishedAt,
                               @Size(max = 20000) String evidenceText,
                               @Min(0) long engagement) { }

    /** 有界批量导入供受控采集器使用，不能由普通用户提交内容。 */
    public record IngestRequest(@NotEmpty @Size(max = 500) List<@Valid ArticleInput> articles) { }

    /** 文章证据版本的身份包含来源、规范网址及内容摘要。 */
    public record Article(String id, SourceSpec source, String url, String title, Instant publishedAt,
                          Instant fetchedAt, String evidenceText, String contentHash, long engagement,
                          TechnicalMetadata technical, Instant evidenceExpiredAt) {
        /** 保留已有文章版本构造协议，清理标记由保留策略设置。 */
        public Article(String id, SourceSpec source, String url, String title, Instant publishedAt,
                       Instant fetchedAt, String evidenceText, String contentHash, long engagement, TechnicalMetadata technical) {
            this(id, source, url, title, publishedAt, fetchedAt, evidenceText, contentHash, engagement, technical, null);
        }
        /** 兼容第一批文章构造方式。 */
        public Article(String id, SourceSpec source, String url, String title, Instant publishedAt,
                       Instant fetchedAt, String evidenceText, String contentHash, long engagement) {
            this(id, source, url, title, publishedAt, fetchedAt, evidenceText, contentHash, engagement, null);
        }
    }

    /** 论坛原生指标和讨论地址不等同于多个独立新闻出版者。 */
    public record TechnicalMetadata(String externalId, String discussionUrl, Instant activityAt,
                                     long nativeScore, long commentCount, GithubTrend github, String evidenceUrl) {
        /** 兼容既有材料，未补读外站时仍按原来的讨论页或文章地址引用。 */
        public TechnicalMetadata(String externalId, String discussionUrl, Instant activityAt,
                                 long nativeScore, long commentCount, GithubTrend github) {
            this(externalId, discussionUrl, activityAt, nativeScore, commentCount, github, null);
        }
    }

    /** 聚合仓库快照不访问单个点赞用户列表。 */
    public record GithubSnapshot(long repositoryId, String fullName, String url, Instant createdAt,
                                 Instant pushedAt, String description, long stars, long forks, Instant observedAt) { }

    /** 保留实际采样端点、覆盖跨度和有符号净变化，不将采样近似标为精确七日计数。 */
    public record GithubTrend(long repositoryId, long stars, long forks, Instant observedAt,
                              Instant baselineAt, Long baselineStars, Long netStars, long coverageSeconds,
                              int sampleCount, TrendStatus status) { }

    /** 采集内部协议，技术指标不能从普通文章导入接口伪造。 */
    public record CollectedArticle(ArticleInput content, TechnicalMetadata technical) { }

    /** 单轮采集的覆盖信息保留到来源记录，不能把抽样集合描述为全站排行。 */
    public record CollectionCoverage(int listed, int visited, int accepted, int skipped, int failed,
                                      List<String> limitations) { }

    /** 各协议共用的提交单元，仓库快照与文章在同一租约事务中提交。 */
    public record CollectedBatch(List<CollectedArticle> articles, List<GithubSnapshot> snapshots,
                                 CollectionCoverage coverage, List<String> withdrawnIds) {
        /** 保留不含撤稿的采集协议。 */
        public CollectedBatch(List<CollectedArticle> articles, List<GithubSnapshot> snapshots, CollectionCoverage coverage) {
            this(articles, snapshots, coverage, List.of());
        }
    }

    /** 地区未验证时本地分类必须明确降级，不能替换为用户设备位置。 */
    public record RegionSnapshot(String status, String regionCode, String nodeId, Instant resolvedAt, RegionModels.RegionDetails details) {
        /** 兼容旧日报和固定地区测试夹具。 */
        public RegionSnapshot(String status, String regionCode, String nodeId, Instant resolvedAt) {
            this(status, regionCode, nodeId, resolvedAt, null);
        }

        /** 仅已选择来源包的可用地区或旧版验证夹具可参与本地匹配。 */
        public boolean usable() {
            return regionCode != null && ("VERIFIED".equals(status) || "PROVINCE_FALLBACK".equals(status) || "CACHED".equals(status))
                    && (details == null ? "VERIFIED".equals(status) : !details.sourceIds().isEmpty());
        }

        /** 构造尚未验证执行节点出口的地区快照。 */
        public static RegionSnapshot unresolved() { return new RegionSnapshot("UNRESOLVED", null, null, null); }
    }

    /** 预览只生成快照，不触发消息发送。 */
    public record PreviewRequest(@NotBlank @Size(max = 128) String idempotencyKey,
                                 @NotNull Instant cutoff) { }

    /** 排名分量均归一化，初版不虚构尚未取得的周增长信号。 */
    public record Score(double freshness, double corroboration, double engagement,
                        double authority, double total) { }

    /** 引用绑定不可变证据版本而非可变网页。 */
    public record Citation(String articleId, String url, String sourceId, String contentHash, Instant fetchedAt) { }

    /** 原文节选保留来源语言，不冒充模型生成的中文摘要。 */
    public record Detail(String text, String method, List<Citation> citations) { }

    /** 每条新闻保留可解释分数与详情缺口。 */
    public record RankedItem(int rank, String eventId, String title, String url, Instant publishedAt,
                             List<String> sourceIds, Score score, DetailStatus detailStatus, Detail detail,
                             TechnicalMetadata technical) { }

    /** 每类最多二十条，前十详情不足时保持名次并呈现原因。 */
    public record Section(Category category, Instant windowStart, Instant windowEnd, int candidateCount,
                          int headlineCount, int detailCount, Quality quality, List<String> gaps,
                          List<RankedItem> items) { }

    /** 日报内容创建后不可原地覆盖，后续更正应形成独立版本。 */
    public record Edition(UUID id, long ownerId, Instant cutoff, Instant createdAt, String zone,
                          String rankingVersion, RegionSnapshot region, Quality quality,
                          Map<Category, Section> sections) { }

    /** 历史列表不重复返回完整正文。 */
    public record EditionHeader(UUID id, Instant cutoff, Instant createdAt, Quality quality) { }

    /** 来源抓取结果只返回计数和健康信息。 */
    public record CollectionResult(String sourceId, String status, int inserted, int skipped, Instant newestPublishedAt,
                                    CollectionCoverage coverage, Instant nextAttemptAt, Instant newestObservedAt) {
        /** 保留既有 RSS 采集调用的构造协议。 */
        public CollectionResult(String sourceId, String status, int inserted, int skipped, Instant newestPublishedAt) {
            this(sourceId, status, inserted, skipped, newestPublishedAt, null, null, null);
        }
    }

    /** 来源管理视图保留条件请求及抓取新鲜度。 */
    public record SourceState(SourceSpec spec, String etag, String lastModified, Instant lastCheckedAt,
                              Instant newestPublishedAt, String health, Instant nextAttemptAt, CollectionCoverage coverage,
                              Instant newestObservedAt) {
        /** 保留既有来源状态构造协议。 */
        public SourceState(SourceSpec spec, String etag, String lastModified, Instant lastCheckedAt,
                           Instant newestPublishedAt, String health) {
            this(spec, etag, lastModified, lastCheckedAt, newestPublishedAt, health, null, null, null);
        }
    }
}
