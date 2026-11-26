package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.service.BaiduHotParser;
import com.yuyutian.mytools.news.service.CctvMilitaryParser;
import com.yuyutian.mytools.news.utils.TechnicalSourcePolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NewsCategorySourcesTest {
    @Test void hotBoardPreservesPlatformFactsWithoutInventingPublicationDate() {
        String row = "{\"word\":\"A trend\",\"desc\":\"Reported platform facts\",\"hotScore\":\"1234\",\"rawUrl\":\"https://www.baidu.com/s?wd=trend\",\"img\":\"https://example.com/picture.jpg\"}";
        byte[] body = ("<!--s-data:{\"data\":{\"cards\":[{\"component\":\"hotList\",\"content\":[" + row + "," + row + "]}]}}-->").getBytes(StandardCharsets.UTF_8);
        var result = BaiduHotParser.parse(body);
        assertThat(result.articles()).hasSize(1);
        var article = result.articles().getFirst();
        assertThat(article.publishedAt()).isNull();
        assertThat(article.engagement()).isEqualTo(1234);
        assertThat(article.evidenceText()).contains("Reported platform facts", "1234");
        assertThat(BaiduHotParser.images(body).get(article.url()).get("url")).isEqualTo("https://example.com/picture.jpg");
        assertThatThrownBy(() -> BaiduHotParser.parse("<html>changed</html>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(NewsException.class);
    }

    @Test void militaryListUsesExplicitTimeAndRejectsForeignLinksAndBadDates() {
        String json = "{\"rollData\":[" + military("https://military.cctv.com/2026/11/02/ARTI12345.shtml", "2026-11-02 10:38")
                + "," + military("https://evil.example/12345.shtml", "2026-11-02 10:38")
                + "," + military("https://military.cctv.com/2026/11/02/ARTI12346.shtml", "2026-02-30 10:38") + "]}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        var feed = CctvMilitaryParser.parse(body);
        assertThat(feed.articles()).hasSize(1);
        assertThat(feed.skipped()).isEqualTo(2);
        assertThat(feed.articles().getFirst().url()).startsWith("https://military.cctv.com/");
        assertThat(feed.articles().getFirst().publishedAt()).isEqualTo(Instant.parse("2026-11-02T02:38:00Z"));
        assertThat(CctvMilitaryParser.images(body)).containsKey(feed.articles().getFirst().url());
        assertThatThrownBy(() -> CctvMilitaryParser.parse("{}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(NewsException.class);
    }

    @Test void specialAdaptersCannotBeRegisteredForArbitrarySitesOrCategories() {
        var source = new SourceSpec("hot", "Hot", "https://top.baidu.com/board?tab=realtime", Category.TRENDING,
                "baidu", null, EvidencePolicy.EXCERPT_ALLOWED, "https://top.baidu.com/", 0.5, true, SourceKind.BAIDU_HOT);
        TechnicalSourcePolicy.validate(source);
        var invalid = new SourceSpec("hot", "Hot", "https://example.com/", Category.TRENDING,
                "baidu", null, EvidencePolicy.EXCERPT_ALLOWED, "https://example.com/", 0.5, true, SourceKind.BAIDU_HOT);
        assertThatThrownBy(() -> TechnicalSourcePolicy.validate(invalid)).isInstanceOf(NewsException.class);
    }

    private String military(String url, String time) {
        return "{\"type\":\"ARTI\",\"url\":\"" + url + "\",\"dateTime\":\"" + time
                + "\",\"title\":\"Defense update\",\"description\":\"Facts\",\"image\":\"https://p1.img.cctvpic.com/news.jpg\"}";
    }
}
