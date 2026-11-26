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
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 读取央视军事公开列表，保留来源明确标记的发布时间和原文图片。 */
public final class CctvMilitaryParser {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private CctvMilitaryParser() { }

    /** 仅提取同站文字报道，不把视频列表或无效日期当作新闻正文。 */
    public static FeedParser.ParsedFeed parse(byte[] body) {
        var articles = new LinkedHashMap<String, ArticleInput>();
        int skipped = 0;
        for (JsonNode row : rows(body)) {
            try {
                String url = articleUrl(row);
                String title = ContentIdentity.plainText(row.path("title").asText());
                if (title.isBlank()) { skipped++; continue; }
                var published = LocalDateTime.parse(row.path("dateTime").asText(), DATE).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
                if (articles.putIfAbsent(url, new ArticleInput(url, ContentIdentity.truncate(title, 1000), published,
                        ContentIdentity.truncate(ContentIdentity.plainText(row.path("description").asText()), 4000), 0)) != null) skipped++;
            } catch (DateTimeException | IllegalArgumentException | NewsException exception) { skipped++; }
        }
        return new FeedParser.ParsedFeed(List.copyOf(articles.values()), skipped);
    }

    /** 只使用新闻条目声明的央视图片，后续仍校验真实格式和下载大小。 */
    public static Map<String, Map<String, String>> images(byte[] body) {
        var result = new LinkedHashMap<String, Map<String, String>>();
        for (JsonNode row : rows(body)) {
            try {
                String image = row.path("image").asText();
                PublicNetworkPolicy.requireHttps(image);
                if (!URI.create(image).getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".img.cctvpic.com")) continue;
                result.put(articleUrl(row), Map.of("url", image, "caption", "CCTV"));
            } catch (IllegalArgumentException | NewsException ignored) { /* 缺图不影响有效文字报道。 */ }
        }
        return result;
    }

    private static String articleUrl(JsonNode row) {
        String url = row.path("url").asText();
        PublicNetworkPolicy.requireHttps(url);
        URI uri = URI.create(url);
        if (!"ARTI".equals(row.path("type").asText()) || !"military.cctv.com".equalsIgnoreCase(uri.getHost())
                || !uri.getPath().matches("/[0-9]{4}/[0-9]{2}/[0-9]{2}/ARTI[A-Za-z0-9]+\\.shtml")) throw new IllegalArgumentException();
        return ContentIdentity.canonicalUrl(url);
    }

    private static JsonNode rows(byte[] body) {
        if (body.length > 2 * 1024 * 1024) throw new NewsException(ErrorCode.NEWS_007);
        try {
            var rows = new ObjectMapper().readTree(body).path("rollData");
            if (!rows.isArray() || rows.isEmpty() || rows.size() > 500) throw new NewsException(ErrorCode.NEWS_007);
            return rows;
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_007); }
    }
}
