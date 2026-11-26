package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.service.FeedParser;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class FeedParserTest {
    private final FeedParser parser = new FeedParser();

    @Test
    void rssParsesPublishedDateCdataAndTrackingUrlWithoutKeepingScripts() {
        var parsed = parse("""
                <rss version="2.0"><channel><item><title>Event &amp; evidence</title>
                <link>https://example.com/a?utm_source=rss&amp;id=1#top</link>
                <pubDate>Sun, 18 Oct 2026 07:30:00 +0800</pubDate>
                <description><![CDATA[<p>Verified text</p><script>alert(1)</script>]]></description>
                </item><item><title>Missing link</title></item></channel></rss>
                """);
        assertThat(parsed.skipped()).isEqualTo(1);
        var article = parsed.articles().getFirst();
        assertThat(article.publishedAt()).isEqualTo(Instant.parse("2026-10-17T23:30:00Z"));
        assertThat(article.url()).isEqualTo("https://example.com/a?id=1");
        assertThat(article.evidenceText()).isEqualTo("Verified text");
    }

    @Test
    void atomUpdatedDoesNotSubstituteForPublishedAndRelativeAlternateResolves() {
        var parsed = parse("""
                <feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Original entry</title>
                <link rel="self" href="/self"/><link rel="alternate" href="/article"/>
                <updated>2026-10-18T00:00:00Z</updated><summary>Only update timestamp</summary>
                </entry></feed>
                """);
        assertThat(parsed.articles().getFirst().publishedAt()).isNull();
        assertThat(parsed.articles().getFirst().url()).isEqualTo("https://example.com/article");
    }

    @Test
    void rejectsExternalEntitiesAndHtmlErrorPages() {
        assertThatThrownBy(() -> parse("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss><channel>&x;</channel></rss>"))
                .isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> parse("<html><body>Forbidden</body></html>")).isInstanceOf(NewsException.class);
    }

    @Test
    void rejectsOversizedFeedsAndTooManyItems() {
        assertThatThrownBy(() -> parser.parse(new byte[2097153], "https://example.com/rss")).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> parse("<rss><channel>" + "<item/>".repeat(501) + "</channel></rss>"))
                .isInstanceOf(NewsException.class);
    }

    @Test
    void invalidDatesAreNotReplacedAndScriptLinksAreSkipped() {
        var result = parse("""
                <rss><channel><item><title>Valid story</title><link>https://example.com/a</link><pubDate>unknown</pubDate></item>
                <item><title>Invalid story</title><link>javascript:alert(1)</link></item></channel></rss>
                """);
        assertThat(result.articles()).hasSize(1);
        assertThat(result.articles().getFirst().publishedAt()).isNull();
        assertThat(result.skipped()).isEqualTo(1);
    }

    private FeedParser.ParsedFeed parse(String xml) {
        return parser.parse(xml.getBytes(StandardCharsets.UTF_8), "https://example.com/rss");
    }
}
