package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.service.JschinaNewsParser;
import com.yuyutian.mytools.news.utils.TechnicalSourcePolicy;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class JschinaNewsParserTest {
    private static final String URL = "https://jsnews.jschina.com.cn/jsyw/";
    private static final String ARTICLE = "202610/t20261019_s6ad571e6e4b0cd8a37594e0c.shtml";

    @Test void readsStaticListAndExactLocalDateWithoutRunningPageScripts() {
        String html = "<nav>" + entry("/navigation", "2026-10-19 10:00") + "</nav>"
                + wrap(entry(ARTICLE, "2026-10-19 09:27"));
        var result = parse(html);
        assertThat(result.articles()).hasSize(1);
        assertThat(result.articles().getFirst().url()).isEqualTo(URL + ARTICLE);
        assertThat(result.articles().getFirst().title()).isEqualTo("Local & original event");
        assertThat(result.articles().getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-19T01:27:00Z"));
        assertThat(result.articles().getFirst().evidenceText()).isEmpty();
    }

    @Test void rejectsForeignUnsafeAndNonArticleLinksWithoutInventingDates() {
        String html = wrap(entry("https://example.org/jsyw/" + ARTICLE, "2026-10-19 09:27")
                + entry("http://jsnews.jschina.com.cn/jsyw/" + ARTICLE, "2026-10-19 09:27")
                + entry("https://user@jsnews.jschina.com.cn/jsyw/" + ARTICLE, "2026-10-19 09:27")
                + entry("/navigation", "2026-10-19 09:27") + entry(ARTICLE, "")
                + entry(ARTICLE, "2026-02-30 09:27"));
        var result = parse(html);
        assertThat(result.articles()).isEmpty();
        assertThat(result.skipped()).isEqualTo(6);
    }

    @Test void repeatedLinksKeepOriginalTimestampAndDoNotInflateCandidates() {
        var result = parse(wrap(entry(ARTICLE, "2026-10-19 09:27") + entry(ARTICLE, "2026-10-19 10:00")));
        assertThat(result.articles()).hasSize(1);
        assertThat(result.articles().getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-19T01:27:00Z"));
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test void changedTemplateAndExcessiveListsFailVisibly() {
        assertThatThrownBy(() -> parse("<html>Unexpected response</html>")).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> parse(wrap(entry(ARTICLE, "2026-10-19 09:27").repeat(501)))).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> JschinaNewsParser.parse(new byte[2 * 1024 * 1024 + 1], URL)).isInstanceOf(NewsException.class);
    }

    @Test void adapterRegistrationRestrictsCategoryRegionAndVerifiedEntryPoint() {
        TechnicalSourcePolicy.validate(source(URL, Category.LOCAL, "CN-JS"));
        assertThat(TechnicalSourcePolicy.interval(SourceKind.JSCHINA_JIANGSU)).isEqualTo(Duration.ofMinutes(30));
        assertThatThrownBy(() -> TechnicalSourcePolicy.validate(source(URL, Category.WORLD, "CN-JS"))).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> TechnicalSourcePolicy.validate(source(URL, Category.LOCAL, "CN-SC"))).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> TechnicalSourcePolicy.validate(source("https://example.org/", Category.LOCAL, "CN-JS"))).isInstanceOf(NewsException.class);
    }

    private com.yuyutian.mytools.news.service.FeedParser.ParsedFeed parse(String html) {
        return JschinaNewsParser.parse(html.getBytes(StandardCharsets.UTF_8), URL);
    }

    private SourceSpec source(String url, Category category, String region) {
        return new SourceSpec("jschina", "Jschina", url, category, "xinhua-daily-group", region,
                EvidencePolicy.EXCERPT_ALLOWED, URL, 0.8, true, SourceKind.JSCHINA_JIANGSU);
    }

    private String wrap(String entries) { return "<div id='hideData'><ul class='dataList'>" + entries + "</ul></div>"; }
    private String entry(String url, String date) {
        return "<li><div class='datatit'><a href='" + url + "'>Local &amp; original event</a></div>"
                + "<div class='datatime'>" + date + "</div></li>";
    }
}
