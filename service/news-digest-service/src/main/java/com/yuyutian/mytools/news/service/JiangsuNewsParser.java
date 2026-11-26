package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.ArticleInput;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.jsoup.Jsoup;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/** 解析中新网江苏频道中带完整发布时间的资讯列表，不把页面广告和导航当作新闻。 */
public final class JiangsuNewsParser {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private JiangsuNewsParser() { }

    /** 保留列表给出的北京时间，不以采集时间或网址日期替代缺失的发布时间。 */
    public static FeedParser.ParsedFeed parse(byte[] body, String feedUrl) {
        if (body.length > 2 * 1024 * 1024) throw new NewsException(ErrorCode.NEWS_007);
        try {
            var document = Jsoup.parse(new ByteArrayInputStream(body), null, feedUrl);
            var entries = document.select(".jszx-item");
            if (entries.isEmpty() || entries.size() > 100) throw new NewsException(ErrorCode.NEWS_007);
            List<ArticleInput> articles = new ArrayList<>();
            int skipped = 0;
            for (var entry : entries) {
                try {
                    var link = entry.selectFirst(".list-tit a[href]");
                    var time = entry.selectFirst(".time");
                    if (link == null || time == null || link.text().isBlank()) { skipped++; continue; }
                    URI uri = URI.create(feedUrl).resolve(link.attr("href"));
                    if (!"https".equals(uri.getScheme()) || !URI.create(feedUrl).getHost().equalsIgnoreCase(uri.getHost())
                            || !uri.getPath().matches("/news/[0-9]{4}/[0-9]{4}/[0-9]+\\.html")) { skipped++; continue; }
                    var published = LocalDateTime.parse(time.text().strip(), DATE).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
                    articles.add(new ArticleInput(ContentIdentity.canonicalUrl(uri.toString()),
                            ContentIdentity.truncate(link.text(), 1000), published, "", 0));
                } catch (DateTimeException | IllegalArgumentException exception) { skipped++; }
            }
            return new FeedParser.ParsedFeed(List.copyOf(articles), skipped);
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_007); }
    }
}
