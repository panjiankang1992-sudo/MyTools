package com.yuyutian.mytools.rss.service;

import com.yuyutian.mytools.feed.*;
import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import com.yuyutian.mytools.rss.mapper.RssStore;
import java.util.List;
import org.springframework.stereotype.Service;

/** 发现过程只预览候选，保存订阅仍须再次校验用户输入。 */
@Service
public class DiscoveryService {
    /** 不携带完整图文正文的添加页预览。 */
    public record Preview(String url, String title, String siteUrl, String description, List<Item> entries) { }
    /** 最近条目仅展示用户选择来源所需的信息。 */
    public record Item(String title, String summary, java.time.Instant publishedAt) { }
    /** 直接 Feed 返回预览，网站返回候选供用户选择。 */
    public record Result(List<FeedModels.Candidate> candidates, Preview preview) { }
    private final FeedAccessService access;
    private final FeedParser parser;
    private final RssStore store;
    /** 注入受限抓取器与用户限流仓储。 */
    public DiscoveryService(FeedAccessService access, FeedParser parser, RssStore store) { this.access = access; this.parser = parser; this.store = store; }
    /** 每次最多执行一个受限 HTTP 请求，不自动追踪全部候选。 */
    public Result discover(long owner, String url) {
        String canonical = FeedParser.canonical(PublicNetworkPolicy.requireHttps(url.strip()).toString());
        store.reserveDiscovery(owner);
        var response = access.fetch(canonical, null, null);
        if (response.status() != 200) throw new RssException(response.status() == 429 ? ErrorCode.RSS_007 : ErrorCode.RSS_003);
        try {
            var feed = parser.parse(response.body(), response.finalUrl());
            String title = feed.title().isBlank() ? java.net.URI.create(canonical).getHost() : feed.title();
            var preview = new Preview(canonical, title, feed.siteUrl(), feed.description(), feed.entries().stream().limit(5).map(entry -> new Item(entry.title(), entry.summary(), entry.publishedAt())).toList());
            return new Result(List.of(), preview);
        } catch (FeedException exception) {
            if (exception.kind() != FeedException.Kind.FORMAT) throw exception;
            var candidates = parser.discover(response.body(), response.finalUrl());
            if (candidates.isEmpty()) throw new RssException(ErrorCode.RSS_002);
            return new Result(candidates, null);
        }
    }
}
