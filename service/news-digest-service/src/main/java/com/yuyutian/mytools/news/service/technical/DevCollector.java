package com.yuyutian.mytools.news.service.technical;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.config.TechSourceProperties;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** DEV 使用官方近七日热门列表，详情只读取已登记允许加工的文章。 */
@Component
public class DevCollector implements TechnicalCollector {
    private final JsonSourceClient client;
    private final TechSourceProperties properties;

    /** 创建 DEV 适配器。 */
    public DevCollector(JsonSourceClient client, TechSourceProperties properties) { this.client = client; this.properties = properties; }

    /** 返回 DEV 协议。 */
    @Override
    public SourceKind kind() { return SourceKind.DEV; }

    /** 获取固定第一页候选，并对有权限的前十篇补取官方详情。 */
    @Override
    public CollectedBatch collect(SourceState source, Instant startedAt) {
        long deadline = TechnicalParsing.deadline();
        JsonNode list = client.get(source, source.spec().feedUrl() + "?top=7&per_page=" + properties.itemLimit() + "&page=1");
        if (!list.isArray() || list.size() > 1000) throw new NewsException(ErrorCode.NEWS_007);
        List<CollectedArticle> articles = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int visited = 0;
        int skipped = 0;
        int failed = 0;
        int details = 0;
        List<String> limitations = new ArrayList<>(List.of("TOP_SEVEN_DAYS_FIRST_PAGE"));
        for (JsonNode item : list) {
            if (visited >= properties.itemLimit()) break;
            if (TechnicalParsing.expired(deadline)) { limitations.add("TIME_BUDGET_EXCEEDED"); break; }
            visited++;
            try {
                long id = TechnicalParsing.positive(item, "id");
                if (!seen.add(id)) { skipped++; continue; }
                // 使用首次发布时间过滤，编辑和再次登上热榜都不能把旧文变成新文。
                Instant published = TechnicalParsing.date(item, "published_at");
                if (!TechnicalParsing.inWeek(published, startedAt)) { skipped++; continue; }
                String title = TechnicalParsing.text(item, "title", 1000);
                if (title.isBlank()) { failed++; continue; }
                String url = ContentIdentity.canonicalUrl(item.path("url").asText(""));
                if (!"dev.to".equalsIgnoreCase(URI.create(url).getHost())) throw new NewsException(ErrorCode.NEWS_007);
                long reactions = TechnicalParsing.nonNegative(item, "positive_reactions_count");
                long comments = TechnicalParsing.nonNegative(item, "comments_count");
                String evidence = TechnicalParsing.text(item, "description", 20000);
                // 只有确认加工范围后才补取正文，详情预算与标题预算分开控制。
                if (source.spec().evidencePolicy() == EvidencePolicy.EXCERPT_ALLOWED && details < properties.detailLimit()) {
                    details++;
                    try {
                        JsonNode detail = client.get(source, "https://dev.to/api/articles/" + id);
                        if (TechnicalParsing.positive(detail, "id") != id
                                || !Objects.equals(published, TechnicalParsing.date(detail, "published_at"))) {
                            throw new NewsException(ErrorCode.NEWS_007);
                        }
                        String body = TechnicalParsing.text(detail, "body_html", 20000);
                        if (!body.isBlank()) evidence = body;
                    } catch (SourceRateLimitException exception) {
                        throw exception;
                    } catch (NewsException exception) {
                        failed++; // 详情失败仍保留已有列表材料，缺口不能被伪造正文填平。
                    }
                }
                articles.add(new CollectedArticle(new ArticleInput(url, title, published, evidence, reactions),
                        new TechnicalMetadata(Long.toString(id), url, published, reactions, comments, null)));
            } catch (SourceRateLimitException exception) {
                throw exception;
            } catch (NewsException | IllegalArgumentException exception) {
                failed++;
            }
        }
        if (list.size() >= properties.itemLimit() || visited < list.size()) limitations.add("ITEM_LIMIT_OR_TIME_BUDGET");
        if (failed > 0) limitations.add("ITEM_OR_DETAIL_FAILURE");
        return new CollectedBatch(List.copyOf(articles), List.of(),
                new CollectionCoverage(list.size(), visited, articles.size(), skipped, failed, List.copyOf(limitations)));
    }
}
