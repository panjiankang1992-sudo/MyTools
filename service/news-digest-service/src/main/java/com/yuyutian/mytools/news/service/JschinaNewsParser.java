package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.ArticleInput;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import com.yuyutian.mytools.news.utils.PublicNetworkPolicy;
import org.jsoup.Jsoup;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;

/** 解析中国江苏网江苏要闻的静态列表，保留站点明确给出的分钟级发布时间。 */
public final class JschinaNewsParser {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private JschinaNewsParser() { }

    /** 只接纳同站正文链接，不执行列表脚本，也不以抓取时间补齐未知日期。 */
    public static FeedParser.ParsedFeed parse(byte[] body, String feedUrl) {
        if (body.length > 2 * 1024 * 1024) throw new NewsException(ErrorCode.NEWS_007);
        try {
            var document = Jsoup.parse(new ByteArrayInputStream(body), null, feedUrl);
            var entries = document.select("#hideData .dataList > li");
            if (entries.isEmpty() || entries.size() > 500) throw new NewsException(ErrorCode.NEWS_007);
            var articles = new LinkedHashMap<String, ArticleInput>();
            int skipped = 0;
            for (var entry : entries) {
                try {
                    var link = entry.selectFirst(".datatit a[href]");
                    var time = entry.selectFirst(".datatime");
                    if (link == null || time == null || link.text().isBlank()) { skipped++; continue; }
                    URI uri = URI.create(feedUrl).resolve(link.attr("href"));
                    PublicNetworkPolicy.requireHttps(uri.toString());
                    // 仅保存本栏目实际文章路径，侧栏、外部转载跳转及资源链接不能进入榜单。
                    if (!"jsnews.jschina.com.cn".equalsIgnoreCase(uri.getHost())
                            || !uri.getPath().matches("/jsyw/[0-9]{6}/t[0-9]{8}_[A-Za-z0-9]{1,64}\\.shtml")) { skipped++; continue; }
                    var published = LocalDateTime.parse(time.text().strip(), DATE).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
                    String url = ContentIdentity.canonicalUrl(uri.toString());
                    if (articles.putIfAbsent(url, new ArticleInput(url, ContentIdentity.truncate(link.text(), 1000), published, "", 0)) != null) skipped++;
                } catch (DateTimeException | IllegalArgumentException | NewsException exception) { skipped++; }
            }
            return new FeedParser.ParsedFeed(List.copyOf(articles.values()), skipped);
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_007); }
    }
}
