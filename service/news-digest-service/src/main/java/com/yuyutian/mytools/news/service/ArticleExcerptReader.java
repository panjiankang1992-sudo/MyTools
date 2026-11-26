package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 优先采用来源摘要，仅为靠前的缺失条目补读公开原文，失败不影响标题采集。 */
public final class ArticleExcerptReader {
    private final FeedTransport transport;

    /** 复用已有 HTTPS、公网拨号、超时和响应大小限制。 */
    public ArticleExcerptReader(FeedTransport transport) { this.transport = transport; }

    /** 短摘要也可包含实质信息，但标题复述和过短片段不视为详情。 */
    public static boolean sufficient(String title, String text) {
        return text != null && text.codePointCount(0, text.length()) >= 80
                && !ContentIdentity.titleKey(text).equals(ContentIdentity.titleKey(title));
    }

    /** 在来源前二十候选中最多补读十篇，使用整轮截止时间，不修改已有足够长的摘要。 */
    public List<CollectedArticle> enrich(SourceState source, List<CollectedArticle> articles, Instant observedAt, long deadline) {
        SourceSpec spec = source.spec();
        if (spec.evidencePolicy() != EvidencePolicy.EXCERPT_ALLOWED
                || (spec.kind() != SourceKind.RSS_ATOM && spec.kind() != SourceKind.HACKER_NEWS
                && spec.kind() != SourceKind.CHINANEWS_JIANGSU && spec.kind() != SourceKind.JSCHINA_JIANGSU && spec.kind() != SourceKind.CCTV_MILITARY)) return articles;
        List<CollectedArticle> result = new ArrayList<>(articles);
        int requests = 0;
        for (int index = 0; index < Math.min(20, articles.size()); index++) {
            if (System.nanoTime() >= deadline) break; // 留出最后一次受限请求及数据库提交的时间。
            CollectedArticle article = articles.get(index);
            ArticleInput input = article.content();
            if (input.publishedAt() == null || input.publishedAt().isAfter(observedAt)
                    || input.publishedAt().isBefore(observedAt.minus(DigestEngine.window(spec.category())))) continue;
            if (sufficient(input.title(), input.evidenceText())) continue;
            if (spec.kind() == SourceKind.HACKER_NEWS && article.technical() != null
                    && input.url().equals(article.technical().discussionUrl())) continue; // 不将评论区拼成故事正文。
            if (requests++ >= 10) break; // 已有摘要不占用后续缺失条目的请求预算。
            try {
                String text = read(spec, input.url(), input.title());
                if (!sufficient(input.title(), text)) continue;
                TechnicalMetadata metadata = article.technical();
                if (metadata != null) metadata = new TechnicalMetadata(metadata.externalId(), metadata.discussionUrl(),
                        metadata.activityAt(), metadata.nativeScore(), metadata.commentCount(), metadata.github(), input.url());
                result.set(index, new CollectedArticle(new ArticleInput(input.url(), input.title(), input.publishedAt(),
                        text, input.engagement()), metadata));
            } catch (SourceRateLimitException ignored) {
                break; // 原文站点限流时停止本轮补读，不继续请求其余条目。
            } catch (NewsException ignored) {
                // 原文不可读、限流或拒绝访问时保留来源已有材料，不重试或绕过访问限制。
            }
        }
        return List.copyOf(result);
    }

    private String read(SourceSpec source, String url, String title) {
        // 普通 RSS 仅补读本站原文；技术论坛的外链保留独立的实际证据地址。
        if ((source.kind() == SourceKind.RSS_ATOM || source.kind() == SourceKind.CHINANEWS_JIANGSU || source.kind() == SourceKind.JSCHINA_JIANGSU || source.kind() == SourceKind.CCTV_MILITARY)
                && !URI.create(source.feedUrl()).getHost().equalsIgnoreCase(URI.create(url).getHost())) return "";
        SourceSpec request = new SourceSpec(source.id(), source.name(), url, source.category(), source.publisherKey(),
                source.regionCode(), source.evidencePolicy(), source.rightsReference(), source.authority(), true, SourceKind.RSS_ATOM);
        FeedTransport.FeedResponse response = transport.fetchPage(new SourceState(request, null, null, null, null, "REQUEST"));
        if (response.status() != 200 || response.body().length > 2 * 1024 * 1024) return "";
        return extract(response.body(), url, title);
    }

    /** 从网页自身简介或文章段落提取纯文本，不执行脚本或收集导航、评论及表单。 */
    public static String extract(byte[] body, String url, String title) {
        return ArticleContentResolver.extract(body, url, title).text();
    }
}
