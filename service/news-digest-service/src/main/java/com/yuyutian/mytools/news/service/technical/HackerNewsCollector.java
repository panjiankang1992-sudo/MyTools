package com.yuyutian.mytools.news.service.technical;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.config.TechSourceProperties;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.springframework.stereotype.Component;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.*;

/** 只采集官方榜单中的存活故事，不把招聘、评论或外链正文混入材料。 */
@Component
public class HackerNewsCollector implements TechnicalCollector {
    private final JsonSourceClient client;
    private final TechSourceProperties properties;

    /** 创建 HN 适配器。 */
    public HackerNewsCollector(JsonSourceClient client, TechSourceProperties properties) { this.client = client; this.properties = properties; }

    /** 返回 HN 协议。 */
    @Override
    public SourceKind kind() { return SourceKind.HACKER_NEWS; }

    /** 有界读取榜单及故事，单条错误不抹去其他有效结果。 */
    @Override
    public CollectedBatch collect(SourceState source, Instant startedAt) {
        long deadline = TechnicalParsing.deadline();
        JsonNode ids = client.get(source, source.spec().feedUrl());
        if (!ids.isArray() || ids.size() > 1000) throw new NewsException(ErrorCode.NEWS_007);
        List<CollectedArticle> articles = new ArrayList<>();
        List<String> withdrawn = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int visited = 0;
        int skipped = 0;
        int failed = 0;
        List<String> limitations = new ArrayList<>(List.of("CURRENT_TOP_STORIES_SAMPLE"));
        for (JsonNode id : ids) {
            if (visited >= properties.itemLimit()) break;
            if (TechnicalParsing.expired(deadline)) { limitations.add("TIME_BUDGET_EXCEEDED"); break; }
            visited++;
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) { failed++; continue; }
            if (!seen.add(id.longValue())) { skipped++; continue; }
            try {
                JsonNode item = client.get(source, "https://hacker-news.firebaseio.com/v0/item/" + id.longValue() + ".json");
                if (item.isNull()) { withdrawn.add(id.asText()); skipped++; continue; }
                if (TechnicalParsing.positive(item, "id") != id.longValue()) throw new NewsException(ErrorCode.NEWS_007);
                // 官方热榜可能混有招聘或撤稿，只有存活故事进入新闻候选。
                if (item.path("dead").asBoolean() || item.path("deleted").asBoolean()
                        || !"story".equals(item.path("type").asText())) { withdrawn.add(id.asText()); skipped++; continue; }
                Instant published = Instant.ofEpochSecond(TechnicalParsing.positive(item, "time"));
                // 热榜位置不能刷新原始发布日期，超出七日窗口直接跳过。
                if (!TechnicalParsing.inWeek(published, startedAt)) { skipped++; continue; }
                String title = TechnicalParsing.text(item, "title", 1000);
                if (title.isBlank()) { failed++; continue; }
                String discussion = "https://news.ycombinator.com/item?id=" + id.longValue();
                String rawUrl = item.path("url").asText("");
                String url = ContentIdentity.canonicalUrl(rawUrl.isBlank() ? discussion : rawUrl);
                long score = TechnicalParsing.nonNegative(item, "score");
                long comments = item.hasNonNull("descendants") ? TechnicalParsing.nonNegative(item, "descendants") : 0;
                ArticleInput content = new ArticleInput(url, title, published, TechnicalParsing.text(item, "text", 20000), score);
                articles.add(new CollectedArticle(content, new TechnicalMetadata(id.asText(), discussion, published, score, comments, null)));
            } catch (SourceRateLimitException exception) {
                throw exception; // 上游限流立即结束整轮，交由持久冷却控制下一轮。
            } catch (NewsException | DateTimeException exception) {
                failed++;
            }
        }
        if (visited < ids.size()) limitations.add("ITEM_LIMIT_OR_TIME_BUDGET");
        if (failed > 0) limitations.add("ITEM_FETCH_OR_SCHEMA_FAILURE");
        return new CollectedBatch(List.copyOf(articles), List.of(),
                new CollectionCoverage(ids.size(), visited, articles.size(), skipped, failed, List.copyOf(limitations)), List.copyOf(withdrawn));
    }
}
