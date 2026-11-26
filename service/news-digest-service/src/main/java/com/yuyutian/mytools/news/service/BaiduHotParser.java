package com.yuyutian.mytools.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.ArticleInput;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import com.yuyutian.mytools.news.utils.PublicNetworkPolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 读取百度公开热榜的嵌入数据，不执行脚本，不把热度采样时间冒充新闻发布时间。 */
public final class BaiduHotParser {
    private static final Pattern DATA = Pattern.compile("<!--s-data:(.*?)-->", Pattern.DOTALL);
    private BaiduHotParser() { }

    /** 保留平台摘要与热搜指数，实际新闻发布时间未知时留空。 */
    public static FeedParser.ParsedFeed parse(byte[] body) {
        var articles = new LinkedHashMap<String, ArticleInput>();
        int skipped = 0;
        for (JsonNode row : rows(body)) {
            try {
                String url = topicUrl(row);
                String title = ContentIdentity.plainText(row.path("word").asText());
                String summary = ContentIdentity.plainText(row.path("desc").asText());
                long score = Long.parseLong(row.path("hotScore").asText("0"));
                if (title.isBlank() || summary.isBlank() || score < 0) { skipped++; continue; }
                String evidence = summary + "\n\u6765\u6e90\uff1a\u767e\u5ea6\u70ed\u641c\u699c\u5355\u6458\u8981\u3002\u5e73\u53f0\u70ed\u641c\u6307\u6570\uff1a" + score
                        + "\u3002\u6307\u6570\u4ec5\u4ee3\u8868\u8be5\u5e73\u53f0\u5f53\u524d\u70ed\u5ea6\uff0c\u672a\u63d0\u4f9b\u539f\u62a5\u9053\u53d1\u5e03\u65f6\u95f4\u3002";
                if (articles.putIfAbsent(url, new ArticleInput(url, ContentIdentity.truncate(title, 1000), null,
                        ContentIdentity.truncate(evidence, 4000), score)) != null) skipped++;
            } catch (IllegalArgumentException | NewsException exception) { skipped++; }
        }
        return new FeedParser.ParsedFeed(List.copyOf(articles.values()), skipped);
    }

    /** 只返回榜单自己声明的图片，后续仍经统一图片下载与格式校验。 */
    public static Map<String, Map<String, String>> images(byte[] body) {
        var result = new LinkedHashMap<String, Map<String, String>>();
        for (JsonNode row : rows(body)) {
            try {
                String image = row.path("img").asText();
                PublicNetworkPolicy.requireHttps(image);
                result.put(topicUrl(row), Map.of("url", image, "caption", "\u767e\u5ea6\u70ed\u699c\u914d\u56fe"));
            } catch (IllegalArgumentException | NewsException ignored) { /* 无效配图不影响有效摘要。 */ }
        }
        return result;
    }

    private static String topicUrl(JsonNode row) {
        String url = row.path("rawUrl").asText();
        PublicNetworkPolicy.requireHttps(url);
        URI uri = URI.create(url);
        if (!"www.baidu.com".equalsIgnoreCase(uri.getHost()) || !"/s".equals(uri.getPath())
                || uri.getRawQuery() == null || !uri.getRawQuery().startsWith("wd=")) throw new IllegalArgumentException();
        return ContentIdentity.canonicalUrl(url);
    }

    private static List<JsonNode> rows(byte[] body) {
        if (body.length > 2 * 1024 * 1024) throw new NewsException(ErrorCode.NEWS_007);
        var match = DATA.matcher(new String(body, StandardCharsets.UTF_8));
        if (!match.find()) throw new NewsException(ErrorCode.NEWS_007);
        try {
            var cards = new ObjectMapper().readTree(match.group(1)).path("data").path("cards");
            var result = new ArrayList<JsonNode>();
            for (JsonNode card : cards) {
                if ("hotList".equals(card.path("component").asText())) card.path("content").forEach(result::add);
            }
            if (result.isEmpty() || result.size() > 100) throw new NewsException(ErrorCode.NEWS_007);
            return result;
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_007); }
    }
}
